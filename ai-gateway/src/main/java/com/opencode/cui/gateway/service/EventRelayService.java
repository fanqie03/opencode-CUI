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
 * Bidirectional event relay between PCAgent and Skill Server (v1 protocol -
 * 方案5).
 *
 * Upstream (PCAgent �?Skill): Events from PCAgent go through WS to Skill Server
 * via SkillServerWSClient (SkillServerRelayTarget interface).
 *
 * Downstream (Skill �?PCAgent): Invoke commands arrive from Skill via WS,
 * are published to Gateway Redis agent:{agentId}, and routed to the Gateway
 * instance holding the Agent's WS connection.
 *
 * Gateway Redis is used only for internal agent routing across Gateway
 * instances.
 */
@Slf4j
@Service
public class EventRelayService {

    /** agentId -> PCAgent WebSocket session (local connections only) */
    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final RedisMessageBroker redisMessageBroker;

    /**
     * Reference to SkillServerWSClient, set after initialization to avoid
     * circular dependency. Injected by SkillServerWSClient.setEventRelayService().
     */
    private volatile SkillServerRelayTarget skillServerRelay;

    public EventRelayService(ObjectMapper objectMapper, RedisMessageBroker redisMessageBroker) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
    }

    /**
     * Callback interface for forwarding messages to Skill Server.
     * Implemented by SkillServerWSClient to break circular dependency.
     */
    public interface SkillServerRelayTarget {
        void sendToSkillServer(GatewayMessage message);
    }

    /**
     * Set the Skill Server relay target (called by SkillServerWSClient on init).
     */
    public void setSkillServerRelay(SkillServerRelayTarget relay) {
        this.skillServerRelay = relay;
    }

    // ========== Agent session management ==========

    /**
     * Register a PCAgent WebSocket session.
     * Subscribes to Redis channel for this agent to receive messages from any
     * instance.
     */
    public void registerAgentSession(String agentId, WebSocketSession session) {
        WebSocketSession old = agentSessions.put(agentId, session);
        if (old != null && old.isOpen()) {
            try {
                old.close();
                log.info("Closed old WebSocket session for agentId={}", agentId);
            } catch (IOException e) {
                log.warn("Error closing old session for agentId={}", agentId, e);
            }
        }

        // Subscribe to Redis channel for this agent
        redisMessageBroker.subscribeToAgent(agentId, message -> {
            sendToLocalAgent(agentId, message);
        });

        log.info("Registered agent session: agentId={}, sessionId={}", agentId, session.getId());
    }

    /**
     * Remove a PCAgent WebSocket session.
     * Unsubscribes from Redis channel for this agent.
     */
    public void removeAgentSession(String agentId) {
        WebSocketSession session = agentSessions.remove(agentId);
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.warn("Error closing session during removal for agentId={}", agentId, e);
            }
        }

        // Unsubscribe from Redis channel
        redisMessageBroker.unsubscribeFromAgent(agentId);

        log.debug("Removed agent session: agentId={}", agentId);
    }

    /**
     * Check if an agent has an active WebSocket session.
     */
    public boolean hasAgentSession(String agentId) {
        WebSocketSession session = agentSessions.get(agentId);
        return session != null && session.isOpen();
    }

    // ========== Relay operations ==========

    /**
     * Relay a message from PCAgent to Skill Server.
     * Attaches the agentId to the message before forwarding.
     *
     * @param agentId the agent's connection ID
     * @param message the message from PCAgent
     */
    public void relayToSkillServer(String agentId, GatewayMessage message) {
        if (skillServerRelay == null) {
            log.warn("Cannot relay to Skill Server: relay target not set. agentId={}, type={}",
                    agentId, message.getType());
            return;
        }

        // Attach agentId for Skill Server routing
        GatewayMessage forwarded = message.withAgentId(agentId);

        // Log envelope metadata if present
        if (forwarded.hasEnvelope()) {
            var env = forwarded.getEnvelope();
            log.debug("Relaying enveloped message: agentId={}, type={}, messageId={}, seq={}, source={}",
                    agentId, message.getType(), env.getMessageId(), env.getSequenceNumber(), env.getSource());
        }

        try {
            skillServerRelay.sendToSkillServer(forwarded);
            log.debug("Relayed to Skill Server: agentId={}, type={}", agentId, message.getType());
        } catch (Exception e) {
            log.error("Failed to relay to Skill Server: agentId={}, type={}",
                    agentId, message.getType(), e);
        }
    }

    /**
     * Relay a message from Skill Server to a specific PCAgent.
     * Publishes to Redis channel - any gateway instance with this agent connected
     * will receive it.
     *
     * @param agentId the target agent's connection ID
     * @param message the message from Skill Server
     */
    public void relayToAgent(String agentId, GatewayMessage message) {
        // Publish to Redis channel instead of direct WebSocket send
        // The instance with the active agent connection will receive and forward it
        redisMessageBroker.publishToAgent(agentId, message);
        log.debug("Published to agent channel: agentId={}, type={}", agentId, message.getType());
    }

    /**
     * Send a message to a locally connected agent (called by Redis subscription
     * handler).
     *
     * @param agentId the target agent's connection ID
     * @param message the message to send
     */
    private void sendToLocalAgent(String agentId, GatewayMessage message) {
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            log.debug("Agent not connected to this instance: agentId={}, type={}",
                    agentId, message.getType());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.debug("Sent to local agent: agentId={}, type={}, seq={}",
                    agentId, message.getType(), message.getSequenceNumber());
        } catch (IOException e) {
            log.error("Failed to send to local agent: agentId={}, type={}",
                    agentId, message.getType(), e);
        }
    }

    /**
     * Get the number of currently active agent sessions.
     */
    public int getActiveSessionCount() {
        return (int) agentSessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }
}
