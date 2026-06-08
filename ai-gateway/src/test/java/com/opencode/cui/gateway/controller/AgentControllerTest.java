package com.opencode.cui.gateway.controller;

import com.opencode.cui.gateway.config.InternalAuthProperties;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentAvailabilityRequest;
import com.opencode.cui.gateway.model.AgentAvailabilityResponse;
import com.opencode.cui.gateway.model.AgentAvailabilityResponse;
import com.opencode.cui.gateway.model.AgentConnection.AgentStatus;
import com.opencode.cui.gateway.model.AgentSummaryResponse;
import com.opencode.cui.gateway.model.ApiResponse;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.EventRelayService;
import com.opencode.cui.gateway.service.RedisMessageBroker;
import com.opencode.cui.gateway.service.SkillRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AgentController 单元测试：验证在线 Agent 查询接口。 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    private static final InternalAuthProperties AUTH = new InternalAuthProperties("test-token");

    @Mock
    private AgentRegistryService agentRegistryService;

    @Mock
    private EventRelayService eventRelayService;

    @Mock
    private SkillRelayService skillRelayService;

    @Mock
    private RedisMessageBroker redisMessageBroker;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentRegistryService, eventRelayService, skillRelayService,
                redisMessageBroker, "gw-local", AUTH);
    }

    @Test
    @DisplayName("listOnlineAgents supports string userId filter")
    void listOnlineAgentsSupportsStringUserIdFilter() {
        AgentConnection agent = AgentConnection.builder()
                .id(1L)
                .userId("user-001")
                .akId("ak_test_001")
                .deviceName("MacBook Pro")
                .os("macOS")
                .toolType("CHANNEL")
                .toolVersion("1.0.0")
                .status(AgentStatus.ONLINE)
                .createdAt(LocalDateTime.now())
                .build();
        when(agentRegistryService.findOnlineByUserId("user-001")).thenReturn(List.of(agent));

        ResponseEntity<ApiResponse<List<AgentSummaryResponse>>> response = controller.listOnlineAgents(
                "Bearer test-token",
                null,
                "user-001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("ak_test_001", response.getBody().getData().get(0).ak());
        verify(agentRegistryService).findOnlineByUserId("user-001");
    }

    // ------------------------------------------------------------------ /internal/agent/availability

    @Test
    @DisplayName("availability: 401 when token missing")
    void availabilityUnauthorizedNoToken() {
        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability(null, new AgentAvailabilityRequest("ak-1"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().getCode());
    }

    @Test
    @DisplayName("availability: 400 when ak blank")
    void availabilityBadRequestBlankAk() {
        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", new AgentAvailabilityRequest("   "));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("availability: exists=false → 200 ok with exists=false, online=false")
    void availabilityNotExists() {
        when(agentRegistryService.queryAvailability("ak-new"))
                .thenReturn(new AgentAvailabilityResponse(false, false, null, null));

        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", new AgentAvailabilityRequest("ak-new"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertFalse(response.getBody().getData().exists());
        assertFalse(response.getBody().getData().online());
        assertNull(response.getBody().getData().latestToolType());
    }

    @Test
    @DisplayName("availability: online → 200 ok with online=true")
    void availabilityOnline() {
        when(agentRegistryService.queryAvailability("ak-1"))
                .thenReturn(new AgentAvailabilityResponse(true, true, "opencode", null));

        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", new AgentAvailabilityRequest("ak-1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().exists());
        assertTrue(response.getBody().getData().online());
        assertEquals("opencode", response.getBody().getData().latestToolType());
    }

    @Test
    @DisplayName("source connections: returns local and cluster websocket links")
    void sourceConnectionsReturnsLinks() {
        when(skillRelayService.getLocalSourceConnectionSnapshots("skill-server"))
                .thenReturn(List.of(new SkillRelayService.LocalSourceConnectionSnapshot(
                        "skill-server", "ss-1", "gw-local", "link-1", true, true, 2)));
        when(redisMessageBroker.listSourceConnectionLinks("skill-server"))
                .thenReturn(List.of(new RedisMessageBroker.SourceConnectionLink(
                        "skill-server", "ss-1", "gw-local", "link-1", 1_000L, 1L)));

        ResponseEntity<?> response = controller.listSourceConnections("Bearer test-token", "skill-server");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertNotNull(body);
        assertEquals(0, body.getCode());
    }

    @Test
    @DisplayName("source connections: 401 when token missing")
    void sourceConnectionsUnauthorized() {
        ResponseEntity<?> response = controller.listSourceConnections(null, "skill-server");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("availability: offline + toolType=opencode")
    void availabilityOfflineWithToolType() {
        when(agentRegistryService.queryAvailability("ak-off"))
                .thenReturn(new AgentAvailabilityResponse(true, false, "opencode",
                        LocalDateTime.of(2026, 5, 18, 10, 0)));

        ResponseEntity<ApiResponse<AgentAvailabilityResponse>> response =
                controller.getAgentAvailability("Bearer test-token", new AgentAvailabilityRequest("ak-off"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().getData().exists());
        assertFalse(response.getBody().getData().online());
        assertEquals("opencode", response.getBody().getData().latestToolType());
        assertNotNull(response.getBody().getData().lastSeenAt());
    }

    // ------------------------------------------------------------------ legacy /agents/{id}/status and /invoke

    @Test
    @DisplayName("legacy status: 401 when token missing")
    void legacyStatusUnauthorizedNoToken() {
        ResponseEntity<Map<String, Object>> response = controller.getAgentStatus(null, 1L);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    @DisplayName("legacy status: returns agent and WebSocket state when authorized")
    void legacyStatusReturnsAgentAndWsState() {
        AgentConnection agent = AgentConnection.builder()
                .id(9L)
                .akId("ak-legacy")
                .status(AgentStatus.ONLINE)
                .build();
        when(agentRegistryService.findById(9L)).thenReturn(agent);
        when(eventRelayService.hasAgentSession("ak-legacy")).thenReturn(true);
        when(eventRelayService.getActiveSessionCount()).thenReturn(2);

        ResponseEntity<Map<String, Object>> response = controller.getAgentStatus("Bearer test-token", 9L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(agent, response.getBody().get("agent"));
        assertEquals(true, response.getBody().get("wsSessionActive"));
        assertEquals(2, response.getBody().get("activeSessionCount"));
    }

    @Test
    @DisplayName("legacy invoke: relays command with resolved ak when authorized")
    void legacyInvokeRelaysWithResolvedAk() {
        AgentConnection agent = AgentConnection.builder()
                .id(9L)
                .akId("ak-legacy")
                .status(AgentStatus.ONLINE)
                .build();
        GatewayMessage message = GatewayMessage.builder()
                .action("chat")
                .build();
        when(agentRegistryService.findById(9L)).thenReturn(agent);
        when(eventRelayService.hasAgentSession("ak-legacy")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.invokeAgent(
                "Bearer test-token",
                9L,
                message);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
        verify(eventRelayService).relayToAgent("ak-legacy", message.withAk("ak-legacy"));
    }
}
