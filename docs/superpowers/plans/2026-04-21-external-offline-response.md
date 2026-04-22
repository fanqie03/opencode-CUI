# External 接口助理离线特异返回 + 文案配置化 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `POST /api/external/invoke` 的 4 个 action 在个人助理离线时返回 `HTTP 200 + code=503 + errormsg=<配置文案>`，同时把离线文案下沉到 `sys_config` 表（external 与 IM 共用），并给 `SysConfigService` 的 Redis 读/写/删加兜底。

**Architecture:** 新增一个 10 行的 `AssistantOfflineMessageProvider` 作为文案入口（`sys_config.assistant_offline:message` → 空走 `DEFAULT_OFFLINE_MESSAGE`）；`InboundProcessingService` 抽出私有 `checkAgentOnline` helper，`chat`/`rebuild` 在 `resolve` 之后调用，`question_reply`/`permission_reply` 在 `findSession+404` 之后调用（保留 404 语义）；`SysConfigService` 对 `RuntimeException` 兜底，Redis 故障降级直查 MySQL 或静默写失败。

**Tech Stack:** Spring Boot 3.4 + Java 21 + JUnit 5 + Mockito + MyBatis + Lettuce (Redis)。测试全部走单元层（Mock），无新增集成测试。

**Spec:** `docs/superpowers/specs/2026-04-21-external-offline-response-design.md`

---

## 前置说明（所有 Task 都要遵守）

