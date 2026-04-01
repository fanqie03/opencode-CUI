# IM Inbound 预缓存消息累积修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 IM inbound 预缓存消息在 Redis List 中无限累积导致 toolSession 重建后历史消息被全部重发的 bug。

**Architecture:** 三处改动：(1) `handleToolDone` 成功后清理预缓存；(2) `rebuildFromStoredUserMessage` 加 Redis 分布式锁防止并发重复重建；(3) 有预缓存消息时跳过 DB 追加防止重复。

**Tech Stack:** Java 21, Spring Boot 3.4, Redis (Lettuce/StringRedisTemplate)

**Spec:** `docs/superpowers/specs/2026-04-01-im-inbound-pending-message-accumulation-fix-design.md`

---

## File Map

| 文件 | 改动类型 | 职责 |
|------|----------|------|
| `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java` | 修改 | 加锁、peek 方法、条件跳过 DB 追加 |
| `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java` | 修改 | handleToolDone 清理预缓存 |
| `skill-server/src/test/java/com/opencode/cui/skill/service/SessionRebuildServiceTest.java` | 修改 | 新增测试用例 |

---

### Task 1: SessionRebuildService 新增 peekPendingMessages 方法

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java:173` (在 `clearPendingMessages` 方法之前)

- [ ] **Step 1: 在 `clearPendingMessages` 方法之前新增 `peekPendingMessages`**

在 `consumePendingMessages` 方法之后、`clearPendingMessages` 方法之前添加：

```java
/**
 * 查看（不消费）待重建消息列表。
 * 用于 rebuildFromStoredUserMessage 判断是否需要从 DB 补充消息。
 *
 * @return 当前待发消息列表（只读），空列表表示无待发消息
 */
