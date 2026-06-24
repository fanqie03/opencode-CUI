package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.service.AllowedSlashCommandsResolver;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;

import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.AssistantAvailabilityService;
import com.opencode.cui.skill.service.AssistantOfflineMessageProvider;
import com.opencode.cui.skill.service.ProtocolUtils;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.GatewayMessageRouter;
import com.opencode.cui.skill.service.SkillMessageFlowService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.SysConfigService;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final ImMessageService imMessageService;
    private final ObjectMapper objectMapper;
    private final SessionAccessControlService accessControlService;
    private final SkillMessageFlowService flowService;
    private final SysConfigService sysConfigService;

    @Autowired
    public SkillMessageController(SkillMessageService messageService,
                                  ImMessageService imMessageService,
                                  ObjectMapper objectMapper,
                                  SessionAccessControlService accessControlService,
                                  SkillMessageFlowService flowService,
                                  SysConfigService sysConfigService) {
        this.messageService = messageService;
        this.imMessageService = imMessageService;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.flowService = flowService;
        this.sysConfigService = sysConfigService;
    }

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
            AssistantOfflineMessageProvider offlineMessageProvider,
            AssistantAvailabilityService availabilityService,
            AssistantAccountResolverService assistantAccountResolverService,
            DefaultAssistantRuleService ruleService,
            AllowedSlashCommandsResolver allowedSlashCommandsResolver,
            MessagePersistenceService persistenceService,
            ApplicationEventPublisher eventPublisher,
            SysConfigService sysConfigService,
            MessageTurnLifecycle messageTurnLifecycle) {
        this(messageService, imMessageService, objectMapper, accessControlService,
                new SkillMessageFlowService(
                        messageService, gatewayRelayService, objectMapper, messageRouter, assistantIdProperties,
                        assistantInfoService, scopeDispatcher, availabilityService, assistantAccountResolverService,
                        ruleService, allowedSlashCommandsResolver, eventPublisher, persistenceService, messageTurnLifecycle),
                sysConfigService);
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

        log.info("[ENTRY] SkillMessageController.sendMessage: sessionId={}, contentLength={}, businessExtParam={}",
                sessionId,
                request.getContent() != null ? request.getContent().length() : 0,
                request.getBusinessExtParam());
        long start = System.nanoTime();

        SkillSession session = accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.ok(ApiResponse.error(409, "Session is closed"));
        }

        ApiResponse<ProtocolMessageView> response = flowService.sendMessage(
                session, sessionId, numericSessionId,
                new SkillMessageFlowService.SendMessageCommand(
                        request.getContent(),
                        request.getToolCallId(),
                        request.getSubagentSessionId(),
                        request.getQuestionId(),
                        request.getBusinessExtParam()),
                userIdCookie);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] SkillMessageController.sendMessage: sessionId={}, ak={}, durationMs={}", sessionId,
                session.getAk(), elapsedMs);
        return ResponseEntity.ok(response);
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

    /** content 最大长度（字符数） */
    private static final int SEND_TO_IM_MAX_CONTENT_LENGTH = 4000;

    /**
     * POST /api/skill/sessions/{sessionId}/send-to-im
     * 将选定的文本内容发送到当前会话关联的 IM 聊天。
     *
     * <p>目标 / 发送人均由后端从 {@code session.businessSessionId} +
     * cookie {@code userId} 解析得出，请求体仅含 {@code content}。
     */
    @PostMapping("/send-to-im")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendToIm(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String sessionId,
            @RequestBody SendToImRequest request) {

        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(400, "Content is required"));
        }
        if (request.getContent().length() > SEND_TO_IM_MAX_CONTENT_LENGTH) {
            return ResponseEntity.ok(ApiResponse.error(
                    400, "Content too long (max " + SEND_TO_IM_MAX_CONTENT_LENGTH + " chars)"));
        }

        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }

        long start = System.nanoTime();
        log.info("[ENTRY] SkillMessageController.sendToIm: sessionId={}, contentLength={}",
                sessionId, request.getContent().length());

        // requireSessionAccess 内部已 requireUserId（缺失 → ProtocolException 400 "userId is required"）
        // 并校验 cookie userId == session.userId（不匹配 → 403 "Session access denied"）
        SkillSession session = accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        java.util.Optional<com.opencode.cui.skill.model.BusinessSessionId> parsedOpt =
                com.opencode.cui.skill.model.BusinessSessionId.parse(session.getBusinessSessionId());
        if (parsedOpt.isEmpty()) {
            log.warn("[REJECT] sendToIm invalid_business_session_id: sessionId={}, businessSessionId={}",
                    sessionId, session.getBusinessSessionId());
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid businessSessionId format"));
        }
        com.opencode.cui.skill.model.BusinessSessionId parsed = parsedOpt.get();

        String cookieAccount = userIdCookie == null ? null : userIdCookie.trim();
        if (!parsed.senderAccount().equals(cookieAccount)) {
            log.warn("[REJECT] sendToIm sender_mismatch: sessionId={}, cookieAccount={}, expectedSender={}, businessSessionId={}",
                    sessionId, cookieAccount, parsed.senderAccount(), session.getBusinessSessionId());
            return ResponseEntity.ok(ApiResponse.error(403, "Sender mismatch"));
        }

        String targetType = parsed.targetType().name().toLowerCase();

        // 读取 msg_ext 配置
        String msgExt = null;
        if ("1".equals(sysConfigService.getValue("msg_ext", "enabled"))) {
            msgExt = sysConfigService.getValue("msg_ext", "content");
        }

        boolean success = imMessageService.sendMessage(
                targetType, parsed.targetId(), parsed.senderAccount(), request.getContent(), msgExt);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (success) {
            log.info("[EXIT] SkillMessageController.sendToIm: sessionId={}, targetType={}, targetId={}, senderAccount={}, contentLength={}, durationMs={}",
                    sessionId, targetType, parsed.targetId(), parsed.senderAccount(),
                    request.getContent().length(), elapsedMs);
            return ResponseEntity.ok(ApiResponse.ok(Map.of("success", true)));
        } else {
            log.error("[ERROR] SkillMessageController.sendToIm: sessionId={}, targetType={}, targetId={}, durationMs={}",
                    sessionId, targetType, parsed.targetId(), elapsedMs);
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
        log.info("[ENTRY] SkillMessageController.replyPermission: sessionId={}, permId={}, response={}, businessExtParam={}",
                sessionId, permId, request.getResponse(), request.getBusinessExtParam());

        SkillSession session;
        session = accessControlService.requireSessionAccess(numericSessionId, userIdCookie);

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.ok(ApiResponse.error(409, "Session is closed"));
        }

        ApiResponse<Map<String, Object>> response = flowService.replyPermission(
                session, sessionId, permId,
                new SkillMessageFlowService.PermissionReplyCommand(
                        request.getResponse(),
                        request.getSubagentSessionId(),
                        request.getBusinessExtParam()),
                userIdCookie);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] SkillMessageController.replyPermission: sessionId={}, permId={}, response={}, durationMs={}",
                sessionId, permId, request.getResponse(), elapsedMs);

        return ResponseEntity.ok(response);
    }

    /** 发送消息请求体。 */
    @Data
    public static class SendMessageRequest {
        private String content;
        /** 可选：存在时路由到 question_reply 而非 chat */
        private String toolCallId;
        /** 可选：subagent 的真实 toolSessionId，用于将 question reply 路由到正确的子会话 */
        private String subagentSessionId;
        /**
         * 可选：opencode question request id（personal scope 快路径）。
         * 非空时 SS 透传给 plugin，新版 plugin 直接 POST /question/{requestID}/reply。
         * 缺失/空白走老路径（plugin GET /question 反查）。
         */
        private String questionId;
        /** 可选：业务扩展参数，透传到云端 extParameters.businessExtParam */
        private JsonNode businessExtParam;
    }

    /** 发送到 IM 请求体。仅含 {@code content}；目标 + 发送人由后端解析。 */
    @Data
    public static class SendToImRequest {
        private String content;
    }

    /** 权限回复请求体。 */
    @Data
    public static class PermissionReplyRequest {
        /** 合法值："once"、"always"、"reject" */
        private String response;
        /** 可选：subagent 的真实 toolSessionId，用于将 permission reply 路由到正确的子会话 */
        private String subagentSessionId;
        /** 可选：业务扩展参数，透传到云端 extParameters.businessExtParam */
        private JsonNode businessExtParam;
    }
}
