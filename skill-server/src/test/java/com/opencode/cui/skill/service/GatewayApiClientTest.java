package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.InternalAuthProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.GatewayAvailabilityResponse;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayApiClientTest {

    private static final InternalAuthProperties AUTH = new InternalAuthProperties("test-token");

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ApiCallMetricsService apiCallMetricsService;

    private GatewayApiClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
         client = new GatewayApiClient(
                 restTemplate,
                 objectMapper,
                 "http://localhost:8081",
                 AUTH,
                 apiCallMetricsService);
    }

    @Test
    @DisplayName("getAgentByAk returns AgentSummary with toolType when agent is online")
    void getAgentByAkReturnsAgentWhenOnline() {
        String responseBody = "{\"code\":200,\"data\":[{\"ak\":\"ak-001\",\"status\":\"ONLINE\",\"toolType\":\"assistant\"}]}";
        when(restTemplate.exchange(
                eq("http://localhost:8081/api/gateway/agents?ak=ak-001"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        AgentSummary result = client.getAgentByAk("ak-001");

        assertNotNull(result);
        assertEquals("ak-001", result.getAk());
        assertEquals("assistant", result.getToolType());
    }

    @Test
    @DisplayName("getAgentByAk returns null when agent is offline (empty data)")
    void getAgentByAkReturnsNullWhenOffline() {
        String responseBody = "{\"code\":200,\"data\":[]}";
        when(restTemplate.exchange(
                eq("http://localhost:8081/api/gateway/agents?ak=ak-offline"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        AgentSummary result = client.getAgentByAk("ak-offline");

        assertNull(result);
    }

    @Test
    @DisplayName("getAgentByAk returns null on HTTP error")
    void getAgentByAkReturnsNullOnError() {
        when(restTemplate.exchange(
                any(String.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        AgentSummary result = client.getAgentByAk("ak-001");

        assertNull(result);
    }

    @Test
    @DisplayName("getAgentByAk returns null for null or blank ak")
    void getAgentByAkReturnsNullForBlankAk() {
        assertNull(client.getAgentByAk(null));
        assertNull(client.getAgentByAk(""));
        assertNull(client.getAgentByAk("  "));
    }

    @Test
    @DisplayName("getAgentByAk URL-encodes ak query parameter")
    void getAgentByAkEncodesAkQueryParam() {
        String responseBody = "{\"code\":200,\"data\":[{\"ak\":\"ak value/with?x=1&y=2+plus\",\"status\":\"ONLINE\"}]}";
        when(restTemplate.exchange(
                eq("http://localhost:8081/api/gateway/agents?ak=ak%20value%2Fwith%3Fx%3D1%26y%3D2%2Bplus"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        AgentSummary result = client.getAgentByAk("ak value/with?x=1&y=2+plus");

        assertNotNull(result);
        assertEquals("ak value/with?x=1&y=2+plus", result.getAk());
    }

    @Test
    @DisplayName("getOnlineAgentsByUserId URL-encodes userId query parameter")
    void getOnlineAgentsByUserIdEncodesUserIdQueryParam() {
        String responseBody = "{\"code\":200,\"data\":[{\"ak\":\"ak-001\",\"status\":\"ONLINE\"}]}";
        when(restTemplate.exchange(
                eq("http://localhost:8081/api/gateway/agents?userId=user%2F001%3Ftenant%3Da%26b%3D1"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        List<AgentSummary> result = client.getOnlineAgentsByUserId("user/001?tenant=a&b=1");

        assertEquals(1, result.size());
        assertEquals("ak-001", result.get(0).getAk());
    }

    @Test
    @DisplayName("getAvailability sends ak in JSON body")
    void getAvailabilitySendsAkInJsonBody() {
        String responseBody = "{\"code\":200,\"data\":{\"exists\":true,\"online\":false,\"latestToolType\":\"opencode\"}}";
        when(restTemplate.exchange(
                eq("http://localhost:8081/api/gateway/internal/agent/availability"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        GatewayAvailabilityResponse result = client.getAvailability("ak value/with?x=1&y=2+plus");

        assertNotNull(result);
        assertEquals("opencode", result.latestToolType());

        ArgumentCaptor<HttpEntity<String>> entityCaptor = ArgumentCaptor.captor();
        verify(restTemplate).exchange(
                eq("http://localhost:8081/api/gateway/internal/agent/availability"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class));
        assertEquals("{\"ak\":\"ak value/with?x=1&y=2+plus\"}", entityCaptor.getValue().getBody());
    }
}
