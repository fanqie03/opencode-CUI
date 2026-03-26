package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;
import com.opencode.cui.skill.service.MessagePersistenceService;

import com.opencode.cui.skill.service.ProtocolUtils;
import com.opencode.cui.skill.service.PayloadBuilder;
import com.opencode.cui.skill.service.ProtocolMessageMapper;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息操作控制器。
 * 提供发送消息、查询历史、转发到 IM、权限回复等接口，
 * 操作均基于指定的 session 上下文。
 */
@Slf4j
@RestController
@RequestMapping("/api/skill/sessions/{sessionId}")
public class SkillMessageController {

    /** 合法的权限响应值集合 */
    private static final Set<String> VALID_PERMISSION_RESPONSES = Set.of("once", "always", "reject");
    private static final int MAX_HISTORY_PAGE_SIZE = 200;

    private final SkillMessageService messageService;
    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final ImMessageService imMessageService;
    private final ObjectMapper objectMapper;
    private final MessagePersistenceService messagePersistenceService;
    private final SessionAccessControlService accessControlService;

    public SkillMessageController(SkillMessageService messageService,
            SkillSessionService sessionService,
            GatewayRelayService gatewayRelayService,
            ImMessageService imMessageService,
            ObjectMapper objectMapper,
            MessagePersistenceService messagePersistenceService,
            SessionAccessControlService accessControlService) {
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.imMessageService = imMessageService;
        this.objectMapper = objectMapper;
        this.messagePersistenceService = messagePersistenceService;
        this.accessControlService = accessControlService;
    }

