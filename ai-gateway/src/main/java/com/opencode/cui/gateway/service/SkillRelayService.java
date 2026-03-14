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
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages upstream service WebSocket connections and routes messages within the
 * correct source domain.
 */
@Slf4j
@Service
public class SkillRelayService {

    public static final String SOURCE_ATTR = "source";
    public static final String SKILL_SOURCE = "skill-server";
    public static final String ERROR_SOURCE_NOT_ALLOWED = "source_not_allowed";
    public static final String ERROR_SOURCE_MISMATCH = "source_mismatch";

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    private final String instanceId;
    private final Duration ownerTtl;

    private final Map<String, Map<String, WebSocketSession>> sourceSessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<String>> defaultLinkIds = new ConcurrentHashMap<>();
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
        String source = resolveBoundSource(session);
        if (source == null || source.isBlank()) {
            log.warn("Skipping upstream session registration without bound source: instanceId={}, linkId={}",
                    instanceId, session.getId());
            return;
        }

        String linkId = session.getId();
        sourceSessions.computeIfAbsent(source, ignored -> new ConcurrentHashMap<>()).put(linkId, session);
        defaultLinkRef(source).compareAndSet(null, linkId);

        ensureRelaySubscription();
        refreshOwnerState(source);

        log.info("Registered upstream link: source={}, instanceId={}, linkId={}, activeLinks={}",
                source, instanceId, linkId, getActiveConnectionCount(source));
    }

    public void removeSkillSession(WebSocketSession session) {
        String source = resolveBoundSource(session);
        if (source == null || source.isBlank()) {
            return;
        }

        Map<String, WebSocketSession> sessionsBySource = sourceSessions.get(source);
        if (sessionsBySource == null) {
            return;
        }

        String linkId = session.getId();
        sessionsBySource.remove(linkId);

        AtomicReference<String> defaultLinkRef = defaultLinkRef(source);
        if (linkId.equals(defaultLinkRef.get())) {
            defaultLinkRef.set(selectAnyOpenLinkId(source));
        }

        if (sessionsBySource.isEmpty()) {
            sourceSessions.remove(source, sessionsBySource);
            defaultLinkIds.remove(source);
            clearOwnerState(source);
        } else {
            refreshOwnerState(source);
        }

        unsubscribeRelayIfIdle();

        log.info("Removed upstream link: source={}, instanceId={}, linkId={}, activeLinks={}",
                source, instanceId, linkId, getActiveConnectionCount(source));
    }

    /**
     * Handle invoke messages from an upstream service and route them to the
     * target agent.
     */
    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String boundSource = resolveBoundSource(session);
        String messageSource = tracedMessage.getSource();
        if (messageSource == null || messageSource.isBlank()) {
            log.warn("Rejected upstream invoke: traceId={}, linkId={}, boundSource={}, messageSource={}, routeDecision=rejected, fallbackUsed=false, errorCode={}, ak={}, action={}",
                    tracedMessage.getTraceId(), session.getId(), boundSource, messageSource,
                    ERROR_SOURCE_NOT_ALLOWED, tracedMessage.getAk(), tracedMessage.getAction());
            sendProtocolError(session, ERROR_SOURCE_NOT_ALLOWED);
            return;
        }
        if (boundSource == null || !boundSource.equals(messageSource)) {
            log.warn("Rejected upstream invoke: traceId={}, linkId={}, boundSource={}, messageSource={}, routeDecision=rejected, fallbackUsed=false, errorCode={}, ak={}, action={}",
                    tracedMessage.getTraceId(), session.getId(), boundSource, messageSource,
                    ERROR_SOURCE_MISMATCH, tracedMessage.getAk(), tracedMessage.getAction());
            sendProtocolError(session, ERROR_SOURCE_MISMATCH);
            return;
        }
        if (tracedMessage.getAk() == null || tracedMessage.getAk().isBlank()) {
            log.warn("Invoke from upstream missing ak: linkId={}, action={}",
                    session.getId(), tracedMessage.getAction());
            return;
        }

        String expectedUserId = redisMessageBroker.getAgentUser(tracedMessage.getAk());
        if (tracedMessage.getUserId() == null || tracedMessage.getUserId().isBlank()) {
            log.warn("Invoke from upstream missing userId: linkId={}, ak={}, action={}",
                    session.getId(), tracedMessage.getAk(), tracedMessage.getAction());
            return;
        }
        if (expectedUserId == null || !tracedMessage.getUserId().equals(expectedUserId)) {
            log.warn("Invoke from upstream rejected by user validation: linkId={}, ak={}, userId={}, expectedUserId={}, action={}",
                    session.getId(), tracedMessage.getAk(), tracedMessage.getUserId(), expectedUserId, tracedMessage.getAction());
            return;
        }

        redisMessageBroker.bindAgentSource(tracedMessage.getAk(), messageSource);
        redisMessageBroker.publishToAgent(tracedMessage.getAk(), tracedMessage.withoutRoutingContext());
        log.debug("Forwarded invoke to agent: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=to_agent, fallbackUsed=false, errorCode=none, linkId={}, ak={}, userId={}, action={}",
                tracedMessage.getTraceId(), messageSource, instanceId,
                RedisMessageBroker.sourceOwnerMember(messageSource, instanceId), tracedMessage.getType(),
                session.getId(), tracedMessage.getAk(), tracedMessage.getUserId(), tracedMessage.getAction());
    }

    /**
     * Route a message from PC Agent to the correct upstream source domain.
     */
    public boolean relayToSkill(GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String source = resolveMessageSource(tracedMessage);
        if (source == null) {
            log.warn("Rejected upstream relay: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=rejected, fallbackUsed=false, errorCode=source_not_allowed, ak={}, toolSessionId={}, welinkSessionId={}",
                    tracedMessage.getTraceId(), null, instanceId, null, tracedMessage.getType(),
                    tracedMessage.getAk(), tracedMessage.getToolSessionId(), tracedMessage.getWelinkSessionId());
            return false;
        }

        GatewayMessage routedMessage = tracedMessage.withSource(source);
        if (sendViaDefaultLink(source, routedMessage)) {
            log.debug("Routed upstream message locally: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=local_link, fallbackUsed=false, errorCode=none, ak={}",
                    routedMessage.getTraceId(), source, instanceId,
                    RedisMessageBroker.sourceOwnerMember(source, instanceId), routedMessage.getType(), routedMessage.getAk());
            return true;
        }

        String ownerId = selectOwner(source, tracedMessage.getType());
        if (ownerId == null) {
            log.warn("Upstream routing failed: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=no_owner, fallbackUsed=true, errorCode=owner_unavailable, agentId={}, ak={}",
                    routedMessage.getTraceId(), source, instanceId, null, routedMessage.getType(),
                    tracedMessage.getAgentId(), tracedMessage.getAk());
            return false;
        }

        if (instanceId.equals(ownerId)) {
            boolean routed = sendViaDefaultLink(source, routedMessage);
            log.debug("Retried upstream message on local owner: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=local_fallback, fallbackUsed=true, errorCode={}, ak={}",
                    routedMessage.getTraceId(), source, instanceId,
                    RedisMessageBroker.sourceOwnerMember(source, instanceId), routedMessage.getType(),
                    routed ? "none" : "owner_unavailable", routedMessage.getAk());
            return routed;
        }

        redisMessageBroker.publishToRelay(ownerId, routedMessage);
        log.debug("Relayed upstream message to remote owner: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=remote_owner, fallbackUsed=true, errorCode=none, ak={}",
                routedMessage.getTraceId(), source, instanceId,
                RedisMessageBroker.sourceOwnerMember(source, ownerId), routedMessage.getType(), routedMessage.getAk());
        return true;
    }

    /**
     * Handle a message relayed from another Gateway instance.
     */
    public void handleRelayedMessage(GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String source = resolveMessageSource(tracedMessage);
        if (source == null) {
            log.warn("Rejected relayed upstream message: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=rejected, fallbackUsed=false, errorCode=source_not_allowed",
                    tracedMessage.getTraceId(), null, instanceId, null, tracedMessage.getType());
            return;
        }

        if (!sendViaDefaultLink(source, tracedMessage.withSource(source))) {
            log.warn("Relay target gateway has no active upstream link: traceId={}, source={}, instanceId={}, ownerKey={}, messageType={}, routeDecision=relay_delivery_failed, fallbackUsed=true, errorCode=owner_unavailable",
                    tracedMessage.getTraceId(), source, instanceId,
                    RedisMessageBroker.sourceOwnerMember(source, instanceId), tracedMessage.getType());
        }
    }

    public int getActiveSkillConnectionCount() {
        return (int) sourceSessions.values().stream()
                .map(Map::values)
                .flatMap(Collection::stream)
                .filter(WebSocketSession::isOpen)
                .count();
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).ofSeconds(${gateway.skill-relay.owner-heartbeat-interval-seconds:10}).toMillis()}")
    public void refreshOwnerHeartbeat() {
        sourceSessions.keySet().forEach(this::refreshOwnerState);
    }

    @PreDestroy
    public void destroy() {
        sourceSessions.keySet().forEach(this::clearOwnerState);
        if (relaySubscribed.compareAndSet(true, false)) {
            redisMessageBroker.unsubscribeFromRelay(instanceId);
        }
    }

    private boolean sendViaDefaultLink(String source, GatewayMessage message) {
        WebSocketSession session = resolveDefaultSession(source);
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
            log.debug("Sent to upstream link: source={}, instanceId={}, linkId={}, type={}, welinkSessionId={}, toolSessionId={}",
                    resolveBoundSource(session), instanceId, session.getId(), message.getType(),
                    message.getWelinkSessionId(), message.getToolSessionId());
            return true;
        } catch (IOException e) {
            log.error("Failed to send to upstream link: source={}, instanceId={}, linkId={}, type={}",
                    resolveBoundSource(session), instanceId, session.getId(), message.getType(), e);
            return false;
        }
    }

    private String selectOwner(String source, String key) {
        Set<String> owners = redisMessageBroker.getActiveSourceOwners(source);
        if (owners.isEmpty()) {
            return null;
        }

        return owners.stream()
                .max(Comparator.comparingLong(ownerKey -> rendezvousScore(key, ownerKey)))
                .map(RedisMessageBroker::instanceIdFromOwnerKey)
                .orElse(null);
    }

    private long rendezvousScore(String key, String ownerKey) {
        String stableKey = key != null && !key.isBlank() ? key : "default";
        return Integer.toUnsignedLong((stableKey + "|" + ownerKey).hashCode());
    }

    private WebSocketSession resolveDefaultSession(String source) {
        AtomicReference<String> preferredLinkRef = defaultLinkIds.get(source);
        String preferredLinkId = preferredLinkRef != null ? preferredLinkRef.get() : null;
        Map<String, WebSocketSession> sessionsBySource = sessionMap(source);
        if (preferredLinkId != null) {
            WebSocketSession preferredSession = sessionsBySource.get(preferredLinkId);
            if (preferredSession != null && preferredSession.isOpen()) {
                return preferredSession;
            }
        }

        String replacement = selectAnyOpenLinkId(source);
        defaultLinkRef(source).set(replacement);
        return replacement != null ? sessionsBySource.get(replacement) : null;
    }

    private String selectAnyOpenLinkId(String source) {
        return sessionMap(source).entrySet().stream()
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

    private void refreshOwnerState(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        if (!hasOpenSession(source)) {
            clearOwnerState(source);
            return;
        }
        redisMessageBroker.refreshSourceOwner(source, instanceId, ownerTtl);
    }

    private void clearOwnerState(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        redisMessageBroker.removeSourceOwner(source, instanceId);
    }

    private void unsubscribeRelayIfIdle() {
        if (!hasAnyOpenSession() && relaySubscribed.compareAndSet(true, false)) {
            redisMessageBroker.unsubscribeFromRelay(instanceId);
        }
    }

    private boolean hasAnyOpenSession() {
        return sourceSessions.keySet().stream().anyMatch(this::hasOpenSession);
    }

    private boolean hasOpenSession(String source) {
        return sessionMap(source).values().stream().anyMatch(WebSocketSession::isOpen);
    }

    private int getActiveConnectionCount(String source) {
        return (int) sessionMap(source).values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }

    private Map<String, WebSocketSession> sessionMap(String source) {
        return sourceSessions.getOrDefault(source, Map.of());
    }

    private AtomicReference<String> defaultLinkRef(String source) {
        return defaultLinkIds.computeIfAbsent(source, ignored -> new AtomicReference<>());
    }

    private String resolveMessageSource(GatewayMessage message) {
        String source = message.getSource();
        if (source != null && !source.isBlank()) {
            return source;
        }
        if (message.getAk() == null || message.getAk().isBlank()) {
            return inferSingleActiveSource();
        }
        String boundSource = redisMessageBroker.getAgentSource(message.getAk());
        if (boundSource != null && !boundSource.isBlank()) {
            return boundSource;
        }
        return inferSingleActiveSource();
    }

    private String inferSingleActiveSource() {
        String resolved = null;
        for (String source : sourceSessions.keySet()) {
            if (!hasOpenSession(source)) {
                continue;
            }
            if (resolved != null) {
                return null;
            }
            resolved = source;
        }
        return resolved;
    }



    private String resolveBoundSource(WebSocketSession session) {
        Object source = session.getAttributes().get(SOURCE_ATTR);
        return source instanceof String ? (String) source : null;
    }

    private void sendProtocolError(WebSocketSession session, String reason) {
        try {
            String json = objectMapper.writeValueAsString(GatewayMessage.registerRejected(reason));
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Failed to send protocol error to upstream link: linkId={}, reason={}",
                    session.getId(), reason, e);
        }
    }
}
