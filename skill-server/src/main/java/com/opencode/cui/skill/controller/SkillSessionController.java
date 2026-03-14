package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.GatewayRelayService;

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
     * Create a new skill session. Also instructs AI-Gateway to create an OpenCode
     * session.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SkillSession>> createSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @RequestBody CreateSessionRequest request) {
        String resolvedUserId = accessControlService.requireUserId(userIdCookie);

        SkillSession session = sessionService.createSession(
                resolvedUserId,
                request.getAk(),
                request.getTitle(),
                request.getImGroupId());

        if (request.getAk() != null) {
            gatewayRelayService.sendInvokeToGateway(
                    new InvokeCommand(request.getAk(),
                            resolvedUserId,
                            session.getId().toString(),
                            GatewayActions.CREATE_SESSION,
                            buildPayload(request.getTitle() != null && !request.getTitle().isBlank()
                                    ? Map.of("title", request.getTitle())
                                    : Map.of())));
        }

        return ResponseEntity.ok(ApiResponse.ok(session));
    }

    /**
     * GET /api/skill/sessions
     * List sessions for a user with pagination.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<SkillSession>>> listSessions(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ak,
            @RequestParam(required = false) String imGroupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String resolvedUserId = accessControlService.requireUserId(userIdCookie);
        PageResult<SkillSession> sessions = sessionService.listSessions(
                resolvedUserId, ak, imGroupId, status, page, size);
        return ResponseEntity.ok(ApiResponse.ok(sessions));
    }

    /**
     * GET /api/skill/sessions/{id}
     * Get a single session by ID.
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
     * Close a session. Also sends close_session to AI-Gateway if a tool session
     * exists.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> closeSession(
            @CookieValue(value = "userId", required = false) String userIdCookie,
            @PathVariable String id) {
        Long sessionId = ProtocolUtils.parseSessionId(id);
        if (sessionId == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "Invalid session ID"));
        }
        SkillSession session = accessControlService.requireSessionAccess(sessionId, userIdCookie);

        if (session.getAk() != null && session.getToolSessionId() != null) {
            gatewayRelayService.sendInvokeToGateway(
                    new InvokeCommand(session.getAk(),
                            session.getUserId(),
                            session.getId().toString(),
                            GatewayActions.CLOSE_SESSION,
                            buildPayload(Map.of("toolSessionId", session.getToolSessionId()))));
        }
        sessionService.closeSession(sessionId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "closed", "welinkSessionId", id)));
    }

    /**
     * POST /api/skill/sessions/{id}/abort
     * Abort a session. Sends abort_session to AI-Gateway to stop ongoing AI
     * operations while keeping the session reusable.
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

        // Send abort_session to AI-Gateway if toolSessionId and ak exist
        if (session.getAk() != null && session.getToolSessionId() != null) {
            gatewayRelayService.sendInvokeToGateway(
                    new InvokeCommand(session.getAk(),
                            session.getUserId(),
                            session.getId().toString(),
                            GatewayActions.ABORT_SESSION,
                            buildPayload(Map.of("toolSessionId", session.getToolSessionId()))));
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "aborted", "welinkSessionId", id)));
    }

    private String buildPayload(Map<String, String> fields) {
        var node = objectMapper.createObjectNode();
        fields.forEach((k, v) -> {
            if (v != null) {
                node.put(k, v);
            }
        });
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload: {}", e.getMessage());
            return "{}";
        }
    }

    @Data
    public static class CreateSessionRequest {
        private String ak;
        private String title;
        private String imGroupId;
    }
}
