package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AgentSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayApiClientTest {

    @Mock
    private RestTemplate restTemplate;

    private GatewayApiClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new GatewayApiClient(
                restTemplate,
                objectMapper,
                "http://localhost:8081",
                "test-token");
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
}
