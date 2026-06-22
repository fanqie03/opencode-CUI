# PR #46 Review — differentiated-error-hints

整体方向正确，PRD v3 的 R1–R6 八成功能性需求已落地。但存在 **1 个 P0 阻断 + 2 个 P1 + 5 个 P2**，**当前状态不可合**。问题逐项列在下面，每条包含代码现场、影响分析、修复建议。

> 建议先把 PR 标题改成符合规范的命名（如 `feat(skill-server,gateway): 差异化助理离线文案`），便于后续 `git log` 追溯。

---

## 🛑 P0（阻断）

### #1 `/internal/agent/availability` 路径不匹配，整套差异化文案全失效

**代码现场**

Gateway 端 (`ai-gateway/src/main/java/com/opencode/cui/gateway/controller/AgentController.java`)：
```java
@RestController
@RequestMapping("/api/gateway")                       // ← 类级别前缀
public class AgentController {
    ...
    @GetMapping("/internal/agent/availability")       // ← 方法路径
    public ResponseEntity<...> getAgentAvailability(...)
}
```
实际监听路径 = `/api/gateway/internal/agent/availability`

Skill-server 端 (`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java` 第 ~160 行)：
```java
String url = gatewayBaseUrl + "/internal/agent/availability?ak=" + ak;
```
请求路径 = `/internal/agent/availability`

协议文档 (`documents/protocol/v3/02-skillserver-gateway.md` 第 416 行)：
```
GET /internal/agent/availability?ak={ak}
```
也写的是不带 `/api/gateway/` 前缀。

**影响**

每次请求都会 404：
1. `GatewayApiClient.getAvailability` 进 catch 或 non-2xx 分支，返 `null`
2. `AssistantAvailabilityService.queryAndCompute` 第 `if (gw == null)` 命中 → 返 `AvailabilityResult.ofFallbackError(DEFAULT_HARDCODED_MESSAGE)`
3. **所有用户永远只看到硬编码的兜底文案**，PRD 想做的 NOT_CONFIGURED / OFFLINE_TYPED / OFFLINE_DEFAULT 三档差异化文案全部失效

**对照 PRD AC**：第 1/2/3/4/16 条全部失败。

**为什么测试没暴露**

`AgentControllerTest` 是单元测试，直接 `controller.getAgentAvailability(...)` 调方法，**没经过 Spring 的 URL 路由**，所以发现不了。

**修复建议（任选其一）**

A. Controller 把新接口独立到不带 `/api/gateway` 前缀的 mapping（与文档对齐）：
```java
@GetMapping("/internal/agent/availability")  // 改为绝对路径
```
但 Spring 中嵌入 controller 的方法路径仍受 `@RequestMapping` 前缀影响，需要把这个方法挪到独立的 controller 或新建一个 `@RequestMapping("/internal/agent")` 的 controller。

B. 接受 `/api/gateway/` 前缀（更符合现有架构），三处一起改：
- `GatewayApiClient.getAvailability`: `gatewayBaseUrl + "/api/gateway/internal/agent/availability?ak=" + ak`
- `documents/protocol/v3/02-skillserver-gateway.md` §5.x 文档更新
- 不动 Controller

**必须补的测试**

加一个真正走 HTTP 路由的测试（MockMvc 或 `@SpringBootTest(webEnvironment=RANDOM_PORT)`），覆盖 200/401/400 三个状态码，防止类似回归：
```java
mockMvc.perform(get("/api/gateway/internal/agent/availability")
        .param("ak", "ak_test")
        .header("Authorization", "Bearer test-token"))
    .andExpect(status().isOk());
```

---

## ⚠️ P1（高优先级）

### #2 `AssistantAvailabilityService.resolve` 反序列化失败时返 null，调用方 NPE

**代码现场** (`skill-server/.../service/AssistantAvailabilityService.java`)：
```java
public AvailabilityResult resolve(String ak) {
    ...
    try {
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return deserialize(cached);              // ← 问题点
        }
    } catch (RuntimeException e) {
        log.warn("Redis read failed ...");
    }
    ...
}

private AvailabilityResult deserialize(String cached) {
    try {
        return objectMapper.readValue(cached, AvailabilityResult.class);
    } catch (Exception e) {
        log.warn("Failed to deserialize ...");
        return null;                                 // ← 失败返 null
    }
}
```

**影响**

当 Redis 里存在但反序列化失败（版本升级、字段定义变更、手动写入脏数据等），`resolve` 直接 `return null`。调用方：

