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

@Slf4j
@Component
public class GatewayMessageRouter {

    private static final String CONTEXT_RESET_MESSAGE = "对话上下文已超出限制，已自动重置，请稍后重试。";
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
    private final Cache<String, Instant> completedSessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(1_000)
            .build();

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

    public void setDownstreamSender(DownstreamSender sender) {
        this.downstreamSender = sender;
    }

    public void clearCompletionMark(String sessionId) {
        if (sessionId != null) {
            completedSessions.invalidate(sessionId);
        }
    }

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

    private void handleToolEvent(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_event missing sessionId, agentId={}, raw keys={}",
                    node.path("agentId").asText(null), node.fieldNames());
            return;
        }

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

    private void activateIdleSession(String sessionId, String userId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId == null) {
            return;
        }
        if (sessionService.activateSession(numericId)) {
            broadcastStreamMessage(sessionId, userId, StreamMessage.sessionStatus("busy"));
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

    private void handleToolDone(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_done missing sessionId, agentId={}", node.path("agentId").asText(null));
            return;
        }

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

    private void handleToolError(String sessionId, String userId, JsonNode node) {
        String error = node.path("error").asText("Unknown error");
        String reason = node.path("reason").asText("");

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

    private void handleSessionCreated(String ak, String userId, JsonNode node) {
        String toolSessionId = node.path("toolSessionId").asText(null);
        String sessionId = node.path("welinkSessionId").asText(null);

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

    private void handlePermissionRequest(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("permission_request missing sessionId");
            return;
        }

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
        } catch (Exception e) {
            log.error("Failed to broadcast StreamMessage to session {}: type={}, error={}",
                    sessionId, msg.getType(), e.getMessage());
        }
    }

    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        broadcastStreamMessage(sessionId, null, msg);
        bufferService.accumulate(sessionId, msg);
    }

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
            SkillSession session = sessionService.getSession(numericId);
            return session.getUserId();
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
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase();
        return lower.contains("not found")
                || lower.contains("session_not_found")
                || lower.contains("json parse error")
                || lower.contains("unexpected eof");
    }

    private boolean isEmittedAtExcluded(String type) {
        return type != null && EMITTED_AT_EXCLUDED_TYPES.contains(type);
    }

    private SkillSession resolveSession(String sessionId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        return numericId != null ? sessionService.findByIdSafe(numericId) : null;
    }

    private boolean isMiniappSession(SkillSession session) {
        return session == null || session.isMiniappDomain();
    }

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

    private void clearPendingImInteractionState(String sessionId) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            interactionStateService.clear(numericId);
        }
    }

    private String formatPermissionPrompt(StreamMessage msg) {
        if (msg == null || msg.getTitle() == null || msg.getTitle().isBlank()) {
            return null;
        }
        return msg.getTitle() + "\n请回复: once / always / reject";
    }

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
