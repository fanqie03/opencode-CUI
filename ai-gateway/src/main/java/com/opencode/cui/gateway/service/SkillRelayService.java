package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Source 服务 WebSocket 连接管理 + 上行消息路由（复合路由器）。
 *
 * <h3>策略分支</h3>
 * <ul>
 * <li><b>Mesh</b>（新版 Source，握手时带 instanceId）：路由缓存 + 广播降级</li>
 * <li><b>Legacy</b>（旧版 Source，无 instanceId）：Owner 心跳 + Redis relay 跨 GW 中继</li>
 * </ul>
 *
 * <p>
 * 连接建立时根据 instanceId 是否存在自动选择策略。
 * 上行消息路由优先走 Mesh 路径，失败后回退到 Legacy 路径。
 * </p>
 */
@Slf4j
@Service
public class SkillRelayService {

    public static final String SOURCE_ATTR = "source";
    public static final String INSTANCE_ID_ATTR = "instanceId";
    public static final String ERROR_SOURCE_NOT_ALLOWED = "source_not_allowed";
    public static final String ERROR_SOURCE_MISMATCH = "source_mismatch";

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final String gatewayInstanceId;
    private final UpstreamRoutingTable routingTable;

    /** 旧版路由策略（兼容不带 instanceId 的 Source 服务） */
    private final LegacySkillRelayStrategy legacyStrategy;

    /**
     * [Mesh] Source 实例连接池：source_type → { ssInstanceId → { sessionId → WebSocketSession } }
     */
    private final Map<String, Map<String, Map<String, WebSocketSession>>> sourceTypeSessions = new ConcurrentHashMap<>();

    /**
     * [V2] Consistent hash ring per source type for deterministic upstream routing.
     */
    private final Map<String, ConsistentHashRing<WebSocketSession>> hashRings = new ConcurrentHashMap<>();

    /**
     * [Mesh] 路由缓存：toolSessionId → SS 连接 或 "w:" + welinkSessionId → SS 连接
     * 被动学习：下行 invoke 经过时写入。
     * 定时清理已关闭连接的条目，防止长期运行时内存无限增长。
     * @deprecated V2 uses UpstreamRoutingTable + ConsistentHashRing instead.
     */
    @Deprecated
    private final Map<String, WebSocketSession> routeCache = new ConcurrentHashMap<>();

    private static final String WELINK_ROUTE_PREFIX = "w:";

    /** Pending queue TTL for offline agent messages. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);

    @Value("${gateway.upstream-routing.broadcast-timeout-ms:200}")
    private int broadcastTimeoutMs;

    @Value("${gateway.legacy-relay.enabled:false}")
    private boolean legacyRelayEnabled;

    // ==================== Metrics counters ====================

    /** Count of invoke messages delivered to a locally connected Agent. */
    private final AtomicLong relayLocalCount = new AtomicLong();
    /** Count of invoke messages relayed to a remote GW instance via Redis pub/sub. */
    private final AtomicLong relayPubsubCount = new AtomicLong();
    /** Count of invoke messages enqueued to the pending queue (Agent offline). */
    private final AtomicLong relayPendingCount = new AtomicLong();
    /** Count of upstream route lookups that hit a known entry in UpstreamRoutingTable. */
    private final AtomicLong routingHitCount = new AtomicLong();
    /** Count of upstream route lookups that fell back to broadcast (no routing table entry). */
    private final AtomicLong routingBroadcastCount = new AtomicLong();
    /** Count of upstream messages routed via Redis L2 (cross-GW relay). */
    private final AtomicLong routingRedisL2Count = new AtomicLong();
    /** Count of upstream messages routed via Level 3 broadcast relay. */
    private final AtomicLong routingL3BroadcastCount = new AtomicLong();

    /**
     * Per-source broadcast rate limiter: sourceType → last broadcast timestamps (epoch millis).
     * Uses a Caffeine cache with sliding window to enforce max 10 broadcasts/source/second.
     */
    private final Cache<String, AtomicLong> broadcastRateLimiter = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(1000)
            .build();
    private static final int BROADCAST_RATE_LIMIT_PER_SECOND = 10;

