package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import com.opencode.cui.gateway.ws.AsyncSessionSender;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * PC Agent WebSocket 会话与 Skill Server 之间的消息路由服务。
 * Agent 会话以 AK（Access Key）为标识，保证整个系统（Gateway ↔ Skill Server）中一致的路由。
 */
@Slf4j
@Service
public class EventRelayService {

    /** 状态查询等待超时（毫秒） */
    private static final long STATUS_QUERY_TIMEOUT_MS = 1500L;
    private static final Duration AGENT_TRACE_TTL = Duration.ofMinutes(30);
    private static final String TRACE_TOOL_PREFIX = "tool:";
    private static final String TRACE_WELINK_PREFIX = "welink:";
    private static final String DEFAULT_SOURCE_TYPE = "skill-server";

    /** 已连接 Agent 的 WebSocket 会话映射：ak → session */
    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AsyncSessionSender> sessionSenders = new ConcurrentHashMap<>();
    private final Map<String, Boolean> opencodeStatusCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> pendingStatusQueries = new ConcurrentHashMap<>();
    private final Cache<String, String> agentTraceByCorrelationKey = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(AGENT_TRACE_TTL)
            .build();

    private final ObjectMapper objectMapper;
    private final RedisMessageBroker redisMessageBroker;
    private final SkillRelayService skillRelayService;
    private final UpstreamRoutingTable routingTable;
    private final String selfInstanceId;

    public EventRelayService(ObjectMapper objectMapper,
            RedisMessageBroker redisMessageBroker,
            SkillRelayService skillRelayService,
            UpstreamRoutingTable routingTable,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String selfInstanceId) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.skillRelayService = skillRelayService;
        this.routingTable = routingTable;
        this.selfInstanceId = selfInstanceId;

