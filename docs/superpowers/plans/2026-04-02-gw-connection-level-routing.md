# GW 连接级路由 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 GW 侧路由粒度从 instanceId 级别提升到 connection 级别，修复连接池扩容后 session 覆盖、哈希环节点丢失、Redis 连接信息去重三个问题。

**Architecture:** 哈希环 nodeKey 从 `ssInstanceId` 改为 `ssInstanceId#sessionId`，`sourceTypeSessions` 改为三层 Map（sourceType → ssInstanceId → sessionId → WebSocketSession），Redis HASH field 从 `gwInstanceId` 改为 `gwInstanceId#sessionId`。SS 侧零改动。

**Tech Stack:** Java 21, Spring Boot 3.4, Redis (Lettuce), JUnit 5 + Mockito

---

### Task 1: RedisMessageBroker — 连接级 Source 注册

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java:445-557`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerSourceConnTest.java` (create)

- [ ] **Step 1: Write failing tests for connection-level registration**

Create `RedisMessageBrokerSourceConnTest.java`:

```java
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerSourceConnTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        broker = new RedisMessageBroker(redisTemplate, listenerContainer, new ObjectMapper());
    }

    @Nested
    @DisplayName("registerSourceConnection with sessionId")
    class RegisterTests {

        @Test
        @DisplayName("should write gwInstanceId#sessionId as HASH field")
        void registerWithSessionId_writesCompoundField() {
            broker.registerSourceConnection("skill-server", "ss-pod-0", "gw-pod-1", "sess-abc");

            verify(hashOperations).put(
                    eq("gw:source-conn:skill-server:ss-pod-0"),
                    eq("gw-pod-1#sess-abc"),
                    anyString());
        }

        @Test
        @DisplayName("should also write compat field gwInstanceId (dual-write)")
        void registerWithSessionId_writesCompatField() {
            broker.registerSourceConnection("skill-server", "ss-pod-0", "gw-pod-1", "sess-abc");

            // Dual-write: compound field + compat field
            verify(hashOperations).put(
                    eq("gw:source-conn:skill-server:ss-pod-0"),
                    eq("gw-pod-1"),
                    anyString());
        }

        @Test
        @DisplayName("should set TTL on HASH key")
        void registerWithSessionId_setsTtl() {
            broker.registerSourceConnection("skill-server", "ss-pod-0", "gw-pod-1", "sess-abc");

            verify(redisTemplate).expire(eq("gw:source-conn:skill-server:ss-pod-0"), eq(Duration.ofHours(2)));
        }

        @Test
        @DisplayName("null sessionId should be ignored")
        void registerWithNullSessionId_ignored() {
            broker.registerSourceConnection("skill-server", "ss-pod-0", "gw-pod-1", null);

            verify(hashOperations, never()).put(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("unregisterSourceConnection with sessionId")
    class UnregisterTests {

        @Test
        @DisplayName("should delete gwInstanceId#sessionId field")
        void unregisterWithSessionId_deletesCompoundField() {
            broker.unregisterSourceConnection("skill-server", "ss-pod-0", "gw-pod-1", "sess-abc");

            verify(hashOperations).delete("gw:source-conn:skill-server:ss-pod-0", "gw-pod-1#sess-abc");
        }
    }

    @Nested
    @DisplayName("refreshSourceConnectionHeartbeat with sessionId")
    class RefreshTests {

        @Test
        @DisplayName("should update timestamp for gwInstanceId#sessionId field")
        void refreshWithSessionId_updatesCompoundField() {
            broker.refreshSourceConnectionHeartbeat("skill-server", "ss-pod-0", "gw-pod-1", "sess-abc");

            verify(hashOperations).put(
                    eq("gw:source-conn:skill-server:ss-pod-0"),
                    eq("gw-pod-1#sess-abc"),
                    anyString());
        }

        @Test
        @DisplayName("should also refresh compat field (dual-write)")
        void refreshWithSessionId_refreshesCompatField() {
            broker.refreshSourceConnectionHeartbeat("skill-server", "ss-pod-0", "gw-pod-1", "sess-abc");

            verify(hashOperations).put(
                    eq("gw:source-conn:skill-server:ss-pod-0"),
                    eq("gw-pod-1"),
                    anyString());
        }
    }

    @Nested
    @DisplayName("extractUniqueGwInstances")
    class ExtractGwInstancesTests {

        @Test
        @DisplayName("should extract gwInstanceId from compound fields")
        void extractFromCompoundFields() {
            Map<String, Long> conns = Map.of(
                    "gw-pod-0#sess-a1", 100L,
                    "gw-pod-0#sess-a2", 100L,
                    "gw-pod-1#sess-b1", 100L);

            Set<String> result = broker.extractUniqueGwInstances(conns);

            assertEquals(Set.of("gw-pod-0", "gw-pod-1"), result);
        }

        @Test
        @DisplayName("should handle legacy format (no #)")
        void extractFromLegacyFields() {
            Map<String, Long> conns = Map.of(
                    "gw-pod-0", 100L,
                    "gw-pod-1#sess-b1", 100L);

            Set<String> result = broker.extractUniqueGwInstances(conns);

            assertEquals(Set.of("gw-pod-0", "gw-pod-1"), result);
        }
    }

    @Nested
    @DisplayName("cleanupStaleSourceConnections with prefix matching")
    class CleanupTests {

        @Test
        @DisplayName("should delete both exact match and prefix match fields")
        void cleanup_deletesBothFormats() {
            Set<String> keys = Set.of("gw:source-conn:skill-server:ss-pod-0");
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(keys);

            Map<Object, Object> entries = new HashMap<>();
            entries.put("gw-pod-1", "100");           // legacy format
            entries.put("gw-pod-1#sess-a1", "100");   // new format
            entries.put("gw-pod-2#sess-b1", "100");   // different GW, should NOT be deleted
            when(hashOperations.entries("gw:source-conn:skill-server:ss-pod-0")).thenReturn(entries);

            broker.cleanupStaleSourceConnections("gw-pod-1");

            verify(hashOperations).delete("gw:source-conn:skill-server:ss-pod-0", "gw-pod-1");
            verify(hashOperations).delete("gw:source-conn:skill-server:ss-pod-0", "gw-pod-1#sess-a1");
            verify(hashOperations, never()).delete("gw:source-conn:skill-server:ss-pod-0", "gw-pod-2#sess-b1");
        }
    }

    @Nested
    @DisplayName("discoverAllSourceGwInstances with compound fields")
    class DiscoverTests {

        @Test
        @DisplayName("should extract unique gwInstanceIds from compound fields")
        void discover_extractsFromCompoundFields() {
            Set<String> keys = Set.of("gw:source-conn:skill-server:ss-pod-0");
            when(redisTemplate.keys("gw:source-conn:*")).thenReturn(keys);

            Map<Object, Object> entries = new HashMap<>();
            long now = java.time.Instant.now().getEpochSecond();
            entries.put("gw-pod-0#sess-a1", String.valueOf(now));
            entries.put("gw-pod-0#sess-a2", String.valueOf(now));
            entries.put("gw-pod-1#sess-b1", String.valueOf(now));
            when(hashOperations.entries("gw:source-conn:skill-server:ss-pod-0")).thenReturn(entries);

            Set<String> result = broker.discoverAllSourceGwInstances();

            assertEquals(Set.of("gw-pod-0", "gw-pod-1"), result);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ai-gateway && mvn test -pl . -Dtest=RedisMessageBrokerSourceConnTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation errors — methods with sessionId parameter don't exist yet.

- [ ] **Step 3: Implement connection-level registration in RedisMessageBroker**

In `RedisMessageBroker.java`, add the new overloaded methods and modify existing ones:

```java
// After the existing registerSourceConnection method (L445-455), add:

/**
 * Registers a source connection in Redis with connection-level granularity.
 * Dual-writes: gwInstanceId#sessionId (new) + gwInstanceId (compat for rolling upgrade).
 *
 * @param sourceType       source type
 * @param sourceInstanceId source instance ID
 * @param gwInstanceId     this GW instance's ID
 * @param sessionId        WebSocket session ID for connection-level tracking
 */
public void registerSourceConnection(String sourceType, String sourceInstanceId,
                                      String gwInstanceId, String sessionId) {
    if (sourceType == null || sourceInstanceId == null || gwInstanceId == null || sessionId == null) {
        return;
    }
    String key = sourceConnKey(sourceType, sourceInstanceId);
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String compoundField = gwInstanceId + "#" + sessionId;
    redisTemplate.opsForHash().put(key, compoundField, timestamp);
    // Dual-write compat field for rolling upgrade
    redisTemplate.opsForHash().put(key, gwInstanceId, timestamp);
    redisTemplate.expire(key, SOURCE_CONN_TTL);
    log.info("RedisMessageBroker.registerSourceConnection: sourceType={}, sourceInstanceId={}, field={}",
            sourceType, sourceInstanceId, compoundField);
}

/**
 * Unregisters a source connection with connection-level granularity.
 *
 * @param sourceType       source type
 * @param sourceInstanceId source instance ID
 * @param gwInstanceId     this GW instance's ID
 * @param sessionId        WebSocket session ID
 */