```java
// SkillMessageController.routeToGateway (line ~200)
AvailabilityResult r = availabilityService.resolve(session.getAk());
if (!r.online()) {        // ← NullPointerException
```

后果：30 秒 TTL 内该 ak 所有请求 500，发消息接口直接挂。

**对照 PRD R2**：明确写「Redis 读/写/删失败一律当 cache miss 或 best-effort，**不改变业务判定**」——程序员处理了 `redisTemplate.get()` 抛异常的情况（外层 try-catch），但漏了"读到了但解析不了"这条路径。

**修复建议**

`deserialize` 返 null 时按 cache miss 处理，并顺手 evict 坏 key：
```java
try {
    String cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
        AvailabilityResult parsed = deserialize(cached);
        if (parsed != null) {
            return parsed;
        }
        // 坏数据，evict 避免下次再踩
        try { redisTemplate.delete(cacheKey); } catch (RuntimeException ignored) {}
    }
} catch (RuntimeException e) {
    log.warn("Redis read failed for availability cache key={}, falling through: {}",
            cacheKey, e.getMessage());
}
// 继续走 queryAndCompute(ak)
```

**补测试**：`AssistantAvailabilityServiceTest` 加用例「Redis 返回非法 JSON → 走 gateway 路径 + key 被 evict」。

---

### #3 Legacy `/agents/{id}/status` 和 `/agents/{id}/invoke` 没有鉴权

**代码现场** (`AgentController.java`)：

新版 4 个接口全部首行调 `isAuthorized()`：
```java
@GetMapping("/agents")
public ResponseEntity<...> listOnlineAgents(
        @RequestHeader(value = "Authorization", required = false) String authorization, ...) {
    if (!isAuthorized(authorization)) return 401;
    ...
}
// 同样的 pattern：/agents/status, /invoke, /internal/agent/availability
```

但 legacy 两个接口**方法签名里完全没有 `Authorization` 参数**，方法体也没鉴权：
```java
@GetMapping("/agents/{id}/status")
public ResponseEntity<Map<String, Object>> getAgentStatus(@PathVariable Long id) {
    AgentConnection agent = agentRegistryService.findById(id);   // ← 直接查
    if (agent == null) return ResponseEntity.notFound().build();
    ...
}

@PostMapping("/agents/{id}/invoke")
public ResponseEntity<Map<String, Object>> invokeAgentLegacy(
        @PathVariable Long id,
        @RequestBody GatewayMessage message) {
    AgentConnection agent = agentRegistryService.findById(id);
    ...
    eventRelayService.relayToAgent(agent.getAkId(), message.withAk(agent.getAkId()));  // ← 远程命令下发
    ...
}
```

**影响（安全）**

- `GET /api/gateway/agents/{id}/status` → 任何人猜对自增 id（从 1 数）就能查到 agent 信息（akId、userId、device、IP 等），**中危信息泄露**
- `POST /api/gateway/agents/{id}/invoke` → 任何人猜对 id 就能向 agent 发任意命令，**高危远程命令执行**

**对照 PRD R6**：明确要求「保护范围：**不只新接口**，已有 `/api/gateway/agents`、`/agents/status` **一并接 InternalAuth filter**（清理 changeme 默认值）」。Legacy 接口属于"已有"，应在保护范围内。

**修复建议（二选一）**

A.（推荐）**删掉这两个 legacy 接口**。理由：
- 类注释自己写了「【旧版】保留向后兼容」，新接口已替代
- 返回类型 `Map<String, Object>` 跟新版 `ApiResponse<...>` 不一致，本来就该淘汰
- 多留口子等于多一个被遗忘的攻击面

先确认无人调用：
```bash
rg -n "agents/\d+/status|agents/\d+/invoke" --type=java --type=ts --type=tsx
```

B. 补鉴权（如果确认还有 caller 必须保留）：
```java
@GetMapping("/agents/{id}/status")
public ResponseEntity<...> getAgentStatus(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable Long id) {
    if (!isAuthorized(authorization)) return 401;
    ...
}
```

---

## 📝 P2（推荐修，团队评估）

### #4 `existsOnlineActiveByAkId` 只看 status 不看心跳，依赖定时任务

**代码现场** (`AgentConnectionMapper.xml`)：
```xml
<select id="existsOnlineActiveByAkId" resultType="boolean">
    SELECT EXISTS(SELECT 1 FROM agent_connection
                  WHERE ak_id = #{akId} AND status = 'ONLINE')
</select>
```

