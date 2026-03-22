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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Set;

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

    /**
     * 下游命令发送接口。
     * 由 WebSocket 或 HTTP 层实现，用于向 Gateway 发送 invoke 命令。
     */
    public interface DownstreamSender {
        /** 向 Gateway 发送 invoke 命令。 */
        void sendInvokeToGateway(InvokeCommand command);
    }

    /** 下游发送者引用（延迟注入） */
    private volatile DownstreamSender downstreamSender;

    /** v3: 会话路由服务（可选注入，null 时跳过 ownership 检查） */
    private volatile SessionRouteService sessionRouteService;

    public GatewayMessageRouter(ObjectMapper objectMapper,
            SkillMessageService messageService,
            SkillSessionService sessionService,
            RedisMessageBroker redisMessageBroker,
            OpenCodeEventTranslator translator,
            MessagePersistenceService persistenceService,
            StreamBufferService bufferService,
            SessionRebuildService rebuildService,
            ImInteractionStateService interactionStateService,
            ImOutboundService imOutboundService) {
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
    }

    /** 设置下游命令发送者。 */
    public void setDownstreamSender(DownstreamSender sender) {
        this.downstreamSender = sender;
    }

    /**
     * v3: 注入会话路由服务，用于广播降级时的 ownership 检查。
     */
    public void setSessionRouteService(SessionRouteService sessionRouteService) {
        this.sessionRouteService = sessionRouteService;
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
     */
    public void route(String type, String ak, String userId, JsonNode node) {
        String sessionId = requiresSessionAffinity(type) ? resolveSessionId(type, node) : null;

        // v3: 广播降级时的 ownership 检查
        // 会话路由服务已注入且 sessionId 已解析时，检查该会话是否属于本实例
        if (sessionId != null && sessionRouteService != null && !sessionRouteService.isMySession(sessionId)) {
            log.debug("[SKIP] GatewayMessageRouter.route: reason=not_my_session, sessionId={}, type={}",
                    sessionId, type);
            return;
        }

        log.info("[ENTRY] GatewayMessageRouter.route: type={}, sessionId={}, ak={}, userId={}",
                type, sessionId, ak, userId);

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

        log.info("[EXIT] GatewayMessageRouter.route: type={}, sessionId={}", type, sessionId);
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

        if ("user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            handleUserToolEvent(sessionId, userId, msg, session);
        } else {
            handleAssistantToolEvent(sessionId, userId, msg, session);
        }
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

    /** 处理用户角色的 tool_event（仅处理 TEXT_DONE 类型，持久化用户消息）。 */
    private void handleUserToolEvent(String sessionId, String userId, StreamMessage msg, SkillSession session) {
        if (!StreamMessage.Types.TEXT_DONE.equals(msg.getType())) {
            return;
        }
        String content = msg.getContent() != null ? msg.getContent() : "";
        if (content.isBlank()) {
            return;
        }

        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            log.warn("Cannot process user message: invalid sessionId={}", sessionId);
            return;
        }

        if (session != null && !isMiniappSession(session)) {
            persistenceService.consumePendingUserMessage(numericSessionId);
            return;
        }

        try {
            if (persistenceService.consumePendingUserMessage(numericSessionId)) {
                log.debug("Skipping miniapp user message echo for session {}", sessionId);
                return;
            }
            messageService.saveUserMessage(numericSessionId, content);
        } catch (Exception e) {
            log.error("Failed to persist user tool event for session {}: {}", sessionId, e.getMessage(), e);
        }
        broadcastStreamMessage(sessionId, userId, msg);
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

    /** 处理 agent_online：向 AK 关联的所有会话广播上线状态。 */
    private void handleAgentOnline(String ak, String userId, JsonNode node) {
        String toolType = node.path("toolType").asText("UNKNOWN");
        String toolVersion = node.path("toolVersion").asText("UNKNOWN");
        log.info("Agent online: ak={}, toolType={}, toolVersion={}", ak, toolType, toolVersion);

        if (ak == null) {
            return;
        }
        StreamMessage msg = StreamMessage.agentOnline();
        sessionService.findByAk(ak).forEach(session -> broadcastStreamMessage(
                session.getId().toString(),
                userId != null && !userId.isBlank() ? userId : session.getUserId(),
                msg));
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

        try {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId == null) {
                log.warn("session_created has invalid sessionId={}, cannot update", sessionId);
                return;
            }
            sessionService.updateToolSessionId(numericId, toolSessionId);
            retryPendingMessage(sessionId, ak, userId, toolSessionId);
        } catch (Exception e) {
            log.error("Failed to update tool session ID: sessionId={}, error={}", sessionId, e.getMessage());
            rebuildService.clearPendingMessage(sessionId);
        }
    }

    /** 重建完成后重试发送待处理的用户消息。 */
    private void retryPendingMessage(String sessionId, String ak, String userId, String toolSessionId) {
        String pendingText = rebuildService.consumePendingMessage(sessionId);
        if (pendingText == null) {
            return;
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

        DownstreamSender sender = downstreamSender;
        if (sender != null) {
            sender.sendInvokeToGateway(new InvokeCommand(ak, userId, sessionId, GatewayActions.CHAT, payloadStr));
        }
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

    /** 从消息 JSON 中解析 sessionId（先尝试 welinkSessionId，再通过 toolSessionId 反查）。 */
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
                    return session.getId().toString();
                }
                log.warn("Dropping upstream message: type={}, toolSessionId={}, reason=session_not_found",
                        messageType, toolSessionId);
            } catch (Exception e) {
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
}
