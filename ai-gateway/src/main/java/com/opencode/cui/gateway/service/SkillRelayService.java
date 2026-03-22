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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source 服务 WebSocket 连接管理 + 上行消息路由（复合路由器）。
 *
 * <h3>策略分支</h3>
 * <ul>
 *   <li><b>Mesh</b>（新版 Source，握手时带 instanceId）：路由缓存 + 广播降级</li>
 *   <li><b>Legacy</b>（旧版 Source，无 instanceId）：Owner 心跳 + Redis relay 跨 GW 中继</li>
 * </ul>
 *
 * <p>连接建立时根据 instanceId 是否存在自动选择策略。
 * 上行消息路由优先走 Mesh 路径，失败后回退到 Legacy 路径。</p>
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

    /** 旧版路由策略（兼容不带 instanceId 的 Source 服务） */
    private final LegacySkillRelayStrategy legacyStrategy;

    /**
     * [Mesh] Source 实例连接池：source_type → { ssInstanceId → WebSocketSession }
     */
    private final Map<String, Map<String, WebSocketSession>> sourceTypeSessions = new ConcurrentHashMap<>();

    /**
     * [Mesh] 路由缓存：toolSessionId → SS 连接 或 "w:" + welinkSessionId → SS 连接
     * 被动学习：下行 invoke 经过时写入。
     * 定时清理已关闭连接的条目，防止长期运行时内存无限增长。
     */
    private final Map<String, WebSocketSession> routeCache = new ConcurrentHashMap<>();

    private static final String WELINK_ROUTE_PREFIX = "w:";

    public SkillRelayService(RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId,
            LegacySkillRelayStrategy legacyStrategy) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.gatewayInstanceId = gatewayInstanceId;
        this.legacyStrategy = legacyStrategy;
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

        sourceTypeSessions
                .computeIfAbsent(sourceType, ignored -> new ConcurrentHashMap<>())
                .put(ssInstanceId, session);

        log.info("[Mesh] Registered source session: sourceType={}, ssInstanceId={}, gwInstanceId={}, activeLinks={}",
                sourceType, ssInstanceId, gatewayInstanceId, getActiveConnectionCount(sourceType));
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

        Map<String, WebSocketSession> pool = sourceTypeSessions.get(sourceType);
        if (pool == null) {
            return;
        }

        String ssInstanceId = resolveSsInstanceId(session);
        if (ssInstanceId != null) {
            pool.remove(ssInstanceId, session); // 仅当 value 是同一连接时才删除，防止误删重连后的新连接
        }
        // 也按 linkId 清理（兼容 fallback 情况）
        pool.remove(session.getId(), session);

        if (pool.isEmpty()) {
            sourceTypeSessions.remove(sourceType, pool);
        }

        // 清除路由缓存中所有指向该 session 的条目
        invalidateRoutesForSession(session);

        log.info("[Mesh] Removed source session: sourceType={}, ssInstanceId={}, gwInstanceId={}, activeLinks={}",
                sourceType, ssInstanceId, gatewayInstanceId, getActiveConnectionCount(sourceType));
    }

    // ==================== 路由缓存 ====================

    /**
     * 被动学习路由：将 toolSessionId / welinkSessionId 映射到 SS 连接。
     * 由 SkillWebSocketHandler 在收到下行 invoke 时调用。
     */
    public void learnRoute(String toolSessionId, String welinkSessionId, WebSocketSession ssSession) {
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            routeCache.put(toolSessionId, ssSession);
            log.debug("Learned route: toolSessionId={} → ssLink={}", toolSessionId, ssSession.getId());
        }
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            routeCache.put(WELINK_ROUTE_PREFIX + welinkSessionId, ssSession);
            log.debug("Learned route: welinkSessionId={} → ssLink={}", welinkSessionId, ssSession.getId());
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
     * 将上行消息路由到正确的 Source 服务。
     *
     * <p>路由顺序：</p>
     * <ol>
     *   <li>[Mesh] 查路由缓存（toolSessionId → SS 连接）→ 命中则直推</li>
     *   <li>[Mesh] 查路由缓存（welinkSessionId → SS 连接）→ 命中则直推</li>
     *   <li>[Mesh] 缓存未命中 → 广播到同 source_type 所有 Mesh SS 连接</li>
     *   <li>[Legacy] Mesh 全部失败 → 回退到 Legacy 路径（owner relay 跨 GW 中继）</li>
     * </ol>
     *
     * @return true 如果消息被投递，false 如果无法投递
     */
    public boolean relayToSkill(GatewayMessage message) {
        // Mesh 路径
        if (meshRelayToSkill(message)) {
            return true;
        }
        // Legacy fallback
        return legacyStrategy.relayToSkill(message);
    }

    /**
     * [Mesh] 路由缓存 + 广播降级。
     */
    private boolean meshRelayToSkill(GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String toolSessionId = tracedMessage.getToolSessionId();
        String welinkSessionId = tracedMessage.getWelinkSessionId();

        // 1. toolSessionId 缓存直推
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            WebSocketSession target = routeCache.get(toolSessionId);
            if (target != null && target.isOpen()) {
                log.debug("[Mesh] Route cache hit (toolSessionId): {} → linkId={}, type={}",
                        toolSessionId, target.getId(), tracedMessage.getType());
                if (sendToSession(target, tracedMessage)) {
                    // 顺带学习消息中可能携带的其他路由信息（如 session_created 同时带 toolSessionId + welinkSessionId）
                    learnRouteFromUpstream(toolSessionId, welinkSessionId, target);
                    return true;
                }
            }
        }

        // 2. welinkSessionId 缓存直推
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            WebSocketSession target = routeCache.get(WELINK_ROUTE_PREFIX + welinkSessionId);
            if (target != null && target.isOpen()) {
                log.debug("[Mesh] Route cache hit (welinkSessionId): {} → linkId={}, type={}",
                        welinkSessionId, target.getId(), tracedMessage.getType());
                if (sendToSession(target, tracedMessage)) {
                    // 关键：session_created 通过 welinkSessionId 缓存命中，此时消息同时携带 toolSessionId
                    // 学习 toolSessionId → SS 连接映射，后续 Agent 的 tool_event 可直接命中
                    learnRouteFromUpstream(toolSessionId, welinkSessionId, target);
                    return true;
                }
            }
        }

        // 3. 缓存未命中 → 广播到 Mesh 连接池
        String sourceType = resolveSourceType(tracedMessage);
        if (sourceType == null) {
            log.debug("[Mesh] Cannot resolve source_type, skipping mesh broadcast: type={}", tracedMessage.getType());
            return false;
        }

        return broadcastToSourceType(sourceType, tracedMessage);
    }

    /**
     * 从上行消息中学习路由。
     * 与 learnRoute 相同逻辑，但只补充缓存中还没有的映射（不覆盖已有路由）。
     */
    private void learnRouteFromUpstream(String toolSessionId, String welinkSessionId, WebSocketSession target) {
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            routeCache.putIfAbsent(toolSessionId, target);
        }
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            routeCache.putIfAbsent(WELINK_ROUTE_PREFIX + welinkSessionId, target);
        }
    }

    /**
     * 广播到指定 source_type 的所有 SS 连接。
     */
    private boolean broadcastToSourceType(String sourceType, GatewayMessage message) {
        Map<String, WebSocketSession> pool = sourceTypeSessions.get(sourceType);
        if (pool == null || pool.isEmpty()) {
            log.warn("No source instances for type: {}, type={}", sourceType, message.getType());
            return false;
        }

        int sent = 0;
        for (WebSocketSession ss : pool.values()) {
            if (ss.isOpen()) {
                sendToSession(ss, message);
                sent++;
            }
        }

        log.debug("Broadcast to source_type={}: sent to {}/{} instances, msgType={}",
                sourceType, sent, pool.size(), message.getType());
        return sent > 0;
    }

    // ==================== 下行 invoke 处理 ====================

    /**
     * 处理从 Source 服务发来的 invoke 消息，路由到目标 Agent。
     * Legacy 连接委托到 LegacySkillRelayStrategy，Mesh 连接进行路由学习。
     */
    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        if (isLegacySession(session)) {
            legacyStrategy.handleInvokeFromSkill(session, message);
            return;
        }

        GatewayMessage tracedMessage = message.ensureTraceId();

        // 验证 source
        String boundSource = resolveBoundSource(session);
        String messageSource = tracedMessage.getSource();
        if (messageSource == null || messageSource.isBlank()) {
            log.warn("Rejected invoke: missing source, linkId={}", session.getId());
            sendProtocolError(session, ERROR_SOURCE_NOT_ALLOWED);
            return;
        }
        if (boundSource == null || !boundSource.equals(messageSource)) {
            log.warn("Rejected invoke: source mismatch, bound={}, message={}, linkId={}",
                    boundSource, messageSource, session.getId());
            sendProtocolError(session, ERROR_SOURCE_MISMATCH);
            return;
        }

        // 验证 ak
        if (tracedMessage.getAk() == null || tracedMessage.getAk().isBlank()) {
            log.warn("Rejected invoke: missing ak, linkId={}", session.getId());
            return;
        }

        // 验证 userId
        String expectedUserId = redisMessageBroker.getAgentUser(tracedMessage.getAk());
        if (tracedMessage.getUserId() == null || tracedMessage.getUserId().isBlank()) {
            log.warn("Rejected invoke: missing userId, linkId={}, ak={}",
                    session.getId(), tracedMessage.getAk());
            return;
        }
        if (expectedUserId == null || !tracedMessage.getUserId().equals(expectedUserId)) {
            log.warn("Rejected invoke: userId mismatch, expected={}, actual={}, ak={}",
                    expectedUserId, tracedMessage.getUserId(), tracedMessage.getAk());
            return;
        }

        // 路由学习：从 invoke 中提取 toolSessionId/welinkSessionId → SS 连接
        learnRouteFromInvoke(tracedMessage, session);

        // 发布到 Agent（通过 Redis pub/sub agent:{ak}）
        redisMessageBroker.publishToAgent(tracedMessage.getAk(), tracedMessage.withoutRoutingContext());

        log.debug("Forwarded invoke to agent: ak={}, action={}, source={}, gwInstanceId={}",
                tracedMessage.getAk(), tracedMessage.getAction(), messageSource, gatewayInstanceId);
    }

    /**
     * 从 invoke 消息中提取路由信息并缓存。
     */
    private void learnRouteFromInvoke(GatewayMessage message, WebSocketSession ssSession) {
        String welinkSessionId = message.getWelinkSessionId();
        String toolSessionId = extractToolSessionIdFromPayload(message);
        learnRoute(toolSessionId, welinkSessionId, ssSession);
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
                ? null : toolSessionNode.asText(null);
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析消息的 source_type。
     * 优先级: message.source → 路由缓存关联的 source → 单一活跃 source 推断
     */
    private String resolveSourceType(GatewayMessage message) {
        String source = message.getSource();
        if (source != null && !source.isBlank()) {
            return source;
        }
        // 尝试从路由缓存推断
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null) {
            WebSocketSession cached = routeCache.get(toolSessionId);
            if (cached != null) {
                String s = resolveBoundSource(cached);
                if (s != null) return s;
            }
        }
        // 最终 fallback: 如果只有一种 source_type，直接用
        return inferSingleActiveSourceType();
    }

    private String inferSingleActiveSourceType() {
        String resolved = null;
        for (Map.Entry<String, Map<String, WebSocketSession>> entry : sourceTypeSessions.entrySet()) {
            boolean hasOpen = entry.getValue().values().stream().anyMatch(WebSocketSession::isOpen);
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
                .map(Map::values)
                .flatMap(Collection::stream)
                .filter(WebSocketSession::isOpen)
                .count();
        return meshCount + legacyStrategy.getActiveConnectionCount();
    }

    private int getActiveConnectionCount(String sourceType) {
        Map<String, WebSocketSession> pool = sourceTypeSessions.get(sourceType);
        if (pool == null) return 0;
        return (int) pool.values().stream().filter(WebSocketSession::isOpen).count();
    }

    private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException e) {
            log.error("Failed to send to source session: linkId={}, type={}",
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

    // ==================== 定时任务 ====================

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

    @PreDestroy
    public void destroy() {
        routeCache.clear();
        sourceTypeSessions.clear();
        log.info("SkillRelayService destroyed: cleared all mesh connections and route cache");
    }
}
