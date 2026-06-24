package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.PendingChatRequest;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.event.ToolDoneEvent;
import com.opencode.cui.skill.model.event.ToolErrorEvent;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnContext;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.service.scope.DefaultAssistantScopeStrategy;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway 上行消息路由器。
 * 接收来自 Gateway
 * 的各类消息（tool_event、tool_done、tool_error、session_created、permission_request 等），
 * 根据消息类型路由到相应的内部处理逻辑。
 *
 * <p>
 * 主要职责：
 * </p>
 * <ul>
 * <li>工具事件的翻译、缓冲、持久化与广播</li>
 * <li>会话生命周期管理（激活、空闲、重建）</li>
 * <li>IM 渠道消息的出站转发</li>
 * <li>权限请求的分发与交互状态同步</li>
 * </ul>
 */
@Slf4j
@Component
public class GatewayMessageRouter {

    /** 上下文溢出时发送给用户的提示消息 */
    private static final String CONTEXT_RESET_MESSAGE = "对话上下文已超出限制，已自动重置，请稍后重试。";

    /** route_confirm 强制重发间隔（lease）：到期后即使 cache 命中也会重发一次，用于自愈 GW 失忆。 */
    private static final Duration FORCE_RECONFIRM_INTERVAL = Duration.ofMinutes(5);

    /** route_confirm 去重缓存最大容量，与 GW UpstreamRoutingTable 对齐。 */
    private static final long DEDUP_CACHE_MAX_SIZE = 100_000L;
    private final ObjectMapper objectMapper;
    private final SkillMessageService messageService;
    private final SkillSessionService sessionService;
    private final RedisMessageBroker redisMessageBroker;
    private final OpenCodeEventTranslator translator;
    private final MessagePersistenceService persistenceService;
    private final StreamBufferService bufferService;
    private final SessionRebuildService rebuildService;
    private final ImInteractionStateService interactionStateService;
    private final ImOutboundService imOutboundService;
    private final OutboundDeliveryDispatcher outboundDeliveryDispatcher;
    private final com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
    private final AssistantInfoService assistantInfoService;
    private final AssistantAvailabilityService availabilityService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final ChannelLookupService channelLookupService;
    private final ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService;
    private final DefaultAssistantRuleService ruleService;
    private final MessageTurnLifecycle messageTurnLifecycle;
    /** 已完成轮次的短期缓存，用于抑制同一 trace 在 tool_done 后的残余事件 */
    private final Cache<String, Instant> completedSessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(1_000)
            .build();

    // ==================== Metrics counters ====================

    /** Count of messages dispatched locally (this instance is the session owner). */
    private final AtomicLong routeLocalCount = new AtomicLong();
    /** Count of messages relayed to a remote SS instance via Redis pub/sub. */
    private final AtomicLong routeRelayCount = new AtomicLong();
    /** Count of successful session owner takeovers. */
    private final AtomicLong takeoverCount = new AtomicLong();
    /** Count of failed takeover attempts (another instance won the race). */
    private final AtomicLong takeoverConflictCount = new AtomicLong();
    /** Count of shouldTakeover detections where the current owner was found dead. */
    private final AtomicLong ownerProbeDeadCount = new AtomicLong();
    /** Count of messages received via SS relay channel (this instance is the relay target). */
    private final AtomicLong routeRelayReceiveCount = new AtomicLong();
    /** Count of route_confirm messages actually sent to GW. */
    private final AtomicLong confirmSentCount = new AtomicLong();
    /** Count of route_confirm dedup-skipped (cache hit, not stale, not remap). */
    private final AtomicLong confirmDedupSkipCount = new AtomicLong();
    /** Count of route_confirm forced re-confirms (lease expired). */
    private final AtomicLong confirmForceReconfirmCount = new AtomicLong();
    /** Count of route_confirm send failures (no GW connection / serialize fail). */
    private final AtomicLong confirmSendFailCount = new AtomicLong();

    /**
     * 下游命令发送接口。
     * 由 WebSocket 或 HTTP 层实现，用于向 Gateway 发送 invoke 命令。
     */
    public interface DownstreamSender {
        /** 向 Gateway 发送 invoke 命令。 */
        void sendInvokeToGateway(InvokeCommand command);
    }

    /**
     * 路由响应发送接口（Task 2.10）。
     * 由 GatewayRelayService 注入，用于向 GW 回复 route_confirm / route_reject。
     */
    public interface RouteResponseSender {
        /**
         * 向 GW 发送 route_confirm：确认该 toolSessionId 路由归属于本 SS。
         *
         * @return true 表示已成功投递到 GW；false 表示因连接缺失/序列化失败等原因未投递。
         *         调用方应根据该返回值决定是否写入去重 cache（cache-after-success）。
         */
        boolean sendRouteConfirm(String toolSessionId, String welinkSessionId);

        /** 向 GW 发送 route_reject：通知 GW 本 SS 无法处理该 toolSessionId 的消息。 */
        void sendRouteReject(String toolSessionId);
    }

    /** route_confirm 去重 cache 的 value 类型：记录上一次确认的 welinkSessionId 与时间。 */
    private record ConfirmedState(String welinkSessionId, Instant confirmedAt) {
    }

    private static final class CloudImTextAccumulator {
        private final String messageId;
        private final long order;
        private final StringBuilder content = new StringBuilder();
        private String traceId;
        private String partId;

        private CloudImTextAccumulator(String messageId, long order) {
            this.messageId = messageId;
            this.order = order;
        }

        private void append(String delta, String traceId, String partId) {
            content.append(delta);
            String normalizedTraceId = ProtocolUtils.firstNonBlank(traceId, null);
            if (normalizedTraceId != null) {
                this.traceId = normalizedTraceId;
            }
            String normalizedPartId = ProtocolUtils.firstNonBlank(partId, null);
            if (normalizedPartId != null) {
                this.partId = normalizedPartId;
            }
        }

        private boolean isEmpty() {
            return content.isEmpty();
        }
    }

    /** 下游发送者引用（延迟注入） */
    private volatile DownstreamSender downstreamSender;

    /** 路由响应发送者引用（延迟注入，避免循环依赖） */
    private volatile RouteResponseSender routeResponseSender;

    /** 会话路由服务，用于多实例场景下的 ownership 检查 */
    private final SessionRouteService sessionRouteService;
    /** SS 实例心跳注册器，用于探活判断 */
    private final SkillInstanceRegistry skillInstanceRegistry;
    /** 本实例 ID */
    private final String instanceId;
    /** Owner 被判定为死亡的超时阈值（秒） */
    private final int ownerDeadThresholdSeconds;

    /** IM text.delta accumulator: sessionId -> messageId/traceId bucket. */
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentHashMap<String, CloudImTextAccumulator>> cloudImTextBuffer =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong cloudImTextBufferOrder = new AtomicLong();

    /** route_confirm 去重 kill switch；false 时退化为每条上行都发 confirm（与改动前行为一致）。 */
    private final boolean confirmDedupEnabled;
    /** route_confirm 去重 cache 过期分钟数（必须 < GW UpstreamRoutingTable 30min）。 */
    private final int confirmCacheExpireMinutes;
    /** 用于 confirmedAt 时间戳的 Clock，测试可注入 fake clock 推进时间。 */
    private final Clock clock;
    /** 用于 Caffeine cache 过期判定的 Ticker，测试可注入 fake ticker 推进虚拟时间。 */
    private final Ticker ticker;
    /** route_confirm 去重缓存：toolSessionId -> (welinkSessionId, confirmedAt)；@PostConstruct 初始化。 */
    private Cache<String, ConfirmedState> confirmedToolSessions;

    /** 业务助手 IM 场景需过滤的云端扩展事件类型集合 */
    private static final Set<String> BUSINESS_IM_FILTERED_TYPES = Set.of(
            StreamMessage.Types.PLANNING_DELTA, StreamMessage.Types.PLANNING_DONE,
            StreamMessage.Types.THINKING_DELTA, StreamMessage.Types.THINKING_DONE,
            StreamMessage.Types.SEARCHING, StreamMessage.Types.SEARCH_RESULT,
            StreamMessage.Types.REFERENCE, StreamMessage.Types.ASK_MORE);

