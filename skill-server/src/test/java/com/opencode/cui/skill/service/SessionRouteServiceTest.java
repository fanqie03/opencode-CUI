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
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionRouteService unit tests (pure Redis ownership).
 * Verifies SETNX-based ownership creation, Redis-only lookups,
 * and Lua CAS takeover.
 */
@ExtendWith(MockitoExtension.class)
class SessionRouteServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SessionRouteService service;

    private static final String INSTANCE_ID = "ss-az1-1";
    private static final int TTL_SECONDS = 1800;
    private static final String CACHE_PREFIX = "ss:internal:session:";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SessionRouteService(redisTemplate, INSTANCE_ID, TTL_SECONDS);
    }

    @Nested
    @DisplayName("createRoute - SETNX ownership")
    class CreateRouteTests {

        @Test
        @DisplayName("createRoute writes ownership via SETNX")
        void createRouteWritesRedis() {
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
        @DisplayName("createRoute does not overwrite existing ownership")
        void createRouteDoesNotOverwrite() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(false);
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn("ss-other-2");

            service.createRoute("ak-1", 12345L, "skill-server", "user-123");

            // No exception, ownership not overwritten
            verify(valueOps).setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)));
        }
    }

    @Nested
    @DisplayName("closeRoute - delete Redis key")
    class CloseRouteTests {

        @Test
        @DisplayName("closeRoute deletes Redis ownership key")
        void closeRouteDeletesRedis() {
            service.closeRoute(12345L, "skill-server");

            verify(redisTemplate).delete(CACHE_PREFIX + "12345");
        }
    }

    @Nested
    @DisplayName("Ownership checks")
    class OwnershipTests {

        @Test
        @DisplayName("isMySession returns true when Redis value matches instanceId")
        void isMySessionReturnsTrueWhenMatching() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn(INSTANCE_ID);

            assertTrue(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMySession returns false when Redis value does not match")
        void isMySessionReturnsFalseWhenNotMatching() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn("ss-other-2");

            assertFalse(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMySession returns false when key not found")
        void isMySessionReturnsFalseWhenNotFound() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn(null);

            assertFalse(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMySession degrades to true on Redis exception")
        void isMySessionDegradesToTrueOnError() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenThrow(new RuntimeException("Redis down"));

            assertTrue(service.isMySession("12345"));
        }
    }

    @Nested
    @DisplayName("getOwnerInstance - pure Redis")
    class GetOwnerInstanceTests {

        @Test
        @DisplayName("getOwnerInstance returns owner from Redis")
        void returnsOwnerFromRedis() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn(INSTANCE_ID);

            String result = service.getOwnerInstance("12345");

            assertEquals(INSTANCE_ID, result);
        }

        @Test
        @DisplayName("getOwnerInstance returns null when key missing")
        void returnsNullWhenMissing() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn(null);

            assertNull(service.getOwnerInstance("12345"));
        }

        @Test
        @DisplayName("getOwnerInstance returns null on Redis error")
        void returnsNullOnRedisError() {
            when(valueOps.get(CACHE_PREFIX + "12345")).thenThrow(new RuntimeException("Redis down"));

            assertNull(service.getOwnerInstance("12345"));
        }
    }

    @Nested
    @DisplayName("ensureRouteOwnership - SETNX")
    class EnsureOwnershipTests {

        @Test
        @DisplayName("ensureRouteOwnership succeeds with SETNX")
        void ensureOwnershipSucceedsViaSetnx() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(true);

            assertTrue(service.ensureRouteOwnership("12345", "ak-1", "user-123"));
        }

        @Test
        @DisplayName("ensureRouteOwnership returns true when already owned by this instance")
        void ensureOwnershipReturnsTrueWhenAlreadyOwned() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(false);
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn(INSTANCE_ID);

            assertTrue(service.ensureRouteOwnership("12345", "ak-1", "user-123"));
        }

        @Test
        @DisplayName("ensureRouteOwnership returns false when owned by another instance")
        void ensureOwnershipReturnsFalseWhenOwnedByOther() {
            when(valueOps.setIfAbsent(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(false);
            when(valueOps.get(CACHE_PREFIX + "12345")).thenReturn("ss-other-2");

            assertFalse(service.ensureRouteOwnership("12345", "ak-1", "user-123"));
        }

        @Test
        @DisplayName("ensureRouteOwnership degrades to true on Redis error")
        void ensureOwnershipDegradesToTrueOnError() {
            when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                    .thenThrow(new RuntimeException("Redis down"));

            assertTrue(service.ensureRouteOwnership("12345", "ak-1", "user-123"));
        }
    }

    @Nested
    @DisplayName("tryTakeover - Lua CAS")
    class TakeoverTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("tryTakeover succeeds when CAS returns 1")
        void takeoverSucceeds() {
            when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                    eq("ss-dead-1"), eq(INSTANCE_ID), eq("1800")))
                    .thenReturn(1L);

            assertTrue(service.tryTakeover("12345", "ss-dead-1", INSTANCE_ID));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("tryTakeover fails when CAS returns 0")
        void takeoverConflict() {
            when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                    eq("ss-dead-1"), eq(INSTANCE_ID), eq("1800")))
                    .thenReturn(0L);

            assertFalse(service.tryTakeover("12345", "ss-dead-1", INSTANCE_ID));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("tryTakeover returns false on Redis error")
        void takeoverFailsOnError() {
            when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                    anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Redis down"));

            assertFalse(service.tryTakeover("12345", "ss-dead-1", INSTANCE_ID));
        }
    }

    @Nested
    @DisplayName("Deprecated methods")
    class DeprecatedTests {

        @Test
        @DisplayName("closeAllByInstance is no-op")
        void closeAllByInstanceIsNoop() {
            // Should not throw
            service.closeAllByInstance();
        }
    }
}