- **项目规则**：`.trellis/workflow.md` 明确 "AI should not commit code"。每个 Task 的 "Commit" 步骤展示了建议的 commit 命令，**由人类操作员执行**（或显式授权的会话执行）。
- **构建命令**：`mvn -pl skill-server -am test -Dtest=<ClassName>`（单类）或 `mvn -pl skill-server test`（全量）。
- **工作目录**：`D:\02_Lab\Projects\sandbox\opencode-CUI`。所有路径默认相对于仓库根。
- **分支**：`release-0417`（当前工作分支）。
- **代码风格**：沿用现有 JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)` + `@Mock` + `lenient().when(...)`)，vanilla `org.junit.jupiter.api.Assertions.*`。中文 `@DisplayName`。
- **导入顺序**：和周围文件保持一致（Java 标准库 → `com.fasterxml.*` → `com.opencode.*` → `lombok.*` → `org.junit.*` → `org.mockito.*` → `org.springframework.*`）。

---

## Task 1: 新增 `AssistantOfflineMessageProvider` + 单元测试

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantOfflineMessageProvider.java`
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/AssistantOfflineMessageProviderTest.java`

- [ ] **Step 1: 写失败的单元测试**

Create `skill-server/src/test/java/com/opencode/cui/skill/service/AssistantOfflineMessageProviderTest.java`:

```java
package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantOfflineMessageProviderTest {

    @Mock
    private SysConfigService sysConfigService;

    private AssistantOfflineMessageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new AssistantOfflineMessageProvider(sysConfigService);
    }

    @Test
    @DisplayName("get: 配置有值时返回该值")
    void getReturnsConfiguredValue() {
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn("自定义文案");

        assertEquals("自定义文案", provider.get());
    }

    @Test
    @DisplayName("get: 配置为 null 时返回 DEFAULT_OFFLINE_MESSAGE")
    void getReturnsDefaultWhenNull() {
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn(null);

        assertEquals(AssistantOfflineMessageProvider.DEFAULT_OFFLINE_MESSAGE, provider.get());
    }

    @Test
    @DisplayName("get: 配置为空串时返回 DEFAULT_OFFLINE_MESSAGE")
    void getReturnsDefaultWhenBlank() {
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn("   ");

        assertEquals(AssistantOfflineMessageProvider.DEFAULT_OFFLINE_MESSAGE, provider.get());
    }

    @Test
    @DisplayName("get: SysConfigService 抛 RuntimeException 时不 catch，异常冒泡")
    void getDoesNotSwallowExceptions() {
        when(sysConfigService.getValue("assistant_offline", "message"))
                .thenThrow(new RuntimeException("db down"));

        RuntimeException ex = assertThrows(RuntimeException.class, provider::get);
        assertEquals("db down", ex.getMessage());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（类不存在）**

Run: `mvn -pl skill-server -am test -Dtest=AssistantOfflineMessageProviderTest`
Expected: 编译失败 — `AssistantOfflineMessageProvider cannot be resolved`。

- [ ] **Step 3: 实现 provider**

Create `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantOfflineMessageProvider.java`:

```java
package com.opencode.cui.skill.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 助理离线提示文案供给组件。
 * 从 sys_config (assistant_offline:message) 读取，缺失/禁用/空串时回退到 DEFAULT_OFFLINE_MESSAGE。
 */
@Component
@RequiredArgsConstructor
public class AssistantOfflineMessageProvider {

    static final String DEFAULT_OFFLINE_MESSAGE =
            "任务下发失败，请检查助理是否离线，确保助理在线后重试";
    static final String CONFIG_TYPE = "assistant_offline";
    static final String CONFIG_KEY  = "message";

    private final SysConfigService sysConfigService;

    public String get() {
        String v = sysConfigService.getValue(CONFIG_TYPE, CONFIG_KEY);
        return (v == null || v.isBlank()) ? DEFAULT_OFFLINE_MESSAGE : v;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -pl skill-server -am test -Dtest=AssistantOfflineMessageProviderTest`
Expected: `BUILD SUCCESS`, 4 tests pass。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/AssistantOfflineMessageProvider.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/AssistantOfflineMessageProviderTest.java
git commit -m "feat(skill-server): add AssistantOfflineMessageProvider with sys_config-backed message + fallback"
```

---

## Task 2: `SysConfigService` Redis 兜底

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/SysConfigServiceTest.java`

- [ ] **Step 1: 写 4 个新的失败测试**

Append these tests to `SysConfigServiceTest.java`（放在 CRUD 测试之后、类尾之前；需要在现有 import 基础上添加 `import org.springframework.data.redis.RedisConnectionFailureException;`）：

```java
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
```

- [ ] **Step 2: 运行测试，确认 4 个新用例失败、其他保持绿**

Run: `mvn -pl skill-server -am test -Dtest=SysConfigServiceTest`
Expected:
- 6 个既有用例 PASS
- 4 个新用例 FAIL（`RedisConnectionFailureException` 未被 catch，直接冒泡）

- [ ] **Step 3: 在 `SysConfigService` 的读/写缓存和 evictCache 里加 `try/catch (RuntimeException)`**

Modify `skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java`:

替换 `getValue` 方法（line 47-75）的完整实现为：

```java
    public String getValue(String configType, String configKey) {
        String cacheKey = buildCacheKey(configType, configKey);

        // 1. 尝试读缓存（Redis 故障时降级直查 DB）
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("Config cache hit: {}", cacheKey);
                return cached;
            }
        } catch (RuntimeException e) {
            log.warn("Redis read failed for {}, falling back to DB: {}", cacheKey, e.getMessage());
        }

        // 2. 缓存未命中，查 DB
        SysConfig config = sysConfigMapper.findByTypeAndKey(configType, configKey);
        if (config == null) {
            log.debug("Config not found in DB: {}", cacheKey);
            return null;
        }

        // 3. status=0 禁用，不缓存
        if (config.getStatus() == null || config.getStatus() != 1) {
            log.debug("Config is disabled, skip caching: {}", cacheKey);
            return null;
        }

        // 4. status=1，写缓存并返回（Redis 故障时静默）
        String value = config.getConfigValue();
        try {
            redisTemplate.opsForValue().set(cacheKey, value, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Config loaded from DB and cached: {}", cacheKey);
        } catch (RuntimeException e) {
            log.warn("Redis write failed for {}: {}", cacheKey, e.getMessage());
        }
        return value;
    }
```

替换 `evictCache` 方法（line 128-132）的完整实现为：

```java
    private void evictCache(String configType, String configKey) {
        String cacheKey = buildCacheKey(configType, configKey);
        try {
            redisTemplate.delete(cacheKey);
            log.debug("Config cache evicted: {}", cacheKey);
        } catch (RuntimeException e) {
            log.warn("Redis evict failed for {}: {}", cacheKey, e.getMessage());
        }
    }
```

- [ ] **Step 4: 运行测试，确认全部绿**

Run: `mvn -pl skill-server -am test -Dtest=SysConfigServiceTest`
Expected: `BUILD SUCCESS`, 10 tests pass（6 既有 + 4 新增）。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/SysConfigServiceTest.java
git commit -m "feat(skill-server): make SysConfigService resilient to Redis runtime failures"
```

---

## Task 3: `InboundProcessingService` 基础设施重构（注入 provider + 替换 handleAgentOffline 文案，行为不变）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

**Goal of this task:** 注入 `AssistantOfflineMessageProvider`、把 `handleAgentOffline` 两处硬编码替换为 `provider.get()`。**不改 `processChat` 的现有内联检查行为**（仍返回 `InboundResult.ok(...)`）。目的是让基础设施就位，后续 Task 按 action 切换语义。

- [ ] **Step 1: 更新现有 `InboundProcessingServiceTest` 让它注入 provider mock 并断言 provider 返回值**

Edit `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`:

在 `@Mock` 字段块末尾（`RedisMessageBroker redisMessageBroker;` 之后）新增：

```java
    @Mock
    private AssistantOfflineMessageProvider offlineMessageProvider;
```

在 `setUp()` 方法里，`service = new InboundProcessingService(...)` 的构造参数末尾加上 `offlineMessageProvider`：

```java
        service = new InboundProcessingService(
                resolverService,
                assistantIdProperties,
                gatewayApiClient,
                sessionManager,
                contextInjectionService,
                gatewayRelayService,
                messageService,
                rebuildService,
                new ObjectMapper(),
                assistantInfoService,
                scopeDispatcher,
                emitter,
                deliveryProperties,
                redisMessageBroker,
                offlineMessageProvider);
```

在 `setUp()` 末尾（默认 mock 之后）加一条 provider 默认返回：

```java
        lenient().when(offlineMessageProvider.get()).thenReturn("MOCK_OFFLINE_MSG");
```

找到既有的 `handleAgentOffline_ExternalWs_shouldRouteViaEmitter` 测试（约 line 249-285），把断言里的 `AGENT_OFFLINE_MESSAGE` 常量替换为字面量 `"MOCK_OFFLINE_MSG"`。例如原来的：

```java
StreamMessage expected = StreamMessage.builder()
        .type(StreamMessage.Types.ERROR)
        .error(/* 原常量引用 */)
        .build();
```

改成：

```java
StreamMessage expected = StreamMessage.builder()
        .type(StreamMessage.Types.ERROR)
        .error("MOCK_OFFLINE_MSG")
        .build();
```

如果测试里还有 `verify(messageService).saveSystemMessage(anyLong(), <常量>)`，同理改为 `verify(messageService).saveSystemMessage(anyLong(), eq("MOCK_OFFLINE_MSG"))`。

- [ ] **Step 2: 运行既有测试，确认失败（构造函数签名不匹配 + 常量不存在）**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest`
Expected: 编译失败 — `The constructor InboundProcessingService(...) is undefined`。

- [ ] **Step 3: 修改 `InboundProcessingService` 加 provider 字段 + 替换 handleAgentOffline 文案**

Edit `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`:

**(a) 删除第 37 行常量**：
```java
    private static final String AGENT_OFFLINE_MESSAGE = "任务下发失败，请检查助理是否离线，确保助理在线后重试";
```

**(b) 在字段块末尾（`RedisMessageBroker redisMessageBroker;` 之后，约 line 52）新增字段**：
```java
    private final AssistantOfflineMessageProvider offlineMessageProvider;
```

**(c) 构造函数加参数 + 赋值**：参数列表末尾加 `AssistantOfflineMessageProvider offlineMessageProvider`，body 末尾加 `this.offlineMessageProvider = offlineMessageProvider;`。

**(d) 修改 `handleAgentOffline` 方法（约 line 331-349）**，把两处 `AGENT_OFFLINE_MESSAGE` 替换为 `offlineMessageProvider.get()`：

```java
    void handleAgentOffline(String businessDomain, String sessionType,
                                     String sessionId, String ak, String assistantAccount) {
        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            String offlineMessage = offlineMessageProvider.get();
            StreamMessage offlineMsg = StreamMessage.builder()
                    .type(StreamMessage.Types.ERROR)
                    .error(offlineMessage)
                    .build();
            emitter.emitToSession(session,
                    String.valueOf(session.getId()), null, offlineMsg);
            if (session.isImDirectSession()) {
                try {
                    messageService.saveSystemMessage(session.getId(), offlineMessage);
                } catch (Exception e) {
                    log.error("Failed to persist agent_offline message: {}", e.getMessage());
                }
            }
        }
    }
```

**(e) 不修改 `processChat` 的 line 115-128**（它仍然走 inline check + 返回 `InboundResult.ok(...)`）；这是下一个 Task 的工作。

- [ ] **Step 4: 运行测试，确认绿**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest`
Expected: `BUILD SUCCESS`, 既有用例（含 `handleAgentOffline_ExternalWs_shouldRouteViaEmitter`）全部通过。

- [ ] **Step 5: 全量编译确认无回归（InboundProcessingService 被其他类构造引用）**

Run: `mvn -pl skill-server -am compile`
Expected: `BUILD SUCCESS`。Spring 会自动把新注入的 `AssistantOfflineMessageProvider` bean 装配进来，无需改任何配置类。

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java
git commit -m "refactor(skill-server): inject AssistantOfflineMessageProvider into InboundProcessingService"
```

---

## Task 4: `processChat` 离线语义从 `ok` 改为 `error(503)`（抽出 `checkAgentOnline` helper）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

- [ ] **Step 1: 写失败测试 — chat 离线返回 503**

Append to `InboundProcessingServiceTest.java`（放在 `processChat` 测试分组内）：

```java
    @Test
    @DisplayName("processChat: agent 离线时返回 error(503, offline_msg, sid, wsid) 且调用 handleAgentOffline 副作用")
    void processChatAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        SkillSession existing = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(existing);

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals("MOCK_OFFLINE_MSG", result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertEquals(String.valueOf(existing.getId()), result.welinkSessionId());

        verify(emitter).emitToSession(eq(existing), anyString(), isNull(), any(StreamMessage.class));
    }

    @Test
    @DisplayName("processChat: business 助手（requiresOnlineCheck=false）跳过在线检查，正常转发")
    void processChatBusinessScopeSkipsOnlineCheck() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "EXTERNAL");

        assertTrue(result.success());
        verify(gatewayApiClient, never()).getAgentByAk(anyString()); // 关键：没查在线状态
        verify(gatewayRelayService).sendInvokeToGateway(any(InvokeCommand.class));
    }
```

- [ ] **Step 2: 运行测试，确认第 1 个新用例失败**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest#processChatAgentOfflineReturns503`
Expected: FAIL — `result.success()` 为 true（现状仍返回 `ok`），`assertFalse` 失败。

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest#processChatBusinessScopeSkipsOnlineCheck`
Expected: 取决于实现 — 现状应该也 PASS（因为 `requiresOnlineCheck=false` 时 inline check 已经跳过）。但作为回归守护保留。

- [ ] **Step 3: 在 `InboundProcessingService` 抽出 `checkAgentOnline` 并改写 `processChat`**

Edit `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`:

**(a) 在 `handleAgentOffline` 定义**上方**（或下方，紧邻即可）新增私有方法 `checkAgentOnline`**：

```java
    /**
     * 返回 null 表示在线（或跳过检查），调用方继续主流程。
     * 返回非 null 表示离线，调用方直接 return 该结果
     * （已带好 code=503、errormsg、session IDs，并已执行 handleAgentOffline 副作用）。
     */
    private InboundResult checkAgentOnline(String businessDomain, String sessionType,
                                           String sessionId, String ak,
                                           String assistantAccount) {
        if (!assistantIdProperties.isEnabled()) return null;
        AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(ak));
        if (!scopeStrategy.requiresOnlineCheck()) return null;
        if (gatewayApiClient.getAgentByAk(ak) != null) return null;

        log.warn("[SKIP] checkAgentOnline: reason=agent_offline, ak={}, domain={}, sessionType={}, sessionId={}",
                ak, businessDomain, sessionType, sessionId);
        SkillSession existing = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        handleAgentOffline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        return InboundResult.error(
                503,
                offlineMessageProvider.get(),
                sessionId,
                existing != null ? String.valueOf(existing.getId()) : null);
    }
```

**(b) 替换 `processChat` 里原来的 inline check 块**（line 115-128 附近，从 `if (assistantIdProperties.isEnabled() && scopeStrategy.requiresOnlineCheck()) {` 开始到 `}` 结束）**整段替换为**：

```java
        // 第 2 步：Agent 在线检查（开关控制，业务助手跳过）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;
```

**注意**：替换后 `processChat` 不再在方法头部声明 `scopeStrategy` 局部变量（因为它已经在 `checkAgentOnline` 内部重新查一遍）。如果 `processChat` 后续代码还用到 `scopeStrategy`，保留原来的获取语句；否则连带删除。（**当前代码里 `scopeStrategy` 只在这段检查里用到**，可以安全移除。）

- [ ] **Step 4: 运行新测试，确认绿**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest`
Expected: `BUILD SUCCESS`, 新增 2 个用例 + 既有用例全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java
git commit -m "feat(skill-server): processChat returns code=503 when agent offline"
```

---

## Task 5: `processRebuild` 新增在线检查

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

- [ ] **Step 1: 写失败测试**

Append to `InboundProcessingServiceTest.java`（`processRebuild` 测试分组内，如果没有则新建一节）：

```java
    // ==================== processRebuild ====================

    @Test
    @DisplayName("processRebuild: agent 离线时返回 error(503, offline_msg, sid, wsid)，不创建 session")
    void processRebuildAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        SkillSession existing = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(existing);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals("MOCK_OFFLINE_MSG", result.message());
        verify(sessionManager, never()).requestToolSession(any(), any());
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any(), any());
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest#processRebuildAgentOfflineReturns503`
Expected: FAIL — `result.success()` 为 true（现状 rebuild 无在线检查，会走 `requestToolSession` 分支并返回 ok）。

- [ ] **Step 3: 在 `processRebuild` 开头加 `checkAgentOnline`**

Edit `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`, 在 `processRebuild` 方法里（约 line 297-317），紧接 `resolverService.resolve(...)` 的 null 校验之后、`findSession` 之前插入：

```java
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;
```

完整的 `processRebuild` 签名保持不变，改动后结构：

```java
    public InboundResult processRebuild(String businessDomain, String sessionType,
                                         String sessionId, String assistantAccount) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            sessionManager.requestToolSession(session, null);
            return InboundResult.ok(sessionId, String.valueOf(session.getId()));
        } else {
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, null, null);
            SkillSession created = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
            return InboundResult.ok(sessionId,
                    created != null ? String.valueOf(created.getId()) : null);
        }
    }
```

- [ ] **Step 4: 运行测试，确认绿**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest`
Expected: 新增用例 + 既有用例全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java
git commit -m "feat(skill-server): processRebuild returns code=503 when agent offline"
```

---

## Task 6: `processQuestionReply` / `processPermissionReply` 新增在线检查（保留 404 语义）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

- [ ] **Step 1: 写 4 个失败/回归测试**

Append to `InboundProcessingServiceTest.java`（放在对应 action 测试分组内）：

```java
    @Test
    @DisplayName("processQuestionReply: session 存在 + agent 离线 → 返回 error(503, offline_msg)")
    void processQuestionReplyAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "answer", "tool-call-1", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals("MOCK_OFFLINE_MSG", result.message());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any(InvokeCommand.class));
    }

    @Test
    @DisplayName("processQuestionReply: session 不存在优先返回 404（即使 agent 离线）")
    void processQuestionReplyMissingSessionReturns404EvenIfOffline() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession(any(), any(), any(), any())).thenReturn(null);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "answer", "tool-call-1", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(404, result.code());
        assertEquals("Session not found or not ready", result.message());
        verify(gatewayApiClient, never()).getAgentByAk(anyString()); // 404 优先，未查在线
    }

    @Test
    @DisplayName("processPermissionReply: session 存在 + agent 离线 → 返回 error(503, offline_msg)")
    void processPermissionReplyAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "perm-1", "once", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals("MOCK_OFFLINE_MSG", result.message());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any(InvokeCommand.class));
    }

    @Test
    @DisplayName("processPermissionReply: session 不存在优先返回 404（即使 agent 离线）")
    void processPermissionReplyMissingSessionReturns404EvenIfOffline() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession(any(), any(), any(), any())).thenReturn(null);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "perm-1", "once", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(404, result.code());
        assertEquals("Session not found or not ready", result.message());
        verify(gatewayApiClient, never()).getAgentByAk(anyString());
    }
```

- [ ] **Step 2: 运行测试，确认 503 两个用例 FAIL、404 两个用例 PASS**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest`
Expected:
- `processQuestionReplyAgentOfflineReturns503` FAIL（现状：走到 Gateway，返回 ok）
- `processPermissionReplyAgentOfflineReturns503` FAIL（同上）
- `processQuestionReplyMissingSessionReturns404EvenIfOffline` PASS（既有 404 路径不受影响）
- `processPermissionReplyMissingSessionReturns404EvenIfOffline` PASS（同上）

- [ ] **Step 3: 在两个方法里 `findSession` + 404 判断**之后**插入 `checkAgentOnline`**

Edit `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`:

**(a) `processQuestionReply` 改动后的完整方法体**（替换原 line 201-230）：

```java
    public InboundResult processQuestionReply(String businessDomain, String sessionType,
                                               String sessionId, String assistantAccount,
                                               String content, String toolCallId,
                                               String subagentSessionId, String inboundSource) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready", sessionId,
                    session != null ? String.valueOf(session.getId()) : null);
        }

        // 在线检查（404 后置：保留 session 不存在 → 404 语义；session 存在 + 离线 → 503）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("answer", content);
        payloadFields.put("toolCallId", toolCallId);
        payloadFields.put("toolSessionId", targetToolSessionId);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.QUESTION_REPLY,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

        writeInvokeSource(session, inboundSource);
        return InboundResult.ok(sessionId, String.valueOf(session.getId()));
    }