public void unregisterSourceConnection(String sourceType, String sourceInstanceId,
                                        String gwInstanceId, String sessionId) {
    if (sourceType == null || sourceInstanceId == null || gwInstanceId == null || sessionId == null) {
        return;
    }
    String key = sourceConnKey(sourceType, sourceInstanceId);
    String compoundField = gwInstanceId + "#" + sessionId;
    redisTemplate.opsForHash().delete(key, compoundField);
    // Note: do NOT delete compat field here — other connections from the same GW may still be active.
    // The compat field will be cleaned up by heartbeat expiry or stale cleanup.
    log.info("RedisMessageBroker.unregisterSourceConnection: sourceType={}, sourceInstanceId={}, field={}",
            sourceType, sourceInstanceId, compoundField);
}

/**
 * Refreshes heartbeat for a source connection with connection-level granularity.
 *
 * @param sourceType       source type
 * @param sourceInstanceId source instance ID
 * @param gwInstanceId     this GW instance's ID
 * @param sessionId        WebSocket session ID
 */
public void refreshSourceConnectionHeartbeat(String sourceType, String sourceInstanceId,
                                              String gwInstanceId, String sessionId) {
    if (sourceType == null || sourceInstanceId == null || gwInstanceId == null || sessionId == null) {
        return;
    }
    String key = sourceConnKey(sourceType, sourceInstanceId);
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    String compoundField = gwInstanceId + "#" + sessionId;
    redisTemplate.opsForHash().put(key, compoundField, timestamp);
    // Dual-write compat field
    redisTemplate.opsForHash().put(key, gwInstanceId, timestamp);
    redisTemplate.expire(key, SOURCE_CONN_TTL);
}

/**
 * Extracts unique GW instance IDs from source connection map.
 * Handles both new format (gwInstanceId#sessionId) and legacy format (gwInstanceId).
 *
 * @param sourceConnections map of field → timestamp from getSourceConnections
 * @return set of unique GW instance IDs
 */
