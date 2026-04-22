package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;

import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.AssistantOfflineMessageProvider;
import com.opencode.cui.skill.service.ProtocolUtils;
import com.opencode.cui.skill.service.PayloadBuilder;
import com.opencode.cui.skill.service.ProtocolMessageMapper;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.GatewayMessageRouter;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
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

import java.util.LinkedHashMap;
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
    private final GatewayApiClient gatewayApiClient;
    private final AssistantIdProperties assistantIdProperties;
    private final ImMessageService imMessageService;
    private final ObjectMapper objectMapper;
    private final SessionAccessControlService accessControlService;
    private final GatewayMessageRouter messageRouter;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final AssistantOfflineMessageProvider offlineMessageProvider;

    public SkillMessageController(SkillMessageService messageService,
            SkillSessionService sessionService,
            GatewayRelayService gatewayRelayService,
            GatewayApiClient gatewayApiClient,
            AssistantIdProperties assistantIdProperties,
            ImMessageService imMessageService,
            ObjectMapper objectMapper,
            SessionAccessControlService accessControlService,
            GatewayMessageRouter messageRouter,
            AssistantInfoService assistantInfoService,
            AssistantScopeDispatcher scopeDispatcher,
            AssistantOfflineMessageProvider offlineMessageProvider) {
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.gatewayApiClient = gatewayApiClient;
        this.assistantIdProperties = assistantIdProperties;
        this.imMessageService = imMessageService;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.messageRouter = messageRouter;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.offlineMessageProvider = offlineMessageProvider;
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
        SkillMessage message = messageService.saveUserMessage(numericSessionId, request.getContent());

        // 广播用户消息到同会话所有 WebSocket 连接（纯广播，不持久化——消息已由 saveUserMessage 入库）
        messageRouter.broadcastStreamMessage(
                sessionId, session.getUserId(),
                StreamMessage.userMessage(
                        message.getMessageId(),
                        message.getSeq(),
                        message.getContent(),
                        sessionId));

        // 路由到 AI-Gateway
        routeToGateway(session, sessionId, numericSessionId, request, userIdCookie);

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
            Long numericSessionId, SendMessageRequest request, String userIdCookie) {
        if (session.getAk() == null) {
            log.warn("[SKIP] SkillMessageController.routeToGateway: reason=no_agent, sessionId={}", sessionId);
            return;
        }

        // Agent 在线检查：开关开启时，业务助手跳过检查
        AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(session.getAk()));
        if (assistantIdProperties.isEnabled() && scopeStrategy.requiresOnlineCheck()) {
            AgentSummary agent = gatewayApiClient.getAgentByAk(session.getAk());
            if (agent == null) {
                // Agent 离线：保存系统错误消息 + WebSocket 广播
                log.warn("[SKIP] SkillMessageController.routeToGateway: reason=agent_offline, sessionId={}, ak={}",
                        sessionId, session.getAk());
                try {
                    messageService.saveSystemMessage(numericSessionId, offlineMessageProvider.get());
                } catch (Exception e) {
                    log.error("Failed to persist agent_offline message for session {}: {}", sessionId, e.getMessage());
                }
                gatewayRelayService.publishProtocolMessage(sessionId, StreamMessage.builder()
                        .type(StreamMessage.Types.ERROR)
                        .error(offlineMessageProvider.get())
                        .build());
                return;
            }
            // Agent 在线但 toolType 不匹配目标值：跳过 assistantId 相关逻辑，正常发送
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
            // 使用子 session 真实 ID（如果是 subagent 的 question reply）
            String targetToolSessionId = request.getSubagentSessionId() != null
                    ? request.getSubagentSessionId()
                    : session.getToolSessionId();
            payload = PayloadBuilder.buildPayload(objectMapper, Map.of(
                    "answer", request.getContent(),
                    "toolCallId", request.getToolCallId(),
                    "toolSessionId", targetToolSessionId));
        } else {
            action = GatewayActions.CHAT;
            Map<String, String> payloadFields = new LinkedHashMap<>();
            payloadFields.put("text", request.getContent());
            payloadFields.put("toolSessionId", session.getToolSessionId());
            // 优先使用实际操作用户（cookie），兜底用 session 创建人
            payloadFields.put("sendUserAccount",
                    userIdCookie != null && !userIdCookie.isBlank() ? userIdCookie : session.getUserId());
            payloadFields.put("assistantAccount", session.getAssistantAccount());
            payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
            payload = PayloadBuilder.buildPayload(objectMapper, payloadFields);
        }

        log.info("SkillMessageController.routeToGateway: sessionId={}, action={}, ak={}",
                sessionId, action, session.getAk());
        String effectiveUserId = userIdCookie != null && !userIdCookie.isBlank()
                ? userIdCookie : session.getUserId();
        gatewayRelayService.sendInvokeToGateway(
                new InvokeCommand(session.getAk(), effectiveUserId, sessionId, action, payload));
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

        // Agent 在线检查：云端助手永远在线（跳过），个人助手需要检查
        AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(session.getAk()));
        if (assistantIdProperties.isEnabled() && scopeStrategy.requiresOnlineCheck()) {
            AgentSummary agent = gatewayApiClient.getAgentByAk(session.getAk());
            if (agent == null) {
                log.warn("[SKIP] replyPermission: reason=agent_offline, sessionId={}, ak={}",
                        sessionId, session.getAk());
                return ResponseEntity.ok(ApiResponse.error(503, offlineMessageProvider.get()));
            }
        }

        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(500, "No toolSessionId available"));
        }

        // 使用子 session 真实 ID（如果是 subagent 的 permission）
        String targetToolSessionId = request.getSubagentSessionId() != null
                ? request.getSubagentSessionId()
                : session.getToolSessionId();

        String payload = PayloadBuilder.buildPayload(objectMapper, Map.of(
                "permissionId", permId,
                "response", request.getResponse(),
                "toolSessionId", targetToolSessionId));

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
                .subagentSessionId(request.getSubagentSessionId())
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
        /** 可选：subagent 的真实 toolSessionId，用于将 question reply 路由到正确的子会话 */
        private String subagentSessionId;
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
        /** 可选：subagent 的真实 toolSessionId，用于将 permission reply 路由到正确的子会话 */
        private String subagentSessionId;
    }
}
