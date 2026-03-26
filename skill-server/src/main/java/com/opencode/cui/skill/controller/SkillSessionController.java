package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.GatewayRelayService;

import com.opencode.cui.skill.service.PayloadBuilder;
import com.opencode.cui.skill.service.ProtocolUtils;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillSessionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话管理控制器。
 * 提供会话的创建、查询、关闭和中止等 RESTful 接口，
 * 操作同时会向 AI-Gateway 发送相应的 invoke 命令。
 */
@Slf4j
@RestController
@RequestMapping("/api/skill/sessions")
public class SkillSessionController {

    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final SessionAccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    public SkillSessionController(SkillSessionService sessionService,
            GatewayRelayService gatewayRelayService,
            SessionAccessControlService accessControlService,
            ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /api/skill/sessions
     * 创建新的 Skill 会话，同时指示 AI-Gateway 创建对应的 OpenCode 会话。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SkillSession>> createSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @RequestBody CreateSessionRequest request) {
        long start = System.nanoTime();
        log.info("[ENTRY] createSession: ak={}, userId={}", request.getAk(), userIdCookie);
        String resolvedUserId = accessControlService.requireUserId(userIdCookie);

        SkillSession session = sessionService.createSession(
                resolvedUserId,
                request.getAk(),
                request.getTitle(),
                request.getBusinessSessionDomain(),
                request.getBusinessSessionType(),
                request.getBusinessSessionId(),
                request.getAssistantAccount());

        if (request.getAk() != null) {
            gatewayRelayService.sendInvokeToGateway(
                    new InvokeCommand(request.getAk(),
                            resolvedUserId,
                            session.getId().toString(),
                            GatewayActions.CREATE_SESSION,
                            PayloadBuilder.buildPayload(objectMapper,
                                    request.getTitle() != null && !request.getTitle().isBlank()
                                            ? Map.of("title", request.getTitle())
                                            : Map.of())));
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] createSession: sessionId={}, durationMs={}", session.getId(), elapsedMs);
        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    /**
     * GET /api/skill/sessions
     * 分页查询用户的会话列表。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<SkillSession>>> listSessions(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ak,
            @RequestParam(required = false) String businessSessionDomain,
            @RequestParam(required = false) String businessSessionType,
            @RequestParam(required = false) String businessSessionId,
            @RequestParam(required = false) String assistantAccount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String resolvedUserId = accessControlService.requireUserId(userIdCookie);
        PageResult<SkillSession> sessions = sessionService.listSessions(
                new com.opencode.cui.skill.model.SessionListQuery(
                        resolvedUserId, ak, businessSessionDomain, businessSessionType,
                        businessSessionId, assistantAccount, status, page, size));
        return ResponseEntity.ok(ApiResponse.ok(sessions));
    }

    /**
     * GET /api/skill/sessions/{id}
     * 按 ID 查询单个会话。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SkillSession>> getSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String id) {
        Long sessionId = ProtocolUtils.parseSessionId(id);
        if (sessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }
        SkillSession session = accessControlService.requireSessionAccess(sessionId, userIdCookie);
        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    /**
     * DELETE /api/skill/sessions/{id}
     * 关闭会话。如果存在 tool session，同时向 AI-Gateway 发送 close_session 命令。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> closeSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String id) {
        Long sessionId = ProtocolUtils.parseSessionId(id);
        if (sessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }
        log.info("[ENTRY] closeSession: sessionId={}", id);
        SkillSession session = accessControlService.requireSessionAccess(sessionId, userIdCookie);

        if (session.getAk() != null && session.getToolSessionId() != null) {
            gatewayRelayService.sendInvokeToGateway(
                    new InvokeCommand(session.getAk(),
                            session.getUserId(),
                            session.getId().toString(),
                            GatewayActions.CLOSE_SESSION,
                            PayloadBuilder.buildPayload(objectMapper,
                                    Map.of("toolSessionId", session.getToolSessionId()))));
        }
        sessionService.closeSession(sessionId);
        log.info("[EXIT] closeSession: sessionId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "closed", "welinkSessionId", id)));
    }

    /**
     * POST /api/skill/sessions/{id}/abort
     * 中止会话。向 AI-Gateway 发送 abort_session 以停止进行中的 AI 操作，但保留会话可复用。
     */
    @PostMapping("/{id}/abort")
    public ResponseEntity<ApiResponse<Map<String, Object>>> abortSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String id) {
        Long sessionId = ProtocolUtils.parseSessionId(id);
        if (sessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }
        SkillSession session = accessControlService.requireSessionAccess(sessionId, userIdCookie);

        if (session.getStatus() == SkillSession.Status.CLOSED) {
            return ResponseEntity.ok(ApiResponse.error(409, "Session is already closed"));
        }

        // 如果 toolSessionId 和 ak 存在，向 AI-Gateway 发送 abort_session 命令
        log.info("[ENTRY] abortSession: sessionId={}", id);
        if (session.getAk() != null && session.getToolSessionId() != null) {
            gatewayRelayService.sendInvokeToGateway(
                    new InvokeCommand(session.getAk(),
                            session.getUserId(),
                            session.getId().toString(),
                            GatewayActions.ABORT_SESSION,
                            PayloadBuilder.buildPayload(objectMapper,
                                    Map.of("toolSessionId", session.getToolSessionId()))));
        }

        log.info("[EXIT] abortSession: sessionId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "aborted", "welinkSessionId", id)));
    }

    /** 创建会话请求体。 */
    @Data
    public static class CreateSessionRequest {
        private String ak;
        private String title;
        private String businessSessionDomain;
        private String businessSessionType;
        private String businessSessionId;
        private String assistantAccount;
    }
}
