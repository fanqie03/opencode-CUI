package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.EventRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for agent management.
 *
 * GET  /api/gateway/agents           - list online agents
 * GET  /api/gateway/agents/{id}/status - agent status
 * POST /api/gateway/agents/{id}/invoke - backup channel to send command to PCAgent
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/agents")
public class AgentController {

    private final AgentRegistryService agentRegistryService;
    private final EventRelayService eventRelayService;
    private final ObjectMapper objectMapper;

    public AgentController(AgentRegistryService agentRegistryService,
                           EventRelayService eventRelayService,
                           ObjectMapper objectMapper) {
        this.agentRegistryService = agentRegistryService;
        this.eventRelayService = eventRelayService;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/gateway/agents - List all online agents.
     */
    @GetMapping
    public ResponseEntity<List<AgentConnection>> listOnlineAgents() {
        List<AgentConnection> agents = agentRegistryService.findOnlineAgents();
        return ResponseEntity.ok(agents);
    }

    /**
     * GET /api/gateway/agents/{id}/status - Get agent status including WebSocket session info.
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> getAgentStatus(@PathVariable Long id) {
        AgentConnection agent = agentRegistryService.findById(id);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> status = new HashMap<>();
        status.put("agent", agent);
        status.put("wsSessionActive", eventRelayService.hasAgentSession(String.valueOf(id)));
        status.put("activeSessionCount", eventRelayService.getActiveSessionCount());

        return ResponseEntity.ok(status);
    }

    /**
     * POST /api/gateway/agents/{id}/invoke - Backup channel to send a command to PCAgent.
     *
     * Request body should be a GatewayMessage (type=invoke with action and payload).
     * This is used when Skill Server needs to reach a PCAgent via REST as a fallback.
     */
    @PostMapping("/{id}/invoke")
    public ResponseEntity<Map<String, Object>> invokeAgent(
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

        if (!eventRelayService.hasAgentSession(String.valueOf(id))) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "No active WebSocket session for agent");
            error.put("agentId", id);
            return ResponseEntity.badRequest().body(error);
        }

        // Forward the invoke message to the PCAgent
        eventRelayService.relayToAgent(String.valueOf(id), message);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("agentId", id);
        result.put("message", "Command sent to agent");

        log.info("REST invoke to agent: agentId={}, action={}", id, message.getAction());
        return ResponseEntity.ok(result);
    }
}