```

**(b) `processPermissionReply` 改动后的完整方法体**（替换原 line 244-285）：

```java
    public InboundResult processPermissionReply(String businessDomain, String sessionType,
                                                 String sessionId, String assistantAccount,
                                                 String permissionId, String response,
                                                 String subagentSessionId, String inboundSource) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready", sessionId,
                    session != null ? String.valueOf(session.getId()) : null);
        }

        // 在线检查（404 后置：同 processQuestionReply）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("permissionId", permissionId);
        payloadFields.put("response", response);
        payloadFields.put("toolSessionId", targetToolSessionId);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.PERMISSION_REPLY,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

        // 广播权限回复消息到前端
        StreamMessage replyMsg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .role("assistant")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId(permissionId)
                        .response(response)
                        .build())
                .subagentSessionId(subagentSessionId)
                .build();
        gatewayRelayService.publishProtocolMessage(String.valueOf(session.getId()), replyMsg);

        writeInvokeSource(session, inboundSource);
        return InboundResult.ok(sessionId, String.valueOf(session.getId()));
    }
```

- [ ] **Step 4: 运行测试，确认全部绿**

Run: `mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest`
Expected: 4 个新用例 + 所有既有用例全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java
git commit -m "feat(skill-server): add online check to processQuestionReply/PermissionReply (preserve 404 priority)"
```