public Set<String> extractUniqueGwInstances(Map<String, Long> sourceConnections) {
    Set<String> gwIds = new HashSet<>();
    for (String field : sourceConnections.keySet()) {
        int hashIdx = field.indexOf('#');
        gwIds.add(hashIdx > 0 ? field.substring(0, hashIdx) : field);
    }
    return gwIds;
}
```

Modify `cleanupStaleSourceConnections` (L540-557) to handle prefix matching:

```java
public void cleanupStaleSourceConnections(String gwInstanceId) {
    if (gwInstanceId == null || gwInstanceId.isBlank()) {
        return;
    }
    Set<String> keys = redisTemplate.keys(SOURCE_CONN_KEY_PREFIX + "*");
    if (keys == null || keys.isEmpty()) {
        return;
    }
    String prefix = gwInstanceId + "#";
    int cleaned = 0;
    for (String key : keys) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (field.equals(gwInstanceId) || field.startsWith(prefix)) {
                redisTemplate.opsForHash().delete(key, field);
                cleaned++;
            }
        }
    }
    log.info("RedisMessageBroker.cleanupStaleSourceConnections: gwInstanceId={}, cleanedFields={}",
            gwInstanceId, cleaned);
}
```

Modify `discoverAllSourceGwInstances` (L569-591) to extract gwInstanceId from compound fields:

```java
public Set<String> discoverAllSourceGwInstances() {
    Set<String> keys = redisTemplate.keys(SOURCE_CONN_KEY_PREFIX + "*");
    if (keys == null || keys.isEmpty()) {
        return Collections.emptySet();
    }
    long now = Instant.now().getEpochSecond();
    Set<String> gwIds = new HashSet<>();
    for (String key : keys) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = String.valueOf(entry.getKey());
            try {
                long ts = Long.parseLong(String.valueOf(entry.getValue()));
                if (now - ts <= 30) {
                    // Extract gwInstanceId from "gwInstanceId#sessionId" or plain "gwInstanceId"
                    int hashIdx = field.indexOf('#');
                    gwIds.add(hashIdx > 0 ? field.substring(0, hashIdx) : field);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }
    return gwIds;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ai-gateway && mvn test -pl . -Dtest=RedisMessageBrokerSourceConnTest`
Expected: all tests PASS.

- [ ] **Step 5: Run existing tests to verify no regression**

Run: `cd ai-gateway && mvn test -pl . -Dtest=RedisMessageBrokerTest`
Expected: all existing tests PASS (old 3-param methods still exist).

- [ ] **Step 6: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerSourceConnTest.java
git commit -m "feat(ai-gateway): add connection-level source registration to RedisMessageBroker"
```

---

### Task 2: SkillRelayService — 三层 Map + 连接级哈希环

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java:58-238`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java` (modify)

- [ ] **Step 1: Write failing tests for multi-connection registration**

Add a new `@Nested` class to `SkillRelayServiceV2Test.java`:

```java
// Add these fields at class level:
@Mock
private WebSocketSession ss1SessionB; // second connection from same SS instance

// Add this helper:
private void registerSs1B() {
    lenient().when(ss1SessionB.getId()).thenReturn("ss1-link-b");
    lenient().when(ss1SessionB.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_SKILL, "ss-1"));
    lenient().when(ss1SessionB.isOpen()).thenReturn(true);
    service.registerSourceSession(ss1SessionB);
}

// Add this nested test class:
@Nested
@DisplayName("Connection-level registration (same instanceId, multiple sessions)")
class ConnectionLevelTests {

    @Test
    @DisplayName("two sessions from same instanceId should both be in hash ring")
    void twoSessionsSameInstance_bothInHashRing() {
        registerSs1();
        registerSs1B();

        ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
        assertNotNull(ring);
        assertEquals(2, ring.size(), "Both connections should be separate hash ring nodes");
    }

    @Test
    @DisplayName("removing one session should keep the other in hash ring")
    void removeOneSession_otherStaysInHashRing() {
        registerSs1();
        registerSs1B();

        service.removeSourceSession(ss1Session);

        ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
        assertNotNull(ring);
        assertEquals(1, ring.size(), "Remaining connection should stay in hash ring");
    }

    @Test
    @DisplayName("registerSourceConnection should be called with sessionId")
    void register_callsRedisWithSessionId() {
        registerSs1();

        verify(redisMessageBroker).registerSourceConnection(
                eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
    }

    @Test
    @DisplayName("removeSourceSession should unregister with sessionId")
    void remove_callsRedisWithSessionId() {
        registerSs1();
        service.removeSourceSession(ss1Session);

        verify(redisMessageBroker).unregisterSourceConnection(
                eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
    }

    @Test
    @DisplayName("getActiveSourceConnectionCount should count all connections")
    void activeCount_countsAllConnections() {
        registerSs1();
        registerSs1B();

        assertEquals(2, service.getActiveSourceConnectionCount());
    }

    @Test
    @DisplayName("findLocalSourceConnection should return an open session for instanceId")
    void findLocal_returnsOpenSession() {
        registerSs1();
        registerSs1B();

        WebSocketSession found = service.findLocalSourceConnection(SOURCE_TYPE_SKILL, "ss-1");
        assertNotNull(found);
        assertTrue(found.isOpen());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ai-gateway && mvn test -pl . -Dtest=SkillRelayServiceV2Test`
Expected: FAIL — `registerSourceConnection` called with 3 args not 4, hash ring size is 1 not 2.

- [ ] **Step 3: Implement three-level Map and connection-level hash ring**

Modify `SkillRelayService.java`:

**a) Change data structure** (L58-61):

```java
// Replace:
//   private final Map<String, Map<String, WebSocketSession>> sourceTypeSessions = new ConcurrentHashMap<>();
// With:
/**
 * [Mesh] Source 实例连接池：source_type → { ssInstanceId → { sessionId → WebSocketSession } }
 * 三层结构支持同一 SS 实例的多条连接独立跟踪。
 */
private final Map<String, Map<String, Map<String, WebSocketSession>>> sourceTypeSessions = new ConcurrentHashMap<>();
```

**b) Change registerSourceSession** (L152-188):

