package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.ContextInjectionService;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.service.ImSessionManager;
import com.opencode.cui.skill.service.PayloadBuilder;
import com.opencode.cui.skill.service.SessionRebuildService;
import com.opencode.cui.skill.service.SkillMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 入站消息控制器。
 * 接收来自 IM 平台（WeLink）的用户消息，经过校验、助手解析、上下文注入后，
 * 路由到 AI Gateway 进行处理。
 *
 * 核心流程：
 * 1. 参数校验 → 2. 解析助手账号获取 ak 和 ownerWelinkId
 * 3. 上下文注入（群聊拼接历史） → 4. 查找/创建 session
 * 5. 持久化用户消息（仅单聊） → 6. 转发到 AI Gateway
 */
@Slf4j
@RestController
@RequestMapping("/api/inbound")
public class ImInboundController {

    /** Agent 离线提示消息 */
    private static final String AGENT_OFFLINE_MESSAGE = "任务下发失败，请检查助理是否离线，确保助理在线后重试";

    private final AssistantAccountResolverService resolverService; // 助手账号解析服务：assistantAccount → (ak, ownerWelinkId)
    private final AssistantIdProperties assistantIdProperties; // AssistantId 注入功能配置
    private final GatewayApiClient gatewayApiClient; // Gateway API 客户端：查询 Agent 在线状态
    private final ImSessionManager sessionManager; // IM 会话管理器：查找/创建 skill session
    private final ImOutboundService imOutboundService; // IM 出站消息服务：向 IM 发送消息
    private final ContextInjectionService contextInjectionService; // 上下文注入服务：群聊时将历史消息拼入 prompt
    private final GatewayRelayService gatewayRelayService; // Gateway 通信服务：通过 WebSocket 转发消息到 AI Gateway
    private final SkillMessageService messageService; // 消息持久化服务：保存用户/助手消息到数据库
    private final SessionRebuildService rebuildService; // 会话重建服务：预缓存消息供 session 重建后重发
    private final ObjectMapper objectMapper; // JSON 序列化工具

