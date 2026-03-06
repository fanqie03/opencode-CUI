package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.SkillSessionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@Slf4j
@RestController
@RequestMapping("/api/skill/sessions")
public class SkillSessionController {

    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final ObjectMapper objectMapper;

    public SkillSessionController(SkillSessionService sessionService,
                                  GatewayRelayService gatewayRelayService,
                                  ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /api/skill/sessions
     * Create a new skill session. Also instructs AI-Gateway to create an OpenCode session.
     */
    @PostMapping
    public ResponseEntity<SkillSession> createSession(@RequestBody CreateSessionRequest request) {
        if (request.getUserId() == null || request.getSkillDefinitionId() == null) {
            return ResponseEntity.badRequest().build();
        }

        SkillSession session = sessionService.createSession(
                request.getUserId(),
                request.getSkillDefinitionId(),
                request.getAgentId(),
                request.getTitle(),
                request.getImChatId()
        );

        // Send create_session invoke to AI-Gateway if agentId is provided
        if (request.getAgentId() != null) {
            gatewayRelayService.sendInvokeToGateway(
                    request.getAgentId().toString(),
                    session.getId().toString(),
                    "create_session",
                    null
            );
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /**
     * GET /api/skill/sessions
     * List sessions for a user with pagination.
     */
    @GetMapping
    public ResponseEntity<PageResult<SkillSession>> listSessions(
            @RequestParam Long userId,
            @RequestParam(required = false) List<SkillSession.Status> statuses,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResult<SkillSession> sessions = sessionService.listSessions(userId, statuses, page, size);
        return ResponseEntity.ok(sessions);
    }

    /**
     * GET /api/skill/sessions/{id}
     * Get a single session by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<SkillSession> getSession(@PathVariable Long id) {
        try {
            SkillSession session = sessionService.getSession(id);
            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * DELETE /api/skill/sessions/{id}
     * Close a session. Also sends close_session to AI-Gateway if a tool session exists.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> closeSession(@PathVariable Long id) {
        try {
            SkillSession session = sessionService.getSession(id);

            // Send close_session to AI-Gateway if toolSessionId and agentId exist
            if (session.getAgentId() != null && session.getToolSessionId() != null) {
                var node = objectMapper.createObjectNode();
                node.put("toolSessionId", session.getToolSessionId());
                String payload;
                try {
                    payload = objectMapper.writeValueAsString(node);
                } catch (JsonProcessingException e) {
                    payload = "{}";
                }
                gatewayRelayService.sendInvokeToGateway(
                        session.getAgentId().toString(),
                        session.getId().toString(),
                        "close_session",
                        payload
                );
            }

            sessionService.closeSession(id);
            return ResponseEntity.ok(Map.of("status", "closed", "sessionId", id.toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Data
    public static class CreateSessionRequest {
        private Long userId;
        private Long skillDefinitionId;
        private Long agentId;
        private String title;
        private String imChatId;
    }
}
