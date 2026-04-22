package com.opencode.cui.skill.service;

import com.opencode.cui.skill.repository.SysConfigMapper;
import com.opencode.cui.skill.model.SysConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SysConfigService 单元测试：验证 Redis 缓存逻辑、DB 查询和 CRUD 操作。
 */
@ExtendWith(MockitoExtension.class)
class SysConfigServiceTest {

    @Mock
    private SysConfigMapper sysConfigMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SysConfigService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SysConfigService(sysConfigMapper, redisTemplate);
    }

    // ------------------------------------------------------------------ getValue

    @Test
    @DisplayName("getValue: Redis 命中时直接返回缓存值")
    void getValueReturnsCachedValue() {
        when(valueOps.get("ss:config:SYSTEM:site_name")).thenReturn("OpenCode");

        String result = service.getValue("SYSTEM", "site_name");

        assertEquals("OpenCode", result);
        verify(sysConfigMapper, never()).findByTypeAndKey(any(), any());
    }

    @Test
    @DisplayName("getValue: Redis 未命中时查 DB，status=1 写缓存并返回")
    void getValueQueriesDbOnCacheMiss() {
        when(valueOps.get("ss:config:SYSTEM:site_name")).thenReturn(null);

        SysConfig config = buildConfig(1L, "SYSTEM", "site_name", "OpenCode", 1);
        when(sysConfigMapper.findByTypeAndKey("SYSTEM", "site_name")).thenReturn(config);

        String result = service.getValue("SYSTEM", "site_name");

        assertEquals("OpenCode", result);
        verify(valueOps).set(eq("ss:config:SYSTEM:site_name"), eq("OpenCode"), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("getValue: DB 中 status=0 时返回 null，不写缓存")
    void getValueReturnsNullForDisabledConfig() {
        when(valueOps.get("ss:config:SYSTEM:disabled_key")).thenReturn(null);

        SysConfig config = buildConfig(2L, "SYSTEM", "disabled_key", "val", 0);
        when(sysConfigMapper.findByTypeAndKey("SYSTEM", "disabled_key")).thenReturn(config);

        String result = service.getValue("SYSTEM", "disabled_key");

        assertNull(result);
        verify(valueOps, never()).set(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("getValue: DB 中不存在记录时返回 null")
    void getValueReturnsNullWhenNotFound() {
        when(valueOps.get("ss:config:SYSTEM:not_exist")).thenReturn(null);
        when(sysConfigMapper.findByTypeAndKey("SYSTEM", "not_exist")).thenReturn(null);

        String result = service.getValue("SYSTEM", "not_exist");

        assertNull(result);
    }

    // ------------------------------------------------------------------ listByType

    @Test
    @DisplayName("listByType: 按 configType 查询，按 sort_order 排序")
    void listByTypeReturnsOrdered() {
        List<SysConfig> configs = List.of(
                buildConfig(1L, "SYSTEM", "key_a", "val_a", 1),
                buildConfig(2L, "SYSTEM", "key_b", "val_b", 1)
        );
        when(sysConfigMapper.findByType("SYSTEM")).thenReturn(configs);

        List<SysConfig> result = service.listByType("SYSTEM");

        assertEquals(2, result.size());
        verify(sysConfigMapper).findByType("SYSTEM");
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("create: 插入 DB 并清除对应缓存")
    void createInsertsAndEvictsCache() {
        SysConfig config = buildConfig(null, "SYSTEM", "new_key", "new_val", 1);

        service.create(config);

        verify(sysConfigMapper).insert(config);
        verify(redisTemplate).delete("ss:config:SYSTEM:new_key");
    }

    // ------------------------------------------------------------------ update

    @Test
    @DisplayName("update: 更新 DB 并清除对应缓存")
    void updateUpdatesAndEvictsCache() {
        SysConfig config = buildConfig(10L, "SYSTEM", "exist_key", "new_val", 1);

        service.update(config);

        verify(sysConfigMapper).update(config);
        verify(redisTemplate).delete("ss:config:SYSTEM:exist_key");
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("delete: 删除 DB 记录（缓存自然过期）")
    void deleteRemovesFromDb() {
        service.delete(99L);

        verify(sysConfigMapper).deleteById(99L);
        // 不主动删缓存，缓存自然过期
        verify(redisTemplate, never()).delete(anyString());
    }

    // ------------------------------------------------------------------ Redis resilience

    @Test
    @DisplayName("getValue: Redis 读异常时降级直查 DB 并返回值")
    void getValueFallsBackToDbOnRedisReadFailure() {
        when(valueOps.get("ss:config:SYSTEM:site_name"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        SysConfig config = buildConfig(1L, "SYSTEM", "site_name", "OpenCode", 1);
        when(sysConfigMapper.findByTypeAndKey("SYSTEM", "site_name")).thenReturn(config);

        String result = service.getValue("SYSTEM", "site_name");

        assertEquals("OpenCode", result);
        verify(sysConfigMapper).findByTypeAndKey("SYSTEM", "site_name");
    }

    @Test
    @DisplayName("getValue: Redis 写异常时不抛，返回 DB 值")
    void getValueReturnsDbValueWhenRedisWriteFails() {
        when(valueOps.get("ss:config:SYSTEM:site_name")).thenReturn(null);

        SysConfig config = buildConfig(1L, "SYSTEM", "site_name", "OpenCode", 1);
        when(sysConfigMapper.findByTypeAndKey("SYSTEM", "site_name")).thenReturn(config);

        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        assertDoesNotThrow(() -> {
            String result = service.getValue("SYSTEM", "site_name");
            assertEquals("OpenCode", result);
        });
    }

    @Test
    @DisplayName("create: Redis delete 异常时 DB 写入仍成功（不触发事务回滚）")
    void createSucceedsWhenRedisEvictFails() {
        SysConfig config = buildConfig(null, "SYSTEM", "new_key", "new_value", 1);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).delete("ss:config:SYSTEM:new_key");

        assertDoesNotThrow(() -> service.create(config));

        verify(sysConfigMapper).insert(config);
    }

    @Test
    @DisplayName("update: Redis delete 异常时 DB 更新仍成功（不触发事务回滚）")
    void updateSucceedsWhenRedisEvictFails() {
        SysConfig config = buildConfig(42L, "SYSTEM", "exist_key", "new_value", 1);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).delete("ss:config:SYSTEM:exist_key");

        assertDoesNotThrow(() -> service.update(config));

        verify(sysConfigMapper).update(config);
    }

    // ------------------------------------------------------------------ helper

    private SysConfig buildConfig(Long id, String type, String key, String value, int status) {
        SysConfig c = new SysConfig();
        c.setId(id);
        c.setConfigType(type);
        c.setConfigKey(key);
        c.setConfigValue(value);
        c.setStatus(status);
        c.setSortOrder(0);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }
}