**问题**

PRD R1 描述「ONLINE 且**活跃**的连接」，实现只判 `status='ONLINE'`，没有 `last_seen_at > now - threshold`。

依赖 `markStaleAgentsOffline` 定时任务把过期连接标 OFFLINE：
- agent 断电/断网后到下次定时任务执行（几十秒）之间，存在「假在线」窗口
- 定时任务卡住/挂掉时，窗口扩大到分钟/小时级
- 假在线期间 → `resolve()` 返 ONLINE → skill-server 推消息 → agent 没收到 → 用户看不到任何提示

**修复建议**

SQL 加心跳新鲜度判定：
```xml
<select id="existsOnlineActiveByAkId" resultType="boolean">
    SELECT EXISTS(SELECT 1 FROM agent_connection
                  WHERE ak_id = #{akId}
                    AND status = 'ONLINE'
                    AND last_seen_at > #{freshThreshold})
</select>
```
service 层传 `now() - heartbeat-timeout` 阈值进来。

或者最低限度：在 repository 方法 javadoc 注明依赖 `markStaleAgentsOffline` 健康，把隐式依赖显式化。

---

### #5 V14 seed 的 `ON DUPLICATE KEY UPDATE` 依赖唯一索引

**代码现场** (`V14__seed_assistant_offline_defaults.sql`)：
```sql
INSERT INTO sys_config (config_type, config_key, config_value, ...) VALUES
  ('assistant_offline', 'not_configured', '...', ...),
  ('assistant_offline', 'tool_type:opencode', '...', ...),
  ('assistant_offline', 'message', '...', ...)
ON DUPLICATE KEY UPDATE id = id;
```

**问题**

`ON DUPLICATE KEY UPDATE` 只在违反唯一约束时触发。如果 `sys_config` 表上没有 `UNIQUE (config_type, config_key)` 索引：
- 重复跑会插重复记录（虽然 Flyway 自身不重跑同版本，但开发库/测试库手动重置时会）
- 若旧库中已有 `(assistant_offline, message)` 记录（PRD 提到 message 是"保留现有 key 向后兼容"），V14 跑完后表里两条 `message`，`SysConfigService.getValue` `LIMIT 1` 取哪条不定

**修复建议**

1. 先确认表结构：
   ```sql
   SHOW CREATE TABLE sys_config\G
   ```
2. 如果没有唯一索引：先补一个迁移建索引，再跑 seed；或 seed 改写法：
   ```sql
   INSERT INTO sys_config (config_type, config_key, config_value, ...)
   SELECT * FROM (
       SELECT 'assistant_offline', 'not_configured', '...', ..., 1
   ) AS tmp
   WHERE NOT EXISTS (
       SELECT 1 FROM sys_config WHERE config_type='assistant_offline' AND config_key='not_configured'
   );
   ```

---

### #6 `resolve(ak)` 在 ak 为空时返 NOT_CONFIGURED 语义错误

**代码现场** (`AssistantAvailabilityService.java` 第 ~33 行)：
```java
public AvailabilityResult resolve(String ak) {
    if (ak == null || ak.isBlank()) {
        return AvailabilityResult.ofNotConfigured(lookupMessage("not_configured"));
    }
    ...
}
```

