package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final UpstreamRoutingTable routingTable;
    private final String selfInstanceId;

    public EventRelayService(ObjectMapper objectMapper,
            RedisMessageBroker redisMessageBroker,
            SkillRelayService skillRelayService,
            UpstreamRoutingTable routingTable,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String selfInstanceId) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.skillRelayService = skillRelayService;
        this.routingTable = routingTable;
        this.selfInstanceId = selfInstanceId;

        // Break circular dependency: SkillRelayService needs EventRelayService for local agent lookup
        skillRelayService.setEventRelayService(this);
    }

    /**
     * Subscribes this GW instance to its own relay channel {@code gw:relay:{selfInstanceId}}.
     *
     * <p>On message receipt:
     * <ol>
     *   <li>If the raw JSON contains {@code "type":"relay"}, parse as {@link RelayMessage} and
     *       extract {@code originalMessage}.</li>
     *   <li>Otherwise treat as legacy raw {@link GatewayMessage} JSON (backward compatibility).</li>
     *   <li>Deserialize to {@link GatewayMessage} and deliver to the local Agent session.</li>
     * </ol>
     */
    @PostConstruct
    public void subscribeToSelfRelayChannel() {
        redisMessageBroker.subscribeToGwRelay(selfInstanceId, this::handleGwRelayMessage);
        log.info("[ENTRY] EventRelayService subscribed to GW relay channel: instanceId={}", selfInstanceId);
    }

    /**
     * Handles a raw JSON string received from the GW relay channel.
     *
     * <p>Distinguishes new-format ({@link RelayMessage}) from legacy raw {@link GatewayMessage}
     * JSON by checking for the {@code "type":"relay"} discriminator.
     *
     * @param rawJson raw JSON string from Redis
     */
    void handleGwRelayMessage(String rawJson) {
        try {
            String gatewayMessageJson;
            String relaySourceType = null;
            java.util.List<String> relayRoutingKeys = null;

            if (rawJson.contains("\"type\":\"relay\"")) {
                // New format: RelayMessage wrapper
                RelayMessage relayMessage = objectMapper.readValue(rawJson, RelayMessage.class);

                // Handle to-source relay: deliver to a local Source WebSocket connection
                if (RelayMessage.RELAY_TO_SOURCE.equals(relayMessage.relayType())) {
                    handleToSourceRelay(relayMessage);
                    return;
                }

                gatewayMessageJson = relayMessage.originalMessage();
                relaySourceType = relayMessage.sourceType();
                relayRoutingKeys = relayMessage.routingKeys();
                log.info("EventRelayService.handleGwRelayMessage: new-format relay, sourceType={}",
                        relaySourceType);
            } else {
                // Legacy format: raw GatewayMessage JSON
                gatewayMessageJson = rawJson;
                log.info("EventRelayService.handleGwRelayMessage: legacy-format relay, length={}", rawJson.length());
            }

            // V2: Propagate routing knowledge from relay metadata
            if (relaySourceType != null && relayRoutingKeys != null && !relayRoutingKeys.isEmpty()) {
                routingTable.learnFromRelay(relayRoutingKeys, relaySourceType);
                log.info("EventRelayService.handleGwRelayMessage: propagated {} routing keys for sourceType={}",
                        relayRoutingKeys.size(), relaySourceType);
            }

            GatewayMessage message = objectMapper.readValue(gatewayMessageJson, GatewayMessage.class);
            String ak = message.getAk();
            if (ak == null || ak.isBlank()) {
                log.warn("[ERROR] EventRelayService.handleGwRelayMessage: ak is null or blank, dropping message type={}",
                        message.getType());
                return;
            }

            log.info("EventRelayService.handleGwRelayMessage: delivering to local agent, ak={}, type={}", ak, message.getType());
            sendToLocalAgent(ak, message);
        } catch (Exception e) {
            log.error("[ERROR] EventRelayService.handleGwRelayMessage: failed to process relay message: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Handles a to-source relay message by delivering the payload to the local Source WebSocket connection.
     *
     * @param relayMessage the relay message with relayType="to-source"
     */
    private void handleToSourceRelay(RelayMessage relayMessage) {
        String targetSourceType = relayMessage.targetSourceType();
        String targetSourceInstanceId = relayMessage.targetSourceInstanceId();
        String payload = relayMessage.originalMessage();

        log.info("EventRelayService.handleToSourceRelay: targetSourceType={}, targetSourceInstanceId={}",
                targetSourceType, targetSourceInstanceId);

        WebSocketSession session = skillRelayService.findLocalSourceConnection(
                targetSourceType, targetSourceInstanceId);
        if (session != null) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
                log.info("[EXIT->SOURCE] Delivered to-source relay: sourceType={}, sourceInstanceId={}",
                        targetSourceType, targetSourceInstanceId);
            } catch (IOException e) {
                log.error("[ERROR] Failed to deliver to-source relay: sourceType={}, sourceInstanceId={}",
                        targetSourceType, targetSourceInstanceId, e);
            }
        } else {
            log.debug("No local connection for source {}/{}, discarding relay",
                    targetSourceType, targetSourceInstanceId);
        }
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

        // 保存调用方的 MDC 上下文，方法结束后恢复（避免清除调用方已设置的 traceId/ak）
        var previousMdc = MdcHelper.snapshot();
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
            MdcHelper.restore(previousMdc);
        }
    }

    public void relayToAgent(String ak, GatewayMessage message) {
        log.info("[ENTRY] EventRelayService.relayToAgent: ak={}, type={}", ak, message.getType());
        redisMessageBroker.publishToAgent(ak, message.withoutRoutingContext());
        log.info("[EXIT->AGENT] EventRelayService.relayToAgent: ak={}, type={}", ak, message.getType());
    }

    /**
     * Attempts to deliver a message to a locally connected Agent.
     * Used by SkillRelayService for V2 local-first delivery.
     *
     * @return true if the Agent is connected locally and the message was sent successfully
     */
    public boolean sendToLocalAgentIfPresent(String ak, GatewayMessage message) {
        WebSocketSession session = agentSessions.get(ak);
        if (session == null || !session.isOpen()) {
            return false;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.info("[EXIT->AGENT] Sent to local agent (V2 direct): ak={}, type={}",
                    ak, message.getType());
            return true;
        } catch (IOException e) {
            log.error("[ERROR] Failed to send to local agent (V2 direct): ak={}, type={}",
                    ak, message.getType(), e);
            return false;
        }
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
