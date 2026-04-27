package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.ResolveOutcome;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** AssistantAccountResolverService 单元测试：三态 existence + 统一 status key 双 TTL 行为。 */
class AssistantAccountResolverServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AssistantAccountResolverService service;

    private static final String RESOLVE_URL = "http://localhost:8080/assistant-api/integration/v4-1/we-crew/instance/query";
    private static final String REQUEST_URL = RESOLVE_URL + "?partnerAccount=assist-001";
    private static final String STATUS_KEY = "assistantAccount:status:assist-001";
    private static final int EXISTS_TTL = 300;
    private static final int NOT_EXISTS_TTL = 60;

    @BeforeEach
    void setUp() {
        service = new AssistantAccountResolverService(
                restTemplate,
                redisTemplate,
                RESOLVE_URL,
                "resolve-token-123",
                true,
                EXISTS_TTL,
                NOT_EXISTS_TTL,
                "该助理已被删除");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== 远端判定三态 ====================

    @Test
    @DisplayName("remote: body.code=200 + data.appKey + ownerWelinkId → EXISTS, 写 status 缓存 TTL=300s")
    void remoteExistsWritesStatusCacheExistsTtl() throws Exception {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(restTemplate.exchange(eq(REQUEST_URL), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":200,\"data\":{\"appKey\":\"ak-001\",\"ownerWelinkId\":\"owner-001\"}}")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.EXISTS, outcome.status());
        assertEquals("ak-001", outcome.ak());
        assertEquals("owner-001", outcome.ownerWelinkId());
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(STATUS_KEY), valueCap.capture(), eq(Duration.ofSeconds(EXISTS_TTL)));
        String written = valueCap.getValue();
        // JSON 里应包含 EXISTS + ak + owner
        assert written.contains("EXISTS") && written.contains("ak-001") && written.contains("owner-001");
    }

    @Test
    @DisplayName("remote: body.code != 200 → UNKNOWN, 不写缓存")
    void remoteBusinessCodeNonSuccessReturnsUnknownNoCache() throws Exception {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":500,\"errormsg\":\"upstream error\"}")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("remote: body.code=200 + data=null → NOT_EXISTS, 写缓存 TTL=60s")
    void remoteDataNullReturnsNotExistsWritesShortTtl() throws Exception {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":200,\"data\":null}")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.NOT_EXISTS, outcome.status());
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(STATUS_KEY), valueCap.capture(), eq(Duration.ofSeconds(NOT_EXISTS_TTL)));
        assert valueCap.getValue().contains("NOT_EXISTS");
    }

    @Test
    @DisplayName("remote: body.code=200 + data.appKey 缺 → NOT_EXISTS")
    void remoteAppKeyMissingReturnsNotExists() throws Exception {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":200,\"data\":{\"ownerWelinkId\":\"owner-only\"}}")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.NOT_EXISTS, outcome.status());
        verify(valueOperations).set(eq(STATUS_KEY), anyString(), eq(Duration.ofSeconds(NOT_EXISTS_TTL)));
    }

    @Test
    @DisplayName("remote: body.code=200 + data.appKey 有 + ownerWelinkId 缺 → UNKNOWN, 不写缓存")
    void remoteOwnerMissingReturnsUnknownNoCache() throws Exception {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":200,\"data\":{\"appKey\":\"ak-only\"}}")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("remote: HTTP 超时/异常 → UNKNOWN, 不写缓存")
    void remoteTimeoutReturnsUnknownNoCache() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException(
                        "I/O error", new SocketTimeoutException("timeout")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    // ==================== 缓存命中 ====================

    @Test
    @DisplayName("cache hit: EXISTS → 不打远端，直接返回")
    void cacheHitExistsSkipsRemote() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"EXISTS\",\"ak\":\"cached-ak\",\"ownerWelinkId\":\"cached-owner\"}");

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.EXISTS, outcome.status());
        assertEquals("cached-ak", outcome.ak());
        assertEquals("cached-owner", outcome.ownerWelinkId());
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET), any(),
                eq(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    @DisplayName("cache hit: NOT_EXISTS → 不打远端，直接返回")
    void cacheHitNotExistsSkipsRemote() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"NOT_EXISTS\"}");

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.NOT_EXISTS, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET), any(),
                eq(com.fasterxml.jackson.databind.JsonNode.class));
    }

    @Test
    @DisplayName("cache flip: 先 EXISTS 缓存命中；TTL 过后远端 NOT_EXISTS 原子覆盖 + TTL 切换为 60s")
    void cacheFlipExistsToNotExistsSwitchesTtl() throws Exception {
        // 第一次：EXISTS 缓存命中，不打远端
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"EXISTS\",\"ak\":\"cached-ak\",\"ownerWelinkId\":\"cached-owner\"}")
                // 第二次：缓存过期 → null，远端返 NOT_EXISTS
                .thenReturn(null);

        ResolveOutcome first = service.resolveWithStatus("assist-001");
        assertEquals(ExistenceStatus.EXISTS, first.status());

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":200,\"data\":null}")));

        ResolveOutcome second = service.resolveWithStatus("assist-001");
        assertEquals(ExistenceStatus.NOT_EXISTS, second.status());
        // 切换到 NOT_EXISTS TTL
        verify(valueOperations).set(eq(STATUS_KEY), anyString(), eq(Duration.ofSeconds(NOT_EXISTS_TTL)));
    }

    // ==================== resolve() / resolveAk() 老接口兼容 ====================

    @Test
    @DisplayName("resolve(): EXISTS → 返回 AssistantResolveResult；基于 status 缓存")
    void resolveReturnsResultOnExists() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"EXISTS\",\"ak\":\"cached-ak\",\"ownerWelinkId\":\"cached-owner\"}");

        AssistantResolveResult result = service.resolve("assist-001");

        assertNotNull(result);
        assertEquals("cached-ak", result.ak());
        assertEquals("cached-owner", result.ownerWelinkId());
    }

    @Test
    @DisplayName("resolve(): NOT_EXISTS → null（兼容旧调用方）")
    void resolveReturnsNullOnNotExists() {
        when(valueOperations.get(STATUS_KEY)).thenReturn("{\"status\":\"NOT_EXISTS\"}");

        AssistantResolveResult result = service.resolve("assist-001");

        assertNull(result);
    }

    @Test
    @DisplayName("resolve(): UNKNOWN → null（兼容旧调用方）")
    void resolveReturnsNullOnUnknown() throws Exception {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree(
                        "{\"code\":500}")));

        AssistantResolveResult result = service.resolve("assist-001");

        assertNull(result);
    }

    @Test
    @DisplayName("resolveAk(): EXISTS → 返 ak；其它 → null；基于新 status 缓存")
    void resolveAkUsesStatusCache() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"EXISTS\",\"ak\":\"cached-ak\",\"ownerWelinkId\":\"cached-owner\"}");

        String ak = service.resolveAk("assist-001");

        assertEquals("cached-ak", ak);
        // 关键：不查老 key
        verify(valueOperations, never()).get("assistantAccount:ak:assist-001");
        verify(valueOperations, never()).get("assistantAccount:owner:assist-001");
    }

    @Test
    @DisplayName("resolve() 对 blank / null 输入返回 null（走 UNKNOWN 短路）")
    void resolveReturnsNullForBlankInput() {
        assertNull(service.resolve(""));
        assertNull(service.resolve(null));
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET), any(),
                eq(com.fasterxml.jackson.databind.JsonNode.class));
    }

    // ==================== check() 轻量接口 ====================

    @Test
    @DisplayName("check(): 返回三态之一；EXISTS 命中缓存时不打远端")
    void checkReturnsStatusFromCache() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"NOT_EXISTS\"}");

        assertEquals(ExistenceStatus.NOT_EXISTS, service.check("assist-001"));
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET), any(),
                eq(com.fasterxml.jackson.databind.JsonNode.class));
    }
}
