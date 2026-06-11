package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.opencode.cui.gateway.ws.AsyncSenderIdentity;
import com.opencode.cui.gateway.ws.AsyncSessionSender;
import com.opencode.cui.gateway.ws.AsyncSessionSenderFactory;
import com.opencode.cui.gateway.service.cloud.InvokeRouteStrategy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Source 服务 WebSocket 连接管理 + 上行消息路由（V2 Mesh）。
 *
 * <p>所有 Source 服务握手时必须带 instanceId。
 * 上行路由通过 {@link UpstreamRoutingTable} + {@link ConsistentHashRing} 实现。</p>
 */
@Slf4j
@Service
public class SkillRelayService {

    public static final String SOURCE_ATTR = "source";
    public static final String INSTANCE_ID_ATTR = "instanceId";
    public static final String ERROR_SOURCE_NOT_ALLOWED = "source_not_allowed";
    public static final String ERROR_SOURCE_MISMATCH = "source_mismatch";
    private static final String ACTION_ABORT_SESSION = "abort_session";
    private static final String SOURCE_TYPE_SKILL_SERVER = "skill-server";
    private static final String LEGACY_SOURCE_TYPE_SKILL_SERVICE = "skill-service";

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final String gatewayInstanceId;
    private final UpstreamRoutingTable routingTable;
    private final GatewayMessageIdentityService messageIdentityService;
    private final AsyncSessionSenderFactory senderFactory;

    /** Invoke 路由策略 Map：scope → strategy */
    private final Map<String, InvokeRouteStrategy> routeStrategyMap;

    /**
     * [Mesh] Source 实例连接池：source_type → { ssInstanceId → { sessionId → WebSocketSession } }
     */
    private final Map<String, Map<String, Map<String, WebSocketSession>>> sourceTypeSessions = new ConcurrentHashMap<>();

    /**
     * [V2] Consistent hash ring per source type for deterministic upstream routing.
     */
    private final Map<String, ConsistentHashRing<WebSocketSession>> hashRings = new ConcurrentHashMap<>();

    /** Pending queue TTL for offline agent messages. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);

    @Value("${gateway.l2-source-stream.max-len:10000}")
    private long sourceL2StreamMaxLen;

    @Value("${gateway.l2-source-stream.poll-batch-size:10}")
    private int sourceL2PollBatchSize;

    @Value("${gateway.l2-source-stream.poll-block-ms:100}")
    private int sourceL2PollBlockMs;

    @Value("${gateway.l2-source-stream.max-attempts:3}")
    private int sourceL2MaxAttempts;

    @Value("${gateway.l2-source-stream.orphan-ttl-seconds:600}")
    private long sourceL2OrphanTtlSeconds = 600L;

    private final Cache<String, String> sourceLinkAffinity = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    // ==================== Metrics counters ====================

    /** Count of invoke messages enqueued to a locally connected Agent. */
    private final AtomicLong relayLocalCount = new AtomicLong();
    /** Count of invoke messages relayed to a remote GW instance via Redis pub/sub. */
    private final AtomicLong relayPubsubCount = new AtomicLong();
    /** Count of invoke messages enqueued to the pending queue (Agent offline). */
    private final AtomicLong relayPendingCount = new AtomicLong();
    /** Count of upstream route lookups that hit a known entry in UpstreamRoutingTable. */
    private final AtomicLong routingHitCount = new AtomicLong();
    /** Count of upstream messages routed via Redis L2 (cross-GW relay). */
    private final AtomicLong routingRedisL2Count = new AtomicLong();
    /** Lazy-initialized reference to EventRelayService (set via setter to break circular dependency). */
    private EventRelayService eventRelayService;