---

## Task 7: `SkillMessageController` 文案替换（三处 → `provider.get()`）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java`

- [ ] **Step 1: 更新 `SkillMessageControllerTest` 注入 provider mock 并把硬编码断言替换为 provider mock 值**

Edit `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java`:

**(a) 在 `@Mock` 字段末尾新增**：

```java
    @Mock
    private AssistantOfflineMessageProvider offlineMessageProvider;
```

记得加 import：`import com.opencode.cui.skill.service.AssistantOfflineMessageProvider;`

**(b) 在 `setUp()` 里把 provider mock 加进 `new SkillMessageController(...)` 构造调用的末尾**，并加一条默认返回：

```java
        lenient().when(offlineMessageProvider.get()).thenReturn("MOCK_OFFLINE_MSG");
        controller = new SkillMessageController(
                messageService, sessionService, gatewayRelayService, gatewayApiClient,
                assistantIdProperties, imMessageService, objectMapper,
                accessControlService, messageRouter, assistantInfoService,
                scopeDispatcher, offlineMessageProvider);
```

**(c) 找到现有离线场景的测试（约 line 311 / line 378 附近，可用 `grep -n "agent_offline\|99" SkillMessageControllerTest.java` 或者已读到的 `when(gatewayApiClient.getAgentByAk("99")).thenReturn(null)` 两处）**，把断言里对 `"任务下发失败，请检查助理是否离线..."` 或 `AGENT_OFFLINE_MESSAGE` 常量的引用全部替换为字面量 `"MOCK_OFFLINE_MSG"`。例如：

