package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.AssistantInfoProperties;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 发布期 Redis 缓存兼容回归。
 * 旧 schema 的 cached JSON（含 "appId" 字段）反序列化时抛 UnrecognizedPropertyException，
 * 应被 try-catch 兜住，fall through 到上游 fetch（不阻塞业务流）。
 */
@ExtendWith(MockitoExtension.class)
class AssistantInfoCacheCompatTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock ApiCallMetricsService apiCallMetricsService;

    private AssistantInfoProperties properties;
    private AssistantInfoService service;

    @BeforeEach
    void setUp() {
        properties = new AssistantInfoProperties();
        // override fetchFromUpstream 以避免真实 HTTP 调用
        service = new AssistantInfoService(properties, redisTemplate, apiCallMetricsService) {
            @Override
            protected AssistantInfo fetchFromUpstream(String ak) {
                AssistantInfo info = new AssistantInfo();
                info.setAssistantScope("business");
                info.setBusinessTag("tag-fresh");
                return info;
            }
        };
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Old schema cached JSON (contains 'appId') → swallowed by try-catch, falls through to upstream")
    void oldSchemaCachedJson_swallowed_fallsThroughToUpstream() {
        // 旧 schema：含 "appId" 字段，没有 "businessTag"
        String oldJson = "{\"assistantScope\":\"business\",\"appId\":\"app_x\"," +
                "\"cloudEndpoint\":\"https://x\",\"cloudProtocol\":\"sse\",\"authType\":\"soa\"}";
        when(valueOperations.get(anyString())).thenReturn(oldJson);

        AssistantInfo info = service.getAssistantInfo("ak-test");

        // 不抛异常，回源后拿到新 schema 的 info
        assertNotNull(info);
    }
}
