package com.opencode.cui.gateway.controller;

import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentConnection.AgentStatus;
import com.opencode.cui.gateway.model.AgentSummaryResponse;
import com.opencode.cui.gateway.model.ApiResponse;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.EventRelayService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AgentController 单元测试：验证在线 Agent 查询接口。 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    private static final String INTERNAL_TOKEN = "test-token";

    @Mock
    private AgentRegistryService agentRegistryService;

    @Mock
    private EventRelayService eventRelayService;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentRegistryService, eventRelayService, INTERNAL_TOKEN);
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
                "Bearer " + INTERNAL_TOKEN,
                null,
                "user-001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("ak_test_001", response.getBody().getData().get(0).ak());
        verify(agentRegistryService).findOnlineByUserId("user-001");
    }
}
