package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes messages between PC Agent WebSocket sessions and Skill Server.
 * Agent sessions are keyed by AK (access key) for consistent routing
 * across the entire system (Gateway ↔ Skill Server).
 */
@Slf4j
@Service
public class EventRelayService {

    /** Map of ak → WebSocket session for connected agents */
    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();

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

    public void registerAgentSession(String ak, WebSocketSession session) {
        WebSocketSession old = agentSessions.put(ak, session);
        if (old != null && old.isOpen()) {
            try {
                old.close();
                log.info("Closed old WebSocket session for ak={}", ak);
            } catch (IOException e) {
                log.warn("Error closing old session for ak={}", ak, e);
            }
        }

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

        redisMessageBroker.unsubscribeFromAgent(ak);
        log.debug("Removed agent session: ak={}", ak);
    }

    public boolean hasAgentSession(String ak) {
        WebSocketSession session = agentSessions.get(ak);
        return session != null && session.isOpen();
    }

    public void relayToSkillServer(String ak, GatewayMessage message) {
        GatewayMessage forwarded = message.withAk(ak);

        log.debug("Relaying to skill: ak={}, type={}, sessionId={}, toolSessionId={}",
                ak, message.getType(), forwarded.getSessionId(), forwarded.getToolSessionId());

        try {
            boolean routed = skillRelayService.relayToSkill(forwarded);
            if (!routed) {
                log.warn("Failed to route message to skill: ak={}, type={}, sessionId={}",
                        ak, message.getType(), forwarded.getSessionId());
            }
        } catch (Exception e) {
            log.error("Failed to relay to skill: ak={}, type={}",
                    ak, message.getType(), e);
        }
    }

    public void relayToAgent(String ak, GatewayMessage message) {
        redisMessageBroker.publishToAgent(ak, message);
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
            String json = objectMapper.writeValueAsString(message);
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

    public int getActiveSessionCount() {
        return (int) agentSessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }
}