    public ImInboundController(
            AssistantAccountResolverService resolverService,
            AssistantIdProperties assistantIdProperties,
            GatewayApiClient gatewayApiClient,
            ImSessionManager sessionManager,
            ImOutboundService imOutboundService,
            ContextInjectionService contextInjectionService,
            GatewayRelayService gatewayRelayService,
            SkillMessageService messageService,
            SessionRebuildService rebuildService,
            ObjectMapper objectMapper) {
        this.resolverService = resolverService;
        this.assistantIdProperties = assistantIdProperties;
        this.gatewayApiClient = gatewayApiClient;
        this.sessionManager = sessionManager;
        this.imOutboundService = imOutboundService;
        this.contextInjectionService = contextInjectionService;
        this.gatewayRelayService = gatewayRelayService;
        this.messageService = messageService;
        this.rebuildService = rebuildService;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收 IM 入站消息的主接口。
     * 由 IM 平台通过 HTTP POST 调用，消息处理后异步返回结果（AI 回复通过出站服务推送）。
     *
     * @param request IM 消息请求体，包含业务域、会话类型、会话 ID、助手账号、消息内容等
     * @return 统一响应，code=0 表示消息已接收（不代表 AI 已回复）
     */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<Void>> receiveMessage(@RequestBody ImMessageRequest request) {
        long start = System.nanoTime();
        log.info(
                "[ENTRY] ImInboundController.receiveMessage: domain={}, sessionType={}, sessionId={}, assistant={}, msgType={}",
                request != null ? request.businessDomain() : null,
                request != null ? request.sessionType() : null,
                request != null ? request.sessionId() : null,
                request != null ? request.assistantAccount() : null,
                request != null ? request.msgType() : null);

        // ========== 第 2 步：参数校验 ==========
        String validationError = validate(request);
        if (validationError != null) {
            log.warn("[SKIP] ImInboundController.receiveMessage: reason=validation_failed, error={}, sessionId={}",
                    validationError, request != null ? request.sessionId() : null);
            return ResponseEntity.badRequest().body(ApiResponse.error(400, validationError));
        }

        // ========== 第 3 步：解析助手账号 → 获取 ak（应用密钥）和 ownerWelinkId（助手拥有者 ID）==========
        AssistantResolveResult resolveResult = resolverService.resolve(request.assistantAccount());
        if (resolveResult == null) {
            log.warn("[SKIP] ImInboundController.receiveMessage: reason=resolve_failed, assistantAccount={}",
                    request.assistantAccount());
            return ResponseEntity.ok(ApiResponse.error(404, "Invalid assistant account"));
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();
        log.info("ImInboundController.receiveMessage: resolved assistant={}, ak={}, ownerWelinkId={}",
                request.assistantAccount(), ak, ownerWelinkId);

        // ========== 第 3.5 步：Agent 在线检查（开关控制，先判断 toolType） ==========
        if (assistantIdProperties.isEnabled()) {
            if (gatewayApiClient.getAgentByAk(ak) == null) {
                log.warn("[SKIP] ImInboundController.receiveMessage: reason=agent_offline, ak={}, sessionType={}, sessionId={}",
                        ak, request.sessionType(), request.sessionId());
                // 通过 IM 回复离线提示
                imOutboundService.sendTextToIm(
                        request.sessionType(), request.sessionId(),
                        AGENT_OFFLINE_MESSAGE, request.assistantAccount());
                // 单聊 + 已有 session：保存系统消息到数据库
                if (SkillSession.SESSION_TYPE_DIRECT.equalsIgnoreCase(request.sessionType())) {
                    SkillSession existingSession = sessionManager.findSession(
                            request.businessDomain(), request.sessionType(), request.sessionId(), ak);
                    if (existingSession != null) {
                        try {
                            messageService.saveSystemMessage(existingSession.getId(), AGENT_OFFLINE_MESSAGE);
                        } catch (Exception e) {
                            log.error("Failed to persist agent_offline message for IM session {}: {}",
                                    existingSession.getId(), e.getMessage());
                        }
                    }
                }
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.info("[EXIT] ImInboundController.receiveMessage: reason=agent_offline, ak={}, durationMs={}", ak, elapsedMs);
                return ResponseEntity.ok(ApiResponse.ok(null));
            }
        }

        // ========== 第 4 步：上下文注入（群聊场景下将 chatHistory 拼接到 prompt）==========
        String prompt = contextInjectionService.resolvePrompt(
                request.sessionType(),
                request.content(),
                request.chatHistory());
        log.debug("Context injection resolved: sessionType={}, promptLength={}",
                request.sessionType(), prompt != null ? prompt.length() : 0);

        // ========== 第 5 步：查找已有 session ==========
        SkillSession session = sessionManager.findSession(
                request.businessDomain(),
                request.sessionType(),
                request.sessionId(),
                ak);

        // 情况 A：session 不存在 → 异步创建 session 并缓存待发消息，Gateway 创建完成后自动重发
        if (session == null) {
            log.info("No existing session found, creating async: domain={}, sessionType={}, sessionId={}, ak={}",
                    request.businessDomain(), request.sessionType(), request.sessionId(), ak);
            sessionManager.createSessionAsync(
                    request.businessDomain(),
                    request.sessionType(),
                    request.sessionId(),
                    ak,
                    ownerWelinkId,
                    request.assistantAccount(),
                    prompt);
            return ResponseEntity.ok(ApiResponse.ok(null));
        }

        // 情况 B：session 存在但 toolSessionId 尚未就绪 → 请求 Gateway 重新创建 tool session
        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            log.info("Session exists but toolSessionId not ready, requesting rebuild: skillSessionId={}",
                    session.getId());
            sessionManager.requestToolSession(session, prompt);
            return ResponseEntity.ok(ApiResponse.ok(null));
        }

        // ========== 情况 C：session 就绪，转发消息到 AI Gateway ==========
        log.info("Session ready, forwarding to gateway: skillSessionId={}, toolSessionId={}, sessionType={}",
                session.getId(), session.getToolSessionId(), request.sessionType());

        // 单聊场景：保存用户消息，标记待处理状态
        if (session.isImDirectSession()) {
            log.info("Direct session: persisting user message turn, skillSessionId={}", session.getId());
            messageService.saveUserMessage(session.getId(), request.content()); // 保存本轮用户消息
        }

        // 预缓存消息到 Redis List：CHAT 发送后若 Agent 报 session_not_found 触发重建，
        // 重建完成后从 Redis List 取出消息逐条重发（群聊不存 DB，依赖此机制恢复消息）
        rebuildService.appendPendingMessage(String.valueOf(session.getId()), prompt);

        // 构建 invoke payload 并发送到 AI Gateway
        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("text", prompt); // 用户消息（可能已注入群聊历史）
        payloadFields.put("toolSessionId", session.getToolSessionId()); // Gateway 侧的 session 标识
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                session.getAk(), // 应用密钥
                ownerWelinkId, // 助手拥有者 ID（用于 Gateway 鉴权）
                String.valueOf(session.getId()), // skill session ID
                GatewayActions.CHAT, // 动作类型：聊天
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] ImInboundController.receiveMessage: skillSessionId={}, ak={}, action={}, durationMs={}",
                session.getId(), ak, GatewayActions.CHAT, elapsedMs);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * 校验 IM 消息请求参数。
     * 依次检查：请求体 → businessDomain → sessionType → sessionId → assistantAccount →
     * content → msgType。
     *
     * @return 校验错误描述；通过校验时返回 null
     */
    private String validate(ImMessageRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (request.businessDomain() == null || request.businessDomain().isBlank()) {
            return "businessDomain is required";
        }
        if (!SkillSession.DOMAIN_IM.equalsIgnoreCase(request.businessDomain())) {
            return "Only IM inbound is supported"; // 当前仅支持 IM 域
        }
        if (request.sessionType() == null || request.sessionType().isBlank()) {
            return "sessionType is required";
        }
        if (!SkillSession.SESSION_TYPE_GROUP.equalsIgnoreCase(request.sessionType())
                && !SkillSession.SESSION_TYPE_DIRECT.equalsIgnoreCase(request.sessionType())) {
            return "Invalid sessionType"; // 仅支持 direct（单聊）和 group（群聊）
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return "sessionId is required";
        }
        if (request.assistantAccount() == null || request.assistantAccount().isBlank()) {
            return "assistantAccount is required";
        }
        if (request.content() == null || request.content().isBlank()) {
            return "content is required";
        }
        if (!request.isTextMessage()) {
            return "Only text messages are supported"; // 当前仅支持文本消息
        }
        return null;
    }
}
