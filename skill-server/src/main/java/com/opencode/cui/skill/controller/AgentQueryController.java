package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.service.GatewayApiClient;

import com.opencode.cui.skill.service.SessionAccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 查询代理控制器。
 * MiniApp 通过本接口查询在线 Agent，而不是直接访问 AI-Gateway。
 */
@Slf4j
@RestController
@RequestMapping("/api/skill/agents")
public class AgentQueryController {

    private final GatewayApiClient gatewayApiClient;
    private final SessionAccessControlService accessControlService;

    public AgentQueryController(GatewayApiClient gatewayApiClient,
            SessionAccessControlService accessControlService) {
        this.gatewayApiClient = gatewayApiClient;
        this.accessControlService = accessControlService;
    }

    /**
     * GET /api/skill/agents
     * 返回当前 Cookie 认证用户的在线 Agent 列表。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AgentSummary>>> getOnlineAgents(
            @CookieValue(value = "userId", required = false) String userIdCookie) {
        String userId = accessControlService.requireUserId(userIdCookie);
        log.debug("Querying online agents for userId={}", userId);
        List<AgentSummary> agents = gatewayApiClient.getOnlineAgentsByUserId(userId);
        return ResponseEntity.ok(ApiResponse.ok(agents));
    }
}
