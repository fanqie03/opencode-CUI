package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.service.GatewayApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Proxy endpoint for querying online agents from AI-Gateway.
 * Miniapp calls this instead of querying Gateway directly.
 */
@Slf4j
@RestController
@RequestMapping("/api/skill/agents")
public class AgentQueryController {

    private final GatewayApiClient gatewayApiClient;

    public AgentQueryController(GatewayApiClient gatewayApiClient) {
        this.gatewayApiClient = gatewayApiClient;
    }

    /**
     * GET /api/skill/agents?userId={userId}
     * Returns online agents belonging to the specified user.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getOnlineAgents(
            @RequestParam Long userId) {
        log.debug("Querying online agents for userId={}", userId);
        List<Map<String, Object>> agents = gatewayApiClient.getOnlineAgentsByUserId(userId);
        return ResponseEntity.ok(agents);
    }
}