```java
public void registerSourceSession(WebSocketSession session) {
    if (isLegacyClient(session)) {
        session.getAttributes().put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.LEGACY);
        legacyStrategy.registerSession(session);
        return;
    }

    session.getAttributes().put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.MESH);

    String sourceType = resolveBoundSource(session);
    String ssInstanceId = resolveSsInstanceId(session);
    if (sourceType == null || sourceType.isBlank()) {
        log.warn("Skipping source session registration: missing source attribute, linkId={}",
                session.getId());
        return;
    }
    if (ssInstanceId == null || ssInstanceId.isBlank()) {
        ssInstanceId = session.getId();
    }

    String sessionId = session.getId();
    String connectionKey = ssInstanceId + "#" + sessionId;

    sourceTypeSessions
            .computeIfAbsent(sourceType, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(ssInstanceId, ignored -> new ConcurrentHashMap<>())
            .put(sessionId, session);

    hashRings.computeIfAbsent(sourceType, k -> new ConsistentHashRing<>(150))
            .addNode(connectionKey, session);

    redisMessageBroker.registerSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);

    log.info("[Mesh] Registered source session: sourceType={}, ssInstanceId={}, sessionId={}, gwInstanceId={}, activeLinks={}, hashRingSize={}",
            sourceType, ssInstanceId, sessionId, gatewayInstanceId, getActiveConnectionCount(sourceType),
            hashRings.containsKey(sourceType) ? hashRings.get(sourceType).size() : 0);
}
```

**c) Change removeSourceSession** (L194-238):

```java
public void removeSourceSession(WebSocketSession session) {
    if (isLegacySession(session)) {
        legacyStrategy.removeSession(session);
        return;
    }

    String sourceType = resolveBoundSource(session);
    if (sourceType == null || sourceType.isBlank()) {
        return;
    }

    String ssInstanceId = resolveSsInstanceId(session);
    String sessionId = session.getId();

    Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
    if (instanceMap != null) {
        Map<String, WebSocketSession> sessionMap = instanceMap.get(ssInstanceId);
        if (sessionMap != null) {
            sessionMap.remove(sessionId);
            if (sessionMap.isEmpty()) {
                instanceMap.remove(ssInstanceId);
            }
        }
        // Also try sessionId as instanceId key (fallback compat)
        if (ssInstanceId == null) {
            Map<String, WebSocketSession> fallbackMap = instanceMap.get(sessionId);
            if (fallbackMap != null) {
                fallbackMap.remove(sessionId);
                if (fallbackMap.isEmpty()) {
                    instanceMap.remove(sessionId);
                }
            }
        }
        if (instanceMap.isEmpty()) {
            sourceTypeSessions.remove(sourceType, instanceMap);
        }
    }

    String nodeKey = (ssInstanceId != null ? ssInstanceId : sessionId) + "#" + sessionId;
    hashRings.computeIfPresent(sourceType, (k, ring) -> {
        ring.removeNode(nodeKey);
        return ring.isEmpty() ? null : ring;
    });

    invalidateRoutesForSession(session);

    if (ssInstanceId != null) {
        redisMessageBroker.unregisterSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);
    }

    log.info("[Mesh] Removed source session: sourceType={}, ssInstanceId={}, sessionId={}, gwInstanceId={}, activeLinks={}",
            sourceType, ssInstanceId, sessionId, gatewayInstanceId, getActiveConnectionCount(sourceType));
}
```

**d) Change broadcastToSourceType** (L589-607):