```java
// 原来
verify(messageService).saveSystemMessage(anyLong(), eq("任务下发失败，请检查助理是否离线，确保助理在线后重试"));
// 改为
verify(messageService).saveSystemMessage(anyLong(), eq("MOCK_OFFLINE_MSG"));
```

```java
// 原来（permission reply 断言）
assertEquals("任务下发失败，请检查助理是否离线，确保助理在线后重试", response.getBody().getErrormsg());
// 改为
assertEquals("MOCK_OFFLINE_MSG", response.getBody().getErrormsg());
```

- [ ] **Step 2: 运行测试，确认失败（构造函数不匹配）**

Run: `mvn -pl skill-server -am test -Dtest=SkillMessageControllerTest`
Expected: 编译失败 — `The constructor SkillMessageController(...) is undefined`（多出一个参数）。

- [ ] **Step 3: 改 `SkillMessageController`**

Edit `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`:

**(a) 删除第 60 行常量**：
```java
    private static final String AGENT_OFFLINE_MESSAGE = "任务下发失败，请检查助理是否离线，确保助理在线后重试";
```

**(b) 在字段块末尾（`AssistantScopeDispatcher scopeDispatcher;` 之后）新增字段**：
```java
    private final AssistantOfflineMessageProvider offlineMessageProvider;
```