    /** Lazy-initialized reference to EventRelayService (set via setter to break circular dependency). */
    private EventRelayService eventRelayService;

    public SkillRelayService(RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId,
            UpstreamRoutingTable routingTable,
            LegacySkillRelayStrategy legacyStrategy) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.gatewayInstanceId = gatewayInstanceId;
        this.routingTable = routingTable;
        this.legacyStrategy = legacyStrategy;
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
     * 根据 instanceId 是否存在自动选择 Mesh / Legacy 策略。
     */
    public void registerSourceSession(WebSocketSession session) {
        // 策略分支：有 instanceId → Mesh，无 → Legacy
        if (isLegacyClient(session)) {
            session.getAttributes().put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.LEGACY);
            legacyStrategy.registerSession(session);
            return;
        }

        session.getAttributes().put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.MESH);

        String sourceType = resolveBoundSource(session);
        String ssInstanceId = resolveSsInstanceId(session);
        if (sourceType == null || sourceType.isBlank()) {
            log.warn("Skipping source session registration: missing source attribute, linkId={}",
                    session.getId());
            return;
        }
        if (ssInstanceId == null || ssInstanceId.isBlank()) {
            ssInstanceId = session.getId(); // fallback 用 WS session ID
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
     * 根据策略标记委托到 Mesh / Legacy。
     */
    public void removeSourceSession(WebSocketSession session) {
        if (isLegacySession(session)) {
            legacyStrategy.removeSession(session);
            return;
        }

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

        // 清除路由缓存中所有指向该 session 的条目
        invalidateRoutesForSession(session);

        // Unregister source connection from Redis
        if (ssInstanceId != null) {
            redisMessageBroker.unregisterSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);
        }

        log.info("[Mesh] Removed source session: sourceType={}, ssInstanceId={}, sessionId={}, gwInstanceId={}, activeLinks={}",
                sourceType, ssInstanceId, sessionId, gatewayInstanceId, getActiveConnectionCount(sourceType));
    }

    // ==================== 路由缓存 ====================

    /**
     * 被动学习路由：将 toolSessionId / welinkSessionId 映射到 SS 连接。
     * 由 SkillWebSocketHandler 在收到下行 invoke 时调用。
     */
    public void learnRoute(String toolSessionId, String welinkSessionId, WebSocketSession ssSession) {
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            routeCache.put(toolSessionId, ssSession);
            log.info("Learned route: toolSessionId={} -> ssLink={}", toolSessionId, ssSession.getId());
        }
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            routeCache.put(WELINK_ROUTE_PREFIX + welinkSessionId, ssSession);
            log.info("Learned route: welinkSessionId={} -> ssLink={}", welinkSessionId, ssSession.getId());
        }
    }

    /**
     * 清除路由缓存中所有指向指定 session 的条目。
     * SS 断连时调用。
     */
    private void invalidateRoutesForSession(WebSocketSession session) {
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, WebSocketSession> entry : routeCache.entrySet()) {
            if (entry.getValue() == session) {
                keysToRemove.add(entry.getKey());
            }
        }
        for (String key : keysToRemove) {
            routeCache.remove(key);
        }
        if (!keysToRemove.isEmpty()) {
            log.info("Invalidated {} route cache entries for disconnected session: linkId={}",
                    keysToRemove.size(), session.getId());
        }
    }

    // ==================== 上行消息路由 ====================

    /**
     * V2: Routes upstream messages to the correct source service using UpstreamRoutingTable + ConsistentHashRing.
     *
     * <p>Routing strategy:</p>
     * <ol>
     * <li>Query UpstreamRoutingTable to resolve sourceType</li>
     * <li>If sourceType known → hash-select a connection from that sourceType's ring</li>
     * <li>If sourceType unknown → broadcast to all sourceType groups (one connection per group via hash)</li>
     * <li>If V2 routing fails and legacy-relay is enabled → fallback to Legacy strategy</li>
     * </ol>
     *
     * @return true if the message was delivered, false if delivery failed
     */
    public boolean relayToSkill(GatewayMessage message) {
        // V2 路径：投递到 MESH 连接（带 instanceId 的 Source 服务）
        boolean v2Delivered = v2RelayToSkill(message);

        // Legacy 路径：投递到 LEGACY 连接（无 instanceId 的 Source 服务）。
        // LEGACY 连接不在 V2 hashRings 中，必须并行投递而非 fallback，
        // 否则 V2 广播"成功"后会短路 Legacy，导致旧版 Source 永远收不到消息。
        // 注意：不检查本地 getActiveConnectionCount()，因为 Legacy 策略内部有
        // Redis owner relay 机制，即使本 GW Pod 没有 Legacy 连接，也能中继到
        // 持有 Legacy 连接的其他 GW Pod。跳过此调用会导致跨 Pod 场景下
        // session_created 等关键消息丢失（旧版 SS 的 toolSessionId 为空）。
        boolean legacyDelivered = legacyStrategy.relayToSkill(message);
        if (legacyDelivered) {
            log.info("[Legacy] Parallel delivery: type={}, ak={}", message.getType(), message.getAk());
        }

        return v2Delivered || legacyDelivered;
    }

    /**
     * V2: Three-level upstream routing.
     *
     * <p>Level 1: Caffeine L1 (UpstreamRoutingTable) + local hashRing — 0-hop local delivery</p>
     * <p>Level 2: Redis L2 — precise session route lookup, local delivery or cross-GW relay</p>
     * <p>Level 3: Broadcast fallback — relay to all GWs holding Source connections</p>
     */
    private boolean v2RelayToSkill(GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String routingKey = resolveRoutingKey(tracedMessage);

        // ===== Level 1: Caffeine L1 — UpstreamRoutingTable + local hashRing =====
        String sourceType = routingTable.resolveSourceType(tracedMessage);

        if (sourceType != null) {
            ConsistentHashRing<WebSocketSession> ring = hashRings.get(sourceType);
            if (ring != null && !ring.isEmpty() && routingKey != null) {
                WebSocketSession target = ring.getNode(routingKey);
                if (target != null && target.isOpen()) {
                    log.info("[V2-L1] Hash-routed to source: sourceType={}, routingKey={}, linkId={}, type={}",
                            sourceType, routingKey, target.getId(), tracedMessage.getType());
                    if (sendToSession(target, tracedMessage)) {
                        routingHitCount.incrementAndGet();
                        return true;
                    }
                }
            }
            // L1 known sourceType but no local connection — try local broadcast to that type first
            if (broadcastToSourceType(sourceType, tracedMessage)) {
                routingHitCount.incrementAndGet();
                return true;
            }
            // Fall through to L2
        }

        // Also try message.source field as sourceType hint for L1
        String messageSource = tracedMessage.getSource();
        if (messageSource != null && !messageSource.isBlank()) {
            ConsistentHashRing<WebSocketSession> ring = hashRings.get(messageSource);
            if (ring != null && !ring.isEmpty() && routingKey != null) {
                WebSocketSession target = ring.getNode(routingKey);
                if (target != null && target.isOpen()) {
                    log.info("[V2-L1] Hash-routed via message.source: sourceType={}, routingKey={}, linkId={}, type={}",
                            messageSource, routingKey, target.getId(), tracedMessage.getType());
                    if (sendToSession(target, tracedMessage)) {
                        routingHitCount.incrementAndGet();
                        return true;
                    }
                }
            }
            if (broadcastToSourceType(messageSource, tracedMessage)) {
                routingHitCount.incrementAndGet();
                return true;
            }
        }

        // ===== Level 2: Redis L2 — precise session route from Redis =====
        boolean l2Result = l2RedisRoute(tracedMessage, routingKey);
        if (l2Result) {
            routingRedisL2Count.incrementAndGet();
            return true;
        }

        // ===== Level 3: Broadcast fallback — relay to all GWs with Source connections =====
        routingBroadcastCount.incrementAndGet();
        // Try local broadcast first
        boolean localBroadcast = broadcastToAllGroups(tracedMessage, routingKey);
        // Also relay to remote GWs
        l3BroadcastRelay(tracedMessage);
        routingL3BroadcastCount.incrementAndGet();
        return localBroadcast;
    }

    /**
     * Level 2: Redis-based precise session route lookup.
     * Queries Redis for toolSessionId/welinkSessionId → sourceType:sourceInstanceId mapping,
     * then either delivers locally or relays to the correct remote GW.
     *
     * @return true if message was delivered or relayed
     */
    private boolean l2RedisRoute(GatewayMessage message, String routingKey) {
        String toolSessionId = message.getToolSessionId();
        String welinkSessionId = message.getWelinkSessionId();

        // Query Redis route table
        String routeValue = redisMessageBroker.getSessionRoute(toolSessionId);
        if (routeValue == null) {
            routeValue = redisMessageBroker.getWelinkSessionRoute(welinkSessionId);
        }

        if (routeValue == null) {
            log.debug("[V2-L2] No Redis route found: toolSessionId={}, welinkSessionId={}",
                    toolSessionId, welinkSessionId);
            return false;
        }

        // Parse "sourceType:sourceInstanceId"
        String[] parts = routeValue.split(":", 2);
        if (parts.length < 2) {
            log.warn("[V2-L2] Invalid route format: routeValue={}", routeValue);
            return false;
        }
        String targetSourceType = parts[0];
        String targetSourceInstanceId = parts[1];

        // Try local delivery first
        WebSocketSession localSession = findLocalSourceConnection(targetSourceType, targetSourceInstanceId);
        if (localSession != null) {
            log.info("[V2-L2] Local delivery: sourceType={}, sourceInstanceId={}, linkId={}, type={}",
                    targetSourceType, targetSourceInstanceId, localSession.getId(), message.getType());
            if (sendToSession(localSession, message)) {
                return true;
            }
        }

        // Query Redis: which GWs hold this Source connection?
        Map<String, Long> gwMap = redisMessageBroker.getSourceConnections(targetSourceType, targetSourceInstanceId);
        Set<String> uniqueGwIds = redisMessageBroker.extractUniqueGwInstances(gwMap);
        for (String targetGwId : uniqueGwIds) {
            if (!gatewayInstanceId.equals(targetGwId)) {
                try {
                    String messageJson = objectMapper.writeValueAsString(message);
                    redisMessageBroker.publishToSourceRelay(targetGwId, targetSourceType,
                            targetSourceInstanceId, messageJson);
                    log.info("[V2-L2] Cross-GW relay: targetGw={}, sourceType={}, sourceInstanceId={}, type={}",
                            targetGwId, targetSourceType, targetSourceInstanceId, message.getType());
                    return true;
                } catch (Exception e) {
                    log.error("[V2-L2] Failed to serialize message for cross-GW relay: type={}", message.getType(), e);
                }
            }
        }

        log.debug("[V2-L2] Route found but no reachable GW: sourceType={}, sourceInstanceId={}",
                targetSourceType, targetSourceInstanceId);
        return false;
    }

    /**
     * Level 3: Broadcast relay to all remote GWs that hold any Source connection.
     * Subject to per-source rate limiting (max {@link #BROADCAST_RATE_LIMIT_PER_SECOND} broadcasts/source/second).
     *
     * <p>Receiving GWs that have no matching local Source connection will silently discard the message.</p>
     */
    private void l3BroadcastRelay(GatewayMessage message) {
        String sourceHint = message.getSource();
        String rateLimitKey = sourceHint != null ? sourceHint : "__all__";

        // Per-source rate limiting using sliding 1-second window
        if (!acquireBroadcastPermit(rateLimitKey)) {
            log.warn("[V2-L3] Broadcast rate limited: source={}", rateLimitKey);
            return;
        }

        try {
            String messageJson = objectMapper.writeValueAsString(message);
            RelayMessage broadcastRelay = RelayMessage.toSourceBroadcast(messageJson);
            String relayJson = objectMapper.writeValueAsString(broadcastRelay);

            // Discover all GW instances that hold Source connections
            java.util.Set<String> gwIds = redisMessageBroker.discoverAllSourceGwInstances();
            int relayed = 0;
            for (String targetGwId : gwIds) {
                if (gatewayInstanceId.equals(targetGwId)) {
                    continue; // skip self — local broadcast already handled
                }
                redisMessageBroker.publishToGwRelay(targetGwId, relayJson);
                relayed++;
            }
            log.info("[V2-L3] Broadcast relay to {} remote GWs: type={}, source={}",
                    relayed, message.getType(), sourceHint);
        } catch (Exception e) {
            log.error("[V2-L3] Failed to broadcast relay: type={}", message.getType(), e);
        }
    }

    /**
     * Attempts to acquire a broadcast permit for the given source key.
     * Implements a simple sliding window rate limiter: max {@link #BROADCAST_RATE_LIMIT_PER_SECOND} per second.
     *
     * @return true if permit acquired, false if rate limited
     */
    private boolean acquireBroadcastPermit(String sourceKey) {
        // Window timestamp tracker — uses CAS to safely rotate window
        AtomicLong windowStart = broadcastRateLimiter.get(sourceKey + ":ts", k -> new AtomicLong(0));
        AtomicLong counter = broadcastRateLimiter.get(sourceKey + ":cnt", k -> new AtomicLong(0));

        long now = System.currentTimeMillis();
        long ws = windowStart.get();

        if (now - ws > 1000) {
            // Attempt to rotate window via CAS; loser threads fall through to increment
            if (windowStart.compareAndSet(ws, now)) {
                counter.set(1);
                return true;
            }
            // CAS failed — another thread already rotated, fall through to increment
        }
        long count = counter.incrementAndGet();
        return count <= BROADCAST_RATE_LIMIT_PER_SECOND;
    }

    /**
     * Resolves the routing key for consistent hash selection.
     * Priority: welinkSessionId > toolSessionId.
     */
    private String resolveRoutingKey(GatewayMessage message) {
        String welinkSessionId = message.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            return welinkSessionId;
        }
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            return toolSessionId;
        }
        return null;
    }

    /**
     * Broadcasts message to all sourceType groups.
     * Each group selects one connection via hash ring (or first open connection as fallback).
     */
    private boolean broadcastToAllGroups(GatewayMessage message, String routingKey) {
        if (hashRings.isEmpty() && sourceTypeSessions.isEmpty()) {
            log.warn("[V2] No source connections available for broadcast: type={}", message.getType());
            return false;
        }

        int groupsSent = 0;
        for (Map.Entry<String, ConsistentHashRing<WebSocketSession>> entry : hashRings.entrySet()) {
            String st = entry.getKey();
            ConsistentHashRing<WebSocketSession> ring = entry.getValue();
            if (ring.isEmpty()) {
                continue;
            }

            WebSocketSession target = null;
            if (routingKey != null) {
                target = ring.getNode(routingKey);
            }
            // Fallback: find any open session in this sourceType pool
            if (target == null || !target.isOpen()) {
                Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(st);
                if (instanceMap != null) {
                    target = instanceMap.values().stream()
                            .flatMap(m -> m.values().stream())
                            .filter(WebSocketSession::isOpen)
                            .findFirst()
                            .orElse(null);
                }
            }

            if (target != null && target.isOpen()) {
                sendToSession(target, message);
                groupsSent++;
            }
        }

        log.info("[V2] Broadcast to all groups: groupsSent={}, totalGroups={}, type={}",
                groupsSent, hashRings.size(), message.getType());
        return groupsSent > 0;
    }

    /**
     * @deprecated V2 uses UpstreamRoutingTable.resolveSourceType instead.
     * Kept for backward compatibility during migration.
     */
    @Deprecated
    private void learnRouteFromUpstream(String toolSessionId, String welinkSessionId, WebSocketSession target) {
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            routeCache.putIfAbsent(toolSessionId, target);
        }
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            routeCache.putIfAbsent(WELINK_ROUTE_PREFIX + welinkSessionId, target);
        }
    }

    /**
     * Broadcasts to all SS connections of the specified source_type.
     */
    private boolean broadcastToSourceType(String sourceType, GatewayMessage message) {
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null || instanceMap.isEmpty()) {
            log.warn("No source instances for type: {}, type={}", sourceType, message.getType());
            return false;
        }

        int sent = 0;
        for (Map<String, WebSocketSession> sessionMap : instanceMap.values()) {
            for (WebSocketSession ss : sessionMap.values()) {
                if (ss.isOpen()) {
                    sendToSession(ss, message);
                    sent++;
                }
            }
        }

        log.info("Broadcast to source_type={}: sent to {} connections, msgType={}",
                sourceType, sent, message.getType());
        return sent > 0;
    }

    /**
     * Handles a to-source-broadcast relay from a remote GW (Level 3).
     * Broadcasts the payload to all local Source connections.
     * Called by EventRelayService when it receives a {@code to-source-broadcast} relay.
     *
     * @param payload the GatewayMessage JSON payload to broadcast
     */
    public void handleToSourceBroadcastRelay(String payload) {
        try {
            GatewayMessage message = objectMapper.readValue(payload, GatewayMessage.class);
            GatewayMessage tracedMessage = message.ensureTraceId();
            String routingKey = resolveRoutingKey(tracedMessage);
            boolean delivered = broadcastToAllGroups(tracedMessage, routingKey);
            if (!delivered) {
                log.debug("[V2-L3-RX] No local source connections for broadcast relay: type={}", message.getType());
            }
        } catch (Exception e) {
            log.error("[V2-L3-RX] Failed to handle to-source-broadcast relay", e);
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
     * <li>If legacy-relay enabled → also publish to legacy agent channel</li>
     * </ol>
     */
    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        if (isLegacySession(session)) {
            legacyStrategy.handleInvokeFromSkill(session, message);
            return;
        }

        GatewayMessage tracedMessage = message.ensureTraceId();

        log.info("[ENTRY] SkillRelayService.handleInvokeFromSkill: ak={}, action={}, linkId={}",
                tracedMessage.getAk(), tracedMessage.getAction(), session.getId());

        // Validate source
        String boundSource = resolveBoundSource(session);
        String messageSource = tracedMessage.getSource();
        if (messageSource == null || messageSource.isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_source, linkId={}",
                    session.getId());
            sendProtocolError(session, ERROR_SOURCE_NOT_ALLOWED);
            return;
        }
        if (boundSource == null || !boundSource.equals(messageSource)) {
            log.warn(
                    "[SKIP] SkillRelayService.handleInvokeFromSkill: reason=source_mismatch, bound={}, message={}, linkId={}",
                    boundSource, messageSource, session.getId());
            sendProtocolError(session, ERROR_SOURCE_MISMATCH);
            return;
        }

        // Validate ak
        if (tracedMessage.getAk() == null || tracedMessage.getAk().isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_ak, linkId={}",
                    session.getId());
            return;
        }

        // Validate userId
        String expectedUserId = redisMessageBroker.getAgentUser(tracedMessage.getAk());
        if (tracedMessage.getUserId() == null || tracedMessage.getUserId().isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_userId, linkId={}, ak={}",
                    session.getId(), tracedMessage.getAk());
            return;
        }
        if (expectedUserId == null || !tracedMessage.getUserId().equals(expectedUserId)) {
            log.warn(
                    "[SKIP] SkillRelayService.handleInvokeFromSkill: reason=userId_mismatch, expected={}, actual={}, ak={}",
                    expectedUserId, tracedMessage.getUserId(), tracedMessage.getAk());
            return;
        }

        // V2: Learn route in UpstreamRoutingTable
        routingTable.learnRoute(tracedMessage, messageSource);

        // V2: Also learn in legacy route cache for backward compatibility
        learnRouteFromInvoke(tracedMessage, session);

        // Write session route to Redis for cross-cluster routing
        String ssInstanceId = resolveSsInstanceId(session);
        if (ssInstanceId != null) {
            String toolSessionId = extractToolSessionIdFromPayload(tracedMessage);
            if (toolSessionId != null && !toolSessionId.isBlank()) {
                redisMessageBroker.setSessionRoute(toolSessionId, messageSource, ssInstanceId);
            }
            String welinkSessionId = tracedMessage.getWelinkSessionId();
            if (welinkSessionId != null && !welinkSessionId.isBlank()) {
                redisMessageBroker.setWelinkSessionRoute(welinkSessionId, messageSource, ssInstanceId);
            }
        }

        String ak = tracedMessage.getAk();
        GatewayMessage agentMessage = tracedMessage.withoutRoutingContext();

        // V2: 3-tier delivery — local → remote GW relay → pending queue
        if (deliverToLocalAgent(ak, agentMessage)) {
            relayLocalCount.incrementAndGet();
            log.info("[EXIT->AGENT] Delivered invoke locally: ak={}, action={}, source={}",
                    ak, tracedMessage.getAction(), messageSource);
            return;
        }

        if (relayToRemoteGw(ak, tracedMessage, messageSource)) {
            relayPubsubCount.incrementAndGet();
            log.info("[EXIT->RELAY] Relayed invoke to remote GW: ak={}, action={}, source={}",
                    ak, tracedMessage.getAction(), messageSource);
            return;
        }

        // Agent not found anywhere → enqueue pending
        enqueueToPending(ak, agentMessage);
        relayPendingCount.incrementAndGet();
        log.info("[EXIT->PENDING] Enqueued invoke to pending: ak={}, action={}, source={}",
                ak, tracedMessage.getAction(), messageSource);

        // Legacy fallback: also publish to agent pub/sub channel if legacy-relay is enabled
        if (legacyRelayEnabled) {
            redisMessageBroker.publishToAgent(ak, agentMessage);
            log.info("[EXIT->LEGACY] Also published to legacy agent channel: ak={}", ak);
        }
    }

    /**
     * Attempts to deliver a message to a locally connected Agent.
     *
     * @return true if the Agent is local and message was delivered
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
    private void enqueueToPending(String ak, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisMessageBroker.enqueuePending(ak, json, PENDING_TTL);
        } catch (Exception e) {
            log.error("[V2] Failed to enqueue pending message: ak={}", ak, e);
        }
    }

    /**
     * 从 invoke 消息中提取路由信息并缓存。
     * @deprecated V2 uses UpstreamRoutingTable instead. Kept for legacy cache compatibility.
     */
    @Deprecated
    private void learnRouteFromInvoke(GatewayMessage message, WebSocketSession ssSession) {
        String welinkSessionId = message.getWelinkSessionId();
        String toolSessionId = extractToolSessionIdFromPayload(message);
        learnRoute(toolSessionId, welinkSessionId, ssSession);
    }

    // ==================== V2 route_confirm / route_reject ====================

    /**
     * Handles route_confirm messages from source services.
     * Learns the confirmed route in UpstreamRoutingTable.
     */
    public void handleRouteConfirm(GatewayMessage message) {
        String toolSessionId = message.getToolSessionId();
        String sourceType = message.getSource();
        log.info("[ENTRY] SkillRelayService.handleRouteConfirm: toolSessionId={}, sourceType={}",
                toolSessionId, sourceType);

        if (toolSessionId != null && sourceType != null) {
            List<String> keys = new ArrayList<>();
            keys.add(toolSessionId);
            String welinkSessionId = message.getWelinkSessionId();
            if (welinkSessionId != null && !welinkSessionId.isBlank()) {
                keys.add(UpstreamRoutingTable.WELINK_KEY_PREFIX + welinkSessionId);
            }
            routingTable.learnFromRelay(keys, sourceType);
            log.info("[EXIT] SkillRelayService.handleRouteConfirm: learned route toolSessionId={} -> sourceType={}",
                    toolSessionId, sourceType);
        }
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

    /**
     * Resolves source_type for a message.
     * Priority: UpstreamRoutingTable -> message.source -> legacy route cache -> single active source inference.
     *
     * @deprecated V2 routing uses routingTable.resolveSourceType() directly in v2RelayToSkill.
     */
    @Deprecated
    private String resolveSourceType(GatewayMessage message) {
        // V2: try UpstreamRoutingTable first
        String fromTable = routingTable.resolveSourceType(message);
        if (fromTable != null) {
            return fromTable;
        }

        String source = message.getSource();
        if (source != null && !source.isBlank()) {
            return source;
        }
        // Legacy: try route cache
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null) {
            WebSocketSession cached = routeCache.get(toolSessionId);
            if (cached != null) {
                String s = resolveBoundSource(cached);
                if (s != null)
                    return s;
            }
        }
        // Final fallback: single active source_type
        return inferSingleActiveSourceType();
    }

    private String inferSingleActiveSourceType() {
        String resolved = null;
        for (Map.Entry<String, Map<String, Map<String, WebSocketSession>>> entry : sourceTypeSessions.entrySet()) {
            boolean hasOpen = entry.getValue().values().stream()
                    .flatMap(m -> m.values().stream())
                    .anyMatch(WebSocketSession::isOpen);
            if (hasOpen) {
                if (resolved != null) {
                    return null; // 多个活跃 source_type，无法推断
                }
                resolved = entry.getKey();
            }
        }
        return resolved;
    }

    public int getActiveSourceConnectionCount() {
        int meshCount = (int) sourceTypeSessions.values().stream()
                .flatMap(instanceMap -> instanceMap.values().stream())
                .flatMap(sessionMap -> sessionMap.values().stream())
                .filter(WebSocketSession::isOpen)
                .count();
        return meshCount + legacyStrategy.getActiveConnectionCount();
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

    private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.info("[EXIT->SS] Sent to skill session: linkId={}, type={}", session.getId(), message.getType());
            return true;
        } catch (IOException e) {
            log.error("[EXIT->SS] Failed to send to skill session: linkId={}, type={}",
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
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Failed to send protocol error: linkId={}, reason={}", session.getId(), reason, e);
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
                .filter(WebSocketSession::isOpen)
                .findFirst()
                .orElse(null);
    }

    // ==================== 定时任务 ====================

    /**
     * Periodically logs routing metrics for observability.
     * Outputs cumulative counters since service startup.
     */
    @Scheduled(fixedDelay = 60_000)
    void logMetrics() {
        log.info("[METRICS] relay: local={}, pubsub={}, pending={} | routing: L1hit={}, L2redis={}, L3broadcast={}, broadcastFallback={}",
                relayLocalCount.get(), relayPubsubCount.get(), relayPendingCount.get(),
                routingHitCount.get(), routingRedisL2Count.get(), routingL3BroadcastCount.get(),
                routingBroadcastCount.get());
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

            log.info("[Mesh] Lazy-cleaned stale session during heartbeat: sourceType={}, ssInstanceId={}, sessionId={}",
                    stale.sourceType(), stale.ssInstanceId(), stale.sessionId());
        }
    }

    /**
     * 定时刷新 Legacy 策略的 owner 心跳。
     */
    @Scheduled(fixedDelayString = "#{T(java.time.Duration).ofSeconds(${gateway.skill-relay.owner-heartbeat-interval-seconds:10}).toMillis()}")
    public void refreshLegacyOwnerHeartbeat() {
        legacyStrategy.refreshOwnerHeartbeat();
    }

    /**
     * 定时清理路由缓存中指向已关闭连接的条目，防止长期运行时内存无限增长。
     */
    @Scheduled(fixedDelay = 300_000) // 每 5 分钟
    public void evictStaleRouteCache() {
        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, WebSocketSession> entry : routeCache.entrySet()) {
            if (!entry.getValue().isOpen()) {
                staleKeys.add(entry.getKey());
            }
        }
        for (String key : staleKeys) {
            routeCache.remove(key);
        }
        if (!staleKeys.isEmpty()) {
            log.info("Evicted {} stale route cache entries, remaining={}", staleKeys.size(), routeCache.size());
        }
    }

    // ==================== 策略判断 ====================

    /**
     * 判断连接是否为旧版客户端（无 instanceId）。
     */
    private boolean isLegacyClient(WebSocketSession session) {
        String instanceId = resolveSsInstanceId(session);
        return instanceId == null || instanceId.isBlank();
    }

    /**
     * 判断已注册连接的策略标记。
     */
    private boolean isLegacySession(WebSocketSession session) {
        return SkillRelayStrategy.LEGACY.equals(
                session.getAttributes().get(SkillRelayStrategy.STRATEGY_ATTR));
    }

    // ==================== Accessors for testing ====================

    /** Returns the consistent hash ring for the given sourceType. Package-private for testing. */
    ConsistentHashRing<WebSocketSession> getHashRing(String sourceType) {
        return hashRings.get(sourceType);
    }

    @PreDestroy
    public void destroy() {
        routeCache.clear();
        sourceTypeSessions.clear();
        hashRings.clear();
        log.info("SkillRelayService destroyed: cleared all mesh connections, hash rings, and route cache");
    }
}