```java
private boolean broadcastToSourceType(String sourceType, GatewayMessage message) {
    Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
    if (instanceMap == null || instanceMap.isEmpty()) {
        log.warn("No source instances for type: {}, type={}", sourceType, message.getType());
        return false;
    }

    int sent = 0;
    for (Map<String, WebSocketSession> sessionMap : instanceMap.values()) {
        for (WebSocketSession ss : sessionMap.values()) {
            if (ss.isOpen()) {
                sendToSession(ss, message);
                sent++;
            }
        }
    }

    log.info("Broadcast to source_type={}: sent to {} connections, msgType={}",
            sourceType, sent, message.getType());
    return sent > 0;
}
```

**e) Change broadcastToAllGroups fallback** (L532-570):

```java
private boolean broadcastToAllGroups(GatewayMessage message, String routingKey) {
    if (hashRings.isEmpty() && sourceTypeSessions.isEmpty()) {
        log.warn("[V2] No source connections available for broadcast: type={}", message.getType());
        return false;
    }

    int groupsSent = 0;
    for (Map.Entry<String, ConsistentHashRing<WebSocketSession>> entry : hashRings.entrySet()) {
        String st = entry.getKey();
        ConsistentHashRing<WebSocketSession> ring = entry.getValue();
        if (ring.isEmpty()) {
            continue;
        }

        WebSocketSession target = null;
        if (routingKey != null) {
            target = ring.getNode(routingKey);
        }
        // Fallback: find any open session in this sourceType pool
        if (target == null || !target.isOpen()) {
            Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(st);
            if (instanceMap != null) {
                target = instanceMap.values().stream()
                        .flatMap(m -> m.values().stream())
                        .filter(WebSocketSession::isOpen)
                        .findFirst()
                        .orElse(null);
            }
        }

        if (target != null && target.isOpen()) {
            sendToSession(target, message);
            groupsSent++;
        }
    }

    log.info("[V2] Broadcast to all groups: groupsSent={}, totalGroups={}, type={}",
            groupsSent, hashRings.size(), message.getType());
    return groupsSent > 0;
}
```

**f) Change findLocalSourceConnection** (L988-1001):

```java
public WebSocketSession findLocalSourceConnection(String sourceType, String sourceInstanceId) {
    if (sourceType == null || sourceInstanceId == null) {
        return null;
    }
    Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
    if (instanceMap == null) {
        return null;
    }
    Map<String, WebSocketSession> sessionMap = instanceMap.get(sourceInstanceId);
    if (sessionMap == null) {
        return null;
    }
    // Return any open session for this instanceId
    return sessionMap.values().stream()
            .filter(WebSocketSession::isOpen)
            .findFirst()
            .orElse(null);
}
```

**g) Change inferSingleActiveSourceType** (L912-924):

```java
private String inferSingleActiveSourceType() {
    String resolved = null;
    for (Map.Entry<String, Map<String, Map<String, WebSocketSession>>> entry : sourceTypeSessions.entrySet()) {
        boolean hasOpen = entry.getValue().values().stream()
                .flatMap(m -> m.values().stream())
                .anyMatch(WebSocketSession::isOpen);
        if (hasOpen) {
            if (resolved != null) {
                return null;
            }
            resolved = entry.getKey();
        }
    }
    return resolved;
}
```

**h) Change getActiveSourceConnectionCount and getActiveConnectionCount** (L926-940):

```java
public int getActiveSourceConnectionCount() {
    int meshCount = (int) sourceTypeSessions.values().stream()
            .flatMap(instanceMap -> instanceMap.values().stream())
            .flatMap(sessionMap -> sessionMap.values().stream())
            .filter(WebSocketSession::isOpen)
            .count();
    return meshCount + legacyStrategy.getActiveConnectionCount();
}

private int getActiveConnectionCount(String sourceType) {
    Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
    if (instanceMap == null)
        return 0;
    return (int) instanceMap.values().stream()
            .flatMap(m -> m.values().stream())
            .filter(WebSocketSession::isOpen)
            .count();
}
```

- [ ] **Step 4: Run new and existing tests**

