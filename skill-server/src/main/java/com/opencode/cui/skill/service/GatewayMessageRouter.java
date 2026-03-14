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

/**
 * Routes upstream messages from AI-Gateway to their respective handlers.
 *
 * Extracted from GatewayRelayService to separate concerns:
 * - GatewayRelayService: downstream (Skill → Gateway) + dispatch coordination
 * - GatewayMessageRouter: upstream (Gateway → Skill) message handling logic
 */
@Slf4j
@Component
public class GatewayMessageRouter {

    private final ObjectMapper objectMapper;
    private final SkillMessageService messageService;
    private final SkillSessionService sessionService;
    private final RedisMessageBroker redisMessageBroker;
    private final OpenCodeEventTranslator translator;
    private final MessagePersistenceService persistenceService;
    private final StreamBufferService bufferService;
    private final SessionRebuildService rebuildService;

    /**
     * 记录已完成（tool_done）的 sessionId，用于抑制 tool_done 后到达的乱序 tool_event。
     * TTL 5 秒后自动过期，不影响同一 session 的后续正常对话。
     */
    private final Cache<String, Instant> completedSessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(1_000)
            .build();

    private static final java.util.Set<String> EMITTED_AT_EXCLUDED_TYPES = java.util.Set.of(
            StreamMessage.Types.PERMISSION_REPLY,
            StreamMessage.Types.AGENT_ONLINE,
            StreamMessage.Types.AGENT_OFFLINE,
            StreamMessage.Types.ERROR);

    /**
     * 下行发送委托：由 GatewayRelayService 提供实现。
     */
    public interface DownstreamSender {
        void sendInvokeToGateway(InvokeCommand command);
    }

    private volatile DownstreamSender downstreamSender;

    public GatewayMessageRouter(ObjectMapper objectMapper,
            SkillMessageService messageService,
            SkillSessionService sessionService,
            RedisMessageBroker redisMessageBroker,
            OpenCodeEventTranslator translator,
            MessagePersistenceService persistenceService,
            StreamBufferService bufferService,
            SessionRebuildService rebuildService) {
        this.objectMapper = objectMapper;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.redisMessageBroker = redisMessageBroker;
        this.translator = translator;
        this.persistenceService = persistenceService;
        this.bufferService = bufferService;
        this.rebuildService = rebuildService;
    }

    public void setDownstreamSender(DownstreamSender sender) {
        this.downstreamSender = sender;
    }

    /**
     * 新对话开始时清除已完成标记，防止新一轮对话的 tool_event 被误拦截。
     */
    public void clearCompletionMark(String sessionId) {
        if (sessionId != null) {
            completedSessions.invalidate(sessionId);
        }
    }

    // ==================== Dispatch ====================

    /**
     * Route a parsed gateway message to the appropriate handler.
     */
    public void route(String type, String ak, String userId, JsonNode node) {
        String sessionId = requiresSessionAffinity(type) ? resolveSessionId(type, node) : null;

        log.debug("Gateway message dispatch: type={}, sessionId={}, ak={}, userId={}",
                type, sessionId, ak, userId);

        switch (type) {
            case "tool_event" -> handleToolEvent(sessionId, userId, node);
            case "tool_done" -> handleToolDone(sessionId, userId, node);
            case "tool_error" -> handleToolError(sessionId, userId, node);
            case "agent_online" -> handleAgentOnline(ak, userId, node);
            case "agent_offline" -> handleAgentOffline(ak, userId);
            case "session_created" -> handleSessionCreated(ak, userId, node);
            case "permission_request" -> handlePermissionRequest(sessionId, userId, node);
            default -> log.warn("Unknown gateway message type: {}", type);
        }
    }

    // ==================== Message Handlers ====================

