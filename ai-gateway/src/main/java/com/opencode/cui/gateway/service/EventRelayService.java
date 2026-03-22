package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * PC Agent WebSocket 会话与 Skill Server 之间的消息路由服务。
 * Agent 会话以 AK（Access Key）为标识，保证整个系统（Gateway ↔ Skill Server）中一致的路由。
 */
@Slf4j
@Service
public class EventRelayService {

    /** 状态查询等待超时（毫秒） */
    private static final long STATUS_QUERY_TIMEOUT_MS = 1500L;

    /** 已连接 Agent 的 WebSocket 会话映射：ak → session */
    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> opencodeStatusCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> pendingStatusQueries = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final RedisMessageBroker redisMessageBroker;
    private final SkillRelayService skillRelayService;

    public EventRelayService(ObjectMapper objectMapper,
            RedisMessageBroker redisMessageBroker,
            SkillRelayService skillRelayService) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.skillRelayService = skillRelayService;
    }

    public void registerAgentSession(String ak, String userId, WebSocketSession session) {
        WebSocketSession old = agentSessions.put(ak, session);
        if (old != null && old.isOpen()) {
            try {
                old.close();
                log.info("Closed old WebSocket session for ak={}", ak);
            } catch (IOException e) {
                log.warn("Error closing old session for ak={}", ak, e);
            }
        }

        redisMessageBroker.bindAgentUser(ak, userId);
        redisMessageBroker.subscribeToAgent(ak, message -> sendToLocalAgent(ak, message));
        log.info("Registered agent session: ak={}, wsSessionId={}", ak, session.getId());
    }

    public void removeAgentSession(String ak) {
        WebSocketSession session = agentSessions.remove(ak);
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.warn("Error closing session during removal for ak={}", ak, e);
            }
        }

        opencodeStatusCache.put(ak, false);
        CompletableFuture<Boolean> pending = pendingStatusQueries.remove(ak);
        if (pending != null) {
            pending.complete(false);
        }
        redisMessageBroker.removeAgentUser(ak);
        redisMessageBroker.unsubscribeFromAgent(ak);
        log.info("Removed agent session: ak={}", ak);
    }

    public boolean hasAgentSession(String ak) {
        WebSocketSession session = agentSessions.get(ak);
        return session != null && session.isOpen();
    }

    /**
     * 上行消息路由到 Source 服务。
     * v3: 注入 ak/userId/traceId 后直接交给 SkillRelayService 路由。
     * Source 解析由 SkillRelayService 的路由缓存处理，不再查 Redis gw:agent:source:{ak}。
     */
    public void relayToSkillServer(String ak, GatewayMessage message) {
        long start = System.nanoTime();
        GatewayMessage tracedMessage = message.ensureTraceId();
        String userId = redisMessageBroker.getAgentUser(ak);
        GatewayMessage forwarded = tracedMessage.withAk(ak)
                .withUserId(userId);

        try {
            MdcHelper.fromGatewayMessage(forwarded);
            MdcHelper.putScenario("relay-to-skill");

            log.info(
                    "[ENTRY] EventRelayService.relayToSkillServer: type={}, ak={}, toolSessionId={}, welinkSessionId={}",
                    tracedMessage.getType(), ak, forwarded.getToolSessionId(), forwarded.getWelinkSessionId());

            boolean routed = skillRelayService.relayToSkill(forwarded);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (!routed) {
                log.warn(
                        "[ERROR] EventRelayService.relayToSkillServer: reason=route_failed, type={}, welinkSessionId={}, durationMs={}",
                        message.getType(), forwarded.getWelinkSessionId(), elapsedMs);
            } else {
                log.info("[EXIT] EventRelayService.relayToSkillServer: type={}, durationMs={}",
                        message.getType(), elapsedMs);
            }
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[ERROR] EventRelayService.relayToSkillServer: type={}, durationMs={}",
                    message.getType(), elapsedMs, e);
        } finally {
            MdcHelper.clearAll();
        }
    }

    public void relayToAgent(String ak, GatewayMessage message) {
        log.info("[ENTRY] EventRelayService.relayToAgent: ak={}, type={}", ak, message.getType());
        redisMessageBroker.publishToAgent(ak, message.withoutRoutingContext());
        log.info("[EXIT->AGENT] EventRelayService.relayToAgent: ak={}, type={}", ak, message.getType());
    }

    private void sendToLocalAgent(String ak, GatewayMessage message) {
        WebSocketSession session = agentSessions.get(ak);
        if (session == null || !session.isOpen()) {
            log.debug("Agent not connected to this instance: ak={}, type={}",
                    ak, message.getType());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message.withoutRoutingContext());
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.info("[EXIT->AGENT] Sent to local agent: type={}, seq={}",
                    message.getType(), message.getSequenceNumber());
        } catch (IOException e) {
            log.error("Failed to send to local agent: ak={}, type={}",
                    ak, message.getType(), e);
        }
    }

    /**
     * 向指定 AK 的 Agent 发送 status_query 消息。
     * PC Agent 将返回包含 OpenCode 健康信息的 status_response。
     */
    public void sendStatusQuery(String ak) {
        GatewayMessage query = GatewayMessage.statusQuery();
        sendToLocalAgent(ak, query);
        log.info("Sent status_query to agent: ak={}", ak);
    }

    /**
     * 请求 Agent 的最新 OpenCode 健康状态，短暂等待 status_response。
     * 超时后降级使用上次缓存的值。
     */
    public Boolean requestAgentStatus(String ak) {
        if (!hasAgentSession(ak)) {
            return opencodeStatusCache.getOrDefault(ak, false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture<Boolean> previous = pendingStatusQueries.put(ak, future);
        if (previous != null && !previous.isDone()) {
            previous.complete(opencodeStatusCache.getOrDefault(ak, false));
        }

        sendStatusQuery(ak);

        try {
            return future.get(STATUS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Timed out waiting for status_response: ak={}", ak);
            return opencodeStatusCache.getOrDefault(ak, false);
        } finally {
            pendingStatusQueries.remove(ak, future);
        }
    }

    public void recordStatusResponse(String ak, Boolean opencodeOnline) {
        if (opencodeOnline == null) {
            return;
        }

        opencodeStatusCache.put(ak, opencodeOnline);
        CompletableFuture<Boolean> pending = pendingStatusQueries.remove(ak);
        if (pending != null) {
            pending.complete(opencodeOnline);
        }
    }

    /** 向所有当前连接的 Agent 发送 status_query。 */
    public void sendStatusQueryToAll() {
        agentSessions.forEach((ak, session) -> {
            if (session.isOpen()) {
                sendStatusQuery(ak);
            }
        });
    }

    public int getActiveSessionCount() {
        return (int) agentSessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }
}