V14 中 `not_configured` 文案是：
> 「该助理尚未完成初始化配置，请前往 **[OpenCode开放平台](https://opencode.woa.com)** 完成绑定后重试。」

**问题**

ak 为 null/blank 有两种语义：
1. 用户的 session 有 ak，但表里查不到 → "机器人未配置"（语义对）
2. session 的 ak 字段本身就是 null/空 → **系统侧错误**，给用户显示"去开放平台绑定"是误导

`SkillMessageController.routeToGateway` 上游有 `if (session.getAk() == null) return;` 兜底，**走不到 resolve**；但 `InboundProcessingService.checkAgentOnline` 直接调 `availabilityService.resolve(ak)`，上游传入空字符串时会落到这个分支。

**修复建议**

```java
if (ak == null || ak.isBlank()) {
    log.error("[BUG] availabilityService.resolve called with blank ak, fallback to FALLBACK_ERROR");
    return AvailabilityResult.ofFallbackError(DEFAULT_HARDCODED_MESSAGE);
}
```
或直接抛 `IllegalArgumentException`，把责任推回 caller，强制上游补前置检查。

---

### #7 PR 标题/分支名与内容完全不符

- PR 标题：`Person p30056214 test`
- 分支：`person_p30056214-test`
- 内容：1718 行差异化离线文案功能

**问题**

仓库其他 PR 命名规范（`feat(skill-server): ...`、`refactor(send-to-im): ...`），这个 PR 命名风格突兀。合并后 `git log` 搜不到「差异化离线文案」是哪个 PR 引入的。

**修复建议**

合前把 PR 标题改为：
```
feat(skill-server,gateway): 差异化助理离线文案（未配置 / 已知类型 / 未知类型）
```

---

### #8 PR 夹带不相关文件 + 其他作者的工作区

**问题文件清单**

```
.trellis/.template-hashes.json                                       # 框架元数据
.trellis/spec/skill-server/backend/database-guidelines.md            # spec 文档
.trellis/spec/skill-server/backend/type-safety.md                    # spec 文档
.trellis/tasks/00-join-feison/prd.md                                 # ⚠️ 别人的任务
.trellis/tasks/00-join-feison/task.json                              # ⚠️ 别人的任务
.trellis/tasks/archive/2026-05/05-18-differentiated-error-hints/*    # 任务归档（应在 merge 后单独提）
.trellis/workspace/feison/index.md                                   # ⚠️ 别人的工作区
.trellis/workspace/feison/journal-1.md                               # ⚠️ 别人的工作区
```

**影响**

- 审查噪音：reviewer 要在 1718 行里筛功能改动，每个 trellis 文件都要判断
- **跨任务污染**：`00-join-feison` 和 `workspace/feison/` 看起来是另一个开发者（feison）的工作区，不应在这个 PR 里。可能是合并时把对方工作区一起带进来了。合并后会与对方 PR 冲突。

**修复建议**

合前至少剔除别人的文件：
```bash
git checkout main -- .trellis/tasks/00-join-feison/ .trellis/workspace/feison/
git commit -m "chore: drop accidentally-included files from other author"
```

理想情况：把 `.trellis/` 下所有文件拆到独立的 chore PR。

---

## ✅ 已正确落地（无需改动）

对照 PRD v3 检查，以下需求实现到位：

| PRD 项 | 状态 |
|---|---|
| R1 三个 repo 查询拆分（`existsByAkId` / `existsOnlineActiveByAkId` / `findLatestByAkIdOrderByLastSeenAtDescIdDesc`） | ✅ |
| R1 排序 `COALESCE(last_seen_at, created_at) DESC, id DESC LIMIT 1` | ✅ |
| R1 V6 索引 `(ak_id, last_seen_at DESC, id DESC)` | ✅ |
| R2 `AvailabilityResult` 五状态枚举 + 单入口 `resolve` + `evict` | ✅（除 #2 漏的反序列化分支） |
| R2 Redis 读/写/删 try-catch best-effort | ✅（同上） |
| R2 主动 evict：`GatewayMessageRouter.handleAgentOnline/Offline` 都在广播前 evict | ✅ |
| R3 V13 TEXT 升级 + V14 三条 seed | ✅ |
| R3 运维脚本用 SCAN（非 KEYS）+ 同时清两个 namespace | ✅ |
| R4 lookup 回退链（typed → message → 硬编码） | ✅ |
| R5 #1 #2 #3 #4 caller 改造 + `handleAgentOffline` 加 `offlineMessage` 参数 | ✅ |
| R6 两端 `InternalAuthProperties` 启动期 fail-fast（`changeme` / blank → IllegalStateException） | ✅ |
| 协议文档 §5.x 新增 availability 段 | ✅（路径要随 #1 同步） |

---

## 修复优先级建议

| 顺序 | 项 | 必做 | 说明 |
|---|---|---|---|
| 1 | #1 | ✅ | 不修整个 PR 等于白做 |
| 2 | #3 | ✅ | 安全裸奔，优先级仅次于 P0 |
| 3 | #2 | ✅ | 稳定性炸弹 |
| 4 | #8 | ⚠️ | 至少剔除别人的工作区，避免合并冲突 |
| 5 | #7 | ⚠️ | 改 PR 标题 |
| 6 | #6 | 推荐 | 5 分钟改 |
| 7 | #5 | 推荐 | 先看 sys_config 表结构再决定 |
| 8 | #4 | 推荐 | 加注释或改 SQL，看团队选择 |

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
