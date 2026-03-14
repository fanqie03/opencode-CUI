package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Routes messages between PC Agent WebSocket sessions and Skill Server.
 * Agent sessions are keyed by AK (access key) for consistent routing
 * across the entire system (Gateway ↔ Skill Server).
 */
@Slf4j
@Service
public class EventRelayService {

    /** 状态查询等待超时（毫秒） */
    private static final long STATUS_QUERY_TIMEOUT_MS = 1500L;

    /** Map of ak → WebSocket session for connected agents */
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
        redisMessageBroker.removeAgentSource(ak);
        redisMessageBroker.unsubscribeFromAgent(ak);
        log.debug("Removed agent session: ak={}", ak);
    }

    public boolean hasAgentSession(String ak) {
        WebSocketSession session = agentSessions.get(ak);
        return session != null && session.isOpen();
    }

    public void relayToSkillServer(String ak, GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String userId = redisMessageBroker.getAgentUser(ak);
        String source = tracedMessage.getSource();
        if (source == null || source.isBlank()) {
            source = redisMessageBroker.getAgentSource(ak);
        }
        GatewayMessage forwarded = tracedMessage.withAk(ak)
                .withUserId(userId)
                .withSource(source);

        log.debug("Routing upstream message: traceId={}, source={}, ak={}, userId={}, type={}, routeDecision=to_upstream, fallbackUsed=false, welinkSessionId={}, toolSessionId={}",
                forwarded.getTraceId(), source, ak, userId, tracedMessage.getType(),
                forwarded.getWelinkSessionId(), forwarded.getToolSessionId());

        try {
            boolean routed = skillRelayService.relayToSkill(forwarded);
            if (!routed) {
                log.warn("Failed to route message to skill: ak={}, type={}, welinkSessionId={}",
                        ak, message.getType(), forwarded.getWelinkSessionId());
            }
        } catch (Exception e) {
            log.error("Failed to relay to skill: ak={}, type={}",
                    ak, message.getType(), e);
        }
    }



    public void relayToAgent(String ak, GatewayMessage message) {
        redisMessageBroker.publishToAgent(ak, message.withoutRoutingContext());
        log.debug("Published to agent channel: ak={}, type={}", ak, message.getType());
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
            log.debug("Sent to local agent: ak={}, type={}, seq={}",
                    ak, message.getType(), message.getSequenceNumber());
        } catch (IOException e) {
            log.error("Failed to send to local agent: ak={}, type={}",
                    ak, message.getType(), e);
        }
    }

    /**
     * Send a status_query message to the agent identified by ak.
     * The PC Agent will respond with a status_response containing OpenCode health
     * info.
     */
    public void sendStatusQuery(String ak) {
        GatewayMessage query = GatewayMessage.statusQuery();
        sendToLocalAgent(ak, query);
        log.debug("Sent status_query to agent: ak={}", ak);
    }

    /**
     * Request the latest OpenCode health from an agent and wait briefly for a
     * status_response. Falls back to the last cached value on timeout.
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

    /**
     * Send status_query to all currently connected agents.
     */
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
