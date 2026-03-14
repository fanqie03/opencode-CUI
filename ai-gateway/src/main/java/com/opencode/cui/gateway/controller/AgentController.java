package com.opencode.cui.gateway.controller;

import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentStatusResponse;
import com.opencode.cui.gateway.model.AgentSummaryResponse;
import com.opencode.cui.gateway.model.ApiResponse;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.InvokeResult;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.EventRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway REST API.
 *
 * Protocol endpoints:
 * - GET /api/gateway/agents
 * - GET /api/gateway/agents/status?ak=
 * - POST /api/gateway/invoke
 *
 * Legacy endpoints are kept for compatibility:
 * - GET /api/gateway/agents/{id}/status
 * - POST /api/gateway/agents/{id}/invoke
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway")
public class AgentController {

    private final AgentRegistryService agentRegistryService;
    private final EventRelayService eventRelayService;
    private final String internalToken;

    public AgentController(AgentRegistryService agentRegistryService,
            EventRelayService eventRelayService,
            @Value("${skill.gateway.internal-token:${gateway.skill-server.internal-token:changeme}}") String internalToken) {
        this.agentRegistryService = agentRegistryService;
        this.eventRelayService = eventRelayService;
        this.internalToken = internalToken;
    }

    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<AgentSummaryResponse>>> listOnlineAgents(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String ak,
            @RequestParam(required = false) String userId) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }

        List<AgentConnection> agents;
        if (ak != null && !ak.isBlank()) {
            AgentConnection latest = agentRegistryService.findLatestByAk(ak);
            agents = latest != null && latest.getStatus() == AgentConnection.AgentStatus.ONLINE
                    ? List.of(latest)
                    : List.of();
        } else if (userId != null && !userId.isBlank()) {
            agents = agentRegistryService.findOnlineByUserId(userId);
        } else {
            agents = agentRegistryService.findOnlineAgents();
        }

        List<AgentSummaryResponse> data = agents.stream()
                .map(AgentSummaryResponse::fromAgent)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @GetMapping("/agents/status")
    public ResponseEntity<ApiResponse<AgentStatusResponse>> getAgentStatusByAk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String ak) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }

        AgentConnection agent = agentRegistryService.findLatestByAk(ak);
        if (agent == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Agent not found"));
        }

        boolean wsActive = eventRelayService.hasAgentSession(ak);
        Boolean opencodeOnline = wsActive ? eventRelayService.requestAgentStatus(ak) : false;

        AgentStatusResponse status = new AgentStatusResponse(
                ak, agent.getStatus(), opencodeOnline, wsActive ? 1 : 0);

        return ResponseEntity.ok(ApiResponse.ok(status));
    }

    @PostMapping("/invoke")
    public ResponseEntity<ApiResponse<InvokeResult>> invokeAgentByAk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody GatewayMessage message) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }

        String ak = message.getAk();
        if (ak == null || ak.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "ak is required"));
        }

        AgentConnection agent = agentRegistryService.findLatestByAk(ak);
        if (agent == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Agent not found"));
        }

        if (agent.getStatus() != AgentConnection.AgentStatus.ONLINE) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Agent is offline"));
        }

        if (!eventRelayService.hasAgentSession(ak)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "No active WebSocket session for agent"));
        }

        eventRelayService.relayToAgent(ak, message.withAk(ak));

        log.info("REST invoke to agent: ak={}, action={}", ak, message.getAction());
        return ResponseEntity.ok(ApiResponse.ok(new InvokeResult(true, "Command sent to agent")));
    }

    @GetMapping("/agents/{id}/status")
    public ResponseEntity<Map<String, Object>> getAgentStatus(@PathVariable Long id) {
        AgentConnection agent = agentRegistryService.findById(id);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> status = new HashMap<>();
        status.put("agent", agent);
        status.put("wsSessionActive", eventRelayService.hasAgentSession(agent.getAkId()));
        status.put("activeSessionCount", eventRelayService.getActiveSessionCount());

        return ResponseEntity.ok(status);
    }

    @PostMapping("/agents/{id}/invoke")
    public ResponseEntity<Map<String, Object>> invokeAgentLegacy(
            @PathVariable Long id,
            @RequestBody GatewayMessage message) {

        AgentConnection agent = agentRegistryService.findById(id);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        if (agent.getStatus() != AgentConnection.AgentStatus.ONLINE) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Agent is offline");
            error.put("agentId", id);
            return ResponseEntity.badRequest().body(error);
        }

        if (!eventRelayService.hasAgentSession(agent.getAkId())) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "No active WebSocket session for agent");
            error.put("agentId", id);
            return ResponseEntity.badRequest().body(error);
        }

        eventRelayService.relayToAgent(agent.getAkId(), message.withAk(agent.getAkId()));

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("agentId", id);
        result.put("message", "Command sent to agent");
        return ResponseEntity.ok(result);
    }

    private boolean isAuthorized(String authorization) {
        return authorization != null && authorization.equals("Bearer " + internalToken);
    }
}