    private void handleToolEvent(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_event missing sessionId, agentId={}, raw keys={}",
                    node.path("agentId").asText(null), node.fieldNames());
            return;
        }
        activateIdleSession(sessionId, userId);

        StreamMessage msg = translateEvent(node, sessionId);
        if (msg == null) {
            return;
        }

        // Suppress stale assistant-side events that arrive after tool_done,
        // but NEVER suppress:
        // - QUESTION events (the completed/error update for a question often
        //   arrives after tool_done due to event ordering)
        // - PERMISSION events (permission resolved updates can also arrive after
        //   tool_done)
        // - USER messages (OpenCode may emit the user's text echo slightly
        //   after tool_done; dropping it would make the message disappear from
        //   ws and history)
        if (completedSessions.getIfPresent(sessionId) != null
                && !StreamMessage.Types.QUESTION.equals(msg.getType())
                && !StreamMessage.Types.PERMISSION_ASK.equals(msg.getType())
                && !StreamMessage.Types.PERMISSION_REPLY.equals(msg.getType())
                && !"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            log.debug("Suppressing stale tool_event after tool_done: sessionId={}, type={}",
                    sessionId, msg.getType());
            return;
        }

        if ("user".equals(msg.getRole())) {
            handleUserToolEvent(sessionId, userId, msg);
        } else {
            handleAssistantToolEvent(sessionId, userId, msg);
        }
    }

    private void activateIdleSession(String sessionId, String userId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            boolean activated = sessionService.activateSession(numericId);
            if (activated) {
                broadcastStreamMessage(sessionId, userId, StreamMessage.sessionStatus("busy"));
            }
        }
    }

    private StreamMessage translateEvent(JsonNode node, String sessionId) {
        JsonNode event = node.get("event");
        StreamMessage msg = translator.translate(event);
        if (msg == null) {
            log.debug("Event ignored by translator for session {}", sessionId);
        }
        return msg;
    }

    private void handleUserToolEvent(String sessionId, String userId, StreamMessage msg) {
        if (!StreamMessage.Types.TEXT_DONE.equals(msg.getType())) {
            log.debug("Skipping non-text user event for session {}: type={}", sessionId, msg.getType());
            return;
        }
        String content = msg.getContent() != null ? msg.getContent() : "";
        if (content.isBlank()) {
            log.debug("Skipping blank user TEXT_DONE for session {}", sessionId);
            return;
        }
        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            log.warn("Cannot process user message: invalid sessionId={}", sessionId);
            return;
        }
        try {
            if (persistenceService.consumePendingUserMessage(numericSessionId)) {
                log.debug("Skipping miniapp user message echo for session {}", sessionId);
                return;
            }
            messageService.saveUserMessage(numericSessionId, content);
            log.info("Persisted user message from opencode CLI: sessionId={}, len={}",
                    sessionId, content.length());
        } catch (Exception e) {
            log.error("Failed to persist CLI user message for session {}: {}",
                    sessionId, e.getMessage(), e);
        }
        broadcastStreamMessage(sessionId, userId, msg);
    }

    private void handleAssistantToolEvent(String sessionId, String userId, StreamMessage msg) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        StreamMessage permissionReply = numericId != null
                ? persistenceService.synthesizePermissionReplyFromToolOutcome(numericId, msg)
                : null;
        if (permissionReply != null) {
            broadcastStreamMessage(sessionId, userId, permissionReply);
            bufferService.accumulate(sessionId, permissionReply);
            try {
                persistenceService.persistIfFinal(numericId, permissionReply);
                if (completedSessions.getIfPresent(sessionId) != null) {
                    persistenceService.finalizeActiveAssistantTurn(numericId);
                }
            } catch (Exception e) {
                log.error("Failed to persist synthesized permission reply for session {}: {}",
                        sessionId, e.getMessage(), e);
            }
            String response = permissionReply.getPermission() != null
                    ? permissionReply.getPermission().getResponse()
                    : null;
            log.info("Synthesized permission.reply from tool outcome: sessionId={}, permissionId={}, response={}",
                    sessionId,
                    permissionReply.getPermission() != null
                            ? permissionReply.getPermission().getPermissionId()
                            : null,
                    response);
            if ("reject".equals(response)) {
                return;
            }
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

    private void handleToolDone(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_done missing sessionId, agentId={}", node.path("agentId").asText(null));
            return;
        }

        completedSessions.put(sessionId, Instant.now());

        StreamMessage msg = StreamMessage.sessionStatus("idle");
        broadcastStreamMessage(sessionId, userId, msg);
        bufferService.accumulate(sessionId, msg);

        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            try {
                persistenceService.persistIfFinal(numericId, msg);
            } catch (Exception e) {
                log.error("Failed to persist tool_done for session {}: {}", sessionId, e.getMessage(), e);
            }
        }

        log.info("Tool done for session {}", sessionId);
    }

    private void handleToolError(String sessionId, String userId, JsonNode node) {
        String error = node.path("error").asText("Unknown error");
        String reason = node.path("reason").asText("");

        if (sessionId != null) {
            if ("session_not_found".equals(reason) || isSessionInvalidError(error)) {
                rebuildService.handleSessionNotFound(sessionId, userId, rebuildCallback());
                return;
            }

            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                try {
                    messageService.saveSystemMessage(numericId, "Error: " + error);
                } catch (Exception e) {
                    log.error("Failed to persist tool_error for session {}: {}", sessionId, e.getMessage());
                }
            }
            StreamMessage msg = StreamMessage.builder()
                    .type(StreamMessage.Types.ERROR)
                    .error(error)
                    .build();
            broadcastStreamMessage(sessionId, userId, msg);
            if (numericId != null) {
                try {
                    persistenceService.finalizeActiveAssistantTurn(numericId);
                } catch (Exception e) {
                    log.warn("Cannot finalize active message after tool_error: sessionId={}", sessionId);
                }
            }
        }

        log.error("Tool error for session {}: {}", sessionId, error);
    }

    private void handleAgentOnline(String ak, String userId, JsonNode node) {
        String toolType = node.path("toolType").asText("UNKNOWN");
        String toolVersion = node.path("toolVersion").asText("UNKNOWN");
        log.info("Agent online: ak={}, toolType={}, toolVersion={}", ak, toolType, toolVersion);

        if (ak != null) {
            StreamMessage msg = StreamMessage.agentOnline();
            sessionService.findByAk(ak).forEach(
                    session -> broadcastStreamMessage(session.getId().toString(),
                            userId != null && !userId.isBlank() ? userId : session.getUserId(),
                            msg));
        }
    }

    private void handleAgentOffline(String ak, String userId) {
        log.warn("Agent offline: ak={}", ak);

        if (ak != null) {
            StreamMessage msg = StreamMessage.agentOffline();
            sessionService.findByAk(ak).forEach(
                    session -> broadcastStreamMessage(session.getId().toString(),
                            userId != null && !userId.isBlank() ? userId : session.getUserId(),
                            msg));
        }
    }

    private void handleSessionCreated(String ak, String userId, JsonNode node) {
        String toolSessionId = node.path("toolSessionId").asText(null);
        String sessionId = node.path("welinkSessionId").asText(null);

        if (sessionId == null || toolSessionId == null) {
            log.warn("session_created missing fields: sessionId={}, toolSessionId={}, ak={}, raw={}",
                    sessionId, toolSessionId, ak, node.toString());
            return;
        }

        try {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId == null) {
                log.warn("session_created has invalid sessionId={}, cannot update", sessionId);
                return;
            }
            sessionService.updateToolSessionId(numericId, toolSessionId);
            log.info("Tool session created: sessionId={}, toolSessionId={}", sessionId, toolSessionId);

            retryPendingMessage(sessionId, ak, userId, toolSessionId);
        } catch (Exception e) {
            log.error("Failed to update tool session ID: sessionId={}, error={}", sessionId, e.getMessage());
            rebuildService.clearPendingMessage(sessionId);
        }
    }

    private void retryPendingMessage(String sessionId, String ak, String userId, String toolSessionId) {
        String pendingText = rebuildService.consumePendingMessage(sessionId);
        if (pendingText == null) {
            return;
        }
        log.info("Retrying pending message after rebuild: sessionId={}, text='{}'",
                sessionId, pendingText.substring(0, Math.min(50, pendingText.length())));

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
        log.info("Pending message re-sent after rebuild: sessionId={}", sessionId);
        broadcastStreamMessage(sessionId, userId, StreamMessage.sessionStatus("busy"));
    }

    private void handlePermissionRequest(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("permission_request missing sessionId");
            return;
        }

        StreamMessage msg = translator.translatePermissionFromGateway(node);
        broadcastStreamMessage(sessionId, userId, msg);

        log.info("Permission request broadcast to session {}: permId={}",
                sessionId, msg.getPermission() != null ? msg.getPermission().getPermissionId() : null);
    }

    // ==================== Broadcast ====================

    /**
     * Broadcast a StreamMessage via Redis pub/sub for multi-instance delivery.
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

            String messageText = objectMapper.writeValueAsString(envelope);
            redisMessageBroker.publishToUser(userId, messageText);
        } catch (Exception e) {
            log.error("Failed to broadcast StreamMessage to session {}: type={}, error={}",
                    sessionId, msg.getType(), e.getMessage());
        }
    }

    /**
     * Publish a protocol message and buffer it (used by controllers for ad-hoc
     * push).
     */
    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        broadcastStreamMessage(sessionId, null, msg);
        bufferService.accumulate(sessionId, msg);
    }

    // ==================== Internal Helpers ====================

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
                    log.debug("Resolved session affinity: type={}, toolSessionId={} -> welinkSessionId={}",
                            messageType, toolSessionId, session.getId());
                    return session.getId().toString();
                } else {
                    log.warn("Dropping upstream message: type={}, toolSessionId={}, reason=session_not_found",
                            messageType, toolSessionId);
                }
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

    private boolean requiresSessionAffinity(String messageType) {
        return switch (messageType) {
            case "tool_event", "tool_done", "tool_error", "permission_request" -> true;
            default -> false;
        };
    }

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
            return sessionService.getSession(numericId).getUserId();
        } catch (Exception e) {
            log.warn("Failed to resolve userId for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

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

    private boolean isSessionInvalidError(String error) {
        if (error == null)
            return false;
        String lower = error.toLowerCase();
        return lower.contains("not found")
                || lower.contains("session_not_found")
                || lower.contains("json parse error")
                || lower.contains("unexpected eof");
    }

    private boolean isEmittedAtExcluded(String type) {
        return type != null && EMITTED_AT_EXCLUDED_TYPES.contains(type);
    }

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

    /** 缓存单例，避免每次 tool_error 都创建新的匿名类实例。 */
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

    private SessionRebuildService.RebuildCallback rebuildCallback() {
        return rebuildCallback;
    }
}
