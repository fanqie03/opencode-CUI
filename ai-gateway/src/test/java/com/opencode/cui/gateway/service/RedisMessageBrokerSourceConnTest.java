package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RedisMessageBroker source-connection methods.
 * Verifies connection-level (compound field) registration, unregistration,
 * heartbeat refresh, extractUniqueGwInstances, cleanup, and discover.
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerSourceConnTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    @SuppressWarnings("rawtypes")
    private HashOperations hashOperations;

    private RedisMessageBroker broker;

    private static final String SOURCE_TYPE = "skill-server";
    private static final String SOURCE_INSTANCE = "ss-001";
    private static final String GW_INSTANCE = "gw-az1-1";
    private static final String SESSION_ID = "session-abc";
    private static final String HASH_KEY = "gw:source-conn:skill-server:ss-001";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        broker = new RedisMessageBroker(redisTemplate, listenerContainer, new ObjectMapper());
    }

    // ==================== RegisterTests ====================

    @Nested
    @DisplayName("4-param registerSourceConnection")
    class RegisterTests {

        @Test
        @DisplayName("writes compound field gwInstanceId#sessionId to HASH")
        @SuppressWarnings("unchecked")
        void writesCompoundField() {
            broker.registerSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            String compoundField = GW_INSTANCE + "#" + SESSION_ID;
            verify(hashOperations).put(eq(HASH_KEY), eq(compoundField), any(String.class));
        }

        @Test
        @DisplayName("dual-writes compat field gwInstanceId to HASH")
        @SuppressWarnings("unchecked")
        void writesDualCompatField() {
            broker.registerSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            verify(hashOperations).put(eq(HASH_KEY), eq(GW_INSTANCE), any(String.class));
        }

        @Test
        @DisplayName("sets TTL on the key")
        void setsTtl() {
            broker.registerSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            verify(redisTemplate).expire(eq(HASH_KEY), eq(Duration.ofHours(2)));
        }

        @Test
        @DisplayName("null sessionId is a no-op")
        @SuppressWarnings("unchecked")
        void nullSessionIdIsNoop() {
            broker.registerSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, null);

            verify(hashOperations, never()).put(any(), any(), any());
            verify(redisTemplate, never()).expire(any(), any(Duration.class));
        }

        @Test
        @DisplayName("null other params is a no-op")
        @SuppressWarnings("unchecked")
        void nullOtherParamsIsNoop() {
            broker.registerSourceConnection(null, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);
            broker.registerSourceConnection(SOURCE_TYPE, null, GW_INSTANCE, SESSION_ID);
            broker.registerSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, null, SESSION_ID);

            verify(hashOperations, never()).put(any(), any(), any());
        }
    }

    // ==================== UnregisterTests ====================

    @Nested
    @DisplayName("4-param unregisterSourceConnection")
    class UnregisterTests {

        @Test
        @DisplayName("deletes compound field gwInstanceId#sessionId from HASH")
        @SuppressWarnings("unchecked")
        void deletesCompoundField() {
            broker.unregisterSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            String compoundField = GW_INSTANCE + "#" + SESSION_ID;
            verify(hashOperations).delete(eq(HASH_KEY), eq(compoundField));
        }

        @Test
        @DisplayName("does NOT delete compat field gwInstanceId")
        @SuppressWarnings("unchecked")
        void doesNotDeleteCompatField() {
            broker.unregisterSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            // compat field must NOT be deleted — other connections may still use it
            verify(hashOperations, never()).delete(eq(HASH_KEY), eq(GW_INSTANCE));
        }

        @Test
        @DisplayName("null sessionId is a no-op")
        @SuppressWarnings("unchecked")
        void nullSessionIdIsNoop() {
            broker.unregisterSourceConnection(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, null);

            verify(hashOperations, never()).delete(any(), any());
        }
    }

    // ==================== RefreshTests ====================

    @Nested
    @DisplayName("4-param refreshSourceConnectionHeartbeat")
    class RefreshTests {

        @Test
        @DisplayName("updates compound field gwInstanceId#sessionId")
        @SuppressWarnings("unchecked")
        void updatesCompoundField() {
            broker.refreshSourceConnectionHeartbeat(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            String compoundField = GW_INSTANCE + "#" + SESSION_ID;
            verify(hashOperations).put(eq(HASH_KEY), eq(compoundField), any(String.class));
        }

        @Test
        @DisplayName("dual-writes compat field gwInstanceId")
        @SuppressWarnings("unchecked")
        void dualWritesCompatField() {
            broker.refreshSourceConnectionHeartbeat(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            verify(hashOperations).put(eq(HASH_KEY), eq(GW_INSTANCE), any(String.class));
        }

        @Test
        @DisplayName("sets TTL on the key")
        void setsTtl() {
            broker.refreshSourceConnectionHeartbeat(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, SESSION_ID);

            verify(redisTemplate).expire(eq(HASH_KEY), eq(Duration.ofHours(2)));
        }

        @Test
        @DisplayName("null sessionId is a no-op")
        @SuppressWarnings("unchecked")
        void nullSessionIdIsNoop() {
            broker.refreshSourceConnectionHeartbeat(SOURCE_TYPE, SOURCE_INSTANCE, GW_INSTANCE, null);

            verify(hashOperations, never()).put(any(), any(), any());
        }
    }

    // ==================== ExtractGwInstancesTests ====================

    @Nested
    @DisplayName("extractUniqueGwInstances")
    class ExtractGwInstancesTests {

        @Test
        @DisplayName("extracts gwInstanceId from compound field format")
        void extractsFromCompoundFormat() {
            Map<String, Long> sourceConnections = new HashMap<>();
            sourceConnections.put("gw-az1-1#session-001", 1000L);
            sourceConnections.put("gw-az1-1#session-002", 1001L);
            sourceConnections.put("gw-az2-1#session-003", 1002L);

            Set<String> result = broker.extractUniqueGwInstances(sourceConnections);

            assertThat(result).containsExactlyInAnyOrder("gw-az1-1", "gw-az2-1");
        }

        @Test
        @DisplayName("extracts gwInstanceId from legacy (plain) format")
        void extractsFromLegacyFormat() {
            Map<String, Long> sourceConnections = new HashMap<>();
            sourceConnections.put("gw-az1-1", 1000L);
            sourceConnections.put("gw-az2-1", 1001L);

            Set<String> result = broker.extractUniqueGwInstances(sourceConnections);

            assertThat(result).containsExactlyInAnyOrder("gw-az1-1", "gw-az2-1");
        }

        @Test
        @DisplayName("handles mixed compound and legacy format")
        void handlesMixedFormats() {
            Map<String, Long> sourceConnections = new HashMap<>();
            sourceConnections.put("gw-az1-1#session-001", 1000L);
            sourceConnections.put("gw-az1-1", 1001L);   // legacy compat field
            sourceConnections.put("gw-az2-1#session-abc", 1002L);

            Set<String> result = broker.extractUniqueGwInstances(sourceConnections);

            assertThat(result).containsExactlyInAnyOrder("gw-az1-1", "gw-az2-1");
        }

        @Test
        @DisplayName("returns empty set for null input")
        void returnsEmptyForNull() {
            Set<String> result = broker.extractUniqueGwInstances(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty set for empty input")
        void returnsEmptyForEmptyMap() {
            Set<String> result = broker.extractUniqueGwInstances(new HashMap<>());
            assertThat(result).isEmpty();
        }
    }

    // ==================== CleanupTests ====================

    @Nested
    @DisplayName("cleanupStaleSourceConnections (scan-and-delete)")
    class CleanupTests {

        @Test
        @DisplayName("deletes exact match field gwInstanceId")
        @SuppressWarnings("unchecked")
        void deletesExactMatchField() {
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(Set.of(HASH_KEY));
            Map<Object, Object> entries = new HashMap<>();
            entries.put(GW_INSTANCE, "1000");
            when(hashOperations.entries(HASH_KEY)).thenReturn(entries);

            broker.cleanupStaleSourceConnections(GW_INSTANCE);

            verify(hashOperations).delete(eq(HASH_KEY), eq(GW_INSTANCE));
        }

        @Test
        @DisplayName("deletes prefix match fields gwInstanceId#*")
        @SuppressWarnings("unchecked")
        void deletesPrefixMatchFields() {
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(Set.of(HASH_KEY));
            Map<Object, Object> entries = new HashMap<>();
            entries.put(GW_INSTANCE + "#session-001", "1000");
            entries.put(GW_INSTANCE + "#session-002", "1001");
            when(hashOperations.entries(HASH_KEY)).thenReturn(entries);

            broker.cleanupStaleSourceConnections(GW_INSTANCE);

            verify(hashOperations).delete(eq(HASH_KEY), eq(GW_INSTANCE + "#session-001"));
            verify(hashOperations).delete(eq(HASH_KEY), eq(GW_INSTANCE + "#session-002"));
        }

        @Test
        @DisplayName("does NOT delete fields belonging to other gwInstanceId")
        @SuppressWarnings("unchecked")
        void doesNotDeleteOtherGwFields() {
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(Set.of(HASH_KEY));
            Map<Object, Object> entries = new HashMap<>();
            entries.put("gw-other#session-001", "1000");
            when(hashOperations.entries(HASH_KEY)).thenReturn(entries);

            broker.cleanupStaleSourceConnections(GW_INSTANCE);

            verify(hashOperations, never()).delete(eq(HASH_KEY), eq("gw-other#session-001"));
        }

        @Test
        @DisplayName("null gwInstanceId is a no-op")
        @SuppressWarnings("unchecked")
        void nullGwInstanceIdIsNoop() {
            broker.cleanupStaleSourceConnections(null);

            verify(redisTemplate, never()).keys(any());
        }
    }

    // ==================== DiscoverTests ====================

    @Nested
    @DisplayName("discoverAllSourceGwInstances (extracts gwInstanceId from compound fields)")
    class DiscoverTests {

        @Test
        @DisplayName("extracts unique gwInstanceIds from compound fields")
        @SuppressWarnings("unchecked")
        void extractsUniqueGwInstanceIds() {
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(Set.of(HASH_KEY));
            long now = System.currentTimeMillis() / 1000;
            Map<Object, Object> entries = new HashMap<>();
            entries.put("gw-az1-1#session-001", String.valueOf(now));
            entries.put("gw-az1-1#session-002", String.valueOf(now));
            entries.put("gw-az2-1#session-abc", String.valueOf(now));
            when(hashOperations.entries(HASH_KEY)).thenReturn(entries);

            Set<String> result = broker.discoverAllSourceGwInstances();

            assertThat(result).containsExactlyInAnyOrder("gw-az1-1", "gw-az2-1");
        }

        @Test
        @DisplayName("handles legacy plain gwInstanceId fields")
        @SuppressWarnings("unchecked")
        void handlesLegacyPlainFields() {
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(Set.of(HASH_KEY));
            long now = System.currentTimeMillis() / 1000;
            Map<Object, Object> entries = new HashMap<>();
            entries.put("gw-az1-1", String.valueOf(now));
            when(hashOperations.entries(HASH_KEY)).thenReturn(entries);

            Set<String> result = broker.discoverAllSourceGwInstances();

            assertThat(result).containsExactlyInAnyOrder("gw-az1-1");
        }

        @Test
        @DisplayName("excludes stale entries older than 30 seconds")
        @SuppressWarnings("unchecked")
        void excludesStaleEntries() {
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(Set.of(HASH_KEY));
            long staleTs = System.currentTimeMillis() / 1000 - 60; // 60s ago
            Map<Object, Object> entries = new HashMap<>();
            entries.put("gw-az1-1#session-stale", String.valueOf(staleTs));
            when(hashOperations.entries(HASH_KEY)).thenReturn(entries);

            Set<String> result = broker.discoverAllSourceGwInstances();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty set when no keys exist")
        @SuppressWarnings("unchecked")
        void returnsEmptyWhenNoKeys() {
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(null);

            Set<String> result = broker.discoverAllSourceGwInstances();

            assertThat(result).isEmpty();
        }
    }
}
