package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.logging.MdcHelper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
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
    /** 不需要 emittedAt 时间戳的消息类型集合 */
    private static final Set<String> EMITTED_AT_EXCLUDED_TYPES = Set.of(
            StreamMessage.Types.PERMISSION_REPLY,
            StreamMessage.Types.AGENT_ONLINE,
            StreamMessage.Types.AGENT_OFFLINE,
            StreamMessage.Types.ERROR);

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
    /** 已完成会话的短期缓存，用于抑制 tool_done 后的残余事件 */
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
        /** 向 GW 发送 route_confirm：确认该 toolSessionId 路由归属于本 SS。 */
        void sendRouteConfirm(String toolSessionId, String welinkSessionId);

        /** 向 GW 发送 route_reject：通知 GW 本 SS 无法处理该 toolSessionId 的消息。 */
        void sendRouteReject(String toolSessionId);
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
            @Value("${skill.relay.owner-dead-threshold-seconds:120}") int ownerDeadThresholdSeconds) {
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
        this.sessionRouteService = sessionRouteService;
        this.skillInstanceRegistry = skillInstanceRegistry;
        this.instanceId = skillInstanceRegistry.getInstanceId();
        this.ownerDeadThresholdSeconds = ownerDeadThresholdSeconds;
    }

    // ==================== SS relay subscription (启动时订阅本实例的中转 channel) ====================

    /**
     * 启动时订阅本实例的 SS relay channel，接收其他 SS 实例中转过来的消息。
     *
     * <p>与 {@link SkillInstanceRegistry#register()} 配合：心跳注册让其他实例知道本实例存活，
     * relay 订阅让其他实例的消息能真正送达。两者缺一不可。</p>
     */
    @PostConstruct
    void initSsRelaySubscription() {
        redisMessageBroker.subscribeToSsRelay(instanceId, this::handleSsRelayMessage);
        log.info("[ENTRY] GatewayMessageRouter.initSsRelaySubscription: instanceId={}, channel=ss:relay:{}",
                instanceId, instanceId);
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

            MdcHelper.putAk(ak);
            MdcHelper.putSessionId(sessionId);
            MdcHelper.putScenario("ss-relay-" + type);

            routeRelayReceiveCount.incrementAndGet();
            log.info("[ENTRY] GatewayMessageRouter.handleSsRelayMessage: type={}, sessionId={}, ak={}",
                    type, sessionId, ak);

            dispatchLocally(type, sessionId, ak, userId, payload);

            log.info("[EXIT] GatewayMessageRouter.handleSsRelayMessage: type={}, sessionId={}",
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
            completedSessions.invalidate(sessionId);
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
            log.info("[ENTRY] GatewayMessageRouter.route: type={}, sessionId=null, ak={}, userId={}",
                    type, ak, userId);
            routeLocalCount.incrementAndGet();
            dispatchLocally(type, sessionId, ak, userId, node);
            log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId=null", type);
            return;
        }

        // Session-affinity messages: resolve owner and decide route strategy
        String ownerInstance = sessionRouteService.getOwnerInstance(sessionId);

        // Case 1: This instance is the owner → process locally
        if (instanceId.equals(ownerInstance)) {
            log.info("[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, ak={}, strategy=local_owner",
                    type, sessionId, ak);
            routeLocalCount.incrementAndGet();
            dispatchLocally(type, sessionId, ak, userId, node);
            log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
            return;
        }

        // Case 2: Remote owner exists → try relay
        if (ownerInstance != null) {
            String rawMessage = serializeRelayMessage(type, sessionId, ak, userId, node);
            long subscribers = redisMessageBroker.publishToSsRelay(ownerInstance, rawMessage);
            if (subscribers > 0) {
                routeRelayCount.incrementAndGet();
                log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId={}, strategy=relay_to_{}",
                        type, sessionId, ownerInstance);
                return; // relay succeeded
            }
            // subscribers == 0: owner is likely dead → fall through to takeover
            log.info("Relay returned 0 subscribers, owner may be dead: sessionId={}, owner={}",
                    sessionId, ownerInstance);
        }

        // Case 3 & 4: No owner, or owner is dead → attempt takeover / auto-claim
        if (shouldTakeover(ownerInstance, sessionId)) {
            if (ownerInstance == null) {
                // No route record → auto-claim via ensureRouteOwnership
                boolean claimed = sessionRouteService.ensureRouteOwnership(sessionId, ak, userId);
                if (claimed) {
                    takeoverCount.incrementAndGet();
                    log.info("[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, strategy=auto_claim",
                            type, sessionId);
                    dispatchLocally(type, sessionId, ak, userId, node);
                    log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
                    return;
                }
            } else {
                // Owner is dead → optimistic-lock takeover
                boolean taken = sessionRouteService.tryTakeover(sessionId, ownerInstance, instanceId);
                if (taken) {
                    takeoverCount.incrementAndGet();
                    log.info("[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, strategy=takeover_from_{}",
                            type, sessionId, ownerInstance);
                    dispatchLocally(type, sessionId, ak, userId, node);
                    log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
                    return;
                }
            }
            // Takeover/claim failed → someone else won → forward to winner
            takeoverConflictCount.incrementAndGet();
            String winner = sessionRouteService.getOwnerInstance(sessionId);
            if (winner != null && !instanceId.equals(winner)) {
                String rawMessage = serializeRelayMessage(type, sessionId, ak, userId, node);
                redisMessageBroker.publishToSsRelay(winner, rawMessage);
                log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId={}, strategy=forward_to_winner_{}",
                        type, sessionId, winner);
                return;
            }
            // Winner is this instance (race condition) → process locally
            if (instanceId.equals(winner)) {
                routeLocalCount.incrementAndGet();
                log.info("[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, strategy=won_race",
                        type, sessionId);
                dispatchLocally(type, sessionId, ak, userId, node);
                log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
                return;
            }
        }

        // Fallback: cannot determine owner, drop message
        log.warn("[SKIP] GatewayMessageRouter.route: no owner resolved, dropping message. type={}, sessionId={}",
                type, sessionId);
    }

    /**
     * Dispatches a message to the appropriate local handler based on type.
     */
    private void dispatchLocally(String type, String sessionId, String ak, String userId, JsonNode node) {
        switch (type) {
            case "tool_event" -> handleToolEvent(sessionId, userId, node);
            case "tool_done" -> handleToolDone(sessionId, userId, node);
            case "tool_error" -> handleToolError(sessionId, userId, node);
            case "agent_online" -> handleAgentOnline(ak, userId, node);
            case "agent_offline" -> handleAgentOffline(ak, userId);
            case "session_created" -> handleSessionCreated(ak, userId, node);
            case "permission_request" -> handlePermissionRequest(sessionId, userId, node);
            default -> log.warn("[SKIP] GatewayMessageRouter.route: reason=unknown_type, type={}", type);
        }
    }

    /**
     * Two-tier liveness check to determine if the owner instance should be taken over.
     *
     * <ol>
     *   <li>No owner → always takeover (auto-claim)</li>
     *   <li>Redis heartbeat missing → takeover</li>
     * </ol>
     *
     * Note: Redis PUBLISH subscriber count (checked before this method) serves as
     * an auxiliary signal — 0 subscribers hints the owner may be dead.
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

    /** 处理 tool_event：翻译事件、区分用户/助手消息、检测上下文溢出。 */
    private void handleToolEvent(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_event missing sessionId, agentId={}, raw keys={}",
                    node.path("agentId").asText(null), node.fieldNames());
            return;
        }

        log.info("handleToolEvent: sessionId={}", sessionId);
        activateIdleSession(sessionId, userId);
        SkillSession session = resolveSession(sessionId);
        StreamMessage msg = translateEvent(node, sessionId);
        if (msg == null) {
            return;
        }

        // 注入 subagent 字段（Plugin 层映射重写后附加的）
        JsonNode subagentNode = node.path("subagentSessionId");
        if (!subagentNode.isMissingNode() && !subagentNode.isNull()) {
            msg.setSubagentSessionId(subagentNode.asText());
            msg.setSubagentName(node.path("subagentName").asText(null));
        }

        if (completedSessions.getIfPresent(sessionId) != null
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
        handleAssistantToolEvent(sessionId, userId, msg, session);
    }

    /** 尝试激活 IDLE 会话，激活后广播 busy 状态。 */
    private void activateIdleSession(String sessionId, String userId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId == null) {
            return;
        }
        if (sessionService.activateSession(numericId)) {
            broadcastStreamMessage(sessionId, userId, StreamMessage.sessionStatus("busy"));
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
    private void handleAssistantToolEvent(String sessionId, String userId, StreamMessage msg, SkillSession session) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        StreamMessage permissionReply = numericId != null
                ? persistenceService.synthesizePermissionReplyFromToolOutcome(numericId, msg)
                : null;

        if (permissionReply != null) {
            routeAssistantMessage(sessionId, userId, permissionReply, session, numericId);
            String response = permissionReply.getPermission() != null
                    ? permissionReply.getPermission().getResponse()
                    : null;
            if (numericId != null && completedSessions.getIfPresent(sessionId) != null) {
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

        routeAssistantMessage(sessionId, userId, msg, session, numericId);
    }

    /** 路由助手消息：MiniApp 走广播+缓冲，IM 走出站转发。 */
    private void routeAssistantMessage(String sessionId, String userId, StreamMessage msg,
            SkillSession session, Long numericId) {
        // 同步会话标题（标题变化时更新）
        if (StreamMessage.Types.SESSION_TITLE.equals(msg.getType()) && numericId != null) {
            sessionService.updateTitle(numericId, msg.getTitle());
        }

        if (session != null && !isMiniappSession(session)) {
            handleImAssistantMessage(session, msg, numericId);
            return;
        }

        broadcastStreamMessage(sessionId, userId, msg);
        bufferService.accumulate(sessionId, msg);
        if (numericId != null) {
            try {
                persistenceService.persistIfFinal(numericId, msg);
            } catch (Exception e) {
                log.error("Failed to persist StreamMessage for session {}: {}", sessionId, e.getMessage(), e);
            }
        }
    }

    /** 处理 IM 渠道的助手消息（同步交互状态 + 出站转发 + 持久化）。 */
    private void handleImAssistantMessage(SkillSession session, StreamMessage msg, Long numericId) {
        syncPendingImInteraction(session, msg);
        String outboundText = buildImText(msg);
        if (outboundText != null && !outboundText.isBlank()) {
            imOutboundService.sendTextToIm(
                    session.getBusinessSessionType(),
                    session.getBusinessSessionId(),
                    outboundText,
                    session.getAssistantAccount());
        }

        if (session.isImDirectSession() && numericId != null) {
            try {
                persistenceService.persistIfFinal(numericId, msg);
            } catch (Exception e) {
                log.error("Failed to persist IM direct message for session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    /** 处理 tool_done：标记会话完成、广播 idle 状态、持久化最终消息。 */
    private void handleToolDone(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_done missing sessionId, agentId={}", node.path("agentId").asText(null));
            return;
        }

        log.info("handleToolDone: sessionId={}", sessionId);
        completedSessions.put(sessionId, Instant.now());

        StreamMessage msg = StreamMessage.sessionStatus("idle");
        SkillSession session = resolveSession(sessionId);
        Long numericId = ProtocolUtils.parseSessionId(sessionId);

        if (isMiniappSession(session)) {
            broadcastStreamMessage(sessionId, userId, msg);
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

    /** 处理 tool_error：会话重建/错误广播/IM 转发/持久化。 */
    private void handleToolError(String sessionId, String userId, JsonNode node) {
        String error = node.path("error").asText("Unknown error");
        String reason = node.path("reason").asText("");
        log.info("handleToolError: sessionId={}, error={}, reason={}", sessionId, error, reason);

        if (sessionId == null) {
            log.error("Tool error without session: {}", error);
            return;
        }

        if ("session_not_found".equals(reason) || isSessionInvalidError(error)) {
            clearPendingImInteractionState(sessionId);
            rebuildService.handleSessionNotFound(sessionId, userId, rebuildCallback());
            return;
        }

        SkillSession session = resolveSession(sessionId);
        Long numericId = ProtocolUtils.parseSessionId(sessionId);

        if (isMiniappSession(session)) {
            if (numericId != null) {
                try {
                    messageService.saveSystemMessage(numericId, "Error: " + error);
                } catch (Exception e) {
                    log.error("Failed to persist tool_error for session {}: {}", sessionId, e.getMessage());
                }
            }
            broadcastStreamMessage(sessionId, userId, StreamMessage.builder()
                    .type(StreamMessage.Types.ERROR)
                    .error(error)
                    .build());
        } else if (session != null) {
            imOutboundService.sendTextToIm(
                    session.getBusinessSessionType(),
                    session.getBusinessSessionId(),
                    "Error: " + error,
                    session.getAssistantAccount());
            if (session.isImDirectSession() && numericId != null) {
                try {
                    messageService.saveSystemMessage(numericId, "Error: " + error);
                } catch (Exception e) {
                    log.error("Failed to persist IM tool_error for session {}: {}", sessionId, e.getMessage());
                }
            }
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

        // Ownership takeover is now message-driven (lazy) — no eager takeoverActiveRoutes needed.

        StreamMessage msg = StreamMessage.agentOnline();
        sessionService.findByAk(ak).forEach(session -> broadcastStreamMessage(
                session.getId().toString(),
                userId != null && !userId.isBlank() ? userId : session.getUserId(),
                msg));
        log.info("[EXIT] handleAgentOnline: ak={}", ak);
    }

    /** 处理 agent_offline：向 AK 关联的所有会话广播离线状态。 */
    private void handleAgentOffline(String ak, String userId) {
        log.warn("Agent offline: ak={}", ak);

        if (ak == null) {
            return;
        }
        StreamMessage msg = StreamMessage.agentOffline();
        sessionService.findByAk(ak).forEach(session -> broadcastStreamMessage(
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

    /** 重建完成后按 FIFO 顺序逐条重发所有待处理的用户消息。 */
    private void retryPendingMessages(String sessionId, String ak, String userId, String toolSessionId) {
        java.util.List<String> pendingMessages = rebuildService.consumePendingMessages(sessionId);
        if (pendingMessages.isEmpty()) {
            log.info("[SKIP] retryPendingMessages: reason=no_pending_messages, sessionId={}", sessionId);
            return;
        }

        log.info("[ENTRY] retryPendingMessages: sessionId={}, ak={}, count={}",
                sessionId, ak, pendingMessages.size());

        DownstreamSender sender = downstreamSender;
        if (sender == null) {
            log.warn("[ERROR] retryPendingMessages: reason=no_downstream_sender, sessionId={}", sessionId);
            return;
        }

        int sent = 0;
        for (String pendingText : pendingMessages) {
            if (pendingText == null || pendingText.isBlank()) {
                continue;
            }
            ObjectNode chatPayload = objectMapper.createObjectNode();
            chatPayload.put("text", pendingText);
            chatPayload.put("toolSessionId", toolSessionId);
            String payloadStr;
            try {
                payloadStr = objectMapper.writeValueAsString(chatPayload);
            } catch (JsonProcessingException e) {
                payloadStr = "{}";
            }
            sender.sendInvokeToGateway(new InvokeCommand(ak, userId, sessionId, GatewayActions.CHAT, payloadStr));
            sent++;
        }

        log.info("[EXIT] retryPendingMessages: sessionId={}, ak={}, sent={}/{}", sessionId, ak, sent, pendingMessages.size());
        broadcastStreamMessage(sessionId, userId, StreamMessage.sessionStatus("busy"));
    }

    /** 处理 permission_request：MiniApp 广播到前端，IM 转发提示文本。 */
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
        if (session != null && !isMiniappSession(session)) {
            if (session.getId() != null && msg.getPermission() != null) {
                interactionStateService.markPermission(
                        session.getId(),
                        msg.getPermission().getPermissionId());
            }
            String text = formatPermissionPrompt(msg);
            if (text != null && !text.isBlank()) {
                imOutboundService.sendTextToIm(
                        session.getBusinessSessionType(),
                        session.getBusinessSessionId(),
                        text,
                        session.getAssistantAccount());
            }
            return;
        }

        broadcastStreamMessage(sessionId, userId, msg);
    }

    /**
     * 广播 StreamMessage 到前端。
     * 自动填充 sessionId、emittedAt、消息上下文后通过 Redis 发布。
     */
    public void broadcastStreamMessage(String sessionId, String userIdHint, StreamMessage msg) {
        try {
            enrichStreamMessage(sessionId, msg);
            String userId = resolveUserId(sessionId, userIdHint);
            if (userId == null || userId.isBlank()) {
                log.warn("Failed to broadcast StreamMessage without userId: sessionId={}, type={}",
                        sessionId, msg.getType());
                return;
            }

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("sessionId", sessionId);
            envelope.put("userId", userId);
            envelope.set("message", objectMapper.valueToTree(msg));
            redisMessageBroker.publishToUser(userId, objectMapper.writeValueAsString(envelope));
            log.info("[EXIT->FE] Broadcast StreamMessage: sessionId={}, type={}, userId={}",
                    sessionId, msg.getType(), userId);
        } catch (Exception e) {
            log.error("Failed to broadcast StreamMessage to session {}: type={}, error={}",
                    sessionId, msg.getType(), e.getMessage());
        }
    }

    /** 发布协议消息（广播 + 缓冲）。 */
    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        broadcastStreamMessage(sessionId, null, msg);
        bufferService.accumulate(sessionId, msg);
    }

    /**
     * 从消息 JSON 中解析 sessionId（先尝试 welinkSessionId，再通过 toolSessionId 反查）。
     *
     * <p>Task 2.10：当通过 toolSessionId 反查时，发送 route_confirm（找到）或 route_reject（未找到）。
     * welinkSessionId 直连路径不发送 confirm，因为 GW 已通过 welinkSessionId 精确路由，无需确认。</p>
     */
    private String resolveSessionId(String messageType, JsonNode node) {
        String sessionId = node.path("welinkSessionId").asText(null);
        if (sessionId != null) {
            return sessionId;
        }

        String toolSessionId = node.path("toolSessionId").asText(null);
        if (toolSessionId != null) {
            try {
                SkillSession session = sessionService.findByToolSessionId(toolSessionId);
                if (session != null) {
                    // 找到归属 session → 回复 route_confirm
                    RouteResponseSender sender = routeResponseSender;
                    if (sender != null) {
                        sender.sendRouteConfirm(toolSessionId, session.getId().toString());
                    }
                    return session.getId().toString();
                }
                // 未找到 → 回复 route_reject
                log.warn("Upstream message session not found: type={}, toolSessionId={}, reason=session_not_found",
                        messageType, toolSessionId);
                RouteResponseSender sender = routeResponseSender;
                if (sender != null) {
                    sender.sendRouteReject(toolSessionId);
                }
            } catch (Exception e) {
                log.error("Failed to resolve upstream session affinity: type={}, toolSessionId={}, reason={}",
                        messageType, toolSessionId, e.getMessage());
                // 查询异常也视为无法处理，回复 route_reject
                RouteResponseSender sender = routeResponseSender;
                if (sender != null) {
                    sender.sendRouteReject(toolSessionId);
                }
            }
        } else {
            log.warn(
                    "Dropping upstream message: type={}, toolSessionId=<missing>, reason=no_session_identifier, keys={}",
                    messageType, fieldNames(node));
        }

        return null;
    }

    /** 判断消息类型是否需要会话亲和性（需要 sessionId 才能处理）。 */
    private boolean requiresSessionAffinity(String messageType) {
        return switch (messageType) {
            case "tool_event", "tool_done", "tool_error", "permission_request" -> true;
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

    /** 填充消息的 sessionId、emittedAt 和消息上下文信息。 */
    private void enrichStreamMessage(String sessionId, StreamMessage msg) {
        msg.setSessionId(sessionId);
        msg.setWelinkSessionId(sessionId);
        if (!isEmittedAtExcluded(msg.getType())
                && (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank())) {
            msg.setEmittedAt(Instant.now().toString());
        }
        if (!"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                persistenceService.prepareMessageContext(numericId, msg);
            }
        }
    }

    /** 判断错误信息是否表示会话失效（需要重建）。 */
    private boolean isSessionInvalidError(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("not found")
                || lower.contains("session_not_found")
                || lower.contains("json parse error")
                || lower.contains("unexpected eof");
    }

    /** 判断消息类型是否不需要 emittedAt 时间戳。 */
    private boolean isEmittedAtExcluded(String type) {
        return type != null && EMITTED_AT_EXCLUDED_TYPES.contains(type);
    }

    /** 根据 sessionId 字符串安全查询会话对象。 */
    private SkillSession resolveSession(String sessionId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        return numericId != null ? sessionService.findByIdSafe(numericId) : null;
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

    /** 处理上下文溢出：清除交互状态、触发重建、发送重置提示。 */
    private void handleContextOverflow(String sessionId, String userId, SkillSession session) {
        clearPendingImInteractionState(sessionId);
        rebuildService.handleContextOverflow(sessionId, userId, rebuildCallback());
        imOutboundService.sendTextToIm(
                session.getBusinessSessionType(),
                session.getBusinessSessionId(),
                CONTEXT_RESET_MESSAGE,
                session.getAssistantAccount());
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

    /** 将 StreamMessage 转换为 IM 可发送的纯文本（仅 TEXT_DONE/ERROR/QUESTION/PERMISSION 类型）。 */
    private String buildImText(StreamMessage msg) {
        if (msg == null) {
            return null;
        }
        return switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> msg.getContent();
            case StreamMessage.Types.ERROR, StreamMessage.Types.SESSION_ERROR -> msg.getError();
            case StreamMessage.Types.PERMISSION_ASK ->
                msg.getTitle() != null && !msg.getTitle().isBlank()
                        ? "Permission required: " + msg.getTitle()
                        : null;
            case StreamMessage.Types.QUESTION -> formatQuestionMessage(msg);
            default -> null;
        };
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

    /** 格式化权限请求提示文本。 */
    private String formatPermissionPrompt(StreamMessage msg) {
        if (msg == null || msg.getTitle() == null || msg.getTitle().isBlank()) {
            return null;
        }
        return msg.getTitle() + "\n请回复: once / always / reject";
    }

    /** 格式化提问消息为 IM 文本（含标题、问题、选项）。 */
    private String formatQuestionMessage(StreamMessage msg) {
        if (msg.getQuestionInfo() == null) {
            return null;
        }
        String status = msg.getStatus();
        if (status != null && !"running".equals(status) && !"pending".equals(status)) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        if (msg.getQuestionInfo().getHeader() != null && !msg.getQuestionInfo().getHeader().isBlank()) {
            text.append(msg.getQuestionInfo().getHeader()).append('\n');
        }
        if (msg.getQuestionInfo().getQuestion() != null && !msg.getQuestionInfo().getQuestion().isBlank()) {
            text.append(msg.getQuestionInfo().getQuestion());
        }
        if (msg.getQuestionInfo().getOptions() != null && !msg.getQuestionInfo().getOptions().isEmpty()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("选项: ").append(String.join(" / ", msg.getQuestionInfo().getOptions()));
        }
        return text.toString();
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
            broadcastStreamMessage(sessionId, userId, msg);
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
        log.info("[METRICS] route: local={}, relaySend={}, relayReceive={} | takeover: success={}, conflict={}, probeDead={}",
                routeLocalCount.get(), routeRelayCount.get(), routeRelayReceiveCount.get(),
                takeoverCount.get(), takeoverConflictCount.get(), ownerProbeDeadCount.get());
    }
}
