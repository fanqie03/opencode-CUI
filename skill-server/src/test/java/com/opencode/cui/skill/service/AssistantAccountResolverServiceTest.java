package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantResolveResult;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** AssistantAccountResolverService 单元测试：验证助手账号解析逻辑。 */
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
        service = new AssistantAccountResolverService(
                restTemplate,
                redisTemplate,
                "http://localhost:8080/assistant-api/integration/v4-1/we-crew/instance/query",
                "resolve-token-123",
                30);
    }

    @Test
    @DisplayName("resolve returns ak and ownerWelinkId from remote API")
    void resolveReturnsAkAndOwnerFromRemote() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("assistantAccount:ak:assist-001")).thenReturn(null);
        when(restTemplate.exchange(
                eq("http://localhost:8080/assistant-api/integration/v4-1/we-crew/instance/query?partnerAccount=assist-001"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(
                        new ObjectMapper().readTree(
                                "{\"data\":{\"appKey\":\"ak-001\",\"ownerWelinkId\":\"owner-001\"},\"code\":200}")));

        AssistantResolveResult result = service.resolve("assist-001");

        assertNotNull(result);
        assertEquals("ak-001", result.ak());
        assertEquals("owner-001", result.ownerWelinkId());

        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://localhost:8080/assistant-api/integration/v4-1/we-crew/instance/query?partnerAccount=assist-001"),
                eq(HttpMethod.GET),
                requestCaptor.capture(),
                eq(com.fasterxml.jackson.databind.JsonNode.class));

        HttpHeaders headers = requestCaptor.getValue().getHeaders();
        assertEquals("Bearer resolve-token-123", headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(requestCaptor.getValue().getBody());
        verify(valueOperations).set("assistantAccount:ak:assist-001", "ak-001", Duration.ofMinutes(30));
        verify(valueOperations).set("assistantAccount:owner:assist-001", "owner-001", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("resolve returns cached result without remote call")
    void resolveReturnsCachedResultWithoutRemoteCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("assistantAccount:ak:assist-001")).thenReturn("cached-ak");
        when(valueOperations.get("assistantAccount:owner:assist-001")).thenReturn("cached-owner");

        AssistantResolveResult result = service.resolve("assist-001");

        assertNotNull(result);
        assertEquals("cached-ak", result.ak());
        assertEquals("cached-owner", result.ownerWelinkId());
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET), any(),
                eq(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    @DisplayName("resolveAk convenience method returns only ak")
    void resolveAkReturnsOnlyAk() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("assistantAccount:ak:assist-001")).thenReturn("cached-ak");
        when(valueOperations.get("assistantAccount:owner:assist-001")).thenReturn("cached-owner");

        String ak = service.resolveAk("assist-001");

        assertEquals("cached-ak", ak);
    }

    @Test
    @DisplayName("resolve returns null when ownerWelinkId is missing from response")
    void resolveReturnsNullWhenOwnerMissing() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("assistantAccount:ak:assist-001")).thenReturn(null);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(
                        new ObjectMapper().readTree("{\"data\":{\"appKey\":\"ak-001\"},\"code\":200}")));

        AssistantResolveResult result = service.resolve("assist-001");

        assertNull(result);
    }

    @Test
    @DisplayName("resolve returns null for blank input")
    void resolveReturnsNullForBlankInput() {
        AssistantResolveResult result = service.resolve("");
        assertNull(result);

        result = service.resolve(null);
        assertNull(result);
    }
}
