package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ReadReportRequest;
import com.opencode.cui.skill.model.ReadReportResponse;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.UnreadRequest;
import com.opencode.cui.skill.model.UnreadResponse;
import com.opencode.cui.skill.model.UnreadSessionItem;
import com.opencode.cui.skill.service.ProtocolUtils;
import com.opencode.cui.skill.config.UnreadProperties;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillSessionFlowService;
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

import java.util.List;
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
    private final SessionAccessControlService accessControlService;
    private final SkillSessionFlowService flowService;
    private final UnreadProperties unreadProperties;

    public SkillSessionController(SkillSessionService sessionService,
                                  SessionAccessControlService accessControlService,
                                  SkillSessionFlowService flowService,
                                  UnreadProperties unreadProperties) {
        this.sessionService = sessionService;
        this.accessControlService = accessControlService;
        this.flowService = flowService;
        this.unreadProperties = unreadProperties;
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

        ApiResponse<SkillSession> response = flowService.createSession(
                resolvedUserId, userIdCookie,
                new SkillSessionFlowService.CreateSessionCommand(
                        request.getAk(),
                        request.getTitle(),
                        request.getBusinessSessionDomain(),
                        request.getBusinessSessionType(),
                        request.getBusinessSessionId(),
                        request.getAssistantAccount()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        SkillSession session = response.getData();
        log.info("[EXIT] createSession: sessionId={}, durationMs={}", session != null ? session.getId() : null, elapsedMs);
        return ResponseEntity.ok(response);
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
     * POST /api/skill/sessions/{id}/close
     * 关闭会话。如果存在 tool session，同时向 AI-Gateway 发送 close_session 命令。
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<ApiResponse<Map<String, Object>>> closeSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String id) {
        Long sessionId = ProtocolUtils.parseSessionId(id);
        if (sessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }
        log.info("[ENTRY] closeSession: sessionId={}", id);
        SkillSession session = accessControlService.requireSessionAccess(sessionId, userIdCookie);

        flowService.closeSession(session);
        log.info("[EXIT] closeSession: sessionId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "closed", "welinkSessionId", id)));
    }

    /**
     * DELETE /api/skill/sessions/{id}
     * 硬删除会话及所有关联数据。ACTIVE 会话先 abort 再删除。
     * 删除后通过 WS 推送 session.deleted 到所有设备，通知 Gateway 释放资源。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String id) {
        Long sessionId = ProtocolUtils.parseSessionId(id);
        if (sessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }
        log.info("[ENTRY] deleteSession: sessionId={}", id);
        SkillSession session = accessControlService.requireSessionAccess(sessionId, userIdCookie);

        String resolvedUserId = accessControlService.requireUserId(userIdCookie);
        flowService.deleteSession(session, resolvedUserId);
        log.info("[EXIT] deleteSession: sessionId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "deleted", "welinkSessionId", id)));
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

        log.info("[ENTRY] abortSession: sessionId={}", id);
        flowService.abortSession(session);

        log.info("[EXIT] abortSession: sessionId={}", id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "aborted", "welinkSessionId", id)));
    }

    /**
     * POST /api/skill/sessions/unread
     * Query unread message state. If {@code sessionIds} is omitted, returns all
     * sessions with unread messages. If provided, returns only the requested sessions.
     */
    @PostMapping("/unread")
    public ResponseEntity<ApiResponse<UnreadResponse>> getUnreadSessions(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @RequestBody UnreadRequest request) {
        String resolvedUserId = accessControlService.requireUserId(userIdCookie);
        if (request == null || request.getAssistantAccount() == null || request.getAssistantAccount().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(400, "assistantAccount is required"));
        }
        if (request.getSessionIds() != null
                && request.getSessionIds().size() > unreadProperties.getMaxQuerySessionIds()) {
            return ResponseEntity.ok(ApiResponse.error(400,
                    "sessionIds exceeds max " + unreadProperties.getMaxQuerySessionIds()));
        }

        List<UnreadSessionItem> items = sessionService.getUnreadSessions(
                resolvedUserId, request.getAssistantAccount(), request.getSessionIds());

        UnreadResponse response = new UnreadResponse(items.size(), items);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * POST /api/skill/sessions/{id}/read
     * Report that the frontend has rendered up to {@code readSeq}.
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<ReadReportResponse>> reportRead(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String id,
            @RequestBody ReadReportRequest request) {
        Long sessionId = ProtocolUtils.parseSessionId(id);
        if (sessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }
        if (request == null || request.getReadSeq() <= 0) {
            return ResponseEntity.ok(ApiResponse.error(400, "readSeq is required and must be positive"));
        }

        String resolvedUserId = accessControlService.requireUserId(userIdCookie);
        accessControlService.requireSessionAccess(sessionId, userIdCookie);

        sessionService.reportRead(sessionId, request.getReadSeq(), resolvedUserId);

        return ResponseEntity.ok(ApiResponse.ok(new ReadReportResponse(id, 0)));
    }

    // ==================== Request / Response DTOs ====================

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