        // Break circular dependency: SkillRelayService needs EventRelayService for local agent lookup
        skillRelayService.setEventRelayService(this);
    }

    /**
     * Subscribes this GW instance to its own relay channel {@code gw:relay:{selfInstanceId}}.
     *
     * <p>On message receipt:
     * <ol>
     *   <li>If the raw JSON contains {@code "type":"relay"}, parse as {@link RelayMessage} and
     *       extract {@code originalMessage}.</li>
     *   <li>Otherwise treat as legacy raw {@link GatewayMessage} JSON (backward compatibility).</li>
     *   <li>Deserialize to {@link GatewayMessage} and deliver to the local Agent session.</li>
     * </ol>
     */
    @PostConstruct
    public void subscribeToSelfRelayChannel() {
        redisMessageBroker.subscribeToGwRelay(selfInstanceId, this::handleGwRelayMessage);
        log.info("[ENTRY] EventRelayService subscribed to GW relay channel: instanceId={}", selfInstanceId);
    }

    /**
     * Handles a raw JSON string received from the GW relay channel.
     *
     * <p>Distinguishes new-format ({@link RelayMessage}) from legacy raw {@link GatewayMessage}
     * JSON by checking for the {@code "type":"relay"} discriminator.
     *
     * @param rawJson raw JSON string from Redis
     */
    void handleGwRelayMessage(String rawJson) {
        try {
            String gatewayMessageJson;
            String relaySourceType = null;
            java.util.List<String> relayRoutingKeys = null;

            if (rawJson.contains("\"type\":\"relay\"")) {
                // New format: RelayMessage wrapper
                RelayMessage relayMessage = objectMapper.readValue(rawJson, RelayMessage.class);

                // Handle to-source relay: deliver to a local Source WebSocket connection
                if (RelayMessage.RELAY_TO_SOURCE.equals(relayMessage.relayType())) {
                    handleToSourceRelay(relayMessage);
                    return;
                }

                // Handle cloud-control relay: process a control frame on the GW instance that owns
                // the local cloud SSE/WebSocket stream.
                if (RelayMessage.RELAY_TO_CLOUD_CONTROL.equals(relayMessage.relayType())) {
                    skillRelayService.handleCloudControlRelay(relayMessage.originalMessage());
                    return;
                }

                gatewayMessageJson = relayMessage.originalMessage();
                relaySourceType = relayMessage.sourceType();
                relayRoutingKeys = relayMessage.routingKeys();
                log.info("EventRelayService.handleGwRelayMessage: new-format relay, sourceType={}",
                        relaySourceType);
            } else {
                // Legacy format: raw GatewayMessage JSON
                gatewayMessageJson = rawJson;
                log.info("EventRelayService.handleGwRelayMessage: legacy-format relay, length={}", rawJson.length());
            }

            // V2: Propagate routing knowledge from relay metadata
            if (relaySourceType != null && relayRoutingKeys != null && !relayRoutingKeys.isEmpty()) {
                routingTable.learnFromRelay(relayRoutingKeys, relaySourceType);
                log.info("EventRelayService.handleGwRelayMessage: propagated {} routing keys for sourceType={}",
                        relayRoutingKeys.size(), relaySourceType);
            }

            GatewayMessage message = objectMapper.readValue(gatewayMessageJson, GatewayMessage.class);
            var previousMdc = MdcHelper.snapshot();
            try {
                MdcHelper.fromGatewayMessage(message);
                MdcHelper.putScenario("gw-relay-rx");
                String ak = message.getAk();
                if (ak == null || ak.isBlank()) {
                    log.warn("[ERROR] EventRelayService.handleGwRelayMessage: ak is null or blank, dropping message type={}",
                            message.getType());
                    return;
                }

                log.info("EventRelayService.handleGwRelayMessage: delivering to local agent, ak={}, type={}",
                        ak, message.getType());
                sendToLocalAgent(ak, message);
            } finally {
                MdcHelper.restore(previousMdc);
            }
        } catch (Exception e) {
            log.error("[ERROR] EventRelayService.handleGwRelayMessage: failed to process relay message: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Handles a to-source relay message by delivering the payload to the local Source WebSocket connection.
     *
     * @param relayMessage the relay message with relayType="to-source"
     */
    private void handleToSourceRelay(RelayMessage relayMessage) {
        var previousMdc = MdcHelper.snapshot();
        try {
            restoreMdcFromGatewayPayload(relayMessage.originalMessage(), "gw-to-source-relay-rx");
            handleToSourceRelayWithMdc(relayMessage);
        } finally {
            MdcHelper.restore(previousMdc);
        }
    }

    private void handleToSourceRelayWithMdc(RelayMessage relayMessage) {
        String targetSourceType = relayMessage.targetSourceType();
        String targetSourceInstanceId = relayMessage.targetSourceInstanceId();
        String payload = relayMessage.originalMessage();

        log.info("EventRelayService.handleToSourceRelay: targetSourceType={}, targetSourceInstanceId={}",
                targetSourceType, targetSourceInstanceId);

        WebSocketSession session = skillRelayService.findLocalSourceConnection(
                targetSourceType, targetSourceInstanceId);
        if (session != null) {
            boolean enqueued = getOrCreateSender(session).enqueue(new TextMessage(payload));
            if (!enqueued) {
                log.warn("[EXIT->SOURCE] Failed to enqueue to-source relay: sourceType={}, sourceInstanceId={}",
                        targetSourceType, targetSourceInstanceId);
            } else {
                log.info("[EXIT->SOURCE] Enqueued to-source relay: sourceType={}, sourceInstanceId={}",
                        targetSourceType, targetSourceInstanceId);
            }
        } else {
            log.debug("No local connection for source {}/{}, discarding relay",
                    targetSourceType, targetSourceInstanceId);
        }
    }

    private void restoreMdcFromGatewayPayload(String payload, String scenario) {
        try {
            GatewayMessage message = objectMapper.readValue(payload, GatewayMessage.class);
            MdcHelper.fromGatewayMessage(message);
            MdcHelper.putScenario(scenario);
        } catch (Exception e) {
            log.debug("Failed to restore MDC from gateway payload: {}", e.getMessage());
        }
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
        if (session != null) {
            removeSessionSender(session.getId());
            if (session.isOpen()) {
                try {
                    session.close();
                } catch (IOException e) {
                    log.warn("Error closing session during removal for ak={}", ak, e);
                }
            }
        }

        opencodeStatusCache.put(ak, false);
        CompletableFuture<Boolean> pending = pendingStatusQueries.remove(ak);
        if (pending != null) {
            pending.complete(false);
        }
        redisMessageBroker.removeAgentUser(ak);
        redisMessageBroker.unsubscribeFromAgent(ak);
        log.info("Removed agent session: ak={}", ak);
    }

    public boolean hasAgentSession(String ak) {
        WebSocketSession session = agentSessions.get(ak);
        return session != null && session.isOpen();
    }

    public boolean hasRememberedAgentTrace(GatewayMessage message) {
        return findRememberedAgentTrace(message) != null;
    }

    public GatewayMessage ensureAgentEventTraceId(GatewayMessage message) {
        if (message == null) {
            return null;
        }
        if (hasText(message.getTraceId())) {
            rememberAgentTrace(message);
            return message;
        }

        String traceId = findRememberedAgentTrace(message);
        GatewayMessage tracedMessage = traceId != null
                ? message.withTraceId(traceId)
                : message.ensureTraceId();
        rememberAgentTrace(tracedMessage);
        return tracedMessage;
    }

    void rememberAgentTrace(GatewayMessage message) {
        if (message == null || !hasText(message.getTraceId())) {
            return;
        }
        for (String key : agentTraceKeys(message)) {
            agentTraceByCorrelationKey.put(key, message.getTraceId());
        }
    }

    /**
     * 上行消息路由到 Source 服务。
     * v3: 注入 ak/userId/traceId 后直接交给 SkillRelayService 路由。
     * Source 解析由 SkillRelayService 的路由缓存处理，不再查 Redis gw:agent:source:{ak}。
     */
    public void relayToSkillServer(String ak, GatewayMessage message) {
        long start = System.nanoTime();
        GatewayMessage tracedMessage = message.ensureTraceId();
        String userId = redisMessageBroker.getAgentUser(ak);
        GatewayMessage forwarded = tracedMessage.withAk(ak)
                .withUserId(userId);
        if (hasUpstreamRoutingKey(forwarded)) {
            forwarded = forwarded.withSource(DEFAULT_SOURCE_TYPE);
        }

        // 保存调用方的 MDC 上下文，方法结束后恢复（避免清除调用方已设置的 traceId/ak）
        var previousMdc = MdcHelper.snapshot();
        try {
            MdcHelper.fromGatewayMessage(forwarded);
            MdcHelper.putScenario("relay-to-skill");

            logMessageInfo(
                    forwarded,
                    "[ENTRY] EventRelayService.relayToSkillServer: type={}, ak={}, toolSessionId={}, welinkSessionId={}",
                    tracedMessage.getType(), ak, forwarded.getToolSessionId(), forwarded.getWelinkSessionId());

            boolean routed = skillRelayService.relayToSkill(forwarded);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (!routed) {
                log.warn(
                        "[ERROR] EventRelayService.relayToSkillServer: reason=route_failed, type={}, welinkSessionId={}, durationMs={}",
                        message.getType(), forwarded.getWelinkSessionId(), elapsedMs);
            } else {
                logMessageInfo(forwarded, "[EXIT] EventRelayService.relayToSkillServer: type={}, durationMs={}",
                        message.getType(), elapsedMs);
            }
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[ERROR] EventRelayService.relayToSkillServer: type={}, durationMs={}",
                    message.getType(), elapsedMs, e);
        } finally {
            MdcHelper.restore(previousMdc);
        }
    }

    public void relayToAgent(String ak, GatewayMessage message) {
        log.info("[ENTRY] EventRelayService.relayToAgent: ak={}, type={}", ak, message.getType());
        redisMessageBroker.publishToAgent(ak, message.withoutRoutingContext());
        log.info("[EXIT->AGENT] EventRelayService.relayToAgent: ak={}, type={}", ak, message.getType());
    }

    /**
     * Attempts to deliver a message to a locally connected Agent.
     * Used by SkillRelayService for V2 local-first delivery.
     *
     * @return true if the Agent is connected locally and the message was sent successfully
     */
    public boolean sendToLocalAgentIfPresent(String ak, GatewayMessage message) {
        WebSocketSession session = agentSessions.get(ak);
        if (session == null || !session.isOpen()) {
            return false;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            boolean enqueued = getOrCreateSender(session).enqueue(new TextMessage(json));
            if (!enqueued) {
                log.warn("[EXIT->AGENT] Failed to enqueue message for local agent (V2 direct): ak={}, type={}",
                        ak, message.getType());
                return false;
            }
            rememberAgentTrace(message);
            log.info("[EXIT->AGENT] Enqueued to local agent (V2 direct): ak={}, type={}",
                    ak, message.getType());
            return true;
        } catch (IOException e) {
            log.error("[ERROR] Failed to serialize message for local agent (V2 direct): ak={}, type={}",
                    ak, message.getType(), e);
            return false;
        }
    }

    private void sendToLocalAgent(String ak, GatewayMessage message) {
        WebSocketSession session = agentSessions.get(ak);
        if (session == null || !session.isOpen()) {
            log.debug("Agent not connected to this instance: ak={}, type={}",
                    ak, message.getType());
            return;
        }

        try {
            GatewayMessage agentMessage = message.withoutRoutingContext();
            String json = objectMapper.writeValueAsString(agentMessage);
            boolean enqueued = getOrCreateSender(session).enqueue(new TextMessage(json));
            if (!enqueued) {
                log.warn("[EXIT->AGENT] Failed to enqueue message for local agent: ak={}, type={}",
                        ak, message.getType());
            } else {
                rememberAgentTrace(agentMessage);
                log.info("[EXIT->AGENT] Enqueued to local agent: type={}, seq={}",
                        message.getType(), message.getSequenceNumber());
            }
        } catch (IOException e) {
            log.error("Failed to serialize message for local agent: ak={}, type={}",
                    ak, message.getType(), e);
        }
    }

    /**
     * 向指定 AK 的 Agent 发送 status_query 消息。
     * PC Agent 将返回包含 OpenCode 健康信息的 status_response。
     */
    public void sendStatusQuery(String ak) {
        GatewayMessage query = GatewayMessage.statusQuery();
        sendToLocalAgent(ak, query);
        log.info("Sent status_query to agent: ak={}", ak);
    }

    private void logMessageInfo(GatewayMessage message, String format, Object... args) {
        if (message != null && GatewayMessage.Type.TOOL_EVENT.equals(message.getType())) {
            log.debug(format, args);
            return;
        }
        log.info(format, args);
    }

    private String findRememberedAgentTrace(GatewayMessage message) {
        for (String key : agentTraceKeys(message)) {
            String traceId = agentTraceByCorrelationKey.getIfPresent(key);
            if (hasText(traceId)) {
                return traceId;
            }
        }
        return null;
    }

    private Set<String> agentTraceKeys(GatewayMessage message) {
        Set<String> keys = new LinkedHashSet<>();
        if (message == null) {
            return keys;
        }
        addTraceKey(keys, TRACE_TOOL_PREFIX, message.getToolSessionId());
        addTraceKey(keys, TRACE_TOOL_PREFIX, payloadText(message, "toolSessionId"));
        addTraceKey(keys, TRACE_WELINK_PREFIX, message.getWelinkSessionId());
        addTraceKey(keys, TRACE_WELINK_PREFIX, payloadText(message, "welinkSessionId"));
        return keys;
    }

    private static void addTraceKey(Set<String> keys, String prefix, String value) {
        if (hasText(value)) {
            keys.add(prefix + value);
        }
    }

    private static boolean hasUpstreamRoutingKey(GatewayMessage message) {
        return hasText(message.getWelinkSessionId())
                || hasText(message.getToolSessionId())
                || hasText(payloadText(message, "toolSessionId"));
    }

    private static String payloadText(GatewayMessage message, String fieldName) {
        JsonNode payload = message.getPayload();
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return null;
        }
        JsonNode value = payload.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return hasText(text) ? text : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 请求 Agent 的最新 OpenCode 健康状态，短暂等待 status_response。
     * 超时后降级使用上次缓存的值。
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

    /** 向所有当前连接的 Agent 发送 status_query。 */
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

    private AsyncSessionSender getOrCreateSender(WebSocketSession session) {
        return sessionSenders.computeIfAbsent(session.getId(), k -> {
            AsyncSessionSender sender = new AsyncSessionSender(session);
            sender.start();
            return sender;
        });
    }

    public void removeSessionSender(String sessionId) {
        AsyncSessionSender sender = sessionSenders.remove(sessionId);
        if (sender != null) {
            sender.shutdown();
        }
    }
}