import：`import com.opencode.cui.skill.service.AssistantOfflineMessageProvider;`

**(c) 在构造函数末尾加参数 `AssistantOfflineMessageProvider offlineMessageProvider` 和赋值 `this.offlineMessageProvider = offlineMessageProvider;`**。

**(d) 替换三处**：

- line 169: `messageService.saveSystemMessage(numericSessionId, AGENT_OFFLINE_MESSAGE);`
  → `messageService.saveSystemMessage(numericSessionId, offlineMessageProvider.get());`

- line 175: `.error(AGENT_OFFLINE_MESSAGE)`
  → `.error(offlineMessageProvider.get())`
  （注意：如果同一离线分支里两次调用 `provider.get()` 不放心文案一致性，可以先读一次到局部变量 `String offlineMessage = offlineMessageProvider.get();` 再用于 `saveSystemMessage` 和 `.error(...)` — 但因为 provider 内部已有 `SysConfigService` 的 Redis 缓存，两次调用开销可忽略；**实现时保持直接调用即可**）

- line 388: `return ResponseEntity.ok(ApiResponse.error(503, AGENT_OFFLINE_MESSAGE));`
  → `return ResponseEntity.ok(ApiResponse.error(503, offlineMessageProvider.get()));`

- [ ] **Step 4: 运行测试，确认绿**