    private static final String MISSING_MESSAGE_ID_BUFFER_KEY = "__missing_message_id__";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * Spring 主构造：自动注入 Clock / Ticker（生产默认 systemUTC / systemTicker）。
     */
    @Autowired
    public GatewayMessageRouter(ObjectMapper objectMapper,
            SkillMessageService messageService,
            SkillSessionService sessionService,
            RedisMessageBroker redisMessageBroker,
            OpenCodeEventTranslator translator,
            MessagePersistenceService persistenceService,
            StreamBufferService bufferService,
            SessionRebuildService rebuildService,
            ImInteractionStateService interactionStateService,
            ImOutboundService imOutboundService,
            SessionRouteService sessionRouteService,
            SkillInstanceRegistry skillInstanceRegistry,
            AssistantInfoService assistantInfoService,
            ChannelLookupService channelLookupService,
            ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService,
            AssistantScopeDispatcher scopeDispatcher,
            OutboundDeliveryDispatcher outboundDeliveryDispatcher,
            com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter,
            AssistantAvailabilityService availabilityService,
            DefaultAssistantRuleService ruleService,
            MessageTurnLifecycle messageTurnLifecycle,
            @Value("${skill.relay.owner-dead-threshold-seconds:120}") int ownerDeadThresholdSeconds,
            @Value("${skill.relay.confirm-dedup.enabled:true}") boolean confirmDedupEnabled,
            @Value("${skill.relay.confirm-dedup.cache-expire-minutes:25}") int confirmCacheExpireMinutes,
            Clock clock,
            Ticker ticker) {
        this.objectMapper = objectMapper;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.redisMessageBroker = redisMessageBroker;
        this.translator = translator;
        this.persistenceService = persistenceService;
        this.bufferService = bufferService;
        this.rebuildService = rebuildService;
        this.interactionStateService = interactionStateService;
        this.imOutboundService = imOutboundService;
        this.outboundDeliveryDispatcher = outboundDeliveryDispatcher;
        this.emitter = emitter;
        this.sessionRouteService = sessionRouteService;
        this.skillInstanceRegistry = skillInstanceRegistry;
        this.assistantInfoService = assistantInfoService;
        this.channelLookupService = channelLookupService;
        this.channelSuppressReplyWhitelistService = channelSuppressReplyWhitelistService;
        this.scopeDispatcher = scopeDispatcher;
        this.availabilityService = availabilityService;
        this.ruleService = ruleService;
        this.messageTurnLifecycle = messageTurnLifecycle;
        this.instanceId = skillInstanceRegistry.getInstanceId();
        this.ownerDeadThresholdSeconds = ownerDeadThresholdSeconds;
        this.confirmDedupEnabled = confirmDedupEnabled;
        this.confirmCacheExpireMinutes = confirmCacheExpireMinutes;
        this.clock = clock;
        this.ticker = ticker;
    }

    // ==================== SS relay subscription (启动时订阅本实例的中转 channel) ====================

