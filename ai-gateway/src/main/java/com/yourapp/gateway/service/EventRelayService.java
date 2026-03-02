package com.yourapp.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional event relay between PCAgent and Skill Server.
 *
 * Maintains an in-memory map of agentId -> WebSocketSession for routing
 * messages from Skill Server back to the correct PCAgent.
 */
@Slf4j
@Service
public class EventRelayService {

    /** agentId -> PCAgent WebSocket session */
    private final Map<Long, WebSocketSession> agentSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /**
     * Reference to SkillServerWSClient, set after initialization to avoid
     * circular dependency. Injected by SkillServerWSClient.setEventRelayService().
     */
    private volatile SkillServerRelayTarget skillServerRelay;

    public EventRelayService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
     */
    public void registerAgentSession(Long agentId, WebSocketSession session) {
        WebSocketSession old = agentSessions.put(agentId, session);
        if (old != null && old.isOpen()) {
            try {
                old.close();
                log.info("Closed old WebSocket session for agentId={}", agentId);
            } catch (IOException e) {
                log.warn("Error closing old session for agentId={}", agentId, e);
            }
        }
        log.info("Registered agent session: agentId={}, sessionId={}", agentId, session.getId());
    }

    /**
     * Remove a PCAgent WebSocket session.
     */
    public void removeAgentSession(Long agentId) {
        WebSocketSession session = agentSessions.remove(agentId);
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.warn("Error closing session during removal for agentId={}", agentId, e);
            }
        }
        log.debug("Removed agent session: agentId={}", agentId);
    }

    /**
     * Check if an agent has an active WebSocket session.
     */
    public boolean hasAgentSession(Long agentId) {
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
    public void relayToSkillServer(Long agentId, GatewayMessage message) {
        if (skillServerRelay == null) {
            log.warn("Cannot relay to Skill Server: relay target not set. agentId={}, type={}",
                    agentId, message.getType());
            return;
        }

        // Attach agentId for Skill Server routing
        GatewayMessage forwarded = message.withAgentId(String.valueOf(agentId));

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
     *
     * @param agentId the target agent's connection ID
     * @param message the message from Skill Server
     */
    public void relayToAgent(Long agentId, GatewayMessage message) {
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            log.warn("Cannot relay to agent: no active session. agentId={}, type={}",
                    agentId, message.getType());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.debug("Relayed to agent: agentId={}, type={}", agentId, message.getType());
        } catch (IOException e) {
            log.error("Failed to relay to agent: agentId={}, type={}", agentId, message.getType(), e);
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