Run: `mvn -pl skill-server -am test -Dtest=SkillMessageControllerTest`
Expected: 所有用例 PASS。

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
git add skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java
git commit -m "refactor(skill-server): SkillMessageController reads offline message from provider"
```

---

## Task 8: `ExternalInboundControllerTest` 契约回归守护（code=503 + sessionData）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java`

**Note:** 此 Task 只加测试、**不改生产代码**。`ExternalInboundController.java:84-100` 的既有逻辑已经会把 `InboundResult.error(503, ..., sessionId, welinkSessionId)` 正确组装成响应体；本测试作为契约守护，防止日后误改丢失 data payload。

- [ ] **Step 1: 写测试**

Append to `ExternalInboundControllerTest.java`（放在类末尾 `}` 之前）：

```java
    @Test
    @DisplayName("chat action: 离线 InboundResult 被组装为 HTTP 200 + code=503 + errormsg + data(sid,wsid)")
    void chatActionOfflineReturns503WithSessionData() throws Exception {
        when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.error(503, "msg-x", "ext-sid", "123"));

        var request = buildRequest("chat", "{\"content\":\"hello\",\"msgType\":\"text\"}");
        var response = controller.invoke(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(503, response.getBody().getCode());
        assertEquals("msg-x", response.getBody().getErrormsg());

        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, String>) response.getBody().getData();
        assertNotNull(data);
        assertEquals("ext-sid", data.get("businessSessionId"));
        assertEquals("123", data.get("welinkSessionId"));
    }
```

如果类里没有现成的 `buildRequest(...)` helper 构造 chat 请求（实际上 line 32-38 已经有），直接复用即可。

- [ ] **Step 2: 运行测试，确认绿（不需要改生产代码）**