    /**
     * 启动时订阅本实例的 SS relay channel，接收其他 SS 实例中转过来的消息。
     *
     * <p>与 {@link SkillInstanceRegistry#register()} 配合：心跳注册让其他实例知道本实例存活，
     * relay 订阅让其他实例的消息能真正送达。两者缺一不可。</p>
     *
     * <p>必须使用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：
     * Spring 的 {@code RedisMessageListenerContainer} 是 {@code SmartLifecycle}（默认
     * {@code autoStartup=true}），会在所有 {@code @PostConstruct} 方法执行完毕之后才
     * {@code start()}。在 {@code @PostConstruct} 阶段调 {@code addMessageListener} 时
     * container 尚未 running，listener 仅被加进 {@code channelMapping} 但**不会真实**
     * 把 SUBSCRIBE 命令发到 Redis（spring-data-redis 3.4.6 + Lettuce 6.4.2 实测）。
     * 监听 {@code ApplicationReadyEvent} 时 container 已 start，
     * {@code addMessageListener} 会同步触发 SUBSCRIBE。详见 PRD D1。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    void initSsRelaySubscription(ApplicationReadyEvent event) {
        redisMessageBroker.subscribeToSsRelay(instanceId, this::handleSsRelayMessage);
        log.info("[ENTRY] GatewayMessageRouter.initSsRelaySubscription: instanceId={}, channel=ss:relay:{}",
                instanceId, instanceId);
    }

    /**
     * 初始化 route_confirm 去重缓存。
     *
     * <p>TTL 由 {@code skill.relay.confirm-dedup.cache-expire-minutes} 配置驱动，
     * 默认 25 分钟（必须 < GW UpstreamRoutingTable 30min 才能保证 GW 失忆前自愈）。</p>
     */
    @PostConstruct
    public void initConfirmDedupCache() {
        this.confirmedToolSessions = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(confirmCacheExpireMinutes))
                .maximumSize(DEDUP_CACHE_MAX_SIZE)
                .ticker(ticker)
                .build();
        log.info("[ENTRY] GatewayMessageRouter.initConfirmDedupCache: enabled={}, expireMinutes={}, forceReconfirmIntervalMin={}",
                confirmDedupEnabled, confirmCacheExpireMinutes, FORCE_RECONFIRM_INTERVAL.toMinutes());
    }

    /**
     * 处理从其他 SS 实例通过 Redis pub/sub 中转过来的消息。
     *
     * <p>消息格式为 {@link #serializeRelayMessage} 生成的 JSON envelope，
     * 包含 type、ak、userId、sessionId 和原始 payload。
     * 直接调用 {@link #dispatchLocally} 进行本地处理，不再走 {@link #route} 避免循环中转。</p>
     */
    private void handleSsRelayMessage(String rawMessage) {
        try {
            JsonNode envelope = objectMapper.readTree(rawMessage);
            String type = envelope.path("type").asText("");
            String ak = envelope.path("ak").asText(null);
            String userId = envelope.path("userId").asText(null);
            String sessionId = envelope.path("sessionId").asText(null);
            JsonNode payload = envelope.path("payload");

            MdcHelper.putTraceId(envelope.path("traceId").asText(null));
            MdcHelper.ensureTraceId();
            MdcHelper.putAk(ak);
            MdcHelper.putSessionId(sessionId);
            MdcHelper.putScenario("ss-relay-" + type);

            routeRelayReceiveCount.incrementAndGet();
            logRouteInfo(type, "[ENTRY] GatewayMessageRouter.handleSsRelayMessage: type={}, sessionId={}, ak={}",
                    type, sessionId, ak);

            dispatchLocally(type, sessionId, ak, userId, payload);

            logRouteInfo(type, "[EXIT] GatewayMessageRouter.handleSsRelayMessage: type={}, sessionId={}",
                    type, sessionId);
        } catch (Exception e) {
            log.error("[ERROR] GatewayMessageRouter.handleSsRelayMessage: error={}", e.getMessage(), e);
        } finally {
            MdcHelper.clearAll();
        }
    }

    /** 设置下游命令发送者。 */
    public void setDownstreamSender(DownstreamSender sender) {
        this.downstreamSender = sender;
    }

    /** 设置路由响应发送者（由 GatewayRelayService 在构造后注入）。 */
    public void setRouteResponseSender(RouteResponseSender sender) {
        this.routeResponseSender = sender;
    }



    /** 清除会话的完成标记，使其可以再次接收事件。 */
    public void clearCompletionMark(String sessionId) {
        if (sessionId != null) {
            completedSessions.invalidate(completionKey(sessionId, null));
        }
    }

    /**
     * 消息路由入口。
     * 根据消息类型分发到对应的处理方法。
     * <p>
     * 多实例路由逻辑：
     * 1. 本实例是 session owner → 本地处理
     * 2. 有远程 owner → relay 给 owner 实例
     * 3. owner 失联（心跳丢失/updatedAt 超时）→ 乐观锁接管
     * 4. 无 owner → auto-claim
     */
    public void route(String type, String ak, String userId, JsonNode node) {
        String sessionId = requiresSessionAffinity(type) ? resolveSessionId(type, node) : null;

        // Non-session-affinity messages (agent_online, agent_offline, session_created): always process locally
        if (sessionId == null) {
            logRouteInfo(type, "[ENTRY] GatewayMessageRouter.route: type={}, sessionId=null, ak={}, userId={}",
                    type, ak, userId);
            routeLocalCount.incrementAndGet();
            dispatchLocally(type, sessionId, ak, userId, node);
            logRouteInfo(type, "[EXIT] GatewayMessageRouter.route: type={}, sessionId=null", type);
            return;
        }

        // Session-affinity messages: resolve owner and decide route strategy
        String ownerInstance = sessionRouteService.getOwnerInstance(sessionId);

        // Case 1: This instance is the owner → process locally
        if (instanceId.equals(ownerInstance)) {
            logRouteInfo(type, "[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, ak={}, strategy=local_owner",
                    type, sessionId, ak);
            routeLocalCount.incrementAndGet();
            dispatchLocally(type, sessionId, ak, userId, node);
            logRouteInfo(type, "[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
            return;
        }

        // Case 2: Remote owner exists -> try relay
        if (ownerInstance != null) {
            String rawMessage = serializeRelayMessage(type, sessionId, ak, userId, node);
            boolean published = redisMessageBroker.publishToSsRelayBestEffort(ownerInstance, rawMessage);
            if (published) {
                routeRelayCount.incrementAndGet();
                logRouteInfo(type, "[EXIT] GatewayMessageRouter.route: type={}, sessionId={}, strategy=relay_to_{}",
                        type, sessionId, ownerInstance);
                return; // relay succeeded
            }
            log.warn("Relay publish failed, probing owner liveness before takeover: sessionId={}, owner={}",
                    sessionId, ownerInstance);
        }

        // Case 3 & 4: No owner, or owner is dead → attempt takeover / auto-claim
        if (shouldTakeover(ownerInstance, sessionId)) {
            if (ownerInstance == null) {
                // No route record → auto-claim via ensureRouteOwnership
                boolean claimed = sessionRouteService.ensureRouteOwnership(sessionId, ak, userId);
                if (claimed) {
                    takeoverCount.incrementAndGet();
                    logRouteInfo(type, "[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, strategy=auto_claim",
                            type, sessionId);
                    dispatchLocally(type, sessionId, ak, userId, node);
                    logRouteInfo(type, "[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
                    return;
                }
            } else {
                // Owner is dead → optimistic-lock takeover
                boolean taken = sessionRouteService.tryTakeover(sessionId, ownerInstance, instanceId);
                if (taken) {
                    takeoverCount.incrementAndGet();
                    logRouteInfo(type, "[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, strategy=takeover_from_{}",
                            type, sessionId, ownerInstance);
                    dispatchLocally(type, sessionId, ak, userId, node);
                    logRouteInfo(type, "[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
                    return;
                }
            }
            // Takeover/claim failed → someone else won → forward to winner
            takeoverConflictCount.incrementAndGet();
            String winner = sessionRouteService.getOwnerInstance(sessionId);
            if (winner != null && !instanceId.equals(winner)) {
                String rawMessage = serializeRelayMessage(type, sessionId, ak, userId, node);
                redisMessageBroker.publishToSsRelayBestEffort(winner, rawMessage);
                logRouteInfo(type, "[EXIT] GatewayMessageRouter.route: type={}, sessionId={}, strategy=forward_to_winner_{}",
                        type, sessionId, winner);
                return;
            }
            // Winner is this instance (race condition) → process locally
            if (instanceId.equals(winner)) {
                routeLocalCount.incrementAndGet();
                logRouteInfo(type, "[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, strategy=won_race",
                        type, sessionId);
                dispatchLocally(type, sessionId, ak, userId, node);
                logRouteInfo(type, "[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
                return;
            }
        }

        // Fallback: no local action completed, drop message
        log.warn("[SKIP] GatewayMessageRouter.route: no route action completed, dropping message. type={}, sessionId={}, owner={}",
                type, sessionId, ownerInstance);
    }

    /**
     * Dispatches a message to the appropriate local handler based on type.
     */
    private void dispatchLocally(String type, String sessionId, String ak, String userId, JsonNode node) {
        switch (type) {
            case "tool_event" -> handleToolEvent(sessionId, ak, userId, node);
            case "tool_done" -> handleToolDone(sessionId, userId, node);
            case "tool_error" -> handleToolError(sessionId, userId, node);
            case "agent_online" -> handleAgentOnline(ak, userId, node);
            case "agent_offline" -> handleAgentOffline(ak, userId);
            case "session_created" -> handleSessionCreated(ak, userId, node);
            case "permission_request" -> handlePermissionRequest(sessionId, userId, node);
            case "im_push" -> handleImPush(sessionId, node);
            default -> log.warn("[SKIP] GatewayMessageRouter.route: reason=unknown_type, type={}", type);
        }
    }

    private void logRouteInfo(String type, String format, Object... args) {
        if ("tool_event".equals(type)) {
            log.debug(format, args);
            return;
        }
        log.info(format, args);
    }

    /**
     * Liveness check to determine if the owner instance should be taken over.
     *
     * <ol>
     *   <li>No owner → always takeover (auto-claim)</li>
     *   <li>Redis heartbeat missing → takeover</li>
     * </ol>
     */
    private boolean shouldTakeover(String ownerInstance, String sessionId) {
        if (ownerInstance == null) {
            return true; // no owner, auto-claim
        }

        // Tier 1: check Redis heartbeat
        if (!skillInstanceRegistry.isInstanceAlive(ownerInstance)) {
            ownerProbeDeadCount.incrementAndGet();
            log.info("shouldTakeover: heartbeat missing for owner={}, sessionId={}", ownerInstance, sessionId);
            return true;
        }

        return false; // owner is probably still alive
    }

    /**
     * Serializes the routing context into a JSON string for relay via Redis pub/sub.
     *
     * @param sessionId 已解析的 welinkSessionId，传入 envelope 避免接收端重复解析
     */
    private String serializeRelayMessage(String type, String sessionId, String ak, String userId, JsonNode node) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("type", type);
            envelope.put("ak", ak);
            envelope.put("userId", userId);
            String traceId = node.path("traceId").asText(null);
            if (traceId != null && !traceId.isBlank()) {
                MdcHelper.putTraceId(traceId);
            } else {
                traceId = MdcHelper.ensureTraceId();
            }
            envelope.put("traceId", traceId);
            if (sessionId != null) {
                envelope.put("sessionId", sessionId);
            }
            envelope.set("payload", node);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize relay message: type={}, error={}", type, e.getMessage());
            return "{}";
        }
    }

    /** Handles tool_event by translating, filtering, and routing stream messages. */
    private void handleToolEvent(String sessionId, String ak, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_event missing sessionId, agentId={}, raw keys={}",
                    node.path("agentId").asText(null), node.fieldNames());
            return;
        }

        SkillSession session = resolveToolEventSession(sessionId, node);
        if (session != null && session.getId() != null) {
            sessionId = session.getId().toString();
        }

        log.info("handleToolEvent: sessionId={}", sessionId);
        activateIdleSession(sessionId, userId, session);

        // 根据助手类型（scope）选择事件翻译策略
        String resolvedAk = ak != null ? ak : node.path("ak").asText(node.path("agentId").asText(null));
        AssistantInfo routerInfo = null;
        AssistantScopeStrategy strategy;
        if (session != null) {
            strategy = scopeDispatcher.getStrategy(
                    session.getBusinessSessionDomain(),
                    session.getBusinessSessionType(),
                    null);
            if (!DefaultAssistantScopeStrategy.SCOPE.equals(strategy.getScope())) {
                routerInfo = resolveAssistantInfoForEvent(resolvedAk, session);
                strategy = scopeDispatcher.getStrategy(
                        session.getBusinessSessionDomain(),
                        session.getBusinessSessionType(),
                        routerInfo);
            }
        } else {
            routerInfo = assistantInfoService.getAssistantInfo(resolvedAk);
            strategy = scopeDispatcher.getStrategy(routerInfo);
        }
        StreamMessage msg = strategy.translateEvent(node.get("event"), sessionId);
        if (msg == null) {
            log.debug("Event ignored by strategy translator for session {}, scope={}", sessionId,
                    strategy.getScope());
            return;
        }
        // 注入 subagent 字段（Plugin 层映射重写后附加的）
        JsonNode subagentNode = node.path("subagentSessionId");
        if (!subagentNode.isMissingNode() && !subagentNode.isNull()) {
            msg.setSubagentSessionId(subagentNode.asText());
            msg.setSubagentName(node.path("subagentName").asText(null));
        }
        String traceId = node.path("traceId").asText(null);
        if (isTurnCompleted(sessionId, traceId)
                && !StreamMessage.Types.QUESTION.equals(msg.getType())
                && !StreamMessage.Types.PERMISSION_ASK.equals(msg.getType())
                && !StreamMessage.Types.PERMISSION_REPLY.equals(msg.getType())
                && !"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            log.debug("Suppressing stale tool_event after tool_done: sessionId={}, type={}",
                    sessionId, msg.getType());
            return;
        }

        if (isContextOverflowEvent(node.get("event")) && session != null && !isMiniappSession(session)) {
            handleContextOverflow(sessionId, userId, session);
            return;
        }

        // user 角色事件是 OpenCode 回传的 echo，不需要持久化：
        // miniapp 用户消息已由 SkillMessageController.sendMessage 保存
        // IM 用户消息已由 ImInboundController 保存
        if ("user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            return;
        }
        handleAssistantToolEvent(sessionId, userId, msg, session, traceId);
    }

    private SkillSession resolveToolEventSession(String sessionId, JsonNode node) {
        SkillSession session = resolveSession(sessionId);
        if (session != null) {
            return session;
        }

        String nodeSessionId = node.path("welinkSessionId").asText(null);
        if (nodeSessionId != null && !nodeSessionId.isBlank()
                && !java.util.Objects.equals(nodeSessionId, sessionId)) {
            session = resolveSession(nodeSessionId);
            if (session != null) {
                log.info("Recovered tool_event session by welinkSessionId: inputSessionId={}, recoveredSessionId={}",
                        sessionId, session.getId());
                return session;
            }
        }

        String toolSessionId = node.path("toolSessionId").asText(null);
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return null;
        }

        try {
            session = sessionService.findByToolSessionId(toolSessionId);
            if (session == null) {
                log.warn("[SKIP] resolveToolEventSession: reason=session_not_found, sessionId={}, toolSessionId={}",
                        sessionId, toolSessionId);
                return null;
            }
            if (session.getId() != null) {
                redisMessageBroker.setToolSessionMapping(toolSessionId, session.getId().toString());
            }
            log.info("Recovered tool_event session by toolSessionId: inputSessionId={}, recoveredSessionId={}, toolSessionId={}",
                    sessionId, session.getId(), toolSessionId);
            return session;
        } catch (Exception e) {
            log.warn("[SKIP] resolveToolEventSession: reason=tool_session_lookup_failed, sessionId={}, toolSessionId={}, error={}",
                    sessionId, toolSessionId, e.getMessage());
            return null;
        }
    }

    private AssistantInfo resolveAssistantInfoForEvent(String resolvedAk, SkillSession session) {
        if (session != null && session.getAssistantAccount() != null
                && !session.getAssistantAccount().isBlank()) {
            return assistantInfoService.getAssistantInfo(session.getAk(), session.getAssistantAccount());
        }
        String sessionAk = session != null ? session.getAk() : null;
        String effectiveAk = sessionAk != null && !sessionAk.isBlank() ? sessionAk : resolvedAk;
        return assistantInfoService.getAssistantInfo(effectiveAk);
    }

    /** 尝试激活 IDLE 会话，激活后广播 busy 状态。 */
    private void activateIdleSession(String sessionId, String userId, SkillSession session) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId == null) {
            return;
        }
        if (sessionService.activateSession(numericId)) {
            StreamMessage busy = StreamMessage.sessionStatus("busy");
            emitter.emitToClient(sessionId, userId, busy);
            if (isMiniappSession(session)) {
                bufferService.accumulate(sessionId, busy);
            }
        }
    }

    /** 使用翻译器将原始事件 JSON 转为 StreamMessage。 */
    private StreamMessage translateEvent(JsonNode node, String sessionId) {
        JsonNode event = node.get("event");
        StreamMessage msg = translator.translate(event);
        if (msg == null) {
            log.debug("Event ignored by translator for session {}", sessionId);
        }
        return msg;
    }

    /** 处理助手角色的 tool_event（含权限回复合成、消息路由）。 */
    private void handleAssistantToolEvent(String sessionId, String userId, StreamMessage msg, SkillSession session,
            String traceId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        StreamMessage permissionReply = numericId != null
                ? persistenceService.synthesizePermissionReplyFromToolOutcome(numericId, msg)
                : null;

        if (permissionReply != null) {
            routeAssistantMessage(sessionId, userId, permissionReply, session, numericId, traceId);
            String response = permissionReply.getPermission() != null
                    ? permissionReply.getPermission().getResponse()
                    : null;
            if (numericId != null && isTurnCompleted(sessionId, traceId)) {
                try {
                    persistenceService.finalizeActiveAssistantTurn(numericId);
                } catch (Exception e) {
                    log.warn("Cannot finalize synthesized permission reply for session {}", sessionId);
                }
            }
            if ("reject".equals(response)) {
                return;
            }
        }

        routeAssistantMessage(sessionId, userId, msg, session, numericId, traceId);
    }

    /** 路由助手消息：通过 OutboundDeliveryDispatcher 统一投递。 */
    private void routeAssistantMessage(String sessionId, String userId, StreamMessage msg,
            SkillSession session, Long numericId, String traceId) {
        // 同步会话标题（标题变化时更新）
        if (StreamMessage.Types.SESSION_TITLE.equals(msg.getType()) && numericId != null) {
            sessionService.updateTitle(numericId, msg.getTitle());
        }

        // 业务助手 IM 场景：过滤云端扩展事件
        if (session != null && !session.isMiniappDomain()) {
            String sessionAk = session.getAk();
            if (sessionAk != null) {
                String sessionScope = assistantInfoService.getCachedScope(sessionAk);
                if ("business".equals(sessionScope) && BUSINESS_IM_FILTERED_TYPES.contains(msg.getType())) {
                    log.debug("Filtering business IM extended event: sessionId={}, type={}", session.getId(), msg.getType());
                    return;
                }
            }
            syncPendingImInteraction(session, msg);
        }

        // IM 非流式渠道：累积 text.delta，兼容上游不发 text.done 的场景（业务/个人助手通用）
        accumulateCloudImText(sessionId, msg, session, traceId);

        // Track TEXT_DELTA token metrics
        if (StreamMessage.Types.TEXT_DELTA.equals(msg.getType())) {
            String messageId = ProtocolUtils.firstNonBlank(msg.getSourceMessageId(), msg.getMessageId());
            if (messageId != null) {
                // Get brainTag from assistant info if available
                String brainTag = null;
                String assistantAccount = null;
                if (session != null) {
                    assistantAccount = session.getAssistantAccount();
                    if (!isDefaultAssistant(session)) {
                        AssistantInfo info = resolveAssistantInfoForEvent(session.getAk(), session);
                        if (info != null) {
                            brainTag = info.getBusinessTag();
                        }
                    }
                }
                int contentLength = msg.getContent() != null ? msg.getContent().length() : 0;
                // Delegate first-token tracking + token counting to MessageTurnLifecycle
                messageTurnLifecycle.onToken(new MessageTurnContext(messageId, brainTag, sessionId, assistantAccount, userId, null, true), contentLength);
            }
        }

        // 统一投递（enrich + deliver）
        prepareStableMessageContext(sessionId, msg, session, numericId);
        emitter.emitToSession(session, sessionId, userId, msg);

        // 缓冲（miniapp 用）
        if (session == null || session.isMiniappDomain()) {
            bufferService.accumulate(sessionId, msg);
        }

        // 持久化
        if (numericId != null && (session == null || session.isMiniappDomain() || session.isImDirectSession())) {
            try {
                persistenceService.persistIfFinal(numericId, msg);
            } catch (Exception e) {
                log.error("Failed to persist StreamMessage for session {}: {}", sessionId, e.getMessage(), e);
            }
        }
    }

    private void accumulateCloudImText(String sessionId, StreamMessage msg, SkillSession session, String traceId) {
        if (session == null || !session.isImDomain() || msg == null || msg.getType() == null) {
            return;
        }

        if (StreamMessage.Types.TEXT_DELTA.equals(msg.getType())) {
            if (msg.getContent() == null) {
                return;
            }
            String messageId = ProtocolUtils.firstNonBlank(msg.getSourceMessageId(), msg.getMessageId());
            String bufferKey = cloudImBufferKey(messageId, traceId);
            CloudImTextAccumulator accumulator = cloudImTextBuffer
                    .computeIfAbsent(sessionId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(bufferKey, k -> new CloudImTextAccumulator(
                            messageId, cloudImTextBufferOrder.incrementAndGet()));
            accumulator.append(msg.getContent(), traceId, msg.getPartId());
            return;
        }

        if (StreamMessage.Types.TEXT_DONE.equals(msg.getType())) {
            removeCloudImTextAccumulator(sessionId,
                    ProtocolUtils.firstNonBlank(msg.getSourceMessageId(), msg.getMessageId()), traceId);
        }
    }

    private String cloudImBufferKey(String messageId, String traceId) {
        String normalizedMessageId = ProtocolUtils.firstNonBlank(messageId, null);
        if (normalizedMessageId != null) {
            return normalizedMessageId;
        }
        String normalizedTraceId = ProtocolUtils.firstNonBlank(traceId, null);
        if (normalizedTraceId != null) {
            return "trace:" + normalizedTraceId;
        }
        return MISSING_MESSAGE_ID_BUFFER_KEY;
    }

    private CloudImTextAccumulator removeCloudImTextAccumulator(String sessionId, String messageId, String traceId) {
        java.util.concurrent.ConcurrentHashMap<String, CloudImTextAccumulator> buffers = cloudImTextBuffer.get(sessionId);
        if (buffers == null || buffers.isEmpty()) {
            return null;
        }

        CloudImTextAccumulator removed = null;
        String normalizedMessageId = ProtocolUtils.firstNonBlank(messageId, null);
        if (normalizedMessageId != null) {
            removed = buffers.remove(normalizedMessageId);
        }
        if (removed == null) {
            removed = removeCloudImTextAccumulatorByTrace(buffers, traceId);
        }
        if (removed == null && normalizedMessageId == null) {
            removed = buffers.remove(MISSING_MESSAGE_ID_BUFFER_KEY);
        }
        cleanupCloudImTextBuffer(sessionId, buffers);
        return removed;
    }

    private CloudImTextAccumulator removeCloudImTextAccumulatorByTrace(
            java.util.concurrent.ConcurrentHashMap<String, CloudImTextAccumulator> buffers, String traceId) {
        String normalizedTraceId = ProtocolUtils.firstNonBlank(traceId, null);
        if (normalizedTraceId == null) {
            return null;
        }
        for (var entry : buffers.entrySet()) {
            CloudImTextAccumulator accumulator = entry.getValue();
            if (normalizedTraceId.equals(accumulator.traceId)
                    && buffers.remove(entry.getKey(), accumulator)) {
                return accumulator;
            }
        }
        return null;
    }

    private void flushAccumulatedCloudImTextDone(String sessionId, String userId, SkillSession session, String traceId) {
        if (session == null || !session.isImDomain()) {
            return;
        }
        java.util.concurrent.ConcurrentHashMap<String, CloudImTextAccumulator> buffers = cloudImTextBuffer.get(sessionId);
        if (buffers == null || buffers.isEmpty()) {
            return;
        }

        CloudImTextAccumulator accumulated;
        String normalizedTraceId = ProtocolUtils.firstNonBlank(traceId, null);
        if (normalizedTraceId != null) {
            accumulated = removeCloudImTextAccumulatorByTrace(buffers, normalizedTraceId);
            if (accumulated == null) {
                log.debug("Skip flushing cloud IM text buffer for unmatched tool_done trace: sessionId={}, traceId={}, remainingBuffers={}",
                        sessionId, normalizedTraceId, buffers.size());
                cleanupCloudImTextBuffer(sessionId, buffers);
                return;
            }
        } else if (buffers.size() == 1) {
            accumulated = removeSingleCloudImTextAccumulator(buffers);
        } else {
            log.debug("Skip flushing cloud IM text buffer without trace: sessionId={}, remainingBuffers={}",
                    sessionId, buffers.size());
            return;
        }
        cleanupCloudImTextBuffer(sessionId, buffers);

        if (accumulated == null || accumulated.isEmpty()) {
            return;
        }
        StreamMessage textDoneMsg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId(accumulated.messageId)
                .sourceMessageId(accumulated.messageId)
                .partId(accumulated.partId)
                .content(accumulated.content.toString())
                .role("assistant")
                .build();
        emitter.emitToSession(session, sessionId, userId, textDoneMsg);
        log.info("Flushed accumulated text.delta as text.done to IM: sessionId={}, messageId={}, length={}",
                sessionId, accumulated.messageId, accumulated.content.length());
    }

    private CloudImTextAccumulator removeSingleCloudImTextAccumulator(
            java.util.concurrent.ConcurrentHashMap<String, CloudImTextAccumulator> buffers) {
        for (var entry : buffers.entrySet()) {
            CloudImTextAccumulator accumulator = entry.getValue();
            if (buffers.remove(entry.getKey(), accumulator)) {
                return accumulator;
            }
        }
        return null;
    }

    private void cleanupCloudImTextBuffer(String sessionId,
            java.util.concurrent.ConcurrentHashMap<String, CloudImTextAccumulator> buffers) {
        if (buffers.isEmpty()) {
            cloudImTextBuffer.remove(sessionId, buffers);
        }
    }

    /** Prepare stable message identity before live emit and Redis buffering. */
    private void prepareStableMessageContext(String sessionId, StreamMessage msg,
            SkillSession session, Long numericId) {
        if (numericId == null || !(session == null || session.isMiniappDomain() || session.isImDirectSession())) {
            return;
        }
        try {
            persistenceService.prepareMessageContext(numericId, msg);
        } catch (Exception e) {
            log.warn("Failed to prepare StreamMessage context before emit: sessionId={}, type={}, error={}",
                    sessionId, msg != null ? msg.getType() : null, e.getMessage());
        }
    }

    /**
     * 通知 MessageTurnLifecycle 轮次结束。从 session 解析 brainTag/assistantAccount，
     * messageId 缺失或 brainTag 为 null 时记录 warn 日志。
     *
     * @param session  会话（可能为 null）
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @param success  是否成功（tool_done=true, tool_error=false）
     */
    private void notifyTurnEnd(SkillSession session, String sessionId, String messageId, boolean success) {
        if (messageId == null || messageId.isBlank()) {
            log.warn("[SKIP] onTurnEnd: messageId is null or blank, sessionId={}, skipping lifecycle cleanup", sessionId);
            return;
        }
        String brainTag = null;
        String assistantAccount = null;
        if (session != null) {
            assistantAccount = session.getAssistantAccount();
            if (!isDefaultAssistant(session)) {
                AssistantInfo info = resolveAssistantInfoForEvent(session.getAk(), session);
                if (info != null) {
                    brainTag = info.getBusinessTag();
                } else {
                    log.warn("[SKIP] onTurnEnd brainTag: assistant info not found, ak={}, sessionId={}, messageId={}, using UNKNOWN",
                        session.getAk(), sessionId, messageId);
                }
            } else {
                log.warn("[SKIP] onTurnEnd brainTag: default assistant, sessionId={}, messageId={}, using UNKNOWN",
                    sessionId, messageId);
            }
        } else {
            log.warn("[SKIP] onTurnEnd brainTag: session is null, sessionId={}, messageId={}, using UNKNOWN",
                sessionId, messageId);
        }
        String senderUserAccount = session != null ? session.getUserId() : null;
        messageTurnLifecycle.onTurnEnd(new MessageTurnContext(messageId, brainTag, sessionId, assistantAccount, senderUserAccount, null, success));
    }

    /** 处理 tool_done：标记会话完成、统一投递 idle 状态、持久化最终消息。 */
    private void handleToolDone(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_done missing sessionId, agentId={}", node.path("agentId").asText(null));
            return;
        }

        log.info("handleToolDone: sessionId={}", sessionId);
        String traceId = node.path("traceId").asText(null);
        String messageId = node.path("messageId").asText(null);
        completedSessions.put(completionKey(sessionId, traceId), Instant.now());

        // 刷出累积的 text.delta 内容（上游未发 text.done 时，用累积内容合成 text.done）
        SkillSession session = resolveSession(sessionId);
        flushAccumulatedCloudImTextDone(sessionId, userId, session, traceId);

        // Call onTurnEnd when stream completes
        notifyTurnEnd(session, sessionId, messageId, true);

        StreamMessage msg = StreamMessage.sessionStatus("idle");
        Long numericId = ProtocolUtils.parseSessionId(sessionId);

        // 先更新未读缓存（同步），再推送 idle 到前端，消除 readSeq 上报时 maxSeq 未写入的竞态窗口
        eventPublisher.publishEvent(new ToolDoneEvent(numericId, userId, session));

        emitter.emitToSession(session, sessionId, userId, msg);

        if (session == null || session.isMiniappDomain()) {
            bufferService.accumulate(sessionId, msg);
        }

        if (numericId != null && (isMiniappSession(session) || (session != null && session.isImDirectSession()))) {
            try {
                persistenceService.persistIfFinal(numericId, msg);
            } catch (Exception e) {
                log.error("Failed to persist tool_done for session {}: {}", sessionId, e.getMessage(), e);
            }
        }

        // 清理该 session 的预缓存消息，防止累积
        rebuildService.clearPendingMessages(sessionId);
    }

    private boolean isTurnCompleted(String sessionId, String traceId) {
        String key = completionKey(sessionId, traceId);
        if (completedSessions.getIfPresent(key) != null) {
            return true;
        }
        String sessionKey = completionKey(sessionId, null);
        return !sessionKey.equals(key) && completedSessions.getIfPresent(sessionKey) != null;
    }

    private String completionKey(String sessionId, String traceId) {
        String normalizedSessionId = ProtocolUtils.firstNonBlank(sessionId, null);
        String normalizedTraceId = ProtocolUtils.firstNonBlank(traceId, null);
        if (normalizedSessionId == null) {
            return "";
        }
        if (normalizedTraceId == null) {
            return normalizedSessionId;
        }
        return normalizedSessionId + ":trace:" + normalizedTraceId;
    }

    /** 处理 tool_error：会话重建/统一投递错误消息/持久化。 */
    private void handleToolError(String sessionId, String userId, JsonNode node) {
        String error = node.path("error").asText("Unknown error");
        String reason = node.path("reason").asText("");
        log.info("handleToolError: sessionId={}, error={}, reason={}", sessionId, error, reason);

        if (sessionId == null) {
            log.error("Tool error without session: {}", error);
            return;
        }

        // callback_config_missing 是配置缺失（GW 侧 ak 未订阅 callback scope），
        // 不应触发会话重建；直接走错误投递路径。reason 字面量与 GW
        // CloudAgentService.REASON_CALLBACK_CONFIG_MISSING 对齐。
        boolean isCallbackConfigMissing = "callback_config_missing".equals(reason);
        if (!isCallbackConfigMissing
                && ("session_not_found".equals(reason) || isSessionInvalidError(error))) {
            clearPendingImInteractionState(sessionId);
            rebuildService.handleSessionNotFound(sessionId, userId, rebuildCallback());
            return;
        }

        SkillSession session = resolveSession(sessionId);
        Long numericId = ProtocolUtils.parseSessionId(sessionId);

        // === Call onTurnEnd on error (same as handleToolDone) ===
        String messageId = node.path("messageId").asText(null);
        notifyTurnEnd(session, sessionId, messageId, false);
        // === END ===

        if (numericId != null) {
            try {
                messageService.saveSystemMessage(numericId, "Error: " + error);
            } catch (Exception e) {
                log.error("Failed to persist tool_error for session {}: {}", sessionId, e.getMessage());
            }
        }

        // 先更新未读缓存（同步），再推送 error 到前端
        if (numericId != null) {
            eventPublisher.publishEvent(
                    new ToolErrorEvent(numericId, userId, session));
        }

        StreamMessage errorMsg = StreamMessage.builder()
                .type(StreamMessage.Types.ERROR)
                .error(error)
                .build();
        emitter.emitToSession(session, sessionId, userId, errorMsg);
        if (isMiniappSession(session)) {
            bufferService.clearSession(sessionId);
        }

        if (numericId != null) {
            try {
                persistenceService.finalizeActiveAssistantTurn(numericId);
            } catch (Exception e) {
                log.warn("Cannot finalize active message after tool_error: sessionId={}", sessionId);
            }
        }
        log.error("Tool error for session {}: {}", sessionId, error);
    }

    /** 处理 agent_online：向关联会话广播上线状态。Ownership 由消息驱动懒接管处理。 */
    private void handleAgentOnline(String ak, String userId, JsonNode node) {
        String toolType = node.path("toolType").asText("UNKNOWN");
        String toolVersion = node.path("toolVersion").asText("UNKNOWN");
        log.info("[ENTRY] handleAgentOnline: ak={}, toolType={}, toolVersion={}", ak, toolType, toolVersion);

        if (ak == null) {
            log.warn("[SKIP] handleAgentOnline: reason=ak_null");
            return;
        }

        // Evict availability cache before broadcasting (avoid 30s stale window)
        if (availabilityService != null) {
            availabilityService.evict(ak);
        }

        // Ownership takeover is now message-driven (lazy) — no eager takeoverActiveRoutes needed.

        StreamMessage msg = StreamMessage.agentOnline();
        List<SkillSession> activeSessions = sessionService.findActiveByAk(ak);
        log.info("handleAgentOnline: ak={}, activeSessions={}", ak, activeSessions.size());
        activeSessions.forEach(session -> emitter.emitToClient(
                session.getId().toString(),
                userId != null && !userId.isBlank() ? userId : session.getUserId(),
                msg));
        log.info("[EXIT] handleAgentOnline: ak={}", ak);
    }

    /** 处理 agent_offline：向 AK 关联的活跃会话广播离线状态。 */
    private void handleAgentOffline(String ak, String userId) {
        log.warn("Agent offline: ak={}", ak);

        if (ak == null) {
            return;
        }

        // Evict availability cache before broadcasting (avoid 30s stale window)
        if (availabilityService != null) {
            availabilityService.evict(ak);
        }

        StreamMessage msg = StreamMessage.agentOffline();
        sessionService.findActiveByAk(ak).forEach(session -> emitter.emitToClient(
                session.getId().toString(),
                userId != null && !userId.isBlank() ? userId : session.getUserId(),
                msg));
    }

    /** 处理 session_created：绑定 toolSessionId 并重试待发消息。 */
    private void handleSessionCreated(String ak, String userId, JsonNode node) {
        String toolSessionId = node.path("toolSessionId").asText(null);
        String sessionId = node.path("welinkSessionId").asText(null);
        log.info("handleSessionCreated: sessionId={}, toolSessionId={}, ak={}", sessionId, toolSessionId, ak);

        if (sessionId == null || toolSessionId == null) {
            log.warn("session_created missing fields: sessionId={}, toolSessionId={}, ak={}, raw={}",
                    sessionId, toolSessionId, ak, node);
            return;
        }

        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId == null) {
            log.warn("session_created has invalid sessionId={}, cannot update", sessionId);
            return;
        }

        try {
            sessionService.updateToolSessionId(numericId, toolSessionId);
        } catch (Exception e) {
            log.error("[ERROR] handleSessionCreated: toolSessionId 绑定失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
            rebuildService.clearPendingMessages(sessionId);
            return;
        }

        retryPendingMessages(sessionId, ak, userId, toolSessionId);
    }

    /**
     * 重建完成后按 FIFO 顺序逐条重发所有待处理的用户消息。
     *
     * <p>PR2: 从结构化 {@link PendingChatRequest} 重建完整 chat invoke payload, 含 6 字段
     * （text / toolSessionId / assistantAccount / sendUserAccount / imGroupId / messageId /
     * businessExtParam）。{@code toolSessionId} 取方法入参（pending 入队时还没绑定）, 其他
     * 字段从 PendingChatRequest 取。
     *
     * <p>{@code businessExtParam} 三态处理（与 PR1 锁定的边界对齐）：
     * <ul>
     *   <li>Java {@code null}（字段缺失反序列化） → 不写入 payload</li>
     *   <li>{@code NullNode}（JSON {@code null} 反序列化） → 不写入 payload</li>
     *   <li>JsonNode object → 写入 payload</li>
     * </ul>
     * 注意严格不要 {@code chatPayload.set("businessExtParam", null)}, 那会被序列化成
     * {@code "businessExtParam":null} 误导下游。
     */
    private void retryPendingMessages(String sessionId, String ak, String userId, String toolSessionId) {
        java.util.List<PendingChatRequest> pendingMessages = rebuildService.consumePendingRequests(sessionId);
        if (pendingMessages.isEmpty()) {
            log.info("[SKIP] retryPendingMessages: reason=no_pending_messages, sessionId={}", sessionId);
            return;
        }

        log.info("[ENTRY] retryPendingMessages: sessionId={}, ak={}, retry_count={}, payload_format=v2",
                sessionId, ak, pendingMessages.size());

        DownstreamSender sender = downstreamSender;
        if (sender == null) {
            log.warn("[ERROR] retryPendingMessages: reason=no_downstream_sender, sessionId={}", sessionId);
            return;
        }

        // 群聊 + channel 命中白名单时一次性算出 suppressReply，避免在循环里每条 invoke 重复查询。
        Boolean suppressReply = computeSuppressReplyForRetry(sessionId, ak);
        if (Boolean.TRUE.equals(suppressReply)) {
            log.info("[ENTRY] retryPendingMessages.suppressReply: reason=channel_in_group_blocklist, sessionId={}, ak={}",
                    sessionId, ak);
        }

        int sent = 0;
        for (PendingChatRequest req : pendingMessages) {
            if (req == null || req.text() == null || req.text().isBlank()) {
                continue;
            }

            // sanity check（采纳 Codex M5）：缺关键 account 字段不静默 drop, 仍发送让云端 fast-fail 暴露问题
            boolean hasAssistantAccount = req.assistantAccount() != null && !req.assistantAccount().isBlank();
            boolean hasSender = req.sendUserAccount() != null && !req.sendUserAccount().isBlank();
            if (!hasAssistantAccount || !hasSender) {
                log.error("[ERROR] retryPendingMessages: reason=critical_field_missing, sessionId={}, ak={}, hasAssistantAccount={}, hasSender={}, fields_degraded=true",
                        sessionId, ak, hasAssistantAccount, hasSender);
            }

            ObjectNode chatPayload = objectMapper.createObjectNode();
            chatPayload.put("text", req.text());
            chatPayload.put("toolSessionId", toolSessionId);
            chatPayload.put("assistantAccount", req.assistantAccount());
            chatPayload.put("sendUserAccount", req.sendUserAccount());
            // imGroupId: 下发给 plugin 的空值统一写为空字符串；内部 pending 仍保留 null 语义。
            chatPayload.put("imGroupId", req.imGroupId() != null ? req.imGroupId() : "");
            chatPayload.put("messageId", req.messageId());

            // PR2 platformExtParam：直接把 businessExtParam + platformExtParam 一并放进
            // extParameters 信封, 与 P2a wire 形态对齐。GatewayRelayService.buildInvokeMessage 内的
            // 幂等保护会检测到 extParameters 已存在 -> 不再二次注入。
            JsonNode ext = req.businessExtParam();
            ObjectNode extParameters = objectMapper.createObjectNode();
            if (ext != null && !ext.isNull()) {
                extParameters.set("businessExtParam", ext);
            } else {
                extParameters.set("businessExtParam", objectMapper.createObjectNode());
            }
            // PR2: businessSessionId 来源复用 req.imGroupId()（PRD R9 命名冗余约定）；
            // 单聊场景 imGroupId == null → platformExtParam.businessSessionId = JSON null。
            // C2 (v3): 升 5 参 builder 传 req.allowedSlashCommands() —— first 入 pending 时 frozen，
            //   retry 复用 frozen 值（sysconfig 期间被更新不影响 retry 下发）。
            extParameters.set("platformExtParam",
                    PlatformExtParamBuilder.build(objectMapper,
                            req.businessSessionDomain(),
                            req.businessSessionType(),
                            req.imGroupId(),
                            req.bizRobotTag(),
                            req.allowedSlashCommands()));
            chatPayload.set("extParameters", extParameters);

            String payloadStr;
            try {
                payloadStr = objectMapper.writeValueAsString(chatPayload);
            } catch (JsonProcessingException e) {
                payloadStr = "{}";
            }
            // A10 (v3): 升 10 参 canonical 传 req.allowedSlashCommands()，与 platformExtParam 5 参 builder
            //   语义对齐（personal scope CHAT retry 路径 frozen 复用 first 时刻的 list）。
            sender.sendInvokeToGateway(new InvokeCommand(
                    ak, userId, sessionId, GatewayActions.CHAT, payloadStr, suppressReply,
                    req.businessSessionDomain(),
                    req.businessSessionType(),
                    req.imGroupId(),
                    req.allowedSlashCommands()));
            sent++;
        }

        log.info("[EXIT] retryPendingMessages: sessionId={}, ak={}, sent={}/{}, suppressReply={}, payload_format=v2",
                sessionId, ak, sent, pendingMessages.size(), suppressReply);
        emitter.emitToClient(sessionId, userId, StreamMessage.sessionStatus("busy"));
    }

    /**
     * 重建后回放 chat 时计算 suppressReply：
     * 仅当会话是 IM 群聊、且当前 ak 对应的 plugin channel 命中"禁群聊"白名单时返回 {@link Boolean#TRUE}；
     * 其它任何情况（单聊、Miniapp、查询失败、未命中白名单）一律返回 {@code null}，
     * 与 {@link InvokeCommand} 中"null = 不写入 INVOKE 报文"语义一致。
     */
    private Boolean computeSuppressReplyForRetry(String sessionId, String ak) {
        SkillSession session = resolveSession(sessionId);
        if (session == null || !session.isImGroupSession()) {
            return null;
        }
        return channelLookupService.getToolType(ak)
                .map(channelSuppressReplyWhitelistService::shouldSuppress)
                .filter(Boolean::booleanValue)
                .orElse(null);
    }

    /** 处理 permission_request：统一投递权限请求消息。 */
    private void handlePermissionRequest(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("permission_request missing sessionId");
            return;
        }
        log.info("handlePermissionRequest: sessionId={}", sessionId);

        StreamMessage msg = translator.translatePermissionFromGateway(node);

        // 注入 subagent 字段
        JsonNode subagentNode = node.path("subagentSessionId");
        if (!subagentNode.isMissingNode() && !subagentNode.isNull()) {
            msg.setSubagentSessionId(subagentNode.asText());
            msg.setSubagentName(node.path("subagentName").asText(null));
        }

        SkillSession session = resolveSession(sessionId);
        if (session != null && !session.isMiniappDomain() && session.getId() != null
                && msg.getPermission() != null) {
            interactionStateService.markPermission(
                    session.getId(),
                    msg.getPermission().getPermissionId());
        }

        emitter.emitToSession(session, sessionId, userId, msg);
    }

    /**
     * 处理云端 IM 推送消息（im_push）。
     *
     * <p>从 payload 中解析 assistantAccount、userAccount、imGroupId、content，
     * 通过 topicId（即 toolSessionId）查找 session，验证 assistantAccount 匹配后，
     * 根据 imGroupId 是否有值判断群聊或单聊，调用 IM 出站服务发送消息。</p>
     *
     * @param sessionId 由 topicId 反查得到的 welinkSessionId
     * @param node      原始 GatewayMessage JSON 节点（含 toolSessionId 和 payload）
     */
    private void handleImPush(String sessionId, JsonNode node) {
        log.info("[ENTRY] handleImPush: sessionId={}", sessionId);

        // 幂等保护：GW 广播时多个 pod 可能同时收到同一条 im_push，用 traceId 去重
        String traceId = node.path("traceId").asText(null);
        if (traceId != null) {
            String dedupKey = "ss:im-push-dedup:" + traceId;
            Boolean acquired = redisMessageBroker.tryAcquire(dedupKey, java.time.Duration.ofSeconds(30));
            if (!Boolean.TRUE.equals(acquired)) {
                log.info("[SKIP] handleImPush: deduplicated by traceId={}", traceId);
                return;
            }
        }

        // 从顶层节点获取 topicId（即 toolSessionId）
        String topicId = node.path("toolSessionId").asText(null);

        // 从 payload 中解析请求字段
        JsonNode payload = node.path("payload");
        String assistantAccount = payload.path("assistantAccount").asText(null);
        String userAccount = payload.path("userAccount").asText(null);
        String imGroupId = payload.path("imGroupId").asText(null);
        String content = payload.path("content").asText(null);

        if (content == null || content.isBlank()) {
            log.warn("[SKIP] handleImPush: reason=blank_content, topicId={}", topicId);
            return;
        }

        if (assistantAccount == null || assistantAccount.isBlank()) {
            log.warn("[SKIP] handleImPush: reason=blank_assistantAccount, topicId={}", topicId);
            return;
        }

        // 通过 sessionId 查 session，验证 assistantAccount 是否匹配
        if (sessionId == null) {
            log.warn("[SKIP] handleImPush: reason=session_not_found, topicId={}", topicId);
            return;
        }
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            SkillSession session = sessionService.findByIdSafe(numericId);
            if (session != null && session.getAssistantAccount() != null
                    && !session.getAssistantAccount().equals(assistantAccount)) {
                log.warn("[SKIP] handleImPush: reason=assistant_account_mismatch, sessionId={}, expected={}, actual={}",
                        sessionId, session.getAssistantAccount(), assistantAccount);
                return;
            }
        }

        // 根据 imGroupId 决定单聊 / 群聊
        boolean isGroup = imGroupId != null && !imGroupId.isBlank();
        String sessionType = isGroup ? SkillSession.SESSION_TYPE_GROUP : SkillSession.SESSION_TYPE_DIRECT;
        String imSessionId = isGroup ? imGroupId : userAccount;

        if (imSessionId == null || imSessionId.isBlank()) {
            log.warn("[SKIP] handleImPush: reason=blank_im_session_id, topicId={}, isGroup={}", topicId, isGroup);
            return;
        }

        boolean sent = imOutboundService.sendTextToIm(sessionType, imSessionId, content, assistantAccount);
        log.info("[EXIT] handleImPush: topicId={}, sessionType={}, imSessionId={}, sent={}",
                topicId, sessionType, imSessionId, sent);
    }

    /**
     * 广播 StreamMessage 到前端。
     * @deprecated 保留以维持外部测试兼容；内部请直接使用 {@link StreamMessageEmitter#emitToClient}。
     */
    @Deprecated
    public void broadcastStreamMessage(String sessionId, String userIdHint, StreamMessage msg) {
        emitter.emitToClient(sessionId, userIdHint, msg);
    }

    /**
     * 发布协议消息（广播 + 缓冲）。
     * @deprecated 保留以维持外部测试兼容；内部请直接使用 {@link StreamMessageEmitter#emitToClientWithBuffer}。
     */
    @Deprecated
    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        emitter.emitToClientWithBuffer(sessionId, msg);
    }

    /**
     * 从消息 JSON 中解析 sessionId（先尝试 welinkSessionId，再通过 toolSessionId 反查）。
     *
     * <p>Task 2.10：当通过 toolSessionId 反查时，发送 route_confirm（找到）或 route_reject（未找到）。
     * welinkSessionId 直连路径不发送 confirm，因为 GW 已通过 welinkSessionId 精确路由，无需确认。</p>
     *
     * <p>route_confirm 走 lease-based 去重（{@link #maybeSendRouteConfirm}），仅在
     * cache miss / remap / lease 到期时实际发送，减少高并发下的冗余 control-plane 流量。
     * route_reject 仅在 DB 明确返回 null 的负向命中分支发送，且不去重。</p>
     */
    private String resolveSessionId(String messageType, JsonNode node) {
        String sessionId = node.path("welinkSessionId").asText(null);
        if (sessionId != null) {
            return sessionId;
        }

        String toolSessionId = node.path("toolSessionId").asText(null);
        if (toolSessionId != null) {
            try {
                // 优先查 Redis 缓存
                String cachedSessionId = redisMessageBroker.getToolSessionMapping(toolSessionId);
                if (cachedSessionId != null) {
                    maybeSendRouteConfirm(toolSessionId, cachedSessionId, routeResponseSender);
                    return cachedSessionId;
                }

                // 缓存未命中，查 DB
                SkillSession session = sessionService.findByToolSessionId(toolSessionId);
                if (session != null) {
                    String resolvedSessionId = session.getId().toString();
                    // 写入 Redis 缓存
                    redisMessageBroker.setToolSessionMapping(toolSessionId, resolvedSessionId);
                    maybeSendRouteConfirm(toolSessionId, resolvedSessionId, routeResponseSender);
                    return resolvedSessionId;
                }
                // 未找到 → 回复 route_reject（不去重，DB 明确返回 null 才发）
                log.warn("Upstream message session not found: type={}, toolSessionId={}, reason=session_not_found",
                        messageType, toolSessionId);
                RouteResponseSender sender = routeResponseSender;
                if (sender != null) {
                    sender.sendRouteReject(toolSessionId);
                }
            } catch (Exception e) {
                // 不再发 route_reject —— 异常 ≠ "definitively not found"，发 reject 会污染 GW 负向路由语义
                log.error("Failed to resolve upstream session affinity: type={}, toolSessionId={}, reason={}",
                        messageType, toolSessionId, e.getMessage());
            }
        } else {
            log.warn(
                    "Dropping upstream message: type={}, toolSessionId=<missing>, reason=no_session_identifier, keys={}",
                    messageType, fieldNames(node));
        }

        return null;
    }

    /**
     * lease-based route_confirm 去重决策。
     *
     * <p>发送条件（任一满足即发）：
     * <ol>
     *   <li>cache 中无记录（首次确认）</li>
     *   <li>cache 中 welinkSessionId 与当前解析结果不同（remap，需立即重发）</li>
     *   <li>cache 中 confirmedAt 距今 ≥ {@link #FORCE_RECONFIRM_INTERVAL}（5min lease 到期）</li>
     * </ol>
     * cache-after-success：仅在 sender 返回 true 后才写入 cache，失败下条上行会重试。</p>
     *
     * <p>{@code skill.relay.confirm-dedup.enabled=false} 时退化为每次都发（与改动前行为一致）。</p>
     */
    private void maybeSendRouteConfirm(String toolSessionId, String resolvedSessionId,
            RouteResponseSender sender) {
        if (sender == null) {
            return;
        }
        if (!confirmDedupEnabled) {
            boolean sent = sender.sendRouteConfirm(toolSessionId, resolvedSessionId);
            if (sent) {
                confirmSentCount.incrementAndGet();
            } else {
                confirmSendFailCount.incrementAndGet();
            }
            return;
        }

        ConfirmedState prev = confirmedToolSessions.getIfPresent(toolSessionId);
        Instant now = clock.instant();
        boolean isRemap = prev != null && !java.util.Objects.equals(prev.welinkSessionId(), resolvedSessionId);
        boolean isStale = prev != null
                && Duration.between(prev.confirmedAt(), now).compareTo(FORCE_RECONFIRM_INTERVAL) >= 0;
        boolean needSend = (prev == null) || isRemap || isStale;
        if (!needSend) {
            confirmDedupSkipCount.incrementAndGet();
            return;
        }

        boolean sent = sender.sendRouteConfirm(toolSessionId, resolvedSessionId);
        if (sent) {
            // cache-after-success：只有成功投递才记录，失败时下条上行会再次尝试
            confirmedToolSessions.put(toolSessionId, new ConfirmedState(resolvedSessionId, now));
            if (isStale) {
                confirmForceReconfirmCount.incrementAndGet();
            } else {
                confirmSentCount.incrementAndGet();
            }
        } else {
            confirmSendFailCount.incrementAndGet();
        }
    }

    /** 判断消息类型是否需要会话亲和性（需要 sessionId 才能处理）。 */
    private boolean requiresSessionAffinity(String messageType) {
        return switch (messageType) {
            case "tool_event", "tool_done", "tool_error", "permission_request", "im_push" -> true;
            default -> false;
        };
    }

    /** 解析 userId：优先使用提示值，否则从数据库反查。 */
    private String resolveUserId(String sessionId, String userIdHint) {
        if (userIdHint != null && !userIdHint.isBlank()) {
            return userIdHint;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId == null) {
                return null;
            }
            SkillSession session = sessionService.getSession(numericId);
            return session.getUserId();
        } catch (Exception e) {
            log.warn("Failed to resolve userId for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 判断错误信息是否表示会话失效（需要重建）。 */
    private boolean isSessionInvalidError(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase();
        // 收紧匹配：原 "not found" 太宽，会把 "Cloud route info not found"、
        // "config not found" 等配置缺失类错误误判为 session 失效，触发不必要的重建。
        return lower.contains("session not found")
                || lower.contains("session_not_found")
                || lower.contains("toolsession not found");
    }

    /** 根据 sessionId 字符串安全查询会话对象。 */
    private SkillSession resolveSession(String sessionId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        return numericId != null ? sessionService.findByIdSafe(numericId) : null;
    }

    /** Check if session uses default assistant rule (no specific business assistant). */
    private boolean isDefaultAssistant(SkillSession session) {
        return ruleService.lookup(session.getBusinessSessionDomain(), session.getBusinessSessionType()).isPresent();
    }

    /** 判断是否为 MiniApp 会话（null 也视为 MiniApp）。 */
    private boolean isMiniappSession(SkillSession session) {
        return session == null || session.isMiniappDomain();
    }

    /** 检测事件是否为上下文溢出错误。 */
    private boolean isContextOverflowEvent(JsonNode eventNode) {
        if (eventNode == null || eventNode.isNull()) {
            return false;
        }
        if (!"session.error".equals(eventNode.path("type").asText(""))) {
            return false;
        }
        return "ContextOverflowError".equals(
                eventNode.path("properties").path("error").path("name").asText(""));
    }

    /** 处理上下文溢出：清除交互状态、触发重建、统一投递重置提示。 */
    private void handleContextOverflow(String sessionId, String userId, SkillSession session) {
        clearPendingImInteractionState(sessionId);
        rebuildService.handleContextOverflow(sessionId, userId, rebuildCallback());

        StreamMessage resetMsg = StreamMessage.builder()
                .type(StreamMessage.Types.ERROR)
                .error(CONTEXT_RESET_MESSAGE)
                .build();
        emitter.emitToSession(session, sessionId, userId, resetMsg);

        if (session.isImDirectSession()) {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                try {
                    messageService.saveSystemMessage(numericId, CONTEXT_RESET_MESSAGE);
                } catch (Exception e) {
                    log.warn("Failed to persist context reset message for session {}", sessionId);
                }
            }
        }
    }

    /** 同步 IM 交互状态：question 和 permission 的标记与清除。 */
    private void syncPendingImInteraction(SkillSession session, StreamMessage msg) {
        if (session == null || session.getId() == null || msg == null) {
            return;
        }

        if (StreamMessage.Types.QUESTION.equals(msg.getType())) {
            String toolCallId = msg.getTool() != null ? msg.getTool().getToolCallId() : null;
            if (toolCallId == null || toolCallId.isBlank()) {
                return;
            }
            if ("running".equals(msg.getStatus()) || "pending".equals(msg.getStatus())) {
                interactionStateService.markQuestion(session.getId(), toolCallId);
            } else {
                interactionStateService.clearIfMatches(
                        session.getId(),
                        ImInteractionStateService.TYPE_QUESTION,
                        toolCallId);
            }
            return;
        }

        if (StreamMessage.Types.PERMISSION_REPLY.equals(msg.getType())) {
            String permissionId = msg.getPermission() != null ? msg.getPermission().getPermissionId() : null;
            interactionStateService.clearIfMatches(
                    session.getId(),
                    ImInteractionStateService.TYPE_PERMISSION,
                    permissionId);
        }
    }

    /** 清除会话的所有待处理 IM 交互状态。 */
    private void clearPendingImInteractionState(String sessionId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            interactionStateService.clear(numericId);
        }
    }

    /** 提取 JSON 节点的所有字段名（逗号分隔，用于日志）。 */
    private String fieldNames(JsonNode node) {
        StringBuilder names = new StringBuilder();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            if (!names.isEmpty()) {
                names.append(',');
            }
            names.append(iterator.next());
        }
        return names.toString();
    }

    /** 会话重建回调实例：委托给本路由器的广播和下游发送方法。 */
    private final SessionRebuildService.RebuildCallback rebuildCallback = new SessionRebuildService.RebuildCallback() {
        @Override
        public void broadcast(String sessionId, String userId, StreamMessage msg) {
            emitter.emitToClient(sessionId, userId, msg);
        }

        @Override
        public void sendInvoke(InvokeCommand command) {
            DownstreamSender sender = downstreamSender;
            if (sender != null) {
                sender.sendInvokeToGateway(command);
            }
        }
    };

    /** 获取重建回调实例。 */
    private SessionRebuildService.RebuildCallback rebuildCallback() {
        return rebuildCallback;
    }

    // ==================== Metrics ====================

    /**
     * Periodically logs routing metrics for observability.
     * Outputs cumulative counters since service startup.
     */
    @Scheduled(fixedDelay = 60_000)
    void logMetrics() {
        log.info("[METRICS] route: local={}, relaySend={}, relayReceive={} | takeover: success={}, conflict={}, probeDead={} | confirm: sent={}, skip={}, forceReconfirm={}, sendFail={}",
                routeLocalCount.get(), routeRelayCount.get(), routeRelayReceiveCount.get(),
                takeoverCount.get(), takeoverConflictCount.get(), ownerProbeDeadCount.get(),
                confirmSentCount.get(), confirmDedupSkipCount.get(),
                confirmForceReconfirmCount.get(), confirmSendFailCount.get());
    }
}
