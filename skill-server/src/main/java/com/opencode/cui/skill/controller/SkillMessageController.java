package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessageView;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/skill/sessions/{sessionId}")
public class SkillMessageController {

    private static final Set<String> VALID_PERMISSION_RESPONSES = Set.of("once", "always", "reject");

    private final SkillMessageService messageService;
    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final ImMessageService imMessageService;
    private final ObjectMapper objectMapper;
    private final SkillMessagePartRepository partRepository;
    private final MessagePersistenceService messagePersistenceService;

    public SkillMessageController(SkillMessageService messageService,
            SkillSessionService sessionService,
            GatewayRelayService gatewayRelayService,
            ImMessageService imMessageService,
            ObjectMapper objectMapper,
            SkillMessagePartRepository partRepository,
            MessagePersistenceService messagePersistenceService) {
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.imMessageService = imMessageService;
        this.objectMapper = objectMapper;
        this.partRepository = partRepository;
        this.messagePersistenceService = messagePersistenceService;
    }

    /**
     * POST /api/skill/sessions/{sessionId}/messages
     * Send a user message. Persists the message and triggers AI invocation via
     * AI-Gateway.
     */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<SkillMessage>> sendMessage(
            @PathVariable Long sessionId,
            @RequestBody SendMessageRequest request) {

        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Content is required"));
        }

        // Verify session exists and is not closed
        SkillSession session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "Session not found"));
        }

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "Session is closed"));
        }

        // Persist user message
        messagePersistenceService.finalizeActiveAssistantTurn(sessionId);
        SkillMessage message = messageService.saveUserMessage(sessionId, request.getContent());

        // Send invoke to AI-Gateway to trigger OpenCode processing
        if (session.getAk() != null) {
            if (session.getToolSessionId() == null) {
                log.warn("Session {} has no toolSessionId, cannot invoke AI", sessionId);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(503, "No toolSessionId available"));
            }

            // Route: toolCallId present → question_reply, otherwise → chat
            String action;
            String payload;
            if (request.getToolCallId() != null && !request.getToolCallId().isBlank()) {
                action = "question_reply";
                payload = buildQuestionReplyPayload(
                        request.getContent(), request.getToolCallId(), session.getToolSessionId());
            } else {
                action = "chat";
                payload = buildChatPayload(request.getContent(), session.getToolSessionId());
            }

            gatewayRelayService.sendInvokeToGateway(
                    session.getAk(),
                    sessionId.toString(),
                    action,
                    payload);
        } else {
            log.warn("No agent associated with session {}, cannot invoke AI", sessionId);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(message));
    }

    /**
     * GET /api/skill/sessions/{sessionId}/messages
     * Get message history with pagination.
     */
    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<PageResult<SkillMessageView>>> getMessages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // Verify session exists
        try {
            sessionService.getSession(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "Session not found"));
        }

        PageResult<SkillMessage> messages = messageService.getMessageHistory(sessionId, page, size);
        var content = messages.getContent().stream()
                .map(message -> SkillMessageView.from(
                        message,
                        partRepository.findByMessageId(message.getId())))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(new PageResult<>(content,
                messages.getTotalElements(),
                messages.getNumber(),
                messages.getSize())));
    }

    /**
     * POST /api/skill/sessions/{sessionId}/send-to-im
     * Send selected text content to the IM chat associated with this session.
     */
    @PostMapping("/send-to-im")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendToIm(
            @PathVariable Long sessionId,
            @RequestBody SendToImRequest request) {

        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "Content is required"));
        }

        // Determine the IM chatId: from request or from session
        String chatId = request.getChatId();
        if (chatId == null || chatId.isBlank()) {
            try {
                SkillSession session = sessionService.getSession(sessionId);
                chatId = session.getImGroupId();
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "Session not found"));
            }
        }

        if (chatId == null || chatId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "No IM chat ID associated with this session"));
        }

        boolean success = imMessageService.sendMessage(chatId, request.getContent());

        if (success) {
            log.info("Sent content to IM: sessionId={}, chatId={}, contentLength={}",
                    sessionId, chatId, request.getContent().length());
            return ResponseEntity.ok(ApiResponse.ok(Map.of("success", true)));
        } else {
            log.error("Failed to send content to IM: sessionId={}, chatId={}", sessionId, chatId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "Failed to send message to IM"));
        }
    }

    /**
     * POST /api/skill/sessions/{sessionId}/permissions/{permId}
     * Reply to a permission request.
     * Valid response values: "once", "always", "reject".
     * Routes the reply to AI-Gateway -> PCAgent -> OpenCode for execution.
     */
    @PostMapping("/permissions/{permId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replyPermission(
            @PathVariable Long sessionId,
            @PathVariable String permId,
            @RequestBody PermissionReplyRequest request) {

        // Validate response field
        if (request.getResponse() == null || request.getResponse().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "Field 'response' is required"));
        }
        if (!VALID_PERMISSION_RESPONSES.contains(request.getResponse())) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "Invalid response value. Must be one of: once, always, reject"));
        }

        // Verify session exists and is not closed
        SkillSession session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "Session not found"));
        }

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "Session is closed"));
        }

        if (session.getAk() == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "No agent associated with this session"));
        }

        // Build permission_reply payload
        String payload = buildPermissionReplyPayload(permId, request.getResponse(),
                session.getToolSessionId());

        // Send permission_reply invoke to AI-Gateway
        gatewayRelayService.sendInvokeToGateway(
                session.getAk(),
                sessionId.toString(),
                "permission_reply",
                payload);

        StreamMessage replyMessage = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .role("assistant")
                .permissionId(permId)
                .response(request.getResponse())
                .build();
        gatewayRelayService.publishProtocolMessage(sessionId.toString(), replyMessage);

        log.info("Permission reply sent: sessionId={}, permId={}, response={}",
                sessionId, permId, request.getResponse());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "success", (Object) true,
                "permissionId", permId,
                "response", request.getResponse())));
    }

    /**
     * Build the JSON payload for a chat invoke command.
     */
    private String buildChatPayload(String text, String toolSessionId) {
        var node = objectMapper.createObjectNode();
        node.put("text", text);
        if (toolSessionId != null) {
            node.put("toolSessionId", toolSessionId);
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize chat payload", e);
            return "{}";
        }
    }

    /**
     * Build the JSON payload for a question_reply invoke command.
     */
    private String buildQuestionReplyPayload(String text, String toolCallId, String toolSessionId) {
        var node = objectMapper.createObjectNode();
        node.put("text", text);
        node.put("toolCallId", toolCallId);
        if (toolSessionId != null) {
            node.put("toolSessionId", toolSessionId);
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize question_reply payload", e);
            return "{}";
        }
    }

    /**
     * Build the JSON payload for a permission_reply invoke command.
     */
    private String buildPermissionReplyPayload(String permissionId, String response,
            String toolSessionId) {
        var node = objectMapper.createObjectNode();
        node.put("permissionId", permissionId);
        node.put("response", response);
        if (toolSessionId != null) {
            node.put("toolSessionId", toolSessionId);
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize permission reply payload", e);
            return "{}";
        }
    }

    @Data
    public static class SendMessageRequest {
        private String content;
        /** Optional: when present, routes to question_reply instead of chat */
        private String toolCallId;
    }

    @Data
    public static class SendToImRequest {
        private String content;
        private String chatId;
    }

    @Data
    public static class PermissionReplyRequest {
        /** Valid values: "once", "always", "reject" */
        private String response;
    }
}
