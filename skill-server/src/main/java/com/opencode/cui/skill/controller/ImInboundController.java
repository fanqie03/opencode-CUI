package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.InboundProcessingService;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 入站消息控制器。
 * 接收来自 IM 平台（WeLink）的用户消息，经过校验后委派给 {@link InboundProcessingService} 处理。
 *
 * <p>职责：参数校验（仅 IM 域 + 仅文本消息） → 委派处理 → 返回响应。
 * 核心处理逻辑（助手解析、在线检查、上下文注入、session 管理、Gateway 转发）
 * 已提取到 {@link InboundProcessingService}，供 IM 和外部渠道复用。
 */
@Slf4j
@RestController
@RequestMapping("/api/inbound")
public class ImInboundController {

    private final InboundProcessingService processingService;

    public ImInboundController(InboundProcessingService processingService) {
        this.processingService = processingService;
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
                "[ENTRY] ImInboundController.receiveMessage: domain={}, sessionType={}, sessionId={}, assistant={}, msgType={}, businessExtParam={}",
                request != null ? request.businessDomain() : null,
                request != null ? request.sessionType() : null,
                request != null ? request.sessionId() : null,
                request != null ? request.assistantAccount() : null,
                request != null ? request.msgType() : null,
                request != null ? request.businessExtParam() : null);

        // ========== 参数校验（控制器级别：仅 IM 域 + 仅文本消息） ==========
        String validationError = validate(request);
        if (validationError != null) {
            log.warn("[SKIP] ImInboundController.receiveMessage: reason=validation_failed, error={}, sessionId={}",
                    validationError, request != null ? request.sessionId() : null);
            return ResponseEntity.badRequest().body(ApiResponse.error(400, validationError));
        }

        // ========== 委派给 InboundProcessingService 处理 ==========
        InboundResult result = processingService.processChat(
                request.businessDomain(),
                request.sessionType(),
                request.sessionId(),
                request.assistantAccount(),
                request.senderUserAccount(),
                request.content(),
                request.msgType(),
                request.imageUrl(),
                request.chatHistory(),
                "IM",
                request.businessExtParam());

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        if (!result.success()) {
            log.warn("[EXIT] ImInboundController.receiveMessage: reason=processing_failed, code={}, message={}, durationMs={}",
                    result.code(), result.message(), elapsedMs);
            return ResponseEntity.ok(ApiResponse.error(result.code(), result.message()));
        }

        log.info("[EXIT] ImInboundController.receiveMessage: durationMs={}", elapsedMs);
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