Run: `cd ai-gateway && mvn test -pl . -Dtest="SkillRelayServiceV2Test,SkillRelayServiceTest"`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java
git commit -m "feat(ai-gateway): connection-level hash ring and three-level sourceTypeSessions"
```

---

### Task 3: SkillRelayService — 心跳刷新改三层遍历 + 僵尸清理

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java:1021-1033`

- [ ] **Step 1: Write failing test for heartbeat with sessionId**

Add to `SkillRelayServiceV2Test.java`:

```java
@Nested
@DisplayName("Heartbeat refresh with connection-level granularity")
class HeartbeatTests {

    @Test
    @DisplayName("refreshSourceConnectionHeartbeats should call Redis with sessionId for each open session")
    void heartbeat_callsRedisWithSessionId() {
        registerSs1();
        registerSs1B();

        service.refreshSourceConnectionHeartbeats();

        verify(redisMessageBroker).refreshSourceConnectionHeartbeat(
                eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
        verify(redisMessageBroker).refreshSourceConnectionHeartbeat(
                eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link-b"));
    }

    @Test
    @DisplayName("refreshSourceConnectionHeartbeats should skip and clean closed sessions")
    void heartbeat_skipsClosedSessions() {
        registerSs1();
        registerSs1B();

        // Close ss1SessionB
        when(ss1SessionB.isOpen()).thenReturn(false);

        service.refreshSourceConnectionHeartbeats();

        // Only ss1Session should be refreshed
        verify(redisMessageBroker).refreshSourceConnectionHeartbeat(
                eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
        verify(redisMessageBroker, never()).refreshSourceConnectionHeartbeat(
                eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link-b"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-gateway && mvn test -pl . -Dtest="SkillRelayServiceV2Test$HeartbeatTests"`
Expected: FAIL — heartbeat still calls 3-param method.

- [ ] **Step 3: Implement three-level heartbeat traversal**

Replace `refreshSourceConnectionHeartbeats` (L1021-1033):

```java
@Scheduled(fixedDelay = 10_000)
public void refreshSourceConnectionHeartbeats() {
    List<String[]> staleEntries = new ArrayList<>();

    for (Map.Entry<String, Map<String, Map<String, WebSocketSession>>> typeEntry : sourceTypeSessions.entrySet()) {
        String sourceType = typeEntry.getKey();
        for (Map.Entry<String, Map<String, WebSocketSession>> instanceEntry : typeEntry.getValue().entrySet()) {
            String ssInstanceId = instanceEntry.getKey();
            for (Map.Entry<String, WebSocketSession> sessionEntry : instanceEntry.getValue().entrySet()) {
                String sessionId = sessionEntry.getKey();
                WebSocketSession session = sessionEntry.getValue();
                if (session.isOpen()) {
                    redisMessageBroker.refreshSourceConnectionHeartbeat(
                            sourceType, ssInstanceId, gatewayInstanceId, sessionId);
                } else {
                    staleEntries.add(new String[]{sourceType, ssInstanceId, sessionId});
                }
            }
        }
    }

    // Lazily clean up stale (closed) sessions
    for (String[] stale : staleEntries) {
        String sourceType = stale[0];
        String ssInstanceId = stale[1];
        String sessionId = stale[2];
        String connectionKey = ssInstanceId + "#" + sessionId;

        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap != null) {
            Map<String, WebSocketSession> sessionMap = instanceMap.get(ssInstanceId);
            if (sessionMap != null) {
                sessionMap.remove(sessionId);
                if (sessionMap.isEmpty()) {
                    instanceMap.remove(ssInstanceId);
                }
            }
            if (instanceMap.isEmpty()) {
                sourceTypeSessions.remove(sourceType, instanceMap);
            }
        }
        hashRings.computeIfPresent(sourceType, (k, ring) -> {
            ring.removeNode(connectionKey);
            return ring.isEmpty() ? null : ring;
        });
        redisMessageBroker.unregisterSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);
        log.info("[Mesh] Heartbeat cleanup: removed stale session sourceType={}, ssInstanceId={}, sessionId={}",
                sourceType, ssInstanceId, sessionId);
    }
}
```

- [ ] **Step 4: Run all tests**

