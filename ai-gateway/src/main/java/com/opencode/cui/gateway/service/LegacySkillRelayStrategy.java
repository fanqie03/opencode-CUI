package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
 * 旧版 Source 服务路由策略。
 *
 * <p>兼容不带 instanceId 的旧版 Source 服务（如未升级的 Skill Server）。
 * 核心机制：</p>
 * <ul>
 *   <li>本地 defaultLink 优先投递</li>
 *   <li>Owner 心跳 + Redis relay 跨 Gateway 实例中继</li>
 *   <li>Rendezvous Hash 选择 owner 实例</li>
 *   <li>bindAgentSource / getAgentSource 进行 source 解析</li>
 * </ul>
 *
 * <p>此类从旧版 SkillRelayService（codeclean-0313）独立提取，
 * 与新版 Mesh 路由逻辑完全隔离。</p>
 */
@Slf4j
@Component
public class LegacySkillRelayStrategy implements SkillRelayStrategy {

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final Duration ownerTtl;

    /** source → { linkId → WebSocketSession } */
    private final Map<String, Map<String, WebSocketSession>> sourceSessions = new ConcurrentHashMap<>();

    /** source → 默认 linkId */
    private final Map<String, AtomicReference<String>> defaultLinkIds = new ConcurrentHashMap<>();

    /** Redis relay 订阅状态 */
    private final AtomicBoolean relaySubscribed = new AtomicBoolean(false);

