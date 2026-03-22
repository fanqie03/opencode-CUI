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
    @DisplayName("register 写入 Redis key gw:instance:{id} 并设置 TTL")
    void registerWritesRedisKeyWithTtl() {
        registry.register();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertEquals("gw:instance:" + INSTANCE_ID, keyCaptor.getValue());
        assertTrue(valueCaptor.getValue().contains(WS_URL));
        assertTrue(valueCaptor.getValue().contains("startedAt"));
        assertEquals(Duration.ofSeconds(TTL_SECONDS), ttlCaptor.getValue());
    }

    @Test
    @DisplayName("refreshHeartbeat 刷新 TTL")
    void refreshHeartbeatExtendsTtl() {
        registry.register();
        registry.refreshHeartbeat();

        // register + refreshHeartbeat = 2 次 set 调用
        verify(valueOperations, org.mockito.Mockito.times(2)).set(
                eq("gw:instance:" + INSTANCE_ID),
                anyString(),
                eq(Duration.ofSeconds(TTL_SECONDS)));
    }

    @Test
    @DisplayName("destroy 删除 Redis key")
    void destroyRemovesKey() {
        registry.destroy();

        verify(redisTemplate).delete("gw:instance:" + INSTANCE_ID);
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
        verify(valueOperations).set(anyString(), valueCaptor.capture(), eq(Duration.ofSeconds(TTL_SECONDS)));

        String value = valueCaptor.getValue();
        assertTrue(value.contains("\"wsUrl\""));
        assertTrue(value.contains("\"startedAt\""));
        assertTrue(value.contains(WS_URL));
    }
}
