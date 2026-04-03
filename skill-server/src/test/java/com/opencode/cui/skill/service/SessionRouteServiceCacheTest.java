package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionRouteService Redis-only ownership tests.
 * Validates that all core operations (create, close, ownership check, getOwner)
 * interact only with Redis and never fall back to MySQL.
 */
@ExtendWith(MockitoExtension.class)
class SessionRouteServiceCacheTest {

    private static final String INSTANCE_ID = "ss-az1-1";
    private static final int TTL_SECONDS = 1800;
    private static final String CACHE_PREFIX = "ss:internal:session:";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SessionRouteService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SessionRouteService(redisTemplate, INSTANCE_ID, TTL_SECONDS);
    }

    // ==================== getOwnerInstance ====================

    @Nested
    @DisplayName("getOwnerInstance - pure Redis")
    class GetOwnerInstance {

        @Test
        @DisplayName("Redis hit returns owner without MySQL fallback")
        void redisHitReturnsOwner() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn(INSTANCE_ID);

            String result = service.getOwnerInstance("12345");

            assertEquals(INSTANCE_ID, result);
        }

        @Test
        @DisplayName("Redis miss returns null without MySQL fallback")
        void redisMissReturnsNull() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn(null);

            String result = service.getOwnerInstance("12345");

            assertNull(result);
        }

        @Test
        @DisplayName("Redis error returns null without MySQL fallback")
        void redisErrorReturnsNull() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenThrow(new RuntimeException("Redis down"));

            String result = service.getOwnerInstance("12345");

            assertNull(result);
        }

        @Test
        @DisplayName("null/blank input returns null")
        void nullInputReturnsNull() {
            assertNull(service.getOwnerInstance(null));
            assertNull(service.getOwnerInstance(""));
            assertNull(service.getOwnerInstance("  "));
        }
    }

    // ==================== createRoute ====================

    @Nested
    @DisplayName("createRoute - SETNX only")
    class CreateRoute {

        @Test
        @DisplayName("createRoute uses SETNX, never writes MySQL")
        void createRouteUsesSetnxNoMySQL() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(true);

            service.createRoute("ak-1", 12345L, "skill-server", "user-123");

            verify(valueOps).setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)));
        }

        @Test
        @DisplayName("createRoute does not overwrite when key already exists")
        void createRouteDoesNotOverwrite() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(false);
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn("ss-other-2");

            service.createRoute("ak-1", 12345L, "skill-server", "user-123");

            // No forced overwrite
            verify(valueOps, never()).set(eq(CACHE_PREFIX + "12345"), any(), any(Duration.class));
        }

        @Test
        @DisplayName("createRoute Redis error does not throw")
        void createRouteRedisErrorDoesNotThrow() {
            when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                    .thenThrow(new RuntimeException("Redis down"));

            // Should not throw
            service.createRoute("ak-1", 12345L, "skill-server", "user-123");
        }
    }

    // ==================== closeRoute ====================

    @Nested
    @DisplayName("closeRoute - delete Redis key only")
    class CloseRoute {

        @Test
        @DisplayName("closeRoute deletes Redis key, no MySQL update")
        void closeRouteDeletesRedisNoMySQL() {
            service.closeRoute(12345L, "skill-server");

            verify(redisTemplate).delete(CACHE_PREFIX + "12345");
        }

        @Test
        @DisplayName("closeRoute Redis error does not propagate")
        void closeRouteRedisErrorDoesNotThrow() {
            when(redisTemplate.delete(CACHE_PREFIX + "12345")).thenThrow(
                    new RuntimeException("Redis down"));

            // Should not throw
            service.closeRoute(12345L, "skill-server");
        }
    }

    // ==================== ensureRouteOwnership ====================

    @Nested
    @DisplayName("ensureRouteOwnership - SETNX based")
    class EnsureOwnership {

        @Test
        @DisplayName("ensureRouteOwnership claims via SETNX, no MySQL")
        void claimsViaSetnxNoMySQL() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(true);

            boolean result = service.ensureRouteOwnership("12345", "ak-1", "user-123");

            assertTrue(result);
        }

        @Test
        @DisplayName("ensureRouteOwnership returns false when owned by other, no MySQL")
        void returnsFalseForOtherOwner() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(false);
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn("ss-other-2");

            boolean result = service.ensureRouteOwnership("12345", "ak-1", "user-123");

            assertFalse(result);
        }
    }
}
