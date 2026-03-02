package com.yourapp.skill.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.skill.model.SkillMessage;
import com.yourapp.skill.model.SkillSession;
import com.yourapp.skill.service.GatewayRelayService;
import com.yourapp.skill.service.ImMessageService;
import com.yourapp.skill.service.SkillMessageService;
import com.yourapp.skill.service.SkillSessionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

@Slf4j
@RestController
@RequestMapping("/api/skill/sessions/{sessionId}")
public class SkillMessageController {

    private final SkillMessageService messageService;
    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final ImMessageService imMessageService;
    private final ObjectMapper objectMapper;

    public SkillMessageController(SkillMessageService messageService,
                                  SkillSessionService sessionService,
                                  GatewayRelayService gatewayRelayService,
                                  ImMessageService imMessageService,
                                  ObjectMapper objectMapper) {
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.imMessageService = imMessageService;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /api/skill/sessions/{sessionId}/messages
     * Send a user message. Persists the message and triggers AI invocation via AI-Gateway.
     */
    @PostMapping("/messages")
    public ResponseEntity<SkillMessage> sendMessage(
            @PathVariable Long sessionId,
            @RequestBody SendMessageRequest request) {

        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Verify session exists and is not closed
        SkillSession session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(null);
        }

        // Persist user message
        SkillMessage message = messageService.saveUserMessage(sessionId, request.getContent());

        // Send chat invoke to AI-Gateway to trigger OpenCode processing
        if (session.getAgentId() != null) {
            String payload = buildChatPayload(request.getContent(), session.getToolSessionId());
            gatewayRelayService.sendInvokeToGateway(
                    session.getAgentId().toString(),
                    sessionId.toString(),
                    "chat",
                    payload
            );
        } else {
            log.warn("No agent associated with session {}, cannot invoke AI", sessionId);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    /**
     * GET /api/skill/sessions/{sessionId}/messages
     * Get message history with pagination.
     */
    @GetMapping("/messages")
    public ResponseEntity<Page<SkillMessage>> getMessages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // Verify session exists
        try {
            sessionService.getSession(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<SkillMessage> messages = messageService.getMessageHistory(sessionId, pageable);
        return ResponseEntity.ok(messages);
    }

    /**
     * POST /api/skill/sessions/{sessionId}/send-to-im
     * Send selected text content to the IM chat associated with this session.
     */
    @PostMapping("/send-to-im")
    public ResponseEntity<Map<String, Object>> sendToIm(
            @PathVariable Long sessionId,
            @RequestBody SendToImRequest request) {

        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "Content is required"));
        }

        // Determine the IM chatId: from request or from session
        String chatId = request.getChatId();
        if (chatId == null || chatId.isBlank()) {
            try {
                SkillSession session = sessionService.getSession(sessionId);
                chatId = session.getImChatId();
            } catch (IllegalArgumentException e) {
                return ResponseEntity.notFound().build();
            }
        }

        if (chatId == null || chatId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "No IM chat ID associated with this session"));
        }

        boolean success = imMessageService.sendMessage(chatId, request.getContent());

        if (success) {
            log.info("Sent content to IM: sessionId={}, chatId={}, contentLength={}",
                    sessionId, chatId, request.getContent().length());
            return ResponseEntity.ok(Map.of("success", true));
        } else {
            log.error("Failed to send content to IM: sessionId={}, chatId={}", sessionId, chatId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Failed to send message to IM"));
        }
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

    @Data
    public static class SendMessageRequest {
        private String content;
    }

    @Data
    public static class SendToImRequest {
        private String content;
        private String chatId;
    }
}