    /**
     * POST /api/skill/sessions/{sessionId}/messages
     * 发送用户消息。持久化消息并通过 AI-Gateway 触发 AI 调用。
     */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<ProtocolMessageView>> sendMessage(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {

        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(400, "Content is required"));
        }

        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }

        log.info("[ENTRY] SkillMessageController.sendMessage: sessionId={}, contentLength={}", sessionId,
                request.getContent() != null ? request.getContent().length() : 0);
        long start = System.nanoTime();

        SkillSession session = accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.ok(ApiResponse.error(409, "Session is closed"));
        }

        // 持久化用户消息
        messagePersistenceService.finalizeActiveAssistantTurn(numericSessionId);
        SkillMessage message = messageService.saveUserMessage(numericSessionId, request.getContent());
        messagePersistenceService.markPendingUserMessage(numericSessionId);

        // 路由到 AI-Gateway
        routeToGateway(session, sessionId, numericSessionId, request);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] SkillMessageController.sendMessage: sessionId={}, ak={}, durationMs={}", sessionId,
                session.getAk(), elapsedMs);
        return ResponseEntity.ok(ApiResponse.ok(ProtocolMessageMapper.toProtocolMessage(
                message, List.of(), objectMapper)));
    }

    /**
     * 根据会话状态和请求类型将用户消息路由到 AI-Gateway。
     */
    private void routeToGateway(SkillSession session, String sessionId,
            Long numericSessionId, SendMessageRequest request) {
        if (session.getAk() == null) {
            log.warn("[SKIP] SkillMessageController.routeToGateway: reason=no_agent, sessionId={}", sessionId);
            return;
        }

        if (session.getToolSessionId() == null) {
            log.info(
                    "[SKIP] SkillMessageController.routeToGateway: reason=no_toolSessionId, sessionId={}, triggering rebuild",
                    sessionId);
            gatewayRelayService.rebuildToolSession(sessionId, session, request.getContent());
            return;
        }

        String action;
        String payload;
        if (request.getToolCallId() != null && !request.getToolCallId().isBlank()) {
            action = GatewayActions.QUESTION_REPLY;
            payload = PayloadBuilder.buildPayload(objectMapper, Map.of(
                    "answer", request.getContent(),
                    "toolCallId", request.getToolCallId(),
                    "toolSessionId", session.getToolSessionId()));
        } else {
            action = GatewayActions.CHAT;
            payload = PayloadBuilder.buildPayload(objectMapper, Map.of(
                    "text", request.getContent(),
                    "toolSessionId", session.getToolSessionId()));
        }

        log.info("SkillMessageController.routeToGateway: sessionId={}, action={}, ak={}",
                sessionId, action, session.getAk());
        gatewayRelayService.sendInvokeToGateway(
                new InvokeCommand(session.getAk(), session.getUserId(), sessionId, action, payload));
    }

    /**
     * GET /api/skill/sessions/{sessionId}/messages
     * 分页查询消息历史。
     */
    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<PageResult<ProtocolMessageView>>> getMessages(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (size <= 0 || size > MAX_HISTORY_PAGE_SIZE) {
            return ResponseEntity.ok(ApiResponse.error(400, "Size must be between 1 and " + MAX_HISTORY_PAGE_SIZE));
        }

        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }

        log.info("[ENTRY] SkillMessageController.getMessages: sessionId={}, page={}, size={}",
                sessionId, page, size);
        long start = System.nanoTime();

        accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        PageResult<ProtocolMessageView> result = messageService.getMessageHistoryWithParts(
                numericSessionId, page, size);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] SkillMessageController.getMessages: sessionId={}, page={}, size={}, items={}, durationMs={}",
                sessionId, page, size, result.getContent() != null ? result.getContent().size() : 0, elapsedMs);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/messages/history")
    public ResponseEntity<ApiResponse<MessageHistoryResult<ProtocolMessageView>>> getCursorMessages(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer beforeSeq,
            @RequestParam(defaultValue = "50") int size) {
        if (size <= 0 || size > MAX_HISTORY_PAGE_SIZE) {
            return ResponseEntity.ok(ApiResponse.error(400, "Size must be between 1 and " + MAX_HISTORY_PAGE_SIZE));
        }

        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }

        log.info("[ENTRY] SkillMessageController.getCursorMessages: sessionId={}, beforeSeq={}, size={}",
                sessionId, beforeSeq, size);
        long start = System.nanoTime();

        accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        MessageHistoryResult<ProtocolMessageView> result = messageService.getCursorMessageHistoryWithParts(
                numericSessionId, beforeSeq, size);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info(
                "[EXIT] SkillMessageController.getCursorMessages: sessionId={}, beforeSeq={}, size={}, items={}, hasMore={}, durationMs={}",
                sessionId,
                beforeSeq,
                size,
                result.getContent() != null ? result.getContent().size() : 0,
                result.getHasMore(),
                elapsedMs);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * POST /api/skill/sessions/{sessionId}/send-to-im
     * 将选定的文本内容发送到当前会话关联的 IM 聊天。
     */
    @PostMapping("/send-to-im")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendToIm(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String sessionId,
            @RequestBody SendToImRequest request) {

        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(400, "Content is required"));
        }

        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }

        long start = System.nanoTime();
        log.info("[ENTRY] SkillMessageController.sendToIm: sessionId={}, contentLength={}",
                sessionId, request.getContent().length());

        SkillSession session;
        session = accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        String chatId = request.getChatId();
        if (chatId == null || chatId.isBlank()) {
            chatId = session.getBusinessSessionId();
        }

        if (chatId == null || chatId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(400, "No IM chat ID associated with this session"));
        }

        boolean success = imMessageService.sendMessage(chatId, request.getContent());

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (success) {
            log.info("[EXIT] SkillMessageController.sendToIm: sessionId={}, chatId={}, contentLength={}, durationMs={}",
                    sessionId, chatId, request.getContent().length(), elapsedMs);
            return ResponseEntity.ok(ApiResponse.ok(Map.of("success", true)));
        } else {
            log.error("[ERROR] SkillMessageController.sendToIm: sessionId={}, chatId={}, durationMs={}",
                    sessionId, chatId, elapsedMs);
            return ResponseEntity.ok(ApiResponse.error(500, "Failed to send message to IM"));
        }
    }

    /**
     * POST /api/skill/sessions/{sessionId}/permissions/{permId}
     * 回复权限请求。合法响应值："once"、"always"、"reject"。
     * 将回复路由到 AI-Gateway → PCAgent → OpenCode 执行。
     */
    @PostMapping("/permissions/{permId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replyPermission(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String sessionId,
            @PathVariable String permId,
            @RequestBody PermissionReplyRequest request) {

        if (request.getResponse() == null || request.getResponse().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(400, "Field 'response' is required"));
        }
        if (!VALID_PERMISSION_RESPONSES.contains(request.getResponse())) {
            return ResponseEntity.ok(
                    ApiResponse.error(400, "Invalid response value. Must be one of: once, always, reject"));
        }

        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }

        long start = System.nanoTime();
        log.info("[ENTRY] SkillMessageController.replyPermission: sessionId={}, permId={}, response={}",
                sessionId, permId, request.getResponse());

        SkillSession session;
        session = accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.ok(ApiResponse.error(409, "Session is closed"));
        }

        if (session.getAk() == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "No agent associated with this session"));
        }

        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(500, "No toolSessionId available"));
        }

        String payload = PayloadBuilder.buildPayload(objectMapper, Map.of(
                "permissionId", permId,
                "response", request.getResponse(),
                "toolSessionId", session.getToolSessionId()));

        // 向 AI-Gateway 发送 permission_reply invoke 命令
        gatewayRelayService.sendInvokeToGateway(
                new InvokeCommand(session.getAk(),
                        session.getUserId(),
                        sessionId,
                        GatewayActions.PERMISSION_REPLY,
                        payload));

        StreamMessage replyMessage = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .role("assistant")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId(permId)
                        .response(request.getResponse())
                        .build())
                .build();
        gatewayRelayService.publishProtocolMessage(sessionId, replyMessage);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] SkillMessageController.replyPermission: sessionId={}, permId={}, response={}, durationMs={}",
                sessionId, permId, request.getResponse(), elapsedMs);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "welinkSessionId", sessionId,
                "permissionId", permId,
                "response", request.getResponse())));
    }

    /** 发送消息请求体。 */
    @Data
    public static class SendMessageRequest {
        private String content;
        /** 可选：存在时路由到 question_reply 而非 chat */
        private String toolCallId;
    }

    /** 发送到 IM 请求体。 */
    @Data
    public static class SendToImRequest {
        private String content;
        private String chatId;
    }

    /** 权限回复请求体。 */
    @Data
    public static class PermissionReplyRequest {
        /** 合法值："once"、"always"、"reject" */
        private String response;
    }
}
