package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.ProtocolException;
import com.opencode.cui.skill.service.SessionAccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** AgentQueryController 单元测试：验证 Agent 代理查询接口的逻辑。 */
class AgentQueryControllerTest {

    @Mock
    private GatewayApiClient gatewayApiClient;
    @Mock
    private SessionAccessControlService accessControlService;

    private AgentQueryController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentQueryController(gatewayApiClient, accessControlService);
    }

    @Test
    @DisplayName("getOnlineAgents returns 200 for cookie-authenticated user")
    void getOnlineAgentsWithCookie() {
        var agent = com.opencode.cui.skill.model.AgentSummary.builder().ak("ak-1").build();
        List<com.opencode.cui.skill.model.AgentSummary> agents = List.of(agent);
        when(accessControlService.requireUserId("10001")).thenReturn("10001");
        when(gatewayApiClient.getOnlineAgentsByUserId("10001")).thenReturn(agents);

        var response = controller.getOnlineAgents("10001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertEquals("ak-1", response.getBody().getData().get(0).getAk());
        verify(gatewayApiClient).getOnlineAgentsByUserId("10001");
    }

    @Test
    @DisplayName("getOnlineAgents throws ProtocolException when userId cookie is missing")
    void getOnlineAgentsWithoutCookie() {
        when(accessControlService.requireUserId(null)).thenThrow(new ProtocolException(400, "userId is required"));

        ProtocolException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ProtocolException.class,
                () -> controller.getOnlineAgents(null));
        assertEquals(400, ex.getCode());
    }
}