public List<String> peekPendingMessages(String sessionId) {
    String key = PENDING_MSG_PREFIX + sessionId;
    try {
        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);
        return messages != null ? messages : Collections.emptyList();
    } catch (Exception e) {
        log.warn("[WARN] peekPendingMessages: Redis 操作失败, sessionId={}, error={}",
                sessionId, e.getMessage());
        return Collections.emptyList();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java
git commit -m "feat(skill-server): add peekPendingMessages for read-only Redis List inspection"
```

---

### Task 2: SessionRebuildService.rebuildFromStoredUserMessage 加分布式锁 + 条件跳过 DB 追加

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java:33-36` (新增常量)
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java:186-208` (重写 `rebuildFromStoredUserMessage`)

- [ ] **Step 1: 新增 Redis 锁相关常量**

在现有常量区域（第 33-38 行附近）新增：

```java
/** 重建分布式锁 Redis key 前缀：ss:rebuild-lock:{sessionId} */
private static final String REBUILD_LOCK_PREFIX = "ss:rebuild-lock:";
/** 重建分布式锁 TTL */
private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(15);
```

- [ ] **Step 2: 重写 `rebuildFromStoredUserMessage` 方法**

将现有的 `rebuildFromStoredUserMessage` 方法（第 186-208 行）替换为：

```java
/** 从数据库中获取最近的用户消息并触发重建。加分布式锁防止并发重复重建。 */
private void rebuildFromStoredUserMessage(String sessionId, RebuildCallback callback) {
    Long sessionIdLong = ProtocolUtils.parseSessionId(sessionId);
    if (sessionIdLong == null) {
        log.error("Failed to rebuild session {}: invalid sessionId", sessionId);
        return;
    }

    // --- 分布式锁：防止并发请求同时触发重建 ---
    String lockKey = REBUILD_LOCK_PREFIX + sessionId;
    String lockValue = java.util.UUID.randomUUID().toString();
    boolean locked = false;
    try {
        locked = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, REBUILD_LOCK_TTL));
    } catch (Exception e) {
        log.warn("[WARN] rebuildFromStoredUserMessage: 获取锁失败, 降级放行, sessionId={}, error={}",
                sessionId, e.getMessage());
        locked = true; // Redis 故障时降级放行，允许单次重建
    }

    if (!locked) {
        log.info("Rebuild already in progress for session={}, skipping (message in pending list)", sessionId);
        return;
    }

    try {
        SkillSession session = sessionService.getSession(sessionIdLong);
        sessionService.clearToolSessionId(sessionIdLong);

        // 如果 Redis List 已有消息（来自 Case C 预缓存），跳过 DB 查询，避免重复追加
        String pendingMessage = null;
        List<String> existingPending = peekPendingMessages(sessionId);
        if (existingPending.isEmpty()) {
            SkillMessage lastUserMsg = messageRepository.findLastUserMessage(sessionIdLong);
            if (lastUserMsg != null && lastUserMsg.getContent() != null) {
                pendingMessage = lastUserMsg.getContent();
            }
        } else {
            log.info("Pending messages already exist for session={}, count={}, skipping DB lookup",
                    sessionId, existingPending.size());
        }

        rebuildToolSession(sessionId, session, pendingMessage, callback);
    } catch (Exception e) {
        log.error("Failed to rebuild session {}: {}", sessionId, e.getMessage(), e);
        clearPendingMessages(sessionId);
    } finally {
        // 安全释放锁（仅释放自己持有的）
        if (locked) {
            try {
                String currentValue = redisTemplate.opsForValue().get(lockKey);
                if (lockValue.equals(currentValue)) {
                    redisTemplate.delete(lockKey);
                }
            } catch (Exception e) {
                log.warn("[WARN] rebuildFromStoredUserMessage: 释放锁失败, sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java
git commit -m "fix(skill-server): add distributed lock and dedup guard to rebuildFromStoredUserMessage"
```

---

### Task 3: GatewayMessageRouter.handleToolDone 清理预缓存

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:519-545` (`handleToolDone` 方法)

- [ ] **Step 1: 在 handleToolDone 末尾添加清理调用**

在 `handleToolDone` 方法的最后（第 544 行 `}` 之前）添加：

```java
// 清理该 session 的预缓存消息，防止累积
rebuildService.clearPendingMessages(sessionId);
```

完整方法变为：

```java
/** 处理 tool_done：标记会话完成、广播 idle 状态、持久化最终消息。 */
private void handleToolDone(String sessionId, String userId, JsonNode node) {
    if (sessionId == null) {
        log.warn("tool_done missing sessionId, agentId={}", node.path("agentId").asText(null));
        return;
    }

    log.info("handleToolDone: sessionId={}", sessionId);
    completedSessions.put(sessionId, Instant.now());

    StreamMessage msg = StreamMessage.sessionStatus("idle");
    SkillSession session = resolveSession(sessionId);
    Long numericId = ProtocolUtils.parseSessionId(sessionId);

    if (isMiniappSession(session)) {
        broadcastStreamMessage(sessionId, userId, msg);
        bufferService.accumulate(sessionId, msg);
    }

    if (numericId != null && (isMiniappSession(session) || (session != null && session.isImDirectSession()))) {
        try {
            persistenceService.persistIfFinal(numericId, msg);
        } catch (Exception e) {
            log.error("Failed to persist tool_done for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    // 清理该 session 的预缓存消息，防止累积
    rebuildService.clearPendingMessages(sessionId);
}
```

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java
git commit -m "fix(skill-server): clear pending messages on tool_done to prevent accumulation"
```

---

### Task 4: 运行现有测试确认无回归

**Files:**
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/SessionRebuildServiceTest.java`

- [ ] **Step 1: 运行现有测试**

Run: `cd skill-server && mvn test -pl . -Dtest="SessionRebuildServiceTest" -q`

Expected: 所有现有测试通过。

> 注意：现有测试使用 5 参数构造函数（无 redisTemplate），新增的锁逻辑和 peekPendingMessages 依赖 redisTemplate。如果编译失败，需要为测试添加一个 `@Mock StringRedisTemplate` 并更新构造函数调用。

- [ ] **Step 2: 如需修复测试构造函数**

如果 Step 1 编译失败，在 `SessionRebuildServiceTest` 的字段区域添加：

```java
@Mock
private StringRedisTemplate redisTemplate;
```

并将 `setUp()` 中的构造函数调用更新为：

```java
service = new SessionRebuildService(new ObjectMapper(), sessionService, messageRepository, redisTemplate, 3, 30);
```

- [ ] **Step 3: 再次运行测试确认通过**

Run: `cd skill-server && mvn test -pl . -Dtest="SessionRebuildServiceTest" -q`
Expected: ALL TESTS PASSED

- [ ] **Step 4: 运行全量测试**

Run: `cd skill-server && mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit（如有测试修复）**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/SessionRebuildServiceTest.java
git commit -m "test(skill-server): fix SessionRebuildServiceTest constructor for redisTemplate"
```

---

### Task 5: 全量编译 + 最终验证

- [ ] **Step 1: 全量编译**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 全量测试**

Run: `cd skill-server && mvn test -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 3: 最终 Commit（如有遗留修正）**

```bash
git add -A
git commit -m "fix(skill-server): final adjustments for pending message accumulation fix"
```
