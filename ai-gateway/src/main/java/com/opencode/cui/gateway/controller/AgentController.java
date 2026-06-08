package com.opencode.cui.gateway.controller;

import com.opencode.cui.gateway.config.InternalAuthProperties;
import com.opencode.cui.gateway.model.AgentAvailabilityRequest;
import com.opencode.cui.gateway.model.AgentAvailabilityResponse;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentStatusResponse;
import com.opencode.cui.gateway.model.AgentSummaryResponse;
import com.opencode.cui.gateway.model.ApiResponse;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.InvokeResult;
import com.opencode.cui.gateway.model.SourceConnectionLinkResponse;
import com.opencode.cui.gateway.model.SourceConnectionOverviewResponse;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.EventRelayService;
import com.opencode.cui.gateway.service.RedisMessageBroker;
import com.opencode.cui.gateway.service.SkillRelayService;
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
 * Gateway REST API 控制器。
 *
 * <p>协议端点：</p>
 * <ul>
 * <li>GET /api/gateway/agents — 查询在线 Agent 列表</li>
 * <li>GET /api/gateway/agents/status?ak= — 查询 Agent 状态</li>
 * <li>POST /api/gateway/invoke — 向 Agent 发送命令</li>
 * <li>POST /api/gateway/internal/agent/availability — 查询 Agent 可及性</li>
 * </ul>
 *
 * <p>兼容旧版端点（保留向后兼容，需内部 Bearer Token）：</p>
 * <ul>
 * <li>GET /api/gateway/agents/{id}/status</li>
 * <li>POST /api/gateway/agents/{id}/invoke</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway")
public class AgentController {

    private final AgentRegistryService agentRegistryService;
    private final EventRelayService eventRelayService;
    private final SkillRelayService skillRelayService;
    private final RedisMessageBroker redisMessageBroker;
    private final String gatewayInstanceId;
    private final String internalToken;

    public AgentController(AgentRegistryService agentRegistryService,
            EventRelayService eventRelayService,
            SkillRelayService skillRelayService,
            RedisMessageBroker redisMessageBroker,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId,
            InternalAuthProperties internalAuthProperties) {
        this.agentRegistryService = agentRegistryService;
        this.eventRelayService = eventRelayService;
        this.skillRelayService = skillRelayService;
        this.redisMessageBroker = redisMessageBroker;
        this.gatewayInstanceId = gatewayInstanceId;
        this.internalToken = internalAuthProperties.getInternalToken();
    }

    /** 查询在线 Agent 列表，支持按 AK 或 userId 过滤。 */
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
            agents = agentRegistryService.findOnlineByAk(ak);
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

    /** 按 AK 查询 Agent 详细状态（含 WebSocket 连接和 OpenCode 在线状态）。 */
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

    /** Query Source WebSocket links between SS and GW for diagnostics. */
    @GetMapping("/source-connections")
    public ResponseEntity<ApiResponse<SourceConnectionOverviewResponse>> listSourceConnections(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "skill-server") String sourceType) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }

        String normalizedSourceType = (sourceType == null || sourceType.isBlank())
                ? "skill-server"
                : sourceType.trim();
        List<SourceConnectionLinkResponse> localLinks = skillRelayService
                .getLocalSourceConnectionSnapshots(normalizedSourceType)
                .stream()
                .map(link -> new SourceConnectionLinkResponse(
                        link.sourceType(),
                        link.ssInstanceId(),
                        link.gwInstanceId(),
                        link.linkId(),
                        true,
                        link.open(),
                        link.senderRunning(),
                        link.pending(),
                        null,
                        null))
                .toList();

        List<SourceConnectionLinkResponse> clusterLinks = redisMessageBroker
                .listSourceConnectionLinks(normalizedSourceType)
                .stream()
                .map(link -> new SourceConnectionLinkResponse(
                        link.sourceType(),
                        link.sourceInstanceId(),
                        link.gwInstanceId(),
                        link.linkId(),
                        gatewayInstanceId.equals(link.gwInstanceId()),
                        true,
                        null,
                        null,
                        link.lastSeenEpochSeconds(),
                        link.ageSeconds()))
                .toList();

        SourceConnectionOverviewResponse data = new SourceConnectionOverviewResponse(
                gatewayInstanceId,
                normalizedSourceType,
                (int) localLinks.stream().filter(SourceConnectionLinkResponse::open).count(),
                clusterLinks.size(),
                localLinks,
                clusterLinks);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /** 通过 AK 向 Agent 发送 invoke 命令（新版协议端点）。 */
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

        log.info("[ENTRY] REST invoke to agent: ak={}, action={}", ak, message.getAction());
        eventRelayService.relayToAgent(ak, message.withAk(ak));

        log.info("[EXIT] REST invoke to agent: ak={}, action={}", ak, message.getAction());
        return ResponseEntity.ok(ApiResponse.ok(new InvokeResult(true, "Command sent to agent")));
    }

    /** 【旧版】按数据库 ID 查询 Agent 状态。 */
    @GetMapping("/agents/{id}/status")
    public ResponseEntity<Map<String, Object>> getAgentStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        if (!isAuthorized(authorization)) {
            return legacyError(401, "Invalid or missing internal token");
        }

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

    /** 【旧版】按数据库 ID 向 Agent 发送 invoke 命令。 */
    @PostMapping("/agents/{id}/invoke")
    public ResponseEntity<Map<String, Object>> invokeAgent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody GatewayMessage message) {
        if (!isAuthorized(authorization)) {
            return legacyError(401, "Invalid or missing internal token");
        }

        AgentConnection agent = agentRegistryService.findById(id);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        if (agent.getStatus() != AgentConnection.AgentStatus.ONLINE) {
            return legacyError(400, "Agent is offline");
        }

        if (!eventRelayService.hasAgentSession(agent.getAkId())) {
            return legacyError(400, "No active WebSocket session for agent");
        }

        log.info("[ENTRY] REST legacy invoke to agent: id={}, ak={}, action={}",
                id, agent.getAkId(), message.getAction());
        eventRelayService.relayToAgent(agent.getAkId(), message.withAk(agent.getAkId()));

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Command sent to agent");
        log.info("[EXIT] REST legacy invoke to agent: id={}, ak={}, action={}",
                id, agent.getAkId(), message.getAction());
        return ResponseEntity.ok(result);
    }

    /** 校验内部 Bearer Token 是否有效。 */
    private boolean isAuthorized(String authorization) {
        return authorization != null && authorization.equals("Bearer " + internalToken);
    }

    private ResponseEntity<Map<String, Object>> legacyError(int status, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        return ResponseEntity.status(status).body(result);
    }

    /** 查询 Agent 可及性信息（供 skill-server 差异化离线文案使用）。 */
    @PostMapping("/internal/agent/availability")
    public ResponseEntity<ApiResponse<AgentAvailabilityResponse>> getAgentAvailability(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AgentAvailabilityRequest request) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }
        String ak = request.ak();
        if (ak == null || ak.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "ak is required"));
        }
        AgentAvailabilityResponse availability = agentRegistryService.queryAvailability(ak);
        return ResponseEntity.ok(ApiResponse.ok(availability));
    }
}
