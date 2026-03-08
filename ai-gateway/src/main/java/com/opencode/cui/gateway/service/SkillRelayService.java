package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages Skill Server WebSocket connections and routes upstream messages
 * to any available Skill link.
 *
 * Gateway does NOT perform session-level routing. All upstream messages are
 * sent to any available Skill Server instance. Skill Server resolves
 * toolSessionId → welinkSessionId via its own DB.
 */
@Slf4j
@Service
public class SkillRelayService {

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    private final String instanceId;
    private final Duration ownerTtl;

    private final Map<String, WebSocketSession> skillSessions = new ConcurrentHashMap<>();
    private final AtomicReference<String> defaultLinkId = new AtomicReference<>();
    private final AtomicBoolean relaySubscribed = new AtomicBoolean(false);

    public SkillRelayService(RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String instanceId,
            @Value("${gateway.skill-relay.owner-ttl-seconds:30}") long ownerTtlSeconds) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.ownerTtl = Duration.ofSeconds(ownerTtlSeconds);
    }

    public void registerSkillSession(WebSocketSession session) {
        String linkId = session.getId();
        skillSessions.put(linkId, session);
        defaultLinkId.compareAndSet(null, linkId);

        ensureRelaySubscription();
        refreshOwnerState();

        log.info("Registered skill link: instanceId={}, linkId={}, activeLinks={}",
                instanceId, linkId, getActiveSkillConnectionCount());
    }

    public void removeSkillSession(WebSocketSession session) {
        String linkId = session.getId();
        skillSessions.remove(linkId);

        if (linkId.equals(defaultLinkId.get())) {
            defaultLinkId.set(selectAnyOpenLinkId());
        }

        if (skillSessions.isEmpty()) {
            clearOwnerState();
        } else {
            refreshOwnerState();
        }

        log.info("Removed skill link: instanceId={}, linkId={}, activeLinks={}",
                instanceId, linkId, getActiveSkillConnectionCount());
    }

    /**
     * Handle invoke messages FROM Skill Server → route to target agent via Redis.
     */
    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        if (message.getAgentId() == null || message.getAgentId().isBlank()) {
            log.warn("Invoke from skill missing agentId: linkId={}, action={}",
                    session.getId(), message.getAction());
            return;
        }

        redisMessageBroker.publishToAgent(message.getAgentId(), message);
        log.debug("Forwarded invoke from skill to agent: linkId={}, agentId={}, action={}",
                session.getId(), message.getAgentId(), message.getAction());
    }

    /**
     * Route an upstream message (from PC Agent) to any available Skill link.
     * No session-level routing — just pick any open link on this instance,
     * or relay to another Gateway instance that has a Skill connection.
     */
    public boolean relayToSkill(GatewayMessage message) {
        // Try local: send via any available skill link on this instance
        if (sendViaDefaultLink(message)) {
            return true;
        }

        // Local failed — relay to another Gateway instance with a Skill connection
        String ownerId = selectOwner(message.getType());
        if (ownerId == null) {
            log.warn("No active skill owner available: type={}, agentId={}",
                    message.getType(), message.getAgentId());
            return false;
        }

        if (instanceId.equals(ownerId)) {
            // We are the selected owner but local send failed — no skill link available
            return sendViaDefaultLink(message);
        }

        redisMessageBroker.publishToRelay(ownerId, message);
        return true;
    }

    /**
     * Handle a message relayed from another Gateway instance.
     */
    public void handleRelayedMessage(GatewayMessage message) {
        if (!sendViaDefaultLink(message)) {
            log.warn("Relay target gateway has no active skill link: instanceId={}, type={}",
                    instanceId, message.getType());
        }
    }

    public int getActiveSkillConnectionCount() {
        return (int) skillSessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).ofSeconds(${gateway.skill-relay.owner-heartbeat-interval-seconds:10}).toMillis()}")
    public void refreshOwnerHeartbeat() {
        if (!skillSessions.isEmpty()) {
            refreshOwnerState();
        }
    }

    @PreDestroy
    public void destroy() {
        clearOwnerState();
    }

    // ========== Internal methods ==========

    private boolean sendViaDefaultLink(GatewayMessage message) {
        WebSocketSession session = resolveDefaultSession();
        if (session == null) {
            return false;
        }
        return sendToSession(session, message);
    }

    private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.debug("Sent to skill link: instanceId={}, linkId={}, type={}, sessionId={}",
                    instanceId, session.getId(), message.getType(), message.getSessionId());
            return true;
        } catch (IOException e) {
            log.error("Failed to send to skill link: instanceId={}, linkId={}, type={}",
                    instanceId, session.getId(), message.getType(), e);
            return false;
        }
    }

    private String selectOwner(String key) {
        Set<String> owners = redisMessageBroker.getActiveSkillOwners();
        if (owners.isEmpty()) {
            return null;
        }

        return owners.stream()
                .max(Comparator.comparingLong(ownerId -> rendezvousScore(key, ownerId)))
                .orElse(null);
    }

    private long rendezvousScore(String key, String ownerId) {
        String stableKey = key != null && !key.isBlank() ? key : "default";
        return Integer.toUnsignedLong((stableKey + "|" + ownerId).hashCode());
    }

    private WebSocketSession resolveDefaultSession() {
        String preferredLinkId = defaultLinkId.get();
        if (preferredLinkId != null) {
            WebSocketSession preferredSession = skillSessions.get(preferredLinkId);
            if (preferredSession != null && preferredSession.isOpen()) {
                return preferredSession;
            }
        }

        String replacement = selectAnyOpenLinkId();
        defaultLinkId.set(replacement);
        return replacement != null ? skillSessions.get(replacement) : null;
    }

    private String selectAnyOpenLinkId() {
        return skillSessions.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().isOpen())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void ensureRelaySubscription() {
        if (relaySubscribed.compareAndSet(false, true)) {
            redisMessageBroker.subscribeToRelay(instanceId, this::handleRelayedMessage);
        }
    }

    private void refreshOwnerState() {
        redisMessageBroker.refreshSkillOwner(instanceId, ownerTtl);
    }

    private void clearOwnerState() {
        redisMessageBroker.removeSkillOwner(instanceId);
        if (relaySubscribed.compareAndSet(true, false)) {
            redisMessageBroker.unsubscribeFromRelay(instanceId);
        }
    }
}
