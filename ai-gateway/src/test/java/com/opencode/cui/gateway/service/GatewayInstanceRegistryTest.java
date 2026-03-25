package com.opencode.cui.gateway.service;

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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/** GatewayInstanceRegistry 单元测试：验证 Redis 实例注册、心跳刷新和销毁逻辑。 */
@ExtendWith(MockitoExtension.class)
class GatewayInstanceRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GatewayInstanceRegistry registry;

    private static final String INSTANCE_ID = "gw-az1-1";
    private static final String WS_URL = "ws://10.0.1.5:8081/ws/skill";
    private static final int TTL_SECONDS = 30;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new GatewayInstanceRegistry(
                redisTemplate, objectMapper, INSTANCE_ID, WS_URL, TTL_SECONDS);
    }

    @Test
    @DisplayName("register 写入新旧两个 Redis key 并设置 TTL")
    void registerWritesRedisKeyWithTtl() {
        registry.register();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        // dual-write: gw:instance:{id} (legacy) + gw:internal:instance:{id} (new)
        verify(valueOperations, org.mockito.Mockito.times(2))
                .set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        var keys = keyCaptor.getAllValues();
        assertTrue(keys.contains("gw:instance:" + INSTANCE_ID));
        assertTrue(keys.contains("gw:internal:instance:" + INSTANCE_ID));
        assertTrue(valueCaptor.getAllValues().stream().allMatch(v -> v.contains(WS_URL)));
        assertTrue(ttlCaptor.getAllValues().stream().allMatch(t -> t.equals(Duration.ofSeconds(TTL_SECONDS))));
    }

    @Test
    @DisplayName("refreshHeartbeat 刷新 TTL")
    void refreshHeartbeatExtendsTtl() {
        registry.register();
        registry.refreshHeartbeat();

        // register(2 keys) + refreshHeartbeat(2 keys) = 4 次 set 调用
        verify(valueOperations, org.mockito.Mockito.times(4)).set(
                anyString(),
                anyString(),
                eq(Duration.ofSeconds(TTL_SECONDS)));
    }

    @Test
    @DisplayName("destroy 删除 Redis key")
    void destroyRemovesKey() {
        registry.destroy();

        // dual-delete: both legacy and internal keys
        verify(redisTemplate).delete("gw:instance:" + INSTANCE_ID);
        verify(redisTemplate).delete("gw:internal:instance:" + INSTANCE_ID);
    }

    @Test
    @DisplayName("getInstanceId 返回配置的实例 ID")
    void getInstanceIdReturnsConfiguredId() {
        assertEquals(INSTANCE_ID, registry.getInstanceId());
    }

    @Test
    @DisplayName("register 的 value 包含 JSON 格式的 wsUrl 和 startedAt")
    void registerValueContainsJsonFields() {
        registry.register();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, org.mockito.Mockito.atLeastOnce())
                .set(anyString(), valueCaptor.capture(), eq(Duration.ofSeconds(TTL_SECONDS)));

        String value = valueCaptor.getValue();
        assertTrue(value.contains("\"wsUrl\""));
        assertTrue(value.contains("\"startedAt\""));
        assertTrue(value.contains(WS_URL));
    }
}
