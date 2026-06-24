package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.AssistantInfoProperties;
import com.opencode.cui.skill.model.AssistantInstanceInfo;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AssistantInfoService TDD 测试。
 *
 * 使用 spy 子类 override fetchFromUpstream，避免直接依赖 HttpClient。
 */
@ExtendWith(MockitoExtension.class)
class AssistantInfoServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AssistantInstanceInfoService assistantInstanceInfoService;

    @Mock
    private ApiCallMetricsService apiCallMetricsService;

    private AssistantInfoProperties properties;

    /** 用于覆盖 fetchFromUpstream 的子类，避免真实 HTTP 调用 */
    private static class TestableAssistantInfoService extends AssistantInfoService {

        private AssistantInfo stubbedResult;
        private boolean shouldThrow = false;

         TestableAssistantInfoService(AssistantInfoProperties properties,
                                     StringRedisTemplate redisTemplate,
                                     ApiCallMetricsService apiCallMetricsService) {
             super(properties, redisTemplate, apiCallMetricsService);
         }

         TestableAssistantInfoService(AssistantInfoProperties properties,
                                      StringRedisTemplate redisTemplate,
                                      AssistantInstanceInfoService assistantInstanceInfoService,
                                      ApiCallMetricsService apiCallMetricsService) {
             super(properties, redisTemplate, assistantInstanceInfoService, apiCallMetricsService);
         }

        void stubFetch(AssistantInfo result) {
            this.stubbedResult = result;
            this.shouldThrow = false;
        }

        void stubFetchThrow() {
            this.shouldThrow = true;
        }

        @Override
        protected AssistantInfo fetchFromUpstream(String ak) {
            if (shouldThrow) {
                throw new RuntimeException("Connection refused");
            }
            return stubbedResult;
        }
    }

    private TestableAssistantInfoService service;

    private static final String AK = "test-ak-001";
    private static final String CACHE_KEY = "ss:assistant:info:" + AK;

    @BeforeEach
    void setUp() {
        properties = new AssistantInfoProperties();
        properties.setApiUrl("http://upstream-api/assistant/info");
        properties.setApiToken("test-token");
        properties.setCacheTtlSeconds(300);

        service = new TestableAssistantInfoService(properties, redisTemplate, apiCallMetricsService);
    }

    // ------------------------------------------------------------------ //
    //  缓存命中场景
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("getAssistantInfo: 缓存命中时直接返回，不调用上游")
    void getAssistantInfo_cacheHit_returnsCachedValue() throws Exception {
        // 缓存中存有 JSON
        String cachedJson = "{\"assistantScope\":\"business\",\"businessTag\":\"app_001\"," +
                "\"id\":\"robot-cache\"," +
                "\"cloudEndpoint\":\"https://cloud.example.com/chat\"," +
                "\"cloudProtocol\":\"sse\",\"authType\":\"soa\"}";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(cachedJson);

        AssistantInfo result = service.getAssistantInfo(AK);

        assertNotNull(result);
        assertEquals("business", result.getAssistantScope());
        assertEquals("app_001", result.getBusinessTag());
        assertEquals("robot-cache", result.getId());
        assertTrue(result.isBusiness());

        // 缓存命中时不应写缓存（set 不应被调用）
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    // ------------------------------------------------------------------ //
    //  缓存未命中 → 调上游 → 写缓存
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("getAssistantInfo: 缓存未命中时调上游并写缓存")
    void getAssistantInfo_cacheMiss_fetchesAndCaches() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);

        AssistantInfo upstream = new AssistantInfo();
        upstream.setAssistantScope("business");
        upstream.setId("robot-upstream");
        upstream.setBusinessTag("app_36209");
        upstream.setCloudEndpoint("https://cloud.example.com/chat");
        upstream.setCloudProtocol("sse");
        upstream.setAuthType("soa");
        service.stubFetch(upstream);

        AssistantInfo result = service.getAssistantInfo(AK);

        assertNotNull(result);
        assertEquals("business", result.getAssistantScope());
        assertEquals("robot-upstream", result.getId());
        // 应写入缓存
        verify(valueOperations).set(eq(CACHE_KEY), any(String.class),
                eq(Duration.ofSeconds(300)));
    }

    // ------------------------------------------------------------------ //
    //  identityType 映射
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("fetchFromUpstream: identityType=3 → scope=business")
    void fetchFromUpstream_identityType3_returnsBusiness() {
        // 直接测试真实实现（不走 stub），需要创建非 spy 的 service 且 mock HTTP
        // 这里通过创建专用子类测试解析逻辑
        AssistantInfo result = buildInfoFromIdentityType("3");
        assertEquals("business", result.getAssistantScope());
        assertTrue(result.isBusiness());
    }

    @Test
    @DisplayName("fetchFromUpstream: identityType=2 → scope=personal")
    void fetchFromUpstream_identityType2_returnsPersonal() {
        AssistantInfo result = buildInfoFromIdentityType("2");
        assertEquals("personal", result.getAssistantScope());
        assertTrue(result.isPersonal());
    }

    /**
     * 通过内部解析方法测试 identityType 映射逻辑。
     */
    private AssistantInfo buildInfoFromIdentityType(String identityType) {
        // 直接调用 service 的 parseResponse 辅助方法（package-private 或 protected）
        // 为保持测试简洁，使用专用 stub service 验证解析逻辑
        String json = String.format(
                "{\"code\":\"200\",\"data\":{\"identityType\":\"%s\"," +
                        "\"id\":\"robot-test\",\"businessTag\":\"app_test\",\"endpoint\":\"https://cloud.example.com\"," +
                        "\"protocol\":\"sse\",\"authType\":\"soa\"}}",
                identityType);

         // 使用匿名子类让 fetchFromUpstream 解析给定 JSON
         AssistantInfoService parseService = new AssistantInfoService(properties, redisTemplate, apiCallMetricsService) {
             @Override
             protected AssistantInfo fetchFromUpstream(String ak) {
                 return parseApiResponse(json);
             }
         };

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);

        AssistantInfo result = parseService.getAssistantInfo("any-ak");
        // 写缓存会调用 set，这里允许
        return result;
    }

    // ------------------------------------------------------------------ //
    //  上游不可用 → 返回 null
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("getAssistantInfo: 上游抛出异常时返回 null（静默降级）")
    void getAssistantInfo_upstreamThrows_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        service.stubFetchThrow();

        AssistantInfo result = service.getAssistantInfo(AK);

        assertNull(result);
        // 不应写缓存
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    // ------------------------------------------------------------------ //
    //  getCachedScope 降级
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("getCachedScope: AssistantInfo 为 null 时降级返回 personal")
    void getCachedScope_nullInfo_returnsPersonal() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        service.stubFetch(null);

        String scope = service.getCachedScope(AK);

        assertEquals("personal", scope);
    }

    @Test
    @DisplayName("getCachedScope: 正常返回 business 时不降级")
    void getCachedScope_businessInfo_returnsBusiness() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);

        AssistantInfo upstream = new AssistantInfo();
        upstream.setAssistantScope("business");
        service.stubFetch(upstream);

        String scope = service.getCachedScope(AK);

        assertEquals("business", scope);
    }

    @Test
    @DisplayName("getCachedScope: 上游异常时降级返回 personal")
    void getCachedScope_upstreamThrows_returnsPersonal() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        service.stubFetchThrow();

        String scope = service.getCachedScope(AK);

        assertEquals("personal", scope);
    }

    // ------------------------------------------------------------------ //
    //  parseApiResponse bug 回归测试：读 data.businessTag，不读 hisAppId
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("parseApiResponse reads data.businessTag (not legacy hisAppId)")
    void parseApiResponse_readsBusinessTag_notHisAppId() {
        String body = "{\"code\":\"200\",\"data\":{" +
                "\"identityType\":\"3\"," +
                "\"id\":\"robot-foo\"," +
                "\"businessTag\":\"tag-foo\"," +
                "\"endpoint\":\"https://cloud.example.com/chat\"," +
                "\"protocol\":\"2\"," +
                "\"authType\":\"1\"" +
                "}}";

        AssistantInfo info = service.parseApiResponse(body);

        assertNotNull(info);
        assertEquals("business", info.getAssistantScope());
        assertEquals("tag-foo", info.getBusinessTag());
        assertEquals("robot-foo", info.getId());
    }

    @Test
    @DisplayName("parseApiResponse: businessTag absent → AssistantInfo.businessTag = null")
    void parseApiResponse_businessTagAbsent_returnsNull() {
        String body = "{\"code\":\"200\",\"data\":{\"identityType\":\"3\"}}";

        AssistantInfo info = service.parseApiResponse(body);

        assertNotNull(info);
        assertNull(info.getBusinessTag());
    }

    @Test
    @DisplayName("getAssistantInfo(ak, account): no-AK remote instance routes as business")
    void getAssistantInfo_noAkRemoteInstance_returnsBusiness() {
        AssistantInstanceInfo instance = new AssistantInstanceInfo();
        instance.setId("robot-remote");
        instance.setPartnerAccount("assist-001");
        instance.setOwnerWelinkId("owner-001");
        instance.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_ASSISTANT_SQUARE);
        instance.setBizRobotTag("tag-001");
        when(assistantInstanceInfoService.getInstanceInfo("assist-001")).thenReturn(instance);

        AssistantInfoService instanceAwareService = new AssistantInfoService(
                properties, redisTemplate, assistantInstanceInfoService, apiCallMetricsService);

        AssistantInfo info = instanceAwareService.getAssistantInfo(null, "assist-001");

        assertNotNull(info);
        assertEquals("business", info.getAssistantScope());
        assertEquals("robot-remote", info.getId());
        assertEquals("tag-001", info.getBusinessTag());
        assertEquals("assistant_square", info.getCloudProfile());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("getAssistantInfo(ak, account): remoteType=2 routes as business with default profile")
    void getAssistantInfo_defaultProtocolRemoteInstance_returnsBusinessWithDefaultProfile() {
        AssistantInstanceInfo instance = new AssistantInstanceInfo();
        instance.setId("robot-default");
        instance.setPartnerAccount("assist-001");
        instance.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_DEFAULT);
        instance.setBizRobotTag("tag-001");
        when(assistantInstanceInfoService.getInstanceInfo("assist-001")).thenReturn(instance);

        AssistantInfoService instanceAwareService = new AssistantInfoService(
                properties, redisTemplate, assistantInstanceInfoService, apiCallMetricsService);

        AssistantInfo info = instanceAwareService.getAssistantInfo(null, "assist-001");

        assertNotNull(info);
        assertEquals("business", info.getAssistantScope());
        assertEquals("robot-default", info.getId());
        assertEquals("tag-001", info.getBusinessTag());
        assertEquals("default", info.getCloudProfile());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("getAssistantInfo(ak, account): local personal instance keeps scope and injects bizRobotTag")
    void getAssistantInfo_localPersonalInstance_injectsBizRobotTag() {
        AssistantInstanceInfo instance = new AssistantInstanceInfo();
        instance.setId("robot-local");
        instance.setPartnerAccount("assist-local");
        instance.setOwnerWelinkId("owner-local");
        instance.setAppKey("ak-local");
        instance.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_LOCAL);
        instance.setBizRobotTag("robot-local");
        when(assistantInstanceInfoService.getInstanceInfo("assist-local")).thenReturn(instance);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant:info:ak-local")).thenReturn(null);

         TestableAssistantInfoService instanceAwareService = new TestableAssistantInfoService(
                 properties, redisTemplate, assistantInstanceInfoService, apiCallMetricsService);
         AssistantInfo upstream = new AssistantInfo();
         upstream.setId("robot-upstream");
         upstream.setAssistantScope("personal");
         upstream.setBusinessTag(null);
         instanceAwareService.stubFetch(upstream);

        AssistantInfo info = instanceAwareService.getAssistantInfo(null, "assist-local");

        assertNotNull(info);
        assertEquals("personal", info.getAssistantScope());
        assertEquals("robot-local", info.getId());
        assertEquals("robot-local", info.getBusinessTag());
    }

    @Test
    @DisplayName("getAssistantInfo(ak, account): no-AK local instance is not business")
    void getAssistantInfo_noAkLocalInstance_returnsNull() {
        AssistantInstanceInfo instance = new AssistantInstanceInfo();
        instance.setPartnerAccount("assist-001");
        instance.setOwnerWelinkId("owner-001");
        instance.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_LOCAL);
         when(assistantInstanceInfoService.getInstanceInfo("assist-001")).thenReturn(instance);

         AssistantInfoService instanceAwareService = new AssistantInfoService(
                 properties, redisTemplate, assistantInstanceInfoService, apiCallMetricsService);

         AssistantInfo info = instanceAwareService.getAssistantInfo(null, "assist-001");

        assertNull(info);
        verify(redisTemplate, never()).opsForValue();
    }
}