    public LegacySkillRelayStrategy(
            RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String instanceId,
            @Value("${gateway.skill-relay.owner-ttl-seconds:30}") long ownerTtlSeconds) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.ownerTtl = Duration.ofSeconds(ownerTtlSeconds);
    }

    // ==================== SkillRelayStrategy 接口实现 ====================

    @Override
    public void registerSession(WebSocketSession session) {
        String source = resolveBoundSource(session);
        if (source == null || source.isBlank()) {
            log.warn("[Legacy] Skipping session registration: missing source, linkId={}", session.getId());
            return;
        }

        String linkId = session.getId();
        sourceSessions.computeIfAbsent(source, ignored -> new ConcurrentHashMap<>()).put(linkId, session);
        defaultLinkRef(source).compareAndSet(null, linkId);

        ensureRelaySubscription();
        refreshOwnerState(source);

        log.info("[Legacy] Registered source session: source={}, instanceId={}, linkId={}, activeLinks={}",
                source, instanceId, linkId, getActiveConnectionCount(source));
    }

    @Override
    public void removeSession(WebSocketSession session) {
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

        log.info("[Legacy] Removed source session: source={}, instanceId={}, linkId={}, activeLinks={}",
                source, instanceId, linkId, getActiveConnectionCount(source));
    }

    @Override
    public boolean relayToSkill(GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String source = resolveMessageSource(tracedMessage);
        if (source == null) {
            log.debug("[Legacy] Cannot resolve source for message: type={}, ak={}",
                    tracedMessage.getType(), tracedMessage.getAk());
            return false;
        }

        GatewayMessage routedMessage = tracedMessage.withSource(source);

        // 1. 本地 defaultLink 投递
        if (sendViaDefaultLink(source, routedMessage)) {
            log.debug("[Legacy] Routed locally: source={}, type={}, ak={}",
                    source, routedMessage.getType(), routedMessage.getAk());
            return true;
        }

        // 2. 选择 owner 实例，跨 GW 中继
        String ownerId = selectOwner(source, tracedMessage.getType());
        if (ownerId == null) {
            log.warn("[Legacy] No owner found for source={}, type={}", source, routedMessage.getType());
            return false;
        }

        if (instanceId.equals(ownerId)) {
            boolean routed = sendViaDefaultLink(source, routedMessage);
            log.debug("[Legacy] Retried on local owner: source={}, routed={}", source, routed);
            return routed;
        }

        redisMessageBroker.publishToRelay(ownerId, routedMessage);
        log.debug("[Legacy] Relayed to remote owner: source={}, ownerId={}", source, ownerId);
        return true;
    }

    @Override
    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String boundSource = resolveBoundSource(session);
        String messageSource = tracedMessage.getSource();

        // 验证 source
        if (messageSource == null || messageSource.isBlank()) {
            log.warn("[Legacy] Rejected invoke: missing source, linkId={}", session.getId());
            sendProtocolError(session, SkillRelayService.ERROR_SOURCE_NOT_ALLOWED);
            return;
        }
        if (boundSource == null || !boundSource.equals(messageSource)) {
            log.warn("[Legacy] Rejected invoke: source mismatch, bound={}, message={}, linkId={}",
                    boundSource, messageSource, session.getId());
            sendProtocolError(session, SkillRelayService.ERROR_SOURCE_MISMATCH);
            return;
        }

        // 验证 ak
        if (tracedMessage.getAk() == null || tracedMessage.getAk().isBlank()) {
            log.warn("[Legacy] Invoke missing ak: linkId={}", session.getId());
            return;
        }

        // 验证 userId
        String expectedUserId = redisMessageBroker.getAgentUser(tracedMessage.getAk());
        if (tracedMessage.getUserId() == null || tracedMessage.getUserId().isBlank()) {
            log.warn("[Legacy] Invoke missing userId: linkId={}, ak={}", session.getId(), tracedMessage.getAk());
            return;
        }
        if (expectedUserId == null || !tracedMessage.getUserId().equals(expectedUserId)) {
            log.warn("[Legacy] Invoke userId mismatch: expected={}, actual={}, ak={}",
                    expectedUserId, tracedMessage.getUserId(), tracedMessage.getAk());
            return;
        }

        // 绑定 agent → source（旧版核心机制）
        redisMessageBroker.bindAgentSource(tracedMessage.getAk(), messageSource);

        // 发布到 Agent
        redisMessageBroker.publishToAgent(tracedMessage.getAk(), tracedMessage.withoutRoutingContext());
        log.debug("[Legacy] Forwarded invoke to agent: ak={}, source={}, instanceId={}",
                tracedMessage.getAk(), messageSource, instanceId);
    }

    @Override
    public int getActiveConnectionCount() {
        return (int) sourceSessions.values().stream()
                .map(Map::values)
                .flatMap(Collection::stream)
                .filter(WebSocketSession::isOpen)
                .count();
    }

    // ==================== 跨 GW 中继 ====================

    /**
     * 处理来自其他 Gateway 实例的中继消息。
     */
    public void handleRelayedMessage(GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String source = resolveMessageSource(tracedMessage);
        if (source == null) {
            log.warn("[Legacy] Rejected relayed message: cannot resolve source, type={}",
                    tracedMessage.getType());
            return;
        }

        if (!sendViaDefaultLink(source, tracedMessage.withSource(source))) {
            log.warn("[Legacy] Relay delivery failed: source={}, type={}", source, tracedMessage.getType());
        }
    }

    // ==================== 定时任务 ====================

    /**
     * 定时刷新所有 source 的 owner 心跳。
     * 注意：调度由 SkillRelayService 的 @Scheduled 统一驱动。
     */
    public void refreshOwnerHeartbeat() {
        sourceSessions.keySet().forEach(this::refreshOwnerState);
    }

    // ==================== 清理 ====================

    @PreDestroy
    public void destroy() {
        sourceSessions.keySet().forEach(this::clearOwnerState);
        if (relaySubscribed.compareAndSet(true, false)) {
            redisMessageBroker.unsubscribeFromRelay(instanceId);
        }
        log.info("[Legacy] Destroyed: cleared all owner state and relay subscriptions");
    }

    // ==================== 内部方法 ====================

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
            return true;
        } catch (IOException e) {
            log.error("[Legacy] Failed to send to source session: linkId={}, type={}",
                    session.getId(), message.getType(), e);
            return false;
        }
    }

    /**
     * 解析消息的 source。
     * 优先级: message.source → agent-source 绑定（Redis） → 推断单一活跃 source。
     */
    private String resolveMessageSource(GatewayMessage message) {
        String source = message.getSource();
        if (source != null && !source.isBlank()) {
            return source;
        }
        if (message.getAk() != null && !message.getAk().isBlank()) {
            String boundSource = redisMessageBroker.getAgentSource(message.getAk());
            if (boundSource != null && !boundSource.isBlank()) {
                return boundSource;
            }
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
                return null; // 多个活跃 source，无法推断
            }
            resolved = source;
        }
        return resolved;
    }

    /**
     * Rendezvous Hash 选择 owner 实例。
     */
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
            WebSocketSession session = sessionsBySource.get(preferredLinkId);
            if (session != null && session.isOpen()) {
                return session;
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

    private String resolveBoundSource(WebSocketSession session) {
        Object source = session.getAttributes().get(SkillRelayService.SOURCE_ATTR);
        return source instanceof String ? (String) source : null;
    }

    private void sendProtocolError(WebSocketSession session, String reason) {
        try {
            String json = objectMapper.writeValueAsString(GatewayMessage.registerRejected(reason));
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("[Legacy] Failed to send protocol error: linkId={}, reason={}", session.getId(), reason, e);
        }
    }
}