Run: `mvn -pl skill-server -am test -Dtest=ExternalInboundControllerTest`
Expected: 新增用例 + 既有用例 PASS。

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "test(skill-server): guard external invoke 503 response body contract"
```

---

## Task 9: 全量构建 + 手工验证清单

**Files:** 无改动。此 Task 只是收尾。

- [ ] **Step 1: 全量构建 + 测试**

Run: `mvn -pl skill-server -am clean test`
Expected: `BUILD SUCCESS`，所有测试通过。

若有新增的 lint warning（例如 unused import），立即清理并补 commit。**按实际修改文件逐个 `git add`**（避免 `git add .` 误纳非本计划的文件）：

```bash
# 示例：假设清理了 InboundProcessingService 和 SkillMessageController 的无用 import
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
git commit -m "chore(skill-server): tidy imports after offline response refactor"
```

（实际 `git add` 路径以 `git status` 列出的未提交改动为准。）

- [ ] **Step 2: 手工验证（按 spec §9.4，需要本地起 skill-server + Redis + MySQL）**

记录每一步结果（截图或文本），交付前挂到 PR 描述里：

1. **启动 skill-server**（DB 无 `assistant_offline:message` 记录）
2. **chat 离线**：停掉对应 agent 的 WS 连接，调用
   ```http
   POST /api/external/invoke
   Content-Type: application/json

   {
     "action":"chat","businessDomain":"im","sessionType":"direct",
     "sessionId":"dm-<test>","assistantAccount":"<personal-agent>",
     "payload":{"content":"hi","msgType":"text"}
   }
   ```
   期望：`{"code":503,"errormsg":"任务下发失败，请检查助理是否离线，确保助理在线后重试","data":{...}}`
3. **录入配置**（改文案覆盖默认）—— 注意 `SysConfigController` 挂在 `/api/admin/configs`：
   ```http
   POST /api/admin/configs
   Content-Type: application/json

   {"configType":"assistant_offline","configKey":"message","configValue":"测试文案-xxx","status":1}
   ```
   （若已存在该记录，用 `PUT /api/admin/configs/{id}` 更新；`update` 会触发 `evictCache`，立即生效，无需 step 4）
4. **清 Redis key 或等 TTL**（仅在 create 场景下必要；create 不触发 evict）：`redis-cli DEL ss:config:assistant_offline:message`
5. 再次调用 step 2 → `errormsg` 应变为 `"测试文案-xxx"`
6. **rebuild 离线**：action 改为 `rebuild`（无 payload），期望 `code=503`
7. **question_reply / permission_reply**：
   - (a) 先用 chat 建一个 session；agent 离线后调用 q/p_reply（带正确 sessionId）→ 期望 `code=503`
   - (b) `sessionId` 填不存在的值调用 q/p_reply（不管 agent 在不在线）→ 期望 `code=404`、`errormsg="Session not found or not ready"`
8. **业务助手跳过**：切到一个 `scope=business` 的助手账号，离线时调用 chat → 期望 `code=0`（走到 Gateway；因为 `requiresOnlineCheck=false`）
9. **Redis 故障兜底**：`redis-cli shutdown nosave`（或 iptables 阻断端口），重新触发 step 2 → 仍应返回 `code=503` 正确文案；skill-server 日志应出现 `Redis read failed ... falling back to DB`

- [ ] **Step 3: 回顾 spec §12 清单勾选状态**

逐项核对 `docs/superpowers/specs/2026-04-21-external-offline-response-design.md` 的 §12 范围清单，确保 11 个 checkbox 全部 tick。

- [ ] **Step 4: 标记 Task 完成（不提交代码）**

无需额外 commit。此时 branch 上应有 **8 个新 commit**（Task 1-8 各 1 个）；Task 9 若有 lint 修补则再加 1 个（见 Appendix A 的 optional 条目）。

---

## Appendix A: 提交顺序回顾

```
1. feat(skill-server): add AssistantOfflineMessageProvider with sys_config-backed message + fallback
2. feat(skill-server): make SysConfigService resilient to Redis runtime failures
3. refactor(skill-server): inject AssistantOfflineMessageProvider into InboundProcessingService
4. feat(skill-server): processChat returns code=503 when agent offline
5. feat(skill-server): processRebuild returns code=503 when agent offline
6. feat(skill-server): add online check to processQuestionReply/PermissionReply (preserve 404 priority)
7. refactor(skill-server): SkillMessageController reads offline message from provider
8. test(skill-server): guard external invoke 503 response body contract
9. (optional) chore(skill-server): tidy imports after offline response refactor
```

每个 commit 都独立可编译、独立可测。

---

## Appendix B: 如果需要回滚

- 全部回滚：`git revert <commit-8> <commit-7> ... <commit-1>`（反向顺序一次性 revert）
- 只回滚 "processChat 离线返回 503" 行为（保留文案配置化）：`git revert <commit-4>`，其他仍生效
- 回退到硬编码文案（保留 checkAgentOnline 架构）：把 `provider.get()` 调用点手动替换为常量字符串，删除 provider bean。但更简单是 revert 对应 commit。

---

## Spec Self-Review（完成于计划写作时）

**Spec coverage check**（对照 spec §12 范围清单 11 项）：
- [x] 新增 `AssistantOfflineMessageProvider.java` — Task 1 Step 3
- [x] 新增 `AssistantOfflineMessageProviderTest.java` — Task 1 Step 1
- [x] 修改 `SysConfigService.java` Redis 兜底 — Task 2 Step 3
- [x] 更新 `SysConfigServiceTest.java` 3 个 Redis 异常用例 — Task 2 Step 1
- [x] 修改 `InboundProcessingService.java`（删常量、注入 provider、抽 `checkAgentOnline`、按 action 分类插入、`handleAgentOffline` 替换）— Tasks 3-6
- [x] 修改 `SkillMessageController.java` 三处替换 — Task 7
- [x] 更新 `InboundProcessingServiceTest.java` 4 离线用例 + 1 business scope 跳过 + handleAgentOffline 文案断言 — Tasks 3-6
- [x] 更新 `ExternalInboundControllerTest.java` 503 契约 — Task 8
- [x] 更新 `SkillMessageControllerTest.java` provider mock + 文案断言 — Task 7
- [x] `mvn -pl skill-server compile test` 绿 — Task 9 Step 1
- [x] 手工验证 step 1-8（含 Redis 故障兜底）— Task 9 Step 2

**Placeholder scan**：无 "TBD" / "implement later" / "similar to Task N" / "add appropriate validation"。

**Type consistency**：
- `checkAgentOnline` 签名 `(String, String, String, String, String) → InboundResult` 在 Task 4 定义，Tasks 5/6 引用一致
- `AssistantOfflineMessageProvider.get()` 在 Task 1 定义，Tasks 3/7 以字符串形式使用
- `InboundResult.error(int, String, String, String)` 四参重载在 spec §5.2.1 / Tasks 4-6 一致使用
- 测试 mock 字面量 `"MOCK_OFFLINE_MSG"` 在 Tasks 3-7 一致

**没有遗漏的 spec 需求**。
