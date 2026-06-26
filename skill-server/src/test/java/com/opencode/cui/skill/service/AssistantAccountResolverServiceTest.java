package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.AssistantInstanceInfo;
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
    private AssistantInstanceInfoService assistantInstanceInfoService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AssistantAccountResolverService service;

    private static final String STATUS_KEY = "assistantAccount:status:assist-001";
    private static final int EXISTS_TTL = 300;
    private static final int NOT_EXISTS_TTL = 60;

    @BeforeEach
    void setUp() {
        service = new AssistantAccountResolverService(
                assistantInstanceInfoService,
                redisTemplate,
                true,
                EXISTS_TTL,
                NOT_EXISTS_TTL,
                "该助理已被删除");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private static AssistantInstanceInfo localAssistant(String appKey, String createdBy) {
        AssistantInstanceInfo info = new AssistantInstanceInfo();
        info.setAppKey(appKey);
        info.setCreatedBy(createdBy);
        info.setRemoteType(0);
        return info;
    }

    private static AssistantInstanceInfo remoteAssistant(int remoteType) {
        AssistantInstanceInfo info = new AssistantInstanceInfo();
        info.setRemoteType(remoteType);
        return info;
    }

    private static AssistantInstanceInfoService.LookupResult exists(AssistantInstanceInfo info) {
        return new AssistantInstanceInfoService.LookupResult(ExistenceStatus.EXISTS, info);
    }

    private static AssistantInstanceInfoService.LookupResult notExists() {
        return new AssistantInstanceInfoService.LookupResult(ExistenceStatus.NOT_EXISTS, null);
    }

    private static AssistantInstanceInfoService.LookupResult unknown() {
        return AssistantInstanceInfoService.LookupResult.unknown();
    }

    // ==================== 远端判定三态 ====================

    @Test
    void remoteExistsWritesStatusCacheExistsTtl() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001"))
                .thenReturn(exists(localAssistant("ak-001", "owner-001")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.EXISTS, outcome.status());
        assertEquals("ak-001", outcome.ak());
        assertEquals("owner-001", outcome.ownerWelinkId());
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(STATUS_KEY), valueCap.capture(), eq(Duration.ofSeconds(EXISTS_TTL)));
        String written = valueCap.getValue();
        assert written.contains("EXISTS") && written.contains("ak-001") && written.contains("owner-001");
    }

    @Test
    @DisplayName("upstream: UNKNOWN → UNKNOWN, 不写缓存")
    void upstreamUnknownReturnsUnknownNoCache() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(unknown());

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("upstream: NOT_EXISTS → NOT_EXISTS, 写缓存 TTL=60s")
    void upstreamNotExistsWritesShortTtl() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(notExists());

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.NOT_EXISTS, outcome.status());
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(STATUS_KEY), valueCap.capture(), eq(Duration.ofSeconds(NOT_EXISTS_TTL)));
        assert valueCap.getValue().contains("NOT_EXISTS");
    }

    @Test
    @DisplayName("remote: remoteType=1 + appKey missing -> EXISTS")
    void remoteAppKeyMissingReturnsExists() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001"))
                .thenReturn(exists(remoteAssistant(1)));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.EXISTS, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations).set(eq(STATUS_KEY), anyString(), eq(Duration.ofSeconds(EXISTS_TTL)));
    }

    @Test
    @DisplayName("remote: remoteType=2 + appKey missing -> EXISTS")
    void defaultProtocolRemoteAppKeyMissingReturnsExists() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001"))
                .thenReturn(exists(remoteAssistant(2)));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.EXISTS, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations).set(eq(STATUS_KEY), anyString(), eq(Duration.ofSeconds(EXISTS_TTL)));
    }

    @Test
    @DisplayName("local: remoteType=0 + appKey missing -> UNKNOWN")
    void localAssistantWithoutAkReturnsUnknown() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        AssistantInstanceInfo info = new AssistantInstanceInfo();
        info.setCreatedBy("owner-only");
        info.setRemoteType(0);
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(exists(info));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("local: remoteType=0 overrides legacy remoteProperty")
    void localRemoteTypeOverridesRemoteProperty() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        AssistantInstanceInfo info = new AssistantInstanceInfo();
        info.setRemoteType(0);
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(exists(info));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("local: no remoteType set → UNKNOWN")
    void remotePropertyWithoutRemoteTypeReturnsUnknown() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        AssistantInstanceInfo info = new AssistantInstanceInfo();
        // remoteType defaults to 0, so remoteAssistant() returns false
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(exists(info));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("local: ownerWelinkId missing does not fall back to assistantAccount")
    void localOwnerMissingReturnsUnknownNoCache() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        AssistantInstanceInfo info = new AssistantInstanceInfo();
        info.setAppKey("ak-only");
        info.setRemoteType(0);
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(exists(info));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.UNKNOWN, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        verify(valueOperations, never()).set(eq(STATUS_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("remote: ownerWelinkId missing remains null, never assistantAccount")
    void remoteOwnerMissingDoesNotFallbackToAssistantAccount() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001"))
                .thenReturn(exists(remoteAssistant(1)));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.EXISTS, outcome.status());
        assertNull(outcome.ak());
        assertNull(outcome.ownerWelinkId());
        assertEquals("assist-001", outcome.assistantAccount());
        verify(valueOperations).set(eq(STATUS_KEY), anyString(), eq(Duration.ofSeconds(EXISTS_TTL)));
    }

    @Test
    @DisplayName("upstream: lookup returns null → UNKNOWN, 不写缓存")
    void upstreamNullReturnsUnknownNoCache() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(null);

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
        verify(assistantInstanceInfoService, never()).lookup(anyString());
    }

    @Test
    @DisplayName("cache hit: legacy owner==assistantAccount is dirty and refreshes")
    void cacheHitLegacyAssistantOwnerFallbackRefreshes() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"EXISTS\",\"ak\":\"cached-ak\","
                        + "\"ownerWelinkId\":\"assist-001\",\"assistantAccount\":\"assist-001\"}");
        when(assistantInstanceInfoService.lookup("assist-001"))
                .thenReturn(exists(localAssistant("fresh-ak", "owner-fresh")));

        ResolveOutcome outcome = service.resolveWithStatus("assist-001");

        assertEquals(ExistenceStatus.EXISTS, outcome.status());
        assertEquals("fresh-ak", outcome.ak());
        assertEquals("owner-fresh", outcome.ownerWelinkId());
        verify(assistantInstanceInfoService).lookup("assist-001");
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
        verify(assistantInstanceInfoService, never()).lookup(anyString());
    }

    @Test
    @DisplayName("cache flip: 先 EXISTS 缓存命中；TTL 过后远端 NOT_EXISTS 原子覆盖 + TTL 切换为 60s")
    void cacheFlipExistsToNotExistsSwitchesTtl() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"EXISTS\",\"ak\":\"cached-ak\",\"ownerWelinkId\":\"cached-owner\"}")
                .thenReturn(null);

        ResolveOutcome first = service.resolveWithStatus("assist-001");
        assertEquals(ExistenceStatus.EXISTS, first.status());

        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(notExists());

        ResolveOutcome second = service.resolveWithStatus("assist-001");
        assertEquals(ExistenceStatus.NOT_EXISTS, second.status());
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
    void resolveReturnsNullOnUnknown() {
        when(valueOperations.get(STATUS_KEY)).thenReturn(null);
        when(assistantInstanceInfoService.lookup("assist-001")).thenReturn(unknown());

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
        verify(valueOperations, never()).get("assistantAccount:ak:assist-001");
        verify(valueOperations, never()).get("assistantAccount:owner:assist-001");
    }

    @Test
    @DisplayName("resolve() 对 blank / null 输入返回 null（走 UNKNOWN 短路）")
    void resolveReturnsNullForBlankInput() {
        assertNull(service.resolve(""));
        assertNull(service.resolve(null));
        verify(assistantInstanceInfoService, never()).lookup(anyString());
    }

    // ==================== check() 轻量接口 ====================

    @Test
    @DisplayName("check(): 返回三态之一；EXISTS 命中缓存时不打远端")
    void checkReturnsStatusFromCache() {
        when(valueOperations.get(STATUS_KEY))
                .thenReturn("{\"status\":\"NOT_EXISTS\"}");

        assertEquals(ExistenceStatus.NOT_EXISTS, service.check("assist-001"));
        verify(assistantInstanceInfoService, never()).lookup(anyString());
    }
}
