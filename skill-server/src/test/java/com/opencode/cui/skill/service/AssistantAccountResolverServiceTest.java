package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantAccountResolverServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AssistantAccountResolverService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new AssistantAccountResolverService(
                restTemplate,
                redisTemplate,
                "http://localhost:8080/assistant-api/integration/v4-1/we-crew/instance/query",
                "resolve-token-123",
                30
        );
    }

    @Test
    @DisplayName("resolveAk uses partnerAccount query and returns data.appKey")
    void resolveAkUsesPartnerAccountAndReturnsDataAppKey() throws Exception {
        when(valueOperations.get("assistantAccount:ak:assist-001")).thenReturn(null);
        when(restTemplate.postForEntity(
                eq("http://localhost:8080/assistant-api/integration/v4-1/we-crew/instance/query?partnerAccount=assist-001"),
                any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(
                        new ObjectMapper().readTree("{\"data\":{\"appKey\":\"ak-001\"},\"code\":200}")));

        String result = service.resolveAk("assist-001");

        assertEquals("ak-001", result);

        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq("http://localhost:8080/assistant-api/integration/v4-1/we-crew/instance/query?partnerAccount=assist-001"),
                requestCaptor.capture(),
                eq(com.fasterxml.jackson.databind.JsonNode.class));

        HttpHeaders headers = requestCaptor.getValue().getHeaders();
        assertEquals("Bearer resolve-token-123", headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(requestCaptor.getValue().getBody());
        verify(valueOperations).set("assistantAccount:ak:assist-001", "ak-001", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("resolveAk returns cached ak without remote call")
    void resolveAkReturnsCachedAkWithoutRemoteCall() {
        when(valueOperations.get("assistantAccount:ak:assist-001")).thenReturn("cached-ak");

        String result = service.resolveAk("assist-001");

        assertEquals("cached-ak", result);
        verify(restTemplate, never()).postForEntity(any(String.class), any(), eq(com.fasterxml.jackson.databind.JsonNode.class));
    }
}