    @Autowired
    public SkillRelayService(RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId,
            UpstreamRoutingTable routingTable,
            GatewayMessageIdentityService messageIdentityService,
            AsyncSessionSenderFactory senderFactory,
            List<InvokeRouteStrategy> invokeRouteStrategies) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.gatewayInstanceId = gatewayInstanceId;
        this.routingTable = routingTable;
        this.messageIdentityService = messageIdentityService;
        this.senderFactory = senderFactory;
        Map<String, InvokeRouteStrategy> strategyMap = new HashMap<>();
        for (InvokeRouteStrategy s : invokeRouteStrategies) {
            strategyMap.put(s.getScope(), s);
        }
        this.routeStrategyMap = strategyMap;
    }

    public SkillRelayService(RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            String gatewayInstanceId,
            UpstreamRoutingTable routingTable,
            GatewayMessageIdentityService messageIdentityService,
            List<InvokeRouteStrategy> invokeRouteStrategies) {
        this(redisMessageBroker, objectMapper, gatewayInstanceId, routingTable, messageIdentityService,
                AsyncSessionSenderFactory.defaultFactory(), invokeRouteStrategies);
    }

    /**
     * Sets the EventRelayService reference. Called by EventRelayService to break circular dependency.
     */
    public void setEventRelayService(EventRelayService eventRelayService) {
        this.eventRelayService = eventRelayService;
    }

    /**
     * Cleans up stale source connection entries left by a previous crash of this GW instance.
     */
    @PostConstruct
    public void cleanupStaleSourceConnectionsOnStartup() {
        redisMessageBroker.cleanupStaleSourceConnections(gatewayInstanceId);
        log.info("[ENTRY] SkillRelayService: cleaned up stale source-conn entries for gwInstanceId={}", gatewayInstanceId);
    }

    // ==================== 连接管理 ====================

    /**
     * 注册 Source 服务的 WebSocket 连接。
     */
    public void registerSourceSession(WebSocketSession session) {
        String sourceType = resolveBoundSource(session);
        String ssInstanceId = resolveSsInstanceId(session);
        if (sourceType == null || sourceType.isBlank()) {
            log.warn("Skipping source session registration: missing source attribute, linkId={}",
                    session.getId());
            return;
        }
        if (ssInstanceId == null || ssInstanceId.isBlank()) {
            ssInstanceId = session.getId(); // fallback 用 WS session ID
            session.getAttributes().put(INSTANCE_ID_ATTR, ssInstanceId);
        }

        String sessionId = session.getId();
        sourceTypeSessions
                .computeIfAbsent(sourceType, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(ssInstanceId, ignored -> new ConcurrentHashMap<>())
                .put(sessionId, session);

        // V2: update consistent hash ring for this source type
        String nodeKey = ssInstanceId + "#" + sessionId;
        hashRings.computeIfAbsent(sourceType, k -> new ConsistentHashRing<>(150))
                .addNode(nodeKey, session);

        // Register source connection in Redis for cross-cluster discovery
        redisMessageBroker.registerSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);

        log.info("[Mesh] Registered source session: sourceType={}, ssInstanceId={}, sessionId={}, gwInstanceId={}, activeLinks={}, hashRingSize={}",
                sourceType, ssInstanceId, sessionId, gatewayInstanceId, getActiveConnectionCount(sourceType),
                hashRings.containsKey(sourceType) ? hashRings.get(sourceType).size() : 0);
    }

    /**
     * 移除 Source 服务的 WebSocket 连接。
     */
    public void removeSourceSession(WebSocketSession session) {
        removeSessionSender(session.getId(), true);
        removeSourceSessionState(session, "ws_closed");
    }

    private void removeSourceSessionState(WebSocketSession session, String reason) {

        String sourceType = resolveBoundSource(session);
        if (sourceType == null || sourceType.isBlank()) {
            return;
        }

        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null) {
            return;
        }

        String ssInstanceId = resolveSsInstanceId(session);
        String sessionId = session.getId();

        if (ssInstanceId != null) {
            Map<String, WebSocketSession> sessionMap = instanceMap.get(ssInstanceId);
            if (sessionMap != null) {
                sessionMap.remove(sessionId, session);
                if (sessionMap.isEmpty()) {
                    instanceMap.remove(ssInstanceId, sessionMap);
                }
            }
        }
        // Also try by linkId as ssInstanceId fallback
        Map<String, WebSocketSession> fallbackMap = instanceMap.get(sessionId);
        if (fallbackMap != null) {
            fallbackMap.remove(sessionId, session);
            if (fallbackMap.isEmpty()) {
                instanceMap.remove(sessionId, fallbackMap);
            }
        }

        if (instanceMap.isEmpty()) {
            sourceTypeSessions.remove(sourceType, instanceMap);
        }

        // V2: remove from consistent hash ring
        String nodeKey = ((ssInstanceId != null) ? ssInstanceId : sessionId) + "#" + sessionId;
        hashRings.computeIfPresent(sourceType, (k, ring) -> {
            ring.removeNode(nodeKey);
            return ring.isEmpty() ? null : ring;
        });

        // Unregister source connection from Redis
        if (ssInstanceId != null) {
            redisMessageBroker.unregisterSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);
        }
        removeLinkAffinities(sessionId);

        log.info("[Mesh] Removed source session: reason={}, sourceType={}, ssInstanceId={}, sessionId={}, gwInstanceId={}, activeLinks={}",
                reason, sourceType, ssInstanceId, sessionId, gatewayInstanceId, getActiveConnectionCount(sourceType));
    }

    // ==================== 上行消息路由 ====================

    /**
     * V2: Routes upstream messages to one Source connection locally or one L2 stream work item.
     *
     * <p>Routing strategy:</p>
     * <ol>
     * <li>Resolve sourceType from UpstreamRoutingTable, message.source, or default skill-server</li>
     * <li>Hash-select exactly one local connection for that sourceType</li>
     * <li>If local skill-server is absent, enqueue exactly one target-GW mailbox work item</li>
     * </ol>
     *
     * @return true if the message was accepted by a local sender or one target-GW mailbox
     */
    public boolean relayToSkill(GatewayMessage message) {
        return v2RelayToSkillWithoutBroadcast(message);
    }

    /**
     * V2: Two-level upstream routing.
     *
     * <p>Level 1: local GW to exactly one local Source connection.</p>
     * <p>Level 2: one target-GW Redis Stream mailbox consumed only by that GW.</p>
     */
    private boolean v2RelayToSkillWithoutBroadcast(GatewayMessage message) {
        GatewayMessage tracedMessage = messageIdentityService.normalizeForSkillRelay(message);
        if (tracedMessage == null) {
            return false;
        }
        String routingKey = resolveRoutingKey(tracedMessage);
        String targetSourceType = resolveTargetSourceType(tracedMessage);

        LocalDeliveryResult localDelivery = deliverToOneLocalSource(targetSourceType, tracedMessage, routingKey, "[V2-L1]");
        if (localDelivery.delivered()) {
            routingHitCount.incrementAndGet();
            return true;
        }
        if (localDelivery.affinityBroken()) {
            log.error("[V2-L1] Route locked to an invalid local source link; not rerouting message: sourceType={}, routingKey={}, type={}",
                    targetSourceType, routingKey, tracedMessage.getType());
            return false;
        }

        if (!isSkillServerSource(targetSourceType)) {
            log.debug("[V2-L1] No local source connection and L2 only supports skill-server: sourceType={}, type={}",
                    targetSourceType, tracedMessage.getType());
            return false;
        }

        return enqueueSkillServerL2Work(tracedMessage, routingKey);
    }

    /**
     * Resolves the routing key for consistent hash selection.
     * Priority: messageId > traceId > toolSessionId > payload.toolSessionId > welinkSessionId > ak.
     */
    private String resolveTargetSourceType(GatewayMessage message) {
        String sourceType = canonicalSourceType(routingTable.resolveSourceType(message));
        if (sourceType != null) {
            return sourceType;
        }
        sourceType = canonicalSourceType(message.getSource());
        if (sourceType != null) {
            return sourceType;
        }
        return SOURCE_TYPE_SKILL_SERVER;
    }

    private String canonicalSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return null;
        }
        String trimmed = sourceType.trim();
        if (LEGACY_SOURCE_TYPE_SKILL_SERVICE.equalsIgnoreCase(trimmed)) {
            return SOURCE_TYPE_SKILL_SERVER;
        }
        return trimmed;
    }

    private boolean isSkillServerSource(String sourceType) {
        return SOURCE_TYPE_SKILL_SERVER.equals(canonicalSourceType(sourceType));
    }

    private record LocalSourceSelection(WebSocketSession session, boolean affinityBroken) {}

    private record LocalDeliveryResult(WebSocketSession session, boolean affinityBroken) {
        boolean delivered() {
            return session != null;
        }
    }

    private LocalDeliveryResult deliverToOneLocalSource(String sourceType, GatewayMessage message,
                                                       String routingKey, String stage) {
        LocalSourceSelection selection = selectOneLocalSourceSession(sourceType, routingKey);
        WebSocketSession target = selection.session();
        if (target == null) {
            log.debug("{} No local source connection: sourceType={}, routingKey={}, type={}",
                    stage, sourceType, routingKey, message.getType());
            return new LocalDeliveryResult(null, selection.affinityBroken());
        }
        String sourceInstanceId = resolveSsInstanceId(target);
        logRoutingInfo(message,
                "{} Delivering to one local source: sourceType={}, sourceInstanceId={}, linkId={}, routingKey={}, type={}",
                stage, sourceType, sourceInstanceId, target.getId(), routingKey, message.getType());
        if (!sendToSession(target, message)) {
            return new LocalDeliveryResult(null, false);
        }
        if (isTerminalMessage(message) && routingKey != null && !routingKey.isBlank()) {
            sourceLinkAffinity.invalidate(affinityKey(sourceType, routingKey));
        }
        return new LocalDeliveryResult(target, false);
    }

    private LocalSourceSelection selectOneLocalSourceSession(String sourceType, String routingKey) {
        if (sourceType == null || sourceType.isBlank()) {
            return new LocalSourceSelection(null, false);
        }

        if (routingKey != null && !routingKey.isBlank()) {
            String affinityKey = affinityKey(sourceType, routingKey);
            String boundSessionId = sourceLinkAffinity.getIfPresent(affinityKey);
            if (boundSessionId != null && !boundSessionId.isBlank()) {
                WebSocketSession bound = findLocalSourceConnectionBySessionId(sourceType, boundSessionId);
                if (isSelectableSourceSession(bound)) {
                    return new LocalSourceSelection(bound, false);
                }
                sourceLinkAffinity.invalidate(affinityKey);
                log.debug("[V2-L1] Bound source link is no longer selectable: sourceType={}, routingKey={}, linkId={}",
                        sourceType, routingKey, boundSessionId);
                return new LocalSourceSelection(null, true);
            }
        }

        WebSocketSession selected = null;
        ConsistentHashRing<WebSocketSession> ring = hashRings.get(sourceType);
        if (ring != null && !ring.isEmpty() && routingKey != null && !routingKey.isBlank()) {
            WebSocketSession target = ring.getNode(routingKey);
            if (isSelectableSourceSession(target)) {
                selected = target;
            }
        }
        if (selected == null) {
            Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
            if (instanceMap == null) {
                return new LocalSourceSelection(null, false);
            }
            selected = instanceMap.values().stream()
                    .flatMap(sessionMap -> sessionMap.values().stream())
                    .filter(this::isSelectableSourceSession)
                    .findFirst()
                    .orElse(null);
        }
        if (selected != null && routingKey != null && !routingKey.isBlank()) {
            sourceLinkAffinity.put(affinityKey(sourceType, routingKey), selected.getId());
        }
        return new LocalSourceSelection(selected, false);
    }

    private boolean enqueueSkillServerL2Work(GatewayMessage message, String routingKey) {
        try {
            String targetGateway = selectTargetSourceGateway(SOURCE_TYPE_SKILL_SERVER, routingKey);
            if (targetGateway == null || targetGateway.isBlank()) {
                log.error("[V2-L2] No remote GW with skill-server connection: routingKey={}, type={}",
                        routingKey, message.getType());
                return false;
            }
            String payload = objectMapper.writeValueAsString(message);
            String streamId = redisMessageBroker.enqueueSourceL2Work(
                    SOURCE_TYPE_SKILL_SERVER,
                    targetGateway,
                    payload,
                    routingKey,
                    message.getTraceId(),
                    message.getType(),
                    sourceL2StreamMaxLen);
            if (streamId == null || streamId.isBlank()) {
                log.error("[V2-L2] Failed to enqueue skill-server L2 work item: routingKey={}, type={}",
                        routingKey, message.getType());
                return false;
            }
            routingRedisL2Count.incrementAndGet();
            logRoutingInfo(message,
                    "[V2-L2] Enqueued one skill-server L2 mailbox item: targetGw={}, streamId={}, routingKey={}, type={}",
                    targetGateway, streamId, routingKey, message.getType());
            return true;
        } catch (Exception e) {
            log.error("[V2-L2] Failed to serialize skill-server L2 work item: routingKey={}, type={}",
                    routingKey, message.getType(), e);
            return false;
        }
    }

    private String selectTargetSourceGateway(String sourceType, String routingKey) {
        var discovered = redisMessageBroker.discoverSourceGwInstances(sourceType);
        var gwIds = new java.util.ArrayList<>(discovered == null ? java.util.Collections.<String>emptySet() : discovered);
        gwIds.removeIf(gw -> gw == null || gw.isBlank() || gatewayInstanceId.equals(gw));
        if (gwIds.isEmpty()) {
            return null;
        }
        String stableKey = (routingKey == null || routingKey.isBlank())
                ? gatewayInstanceId + ":" + System.nanoTime()
                : routingKey;
        return gwIds.stream()
                .max(java.util.Comparator.comparingLong(gw -> rendezvousScore(stableKey, gw)))
                .orElse(null);
    }

    private static long rendezvousScore(String routingKey, String gwId) {
        return Integer.toUnsignedLong((routingKey + "|" + gwId).hashCode());
    }

    private String resolveRoutingKey(GatewayMessage message) {
        String messageId = messageIdentityService.extractMessageId(message);
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }
        String traceId = message.getTraceId();
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            return toolSessionId;
        }
        String payloadToolSessionId = extractToolSessionIdFromPayload(message);
        if (payloadToolSessionId != null && !payloadToolSessionId.isBlank()) {
            return payloadToolSessionId;
        }
        String welinkSessionId = message.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            return welinkSessionId;
        }
        String ak = message.getAk();
        if (ak != null && !ak.isBlank()) {
            return ak;
        }
        return null;
    }

    /**
     * Handles a cloud-control relay from another GW instance.
     * Used for abort_session when the receiving GW is the owner of the local cloud stream.
     *
     * @param payload the GatewayMessage JSON payload to process through business cloud routing
     */
    public void handleCloudControlRelay(String payload) {
        InvokeRouteStrategy strategy = routeStrategyMap.get("business");
        if (strategy == null) {
            log.error("[CLOUD_CONTROL_RX] Business route strategy missing, dropping control relay");
            return;
        }
        try {
            GatewayMessage message = objectMapper.readValue(payload, GatewayMessage.class).ensureTraceId();
            log.info("[CLOUD_CONTROL_RX] Routing cloud control relay locally: action={}, toolSessionId={}, traceId={}",
                    message.getAction(), resolveRoutingKey(message), message.getTraceId());
            strategy.route(message, this::relayToSkill);
        } catch (Exception e) {
            log.error("[CLOUD_CONTROL_RX] Failed to handle cloud-control relay", e);
        }
    }

    // ==================== 下行 invoke 处理 ====================

    /**
     * V2: Handles invoke messages from source services, routing to the target Agent.
     *
     * <p>Routing strategy:</p>
     * <ol>
     * <li>Learn route via UpstreamRoutingTable</li>
     * <li>Check local Agent session → deliver locally if found</li>
     * <li>Check Redis internal agent registry → relay via GW pub/sub if found on another GW</li>
     * <li>Neither found → enqueue to pending queue</li>
     * </ol>
     */
    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        // 业务助手走云端路由策略，个人助手保持现有逻辑
        String scope = Optional.ofNullable(message.getAssistantScope()).orElse("personal");
        if (shouldRouteBusinessInvoke(message, scope) && routeBusinessInvoke(session, message)) {
            return;
        }

        GatewayMessage tracedMessage = message.ensureTraceId();

        log.info("[ENTRY] SkillRelayService.handleInvokeFromSkill: ak={}, action={}, linkId={}",
                tracedMessage.getAk(), tracedMessage.getAction(), session.getId());

        if (!validateInvokeMessage(session, tracedMessage)) {
            return;
        }

        String messageSource = tracedMessage.getSource();

        // Learn route and write session route to Redis
        routingTable.learnRoute(tracedMessage, messageSource);
        writeSessionRouteToRedis(tracedMessage, messageSource, session);

        // 3-tier delivery — local → remote GW relay → pending queue
        dispatchToAgent(tracedMessage, messageSource);
    }

    /**
     * 业务助手云端路由。
     *
     * @return true 表示已由云端路由策略处理
     */
    private boolean routeBusinessInvoke(WebSocketSession session, GatewayMessage message) {
        InvokeRouteStrategy strategy = routeStrategyMap.get("business");
        if (strategy == null) {
            return false;
        }
        GatewayMessage tracedMsg = message.ensureTraceId();
        String source = tracedMsg.getSource();

        if (source != null && !source.isBlank()) {
            routingTable.learnRoute(tracedMsg, source);
            writeSessionRouteToRedis(tracedMsg, source, session);
        }

        strategy.route(tracedMsg, this::relayToSkill);
        return true;
    }

    /**
     * 校验上行 invoke 消息的 source、ak、userId。
     *
     * @return true 表示校验通过
     */
    private boolean shouldRouteBusinessInvoke(GatewayMessage message, String scope) {
        if ("business".equals(scope)) {
            return true;
        }
        if (message == null) {
            return false;
        }
        return ACTION_ABORT_SESSION.equals(normalizeAction(message.getAction()))
                && hasCloudRoutingHint(message);
    }

    private boolean hasCloudRoutingHint(GatewayMessage message) {
        if (message == null) {
            return false;
        }
        if (message.getBusinessTag() != null && !message.getBusinessTag().isBlank()) {
            return true;
        }
        return textAt(message.getPayload(), "cloudProfile") != null;
    }

    private static String normalizeAction(String action) {
        return action == null ? null : action.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String textAt(com.fasterxml.jackson.databind.JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    private boolean validateInvokeMessage(WebSocketSession session, GatewayMessage tracedMessage) {
        // Validate source
        String boundSource = resolveBoundSource(session);
        String messageSource = tracedMessage.getSource();
        if (messageSource == null || messageSource.isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_source, linkId={}",
                    session.getId());
            sendProtocolError(session, ERROR_SOURCE_NOT_ALLOWED);
            return false;
        }
        if (boundSource == null || !boundSource.equals(messageSource)) {
            log.warn(
                    "[SKIP] SkillRelayService.handleInvokeFromSkill: reason=source_mismatch, bound={}, message={}, linkId={}",
                    boundSource, messageSource, session.getId());
            sendProtocolError(session, ERROR_SOURCE_MISMATCH);
            return false;
        }

        // Validate ak
        if (tracedMessage.getAk() == null || tracedMessage.getAk().isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_ak, linkId={}",
                    session.getId());
            return false;
        }

        // userId is attribution context for SS-origin invokes; ak is the delivery key.
        String actualUserId = tracedMessage.getUserId();
        if (actualUserId == null || actualUserId.isBlank()) {
            log.debug("[OBSERVE] SkillRelayService.handleInvokeFromSkill: reason=missing_userId, linkId={}, ak={}",
                    session.getId(), tracedMessage.getAk());
            return true;
        }
        String expectedUserId = redisMessageBroker.getAgentUser(tracedMessage.getAk());
        if (expectedUserId == null || expectedUserId.isBlank()) {
            log.debug(
                    "[OBSERVE] SkillRelayService.handleInvokeFromSkill: reason=agent_user_unavailable, actual={}, ak={}",
                    actualUserId, tracedMessage.getAk());
            return true;
        }
        if (!actualUserId.equals(expectedUserId)) {
            log.warn(
                    "[OBSERVE] SkillRelayService.handleInvokeFromSkill: reason=userId_mismatch, expected={}, actual={}, ak={}",
                    expectedUserId, actualUserId, tracedMessage.getAk());
        }
        return true;
    }

    /**
     * 将 session 路由信息写入 Redis，用于跨集群路由。
     */
    private void writeSessionRouteToRedis(GatewayMessage tracedMessage, String source,
                                          WebSocketSession session) {
        String ssInstanceId = resolveSsInstanceId(session);
        if (ssInstanceId == null) {
            return;
        }
        String toolSessionId = extractToolSessionIdFromPayload(tracedMessage);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            redisMessageBroker.setSessionRoute(toolSessionId, source, ssInstanceId);
        }
        String welinkSessionId = tracedMessage.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            redisMessageBroker.setWelinkSessionRoute(welinkSessionId, source, ssInstanceId);
        }
    }

    /**
     * 3-tier delivery：local → remote GW relay → pending queue。
     */
    private void dispatchToAgent(GatewayMessage tracedMessage, String messageSource) {
        String ak = tracedMessage.getAk();
        GatewayMessage agentMessage = tracedMessage.withoutRoutingContext();

        if (deliverToLocalAgent(ak, agentMessage)) {
            relayLocalCount.incrementAndGet();
            log.info("[EXIT->AGENT] Enqueued invoke locally: ak={}, action={}, source={}",
                    ak, tracedMessage.getAction(), messageSource);
            return;
        }

        if (relayToRemoteGw(ak, tracedMessage, messageSource)) {
            relayPubsubCount.incrementAndGet();
            log.info("[EXIT->RELAY] Relayed invoke to remote GW: ak={}, action={}, source={}",
                    ak, tracedMessage.getAction(), messageSource);
            return;
        }

        long pending = enqueueToPending(ak, agentMessage);
        relayPendingCount.incrementAndGet();
        log.info("[EXIT->PENDING] Enqueued invoke to pending: ak={}, action={}, source={}, pending={}",
                ak, tracedMessage.getAction(), messageSource, pending);
    }

    /**
     * Attempts to deliver a message to a locally connected Agent.
     *
     * @return true if the Agent is local and message was accepted by its local sender
     */
    private boolean deliverToLocalAgent(String ak, GatewayMessage message) {
        if (eventRelayService == null) {
            return false;
        }
        return eventRelayService.sendToLocalAgentIfPresent(ak, message);
    }

    /**
     * Relays a message to a remote GW instance that holds the Agent connection.
     *
     * @return true if a remote GW instance was found and the relay was published
     */
    private boolean relayToRemoteGw(String ak, GatewayMessage originalMessage, String sourceType) {
        String targetInstanceId = redisMessageBroker.getInternalAgentInstance(ak);
        if (targetInstanceId == null || targetInstanceId.isBlank()) {
            return false;
        }
        // Do not relay to self
        if (gatewayInstanceId.equals(targetInstanceId)) {
            return false;
        }

        try {
            // Build routing keys for UpstreamRoutingTable propagation on the target GW
            List<String> routingKeys = buildRoutingKeys(originalMessage);
            String originalJson = objectMapper.writeValueAsString(originalMessage.withoutRoutingContext());
            RelayMessage relayMessage = RelayMessage.of(sourceType, routingKeys, originalJson);
            String relayJson = objectMapper.writeValueAsString(relayMessage);

            redisMessageBroker.publishToGwRelay(targetInstanceId, relayJson);
            log.info("[V2] Published relay to GW instance: target={}, ak={}, routingKeys={}",
                    targetInstanceId, ak, routingKeys);
            return true;
        } catch (Exception e) {
            log.error("[V2] Failed to relay to remote GW: target={}, ak={}", targetInstanceId, ak, e);
            return false;
        }
    }

    /**
     * Builds routing keys from a message for UpstreamRoutingTable propagation.
     */
    private List<String> buildRoutingKeys(GatewayMessage message) {
        List<String> keys = new ArrayList<>();
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            keys.add(toolSessionId);
        }
        String welinkSessionId = message.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            keys.add(UpstreamRoutingTable.WELINK_KEY_PREFIX + welinkSessionId);
        }
        // Also check payload.toolSessionId
        String payloadToolSessionId = extractToolSessionIdFromPayload(message);
        if (payloadToolSessionId != null && !payloadToolSessionId.isBlank() && !payloadToolSessionId.equals(toolSessionId)) {
            keys.add(payloadToolSessionId);
        }
        return keys;
    }

    /**
     * Enqueues a message to the pending queue for an offline Agent.
     */
    private long enqueueToPending(String ak, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            return redisMessageBroker.enqueuePending(ak, json, PENDING_TTL);
        } catch (Exception e) {
            log.error("[V2] Failed to enqueue pending message: ak={}", ak, e);
            return -1;
        }
    }

    // ==================== V2 route_confirm / route_reject ====================

    /**
     * Handles route_confirm messages from source services.
     * Kept only for rolling compatibility; correctness no longer depends on
     * source-side route confirmation.
     */
    public void handleRouteConfirm(GatewayMessage message) {
        String toolSessionId = message.getToolSessionId();
        String sourceType = message.getSource();
        log.info("[ENTRY] SkillRelayService.handleRouteConfirm: ignored_compat, toolSessionId={}, sourceType={}",
                toolSessionId, sourceType);
    }

    /**
     * Handles route_reject messages from source services.
     * Only logs the rejection; no further action taken.
     */
    public void handleRouteReject(GatewayMessage message) {
        log.info("[ENTRY] SkillRelayService.handleRouteReject: toolSessionId={}, source={}, reason=routing_rejected",
                message.getToolSessionId(), message.getSource());
    }

    /**
     * 从 invoke 消息的 payload 中提取 toolSessionId。
     */
    private String extractToolSessionIdFromPayload(GatewayMessage message) {
        if (message.getPayload() == null || message.getPayload().isNull()) {
            return null;
        }
        var toolSessionNode = message.getPayload().path("toolSessionId");
        return toolSessionNode.isMissingNode() || toolSessionNode.isNull()
                ? null
                : toolSessionNode.asText(null);
    }

    // ==================== 辅助方法 ====================

    public int getActiveSourceConnectionCount() {
        return (int) sourceTypeSessions.values().stream()
                .flatMap(instanceMap -> instanceMap.values().stream())
                .flatMap(sessionMap -> sessionMap.values().stream())
                .filter(WebSocketSession::isOpen)
                .count();
    }

    public record LocalSourceConnectionSnapshot(
            String sourceType,
            String ssInstanceId,
            String gwInstanceId,
            String linkId,
            boolean open,
            boolean senderRunning,
            int pending) {}

    public List<LocalSourceConnectionSnapshot> getLocalSourceConnectionSnapshots(String sourceType) {
        String normalizedSourceType = canonicalSourceType(sourceType);
        if (normalizedSourceType == null) {
            normalizedSourceType = SOURCE_TYPE_SKILL_SERVER;
        }
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(normalizedSourceType);
        if (instanceMap == null || instanceMap.isEmpty()) {
            return List.of();
        }

        List<LocalSourceConnectionSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, Map<String, WebSocketSession>> instanceEntry : instanceMap.entrySet()) {
            String ssInstanceId = instanceEntry.getKey();
            for (Map.Entry<String, WebSocketSession> sessionEntry : instanceEntry.getValue().entrySet()) {
                String linkId = sessionEntry.getKey();
                WebSocketSession session = sessionEntry.getValue();
                AsyncSessionSender sender = senderFactory.get(linkId);
                snapshots.add(new LocalSourceConnectionSnapshot(
                        normalizedSourceType,
                        ssInstanceId,
                        gatewayInstanceId,
                        linkId,
                        session != null && session.isOpen(),
                        sender == null || sender.isRunning(),
                        sender == null ? 0 : sender.pendingCount()));
            }
        }
        return snapshots;
    }

    private int getActiveConnectionCount(String sourceType) {
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null)
            return 0;
        return (int) instanceMap.values().stream()
                .flatMap(sessionMap -> sessionMap.values().stream())
                .filter(WebSocketSession::isOpen)
                .count();
    }

    private AsyncSessionSender getOrCreateSender(WebSocketSession session) {
        return senderFactory.getOrCreate(session,
                AsyncSenderIdentity.source(resolveBoundSource(session), resolveSsInstanceId(session)),
                () -> handleSourceSenderFailure(session, "sender_write_failed"));
    }

    public void removeSessionSender(String sessionId) {
        senderFactory.remove(sessionId);
    }

    private void removeSessionSender(String sessionId, boolean shutdown) {
        senderFactory.remove(sessionId, shutdown);
    }

    private void handleSourceSenderFailure(WebSocketSession session, String reason) {
        if (session == null) {
            return;
        }
        senderFactory.remove(session.getId(), false);
        removeSourceSessionState(session, reason);
    }

    private void logRoutingInfo(GatewayMessage message, String format, Object... args) {
        if (message != null && GatewayMessage.Type.TOOL_EVENT.equals(message.getType())) {
            log.debug(format, args);
            return;
        }
        log.info(format, args);
    }

    private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            String sourceType = resolveBoundSource(session);
            String sourceInstanceId = resolveSsInstanceId(session);
            AsyncSessionSender sender = getOrCreateSender(session);
            boolean enqueued = sender.enqueue(new TextMessage(json));
            if (enqueued) {
                logRoutingInfo(message, "[EXIT->SS] Enqueued to skill session: channel=source, sourceType={}, sourceInstanceId={}, linkId={}, type={}, pending={}",
                        sourceType, sourceInstanceId, session.getId(), message.getType(), sender.pendingCount());
            } else if (!session.isOpen() || !sender.isRunning()) {
                handleSourceSenderFailure(session, "enqueue_rejected_link_invalid");
            } else {
                log.error("[EXIT->SS] Failed to enqueue to skill session: channel=source, sourceType={}, sourceInstanceId={}, linkId={}, type={}, pending={}",
                        sourceType, sourceInstanceId, session.getId(), message.getType(), sender.pendingCount());
            }
            return enqueued;
        } catch (IOException e) {
            log.error("[EXIT->SS] Failed to serialize message: linkId={}, type={}",
                    session.getId(), message.getType(), e);
            return false;
        }
    }

    private String resolveBoundSource(WebSocketSession session) {
        Object source = session.getAttributes().get(SOURCE_ATTR);
        return source instanceof String ? (String) source : null;
    }

    private String resolveSsInstanceId(WebSocketSession session) {
        Object instanceId = session.getAttributes().get(INSTANCE_ID_ATTR);
        return instanceId instanceof String ? (String) instanceId : null;
    }

    private void sendProtocolError(WebSocketSession session, String reason) {
        try {
            String json = objectMapper.writeValueAsString(GatewayMessage.registerRejected(reason));
            AsyncSessionSender sender = getOrCreateSender(session);
            sender.enqueue(new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to serialize protocol error: linkId={}, reason={}", session.getId(), reason, e);
        }
    }

    // ==================== Source 连接查找 ====================

    /**
     * Finds a locally connected Source WebSocket session by sourceType and sourceInstanceId.
     * Used by EventRelayService for to-source relay delivery.
     *
     * @param sourceType       source type, e.g. "skill-server"
     * @param sourceInstanceId source instance ID
     * @return the WebSocket session if found locally and open, null otherwise
     */
    public WebSocketSession findLocalSourceConnection(String sourceType, String sourceInstanceId) {
        if (sourceType == null || sourceInstanceId == null) {
            return null;
        }
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null) {
            return null;
        }
        Map<String, WebSocketSession> sessionMap = instanceMap.get(sourceInstanceId);
        if (sessionMap == null) {
            return null;
        }
        return sessionMap.values().stream()
                .filter(this::isSelectableSourceSession)
                .findFirst()
                .orElse(null);
    }

    public boolean sendToLocalSourceConnection(String sourceType, String sourceInstanceId, String payload) {
        WebSocketSession session = findLocalSourceConnection(sourceType, sourceInstanceId);
        if (session == null) {
            return false;
        }

        AsyncSessionSender sender = getOrCreateSender(session);
        boolean enqueued = sender.enqueue(new TextMessage(payload));
        if (enqueued) {
            log.info("[EXIT->SOURCE] Enqueued to-source relay: channel=source, sourceType={}, sourceInstanceId={}, linkId={}, pending={}",
                    sourceType, sourceInstanceId, session.getId(), sender.pendingCount());
            return true;
        }
        if (!session.isOpen() || !sender.isRunning()) {
            handleSourceSenderFailure(session, "to_source_enqueue_rejected_link_invalid");
        }
        log.error("[EXIT->SOURCE] Failed to enqueue to-source relay: channel=source, sourceType={}, sourceInstanceId={}, linkId={}, pending={}",
                sourceType, sourceInstanceId, session.getId(), sender.pendingCount());
        return false;
    }

    private WebSocketSession findLocalSourceConnectionBySessionId(String sourceType, String sessionId) {
        if (sourceType == null || sourceType.isBlank() || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null) {
            return null;
        }
        return instanceMap.values().stream()
                .map(sessionMap -> sessionMap.get(sessionId))
                .filter(this::isSelectableSourceSession)
                .findFirst()
                .orElse(null);
    }

    private boolean isSelectableSourceSession(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        AsyncSessionSender sender = senderFactory.get(session.getId());
        return sender == null || sender.isRunning();
    }

    private String affinityKey(String sourceType, String routingKey) {
        return sourceType + ":" + routingKey;
    }

    private void removeLinkAffinities(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sourceLinkAffinity.asMap().entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
    }

    private static boolean isTerminalMessage(GatewayMessage message) {
        return message != null
                && (GatewayMessage.Type.TOOL_DONE.equals(message.getType())
                || GatewayMessage.Type.TOOL_ERROR.equals(message.getType()));
    }

    // ==================== 定时任务 ====================

    /**
     * Periodically logs routing metrics for observability.
     * Outputs cumulative counters since service startup.
     */
    @Scheduled(fixedDelayString = "${gateway.l2-source-stream.poll-delay-ms:200}")
    public void consumeSkillServerL2Work() {
        if (getActiveConnectionCount(SOURCE_TYPE_SKILL_SERVER) <= 0) {
            return;
        }
        List<RedisMessageBroker.SourceL2Work> works = redisMessageBroker.readSourceL2Work(
                SOURCE_TYPE_SKILL_SERVER,
                gatewayInstanceId,
                gatewayInstanceId,
                Math.max(1, sourceL2PollBatchSize),
                Duration.ofMillis(Math.max(0, sourceL2PollBlockMs)));
        if (works == null || works.isEmpty()) {
            return;
        }
        for (RedisMessageBroker.SourceL2Work work : works) {
            consumeOneSkillServerL2Work(work);
        }
    }

    private void consumeOneSkillServerL2Work(RedisMessageBroker.SourceL2Work work) {
        try {
            if (work.payload() == null || work.payload().isBlank()) {
                failSourceL2Work(work, "empty_payload");
                return;
            }
            GatewayMessage message = messageIdentityService.normalizeForSkillRelay(
                    objectMapper.readValue(work.payload(), GatewayMessage.class));
            String routingKey = firstNonBlank(work.routingKey(), resolveRoutingKey(message));
            logRoutingInfo(message,
                    "[V2-L2-CONSUME] Claimed skill-server L2 work item: streamId={}, consumerGw={}, routingKey={}, type={}",
                    work.id(), gatewayInstanceId, routingKey, message.getType());

            LocalDeliveryResult delivery = deliverToOneLocalSource(SOURCE_TYPE_SKILL_SERVER, message, routingKey,
                    "[V2-L2-CONSUME]");
            if (!delivery.delivered()) {
                failSourceL2Work(work, delivery.affinityBroken()
                        ? "local_link_affinity_broken"
                        : "local_delivery_failed");
                return;
            }
            redisMessageBroker.ackSourceL2Work(SOURCE_TYPE_SKILL_SERVER, gatewayInstanceId, work.id());
            logRoutingInfo(message,
                    "[V2-L2-CONSUME] Enqueued to one local skill-server: streamId={}, sourceInstanceId={}, linkId={}, routingKey={}, type={}",
                    work.id(), resolveSsInstanceId(delivery.session()), delivery.session().getId(), routingKey, message.getType());
        } catch (Exception e) {
            log.error("[V2-L2-CONSUME] Failed to consume skill-server L2 work item: streamId={}",
                    work == null ? null : work.id(), e);
            if (work != null) {
                failSourceL2Work(work, e.getClass().getSimpleName());
            }
        }
    }

    private void failSourceL2Work(RedisMessageBroker.SourceL2Work work, String reason) {
        int nextAttempt = work.attempt() + 1;
        if (nextAttempt >= Math.max(1, sourceL2MaxAttempts)) {
            String deadLetterId = redisMessageBroker.deadLetterSourceL2Work(SOURCE_TYPE_SKILL_SERVER, gatewayInstanceId, work, reason);
            if (deadLetterId != null && !deadLetterId.isBlank()) {
                redisMessageBroker.ackSourceL2Work(SOURCE_TYPE_SKILL_SERVER, gatewayInstanceId, work.id());
                log.error("[V2-L2-CONSUME] Dead-lettered skill-server L2 work item: streamId={}, deadLetterId={}, attempts={}, reason={}",
                        work.id(), deadLetterId, nextAttempt, reason);
            }
            return;
        }

        String requeuedId = redisMessageBroker.requeueSourceL2Work(
                SOURCE_TYPE_SKILL_SERVER, gatewayInstanceId, work, nextAttempt, sourceL2StreamMaxLen);
        if (requeuedId != null && !requeuedId.isBlank()) {
            redisMessageBroker.ackSourceL2Work(SOURCE_TYPE_SKILL_SERVER, gatewayInstanceId, work.id());
            log.warn("[V2-L2-CONSUME] Requeued skill-server L2 work item: streamId={}, requeuedId={}, nextAttempt={}, reason={}",
                    work.id(), requeuedId, nextAttempt, reason);
        }
    }

    @Scheduled(fixedDelayString = "${gateway.l2-source-stream.cleanup-delay-ms:60000}")
    public void cleanupSourceL2Mailboxes() {
        Set<String> activeGwIds = redisMessageBroker.discoverSourceGwInstances(SOURCE_TYPE_SKILL_SERVER);
        RedisMessageBroker.SourceL2MailboxCleanupResult result =
                redisMessageBroker.cleanupOrphanSourceL2Mailboxes(
                        SOURCE_TYPE_SKILL_SERVER,
                        activeGwIds,
                        Duration.ofSeconds(Math.max(1L, sourceL2OrphanTtlSeconds)));
        if (result.hasWork()) {
            log.info("[V2-L2-CLEANUP] sourceType={}, scanned={}, active={}, emptyDeleted={}, orphanExpiring={}, errors={}",
                    SOURCE_TYPE_SKILL_SERVER,
                    result.scanned(),
                    result.active(),
                    result.emptyDeleted(),
                    result.orphanExpiring(),
                    result.errors());
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    @Scheduled(fixedDelay = 60_000)
    void logMetrics() {
        log.info("[METRICS] relay: local={}, pubsub={}, pending={} | routing: L1hit={}, L2stream={}",
                relayLocalCount.get(), relayPubsubCount.get(), relayPendingCount.get(),
                routingHitCount.get(), routingRedisL2Count.get());
    }

    /**
     * Periodically refreshes source connection heartbeats in Redis.
     * Updates the timestamp for all locally connected Mesh source sessions.
     */
    @Scheduled(fixedDelay = 10_000)
    public void refreshSourceConnectionHeartbeats() {
        record StaleEntry(String sourceType, String ssInstanceId, String sessionId) {}
        List<StaleEntry> staleEntries = new ArrayList<>();

        for (Map.Entry<String, Map<String, Map<String, WebSocketSession>>> typeEntry : sourceTypeSessions.entrySet()) {
            String sourceType = typeEntry.getKey();
            for (Map.Entry<String, Map<String, WebSocketSession>> instanceEntry : typeEntry.getValue().entrySet()) {
                String ssInstanceId = instanceEntry.getKey();
                for (Map.Entry<String, WebSocketSession> sessionEntry : instanceEntry.getValue().entrySet()) {
                    String sessionId = sessionEntry.getKey();
                    WebSocketSession session = sessionEntry.getValue();
                    if (session.isOpen()) {
                        redisMessageBroker.refreshSourceConnectionHeartbeat(sourceType, ssInstanceId, gatewayInstanceId, sessionId);
                    } else {
                        staleEntries.add(new StaleEntry(sourceType, ssInstanceId, sessionId));
                    }
                }
            }
        }

        for (StaleEntry stale : staleEntries) {
            removeSessionSender(stale.sessionId(), true);
            Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(stale.sourceType());
            if (instanceMap != null) {
                Map<String, WebSocketSession> sessionMap = instanceMap.get(stale.ssInstanceId());
                if (sessionMap != null) {
                    sessionMap.remove(stale.sessionId());
                    if (sessionMap.isEmpty()) {
                        instanceMap.remove(stale.ssInstanceId());
                    }
                }
                if (instanceMap.isEmpty()) {
                    sourceTypeSessions.remove(stale.sourceType());
                }
            }

            String nodeKey = stale.ssInstanceId() + "#" + stale.sessionId();
            hashRings.computeIfPresent(stale.sourceType(), (k, ring) -> {
                ring.removeNode(nodeKey);
                return ring.isEmpty() ? null : ring;
            });

            redisMessageBroker.unregisterSourceConnection(stale.sourceType(), stale.ssInstanceId(), gatewayInstanceId, stale.sessionId());
            removeLinkAffinities(stale.sessionId());

            log.info("[Mesh] Lazy-cleaned stale session during heartbeat: sourceType={}, ssInstanceId={}, sessionId={}",
                    stale.sourceType(), stale.ssInstanceId(), stale.sessionId());
        }
    }

    // ==================== Accessors for testing ====================

    /** Returns the consistent hash ring for the given sourceType. Package-private for testing. */
    ConsistentHashRing<WebSocketSession> getHashRing(String sourceType) {
        return hashRings.get(sourceType);
    }

    @PreDestroy
    public void destroy() {
        sourceTypeSessions.values().forEach(instanceMap ->
                instanceMap.values().forEach(sessionMap ->
                        sessionMap.keySet().forEach(senderFactory::remove)));
        sourceTypeSessions.clear();
        hashRings.clear();
        sourceLinkAffinity.invalidateAll();
        log.info("SkillRelayService destroyed: cleared all mesh connections and hash rings");
    }
}
