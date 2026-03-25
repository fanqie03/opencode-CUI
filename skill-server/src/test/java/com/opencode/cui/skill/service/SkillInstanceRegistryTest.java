package com.opencode.cui.skill.service;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** SkillInstanceRegistry 单元测试：验证 SS 实例心跳写入、探活查询和销毁逻辑。 */
class SkillInstanceRegistryTest {

    private static final String INSTANCE_ID = "ss-az1-test";
    private static final String REDIS_KEY = "ss:internal:instance:" + INSTANCE_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SkillInstanceRegistry registry;

    @BeforeEach
    void setUp() {
        // lenient：仅 register/refresh 等写入场景需要 opsForValue；其他测试无需此 stub
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new SkillInstanceRegistry(redisTemplate, INSTANCE_ID);
    }

    @Test
    @DisplayName("register 启动时向 Redis 写入心跳 key，TTL 30s")
    void register_shouldWriteRedisKey() {
        registry.register();

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(REDIS_KEY), eq("alive"), ttlCaptor.capture());
        assertEquals(Duration.ofSeconds(30), ttlCaptor.getValue());
    }

    @Test
    @DisplayName("isInstanceAlive 目标实例存在时返回 true")
    void isInstanceAlive_existing_shouldReturnTrue() {
        when(redisTemplate.hasKey(REDIS_KEY)).thenReturn(Boolean.TRUE);

        assertTrue(registry.isInstanceAlive(INSTANCE_ID));
    }

    @Test
    @DisplayName("isInstanceAlive 目标实例不存在时返回 false")
    void isInstanceAlive_missing_shouldReturnFalse() {
        when(redisTemplate.hasKey(REDIS_KEY)).thenReturn(Boolean.FALSE);

        assertFalse(registry.isInstanceAlive(INSTANCE_ID));
    }

    @Test
    @DisplayName("destroy 关闭时删除 Redis 心跳 key")
    void destroy_shouldDeleteKey() {
        registry.destroy();

        verify(redisTemplate).delete(REDIS_KEY);
    }

    @Test
    @DisplayName("refreshHeartbeat 定时刷新向 Redis 写入心跳 key，TTL 30s")
    void refreshHeartbeat_shouldRenewTtl() {
        registry.refreshHeartbeat();

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(REDIS_KEY), eq("alive"), ttlCaptor.capture());
        assertEquals(Duration.ofSeconds(30), ttlCaptor.getValue());
    }

    @Test
    @DisplayName("getInstanceId 返回注入的实例 ID")
    void getInstanceId_shouldReturnInjectedId() {
        assertEquals(INSTANCE_ID, registry.getInstanceId());
    }
}