Run: `cd ai-gateway && mvn test -pl . -Dtest="SkillRelayServiceV2Test"`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java
git commit -m "feat(ai-gateway): connection-level heartbeat refresh with stale session cleanup"
```

---

### Task 4: SkillRelayService — L2 路由适配

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java:391-446`

- [ ] **Step 1: Write failing test for L2 routing with compound fields**

Add to `SkillRelayServiceV2Test.java`:

```java
@Nested
@DisplayName("L2 Redis routing with connection-level fields")
class L2RoutingTests {

    @Test
    @DisplayName("L2 should extract gwInstanceId from compound field and relay correctly")
    void l2Routing_extractsGwIdFromCompoundField() throws Exception {
        // No local connections — force L2 path
        when(redisMessageBroker.getSessionRoute("T1")).thenReturn("skill-server:ss-pod-0");

        Map<String, Long> gwMap = Map.of(
                "gw-remote#sess-a1", 100L,
                "gw-remote#sess-a2", 100L);
        when(redisMessageBroker.getSourceConnections("skill-server", "ss-pod-0")).thenReturn(gwMap);
        when(redisMessageBroker.extractUniqueGwInstances(gwMap)).thenReturn(Set.of("gw-remote"));

        GatewayMessage msg = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .toolSessionId("T1")
                .source(SOURCE_TYPE_SKILL)
                .build();

        boolean result = service.relayToSkill(msg);

        assertTrue(result);
        verify(redisMessageBroker).publishToSourceRelay(
                eq("gw-remote"), eq("skill-server"), eq("ss-pod-0"), anyString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-gateway && mvn test -pl . -Dtest="SkillRelayServiceV2Test$L2RoutingTests"`
Expected: FAIL — current L2 code iterates gwMap keys directly as gwInstanceIds.

- [ ] **Step 3: Modify l2RedisRoute to extract gwInstanceId from compound fields**

In `l2RedisRoute` method (L391-446), replace the cross-GW relay section (L427-441):

```java
// Replace this block:
//   for (String targetGwId : gwMap.keySet()) {
//       if (!gatewayInstanceId.equals(targetGwId)) {
// With:
Set<String> uniqueGwIds = redisMessageBroker.extractUniqueGwInstances(gwMap);
for (String targetGwId : uniqueGwIds) {
    if (!gatewayInstanceId.equals(targetGwId)) {
        try {
            String messageJson = objectMapper.writeValueAsString(message);
            redisMessageBroker.publishToSourceRelay(targetGwId, targetSourceType,
                    targetSourceInstanceId, messageJson);
            log.info("[V2-L2] Cross-GW relay: targetGw={}, sourceType={}, sourceInstanceId={}, type={}",
                    targetGwId, targetSourceType, targetSourceInstanceId, message.getType());
            return true;
        } catch (Exception e) {
            log.error("[V2-L2] Failed to serialize message for cross-GW relay: type={}", message.getType(), e);
        }
    }
}
```

- [ ] **Step 4: Run all tests**

Run: `cd ai-gateway && mvn test -pl . -Dtest="SkillRelayServiceV2Test,SkillRelayServiceTest"`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
git add ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java
git commit -m "feat(ai-gateway): adapt L2 routing for connection-level Redis fields"
```

---

### Task 5: Full integration test + typecheck

**Files:**
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java`

- [ ] **Step 1: Run full test suite**

Run: `cd ai-gateway && mvn test -pl .`
Expected: all tests PASS.

- [ ] **Step 2: Run compilation check**

Run: `cd ai-gateway && mvn compile -pl .`
Expected: BUILD SUCCESS, no compilation errors.

- [ ] **Step 3: Verify no leftover references to old 3-param registration in SkillRelayService**

Search for old-style calls:

```bash
grep -n "registerSourceConnection(sourceType, ssInstanceId, gatewayInstanceId)" ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
grep -n "unregisterSourceConnection(sourceType, ssInstanceId, gatewayInstanceId)" ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
grep -n "refreshSourceConnectionHeartbeat(sourceType, ssInstanceId, gatewayInstanceId)" ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
```

Expected: no matches — all calls should now use 4-param versions.

- [ ] **Step 4: Commit (if any remaining fixes)**

```bash
git add -A ai-gateway/
git commit -m "test(ai-gateway): verify connection-level routing integration"
```
