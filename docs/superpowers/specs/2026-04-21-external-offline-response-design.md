# External 接口助理离线特异返回 + 文案配置化 设计

- **日期**: 2026-04-21
- **分支**: `release-0417`
- **作者**: Llllviaaaa + Claude
- **修订历史**:
  - v1: 初稿
  - v2: 按代码评审修正 F1（Redis 故障兜底下沉到 SysConfigService）、F2（保留 q/p_reply 的 404 语义）、F3（scope 术语 `cloud`→`business`）、F4（回滚策略说明 Redis TTL 延迟）
  - v3: 按二轮评审修正 §7.5 IM 接口路径（`/api/skill-message/...` → `/api/skill/sessions/{sessionId}/...`）、§7.1 q/p_reply 404 行措辞（去掉"助理在线"限定）、§10 回滚策略拆成方案 A / 方案 B 两条并列，收紧 catch 异常类型至 `RuntimeException`
  - v3.1: 修正 §5.2.3 散文描述里一处残留的 `Exception` 字样，与参考实现保持一致
- **相关文件**:
  - `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/SysConfigService.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`

---

## 1 背景

`POST /api/external/invoke` 是外部系统（external 渠道）向个人助理下发消息的 REST 入口，支持四个 action：`chat` / `question_reply` / `permission_reply` / `rebuild`。

当前实现存在两个问题：

### 1.1 助理离线时 REST 响应无差异

只有 `action=chat` 这条路径在 `InboundProcessingService.processChat` 中做了 agent 在线检查（`gatewayApiClient.getAgentByAk(ak) == null` 视为离线）。命中离线分支时：

1. 日志打 `[SKIP] processChat: reason=agent_offline`
2. 通过 `StreamMessageEmitter` 向 WS 通道广播 `StreamMessage(type=ERROR, error=<文案>)`
3. 若是单聊，则 `SkillMessageService.saveSystemMessage` 持久化系统消息
4. **REST 接口返回 `InboundResult.ok(sessionId, welinkSessionId)`** —— Controller 包装为 `HTTP 200 / code=0 / data={businessSessionId, welinkSessionId}`

上游拿到的响应与"消息已正常下发"完全一致，只能被动等 WS 推送。同步调用方感知不到助理离线。

`question_reply` / `permission_reply` / `rebuild` 三个 action **完全没有在线检查**，agent 离线时会直接往 Gateway 转发后静默失败。

### 1.2 离线文案硬编码且重复

字符串 `"任务下发失败，请检查助理是否离线，确保助理在线后重试"` 分别硬编码在：

- `InboundProcessingService.java:37` —— `AGENT_OFFLINE_MESSAGE` 常量
- `SkillMessageController.java:60` —— 同名常量（另一份拷贝）

产品/运营无法不改代码调整文案；两处常量值也有漂移风险。

### 1.3 SysConfigService 对 Redis 故障无兜底

`SysConfigService.getValue` (line 47-75) 的读缓存 / 写缓存，`evictCache` 的删缓存，都未捕获 Redis 异常。Redis 连接故障时，任何走配置读取的请求路径都会直接抛 500；本次改造把离线文案下沉到 `sys_config`，会把"一个 Redis 单点故障"的影响面扩大到 external 和 IM 的离线响应路径。必须在 SysConfigService 层补 Redis 异常兜底。

### 1.4 IM 渠道的语义参照

项目里已有先例符合"离线=`code=503`"语义：`SkillMessageController.replyPermission` (line 388) 返回 `ApiResponse.error(503, AGENT_OFFLINE_MESSAGE)`。external 的 4 个 action 目前和它语义不一致，本次改造会对齐。

---

## 2 目标

1. external 4 个 action 离线时的 REST 响应对上游可识别：`HTTP 200 + code=503 + errormsg=<文案> + data={businessSessionId, welinkSessionId}`。
2. 离线文案从硬编码迁移到 `sys_config` 表，external 和 IM 两条链路共用同一条配置记录。
3. 补齐 `question_reply` / `permission_reply` / `rebuild` 的在线检查，消除静默失败。
4. 保留 `question_reply` / `permission_reply` 已有的 `404 "Session not found or not ready"` 语义：session 缺失优先于离线。
5. 不破坏 IM 渠道现有对外语义（HTTP 码、WS 广播、系统消息持久化不变）。
6. 配置缺失/失效时代码有兜底默认文案，不要求强依赖运维录入。
7. Redis 故障不应让离线文案读取路径 500；`SysConfigService` 内部把 Redis 异常降级到"直查 MySQL / 静默写失败"。

## 3 非目标

- 不改动 `AssistantScopeStrategy` 对"个人助手 / 业务助手"的判定方式（业务助手依旧天然跳过在线检查）。
- 不修改 `StreamMessageEmitter` / `StreamBufferService` 等出站/回放链路。
- 不做前端（miniapp）改动；miniapp 的离线提示仍通过 WS 的 `type=error` 消费。
- 不通过 migration 预埋配置记录；由运维 / 开发通过现有 `SysConfigController` REST 接口录入。

---

## 4 决策摘要

| # | 决策 | 理由 |
|---|------|------|
| 1 | external 4 个 action 离线时响应 `HTTP 200 + code=503 + errormsg=<文案>` | 与 `SkillMessageController.replyPermission` 既有语义一致；HTTP 层保持 200 避免触发上游网关的断路告警 |
| 2 | 4 个 action 全部补在线检查 | 统一语义 + 修复 3 个 action 的静默失败 bug |
| 3 | **`question_reply` / `permission_reply` 的在线检查放在 `findSession` + 404 判断之后** | 保留 "session 不存在 → 404" 语义（agent 在线时 404 仍然能返回）；`chat` 和 `rebuild` 无 404 语义，继续在 `resolve` 之后检查 |
| 4 | 配置 key：`config_type='assistant_offline', config_key='message'`（单条全局） | 现状即一字符串两处复用，无差异化需求；YAGNI |
| 5 | IM 和 external 共享同一配置 key，但 IM 侧 HTTP 码 / WS / 系统消息语义完全不变 | 只消除硬编码重复，不拉平不同业务层的协议差异 |
| 6 | Java 侧硬编码 `DEFAULT_OFFLINE_MESSAGE` 作 fallback；不加 migration | 配置记录不存在或 status=0 时兜底；避免 migration 预埋"业务文案"类配置 |
| 7 | **`SysConfigService` 的读缓存 / 写缓存 / evict 均捕获 Redis 异常，降级到直查 MySQL / 静默失败**；底层 DB 故障仍冒泡 | Redis 单点故障不应让一次配置读取变成 500；其他 `SysConfigService` 调用方（如 `CloudRequestBuilder`）同样受益 |

---

## 5 架构与组件

### 5.1 新增文件

**`skill-server/src/main/java/com/opencode/cui/skill/service/AssistantOfflineMessageProvider.java`**

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

职责：
- 单一职责 —— 读配置 + fallback。
- 无状态，Spring 单例天然线程安全。
- 缓存和 Redis 故障兜底均交给 `SysConfigService`。
- 不 catch 异常：DB 不可用时让 500 冒泡（Redis 故障已在 `SysConfigService` 层兜底）。

### 5.2 改动文件

#### 5.2.1 `InboundProcessingService.java`

- 删除常量 `private static final String AGENT_OFFLINE_MESSAGE = ...`（line 37）
- 构造注入新字段 `AssistantOfflineMessageProvider offlineMessageProvider`
- **新增私有方法 `checkAgentOnline`**：

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

- **在线检查的插入位置因 action 不同而不同**（决策 #3）：

  | Action | 位置 | 原因 |
  |--------|------|------|
  | `processChat` | `resolve` 成功之后、`contextInjectionService.resolvePrompt` 之前（现有 line 115-128 处） | 无 404 语义；保持现状位置 |
  | `processRebuild` | `resolve` 成功之后、`findSession` 之前 | 无 404 语义；防止为离线 agent 创建孤儿 session |
  | `processQuestionReply` | `findSession` + 既有 `404 "Session not found or not ready"` 判断**之后** | 保留 404 语义（agent 在线时 session 缺失仍然返回 404） |
  | `processPermissionReply` | 同上 | 同上 |

  所有 4 个方法的调用代码统一为：
  ```java
  InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
  if (offline != null) return offline;
  ```

- `processChat` 的**行为变化**：此前离线分支返回 `InboundResult.ok(sessionId, welinkSessionId)`；变更后返回 `InboundResult.error(503, ..., sessionId, welinkSessionId)`。
- `processQuestionReply` / `processPermissionReply` / `processRebuild` 的**行为变化**：agent 离线时不再静默失败，返回 `InboundResult.error(503, ..., sessionId, <welinkSessionId if exists>)`。
- `handleAgentOffline` 方法内部（line 337 / 343）：
  - `.error(AGENT_OFFLINE_MESSAGE)` → `.error(offlineMessageProvider.get())`
  - `saveSystemMessage(session.getId(), AGENT_OFFLINE_MESSAGE)` → `saveSystemMessage(session.getId(), offlineMessageProvider.get())`
  - 既有 try/catch 保持不变。

#### 5.2.2 `SkillMessageController.java`

- 删除常量 `private static final String AGENT_OFFLINE_MESSAGE = ...`（line 60）
- 构造注入 `AssistantOfflineMessageProvider offlineMessageProvider`
- 三处替换：
  - line 169: `messageService.saveSystemMessage(numericSessionId, AGENT_OFFLINE_MESSAGE)` → `offlineMessageProvider.get()`
  - line 175: `.error(AGENT_OFFLINE_MESSAGE)` → `.error(offlineMessageProvider.get())`
  - line 388: `ApiResponse.error(503, AGENT_OFFLINE_MESSAGE)` → `ApiResponse.error(503, offlineMessageProvider.get())`
- **行为不变**：IM 侧 HTTP 码 / WS 广播 / 系统消息持久化语义全部保持。

#### 5.2.3 `SysConfigService.java`（**新增于 v2**）

- `getValue` 读缓存（line 51）和写缓存（line 72）分别用 `try { ... } catch (RuntimeException e) { log.warn(...); }` 包起来：
  - 读异常 → 不 return，继续走 "查 DB + 可能重新写缓存"
  - 写异常 → 吞异常，已有 DB 返回值照常返回
- `evictCache` (line 128) 用同样的 `try/catch` 包 `redisTemplate.delete(cacheKey)`：
  - 避免在 `create` / `update` 的 `@Transactional` 上下文里因 Redis 故障触发事务回滚，导致配置更新失败

- 参考实现：
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

      // 2. 查 DB
      SysConfig config = sysConfigMapper.findByTypeAndKey(configType, configKey);
      if (config == null) return null;
      if (config.getStatus() == null || config.getStatus() != 1) return null;

      // 3. 写缓存（Redis 故障时静默）
      String value = config.getConfigValue();
      try {
          redisTemplate.opsForValue().set(cacheKey, value, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
      } catch (RuntimeException e) {
          log.warn("Redis write failed for {}: {}", cacheKey, e.getMessage());
      }
      return value;
  }

  private void evictCache(String configType, String configKey) {
      String cacheKey = buildCacheKey(configType, configKey);
      try {
          redisTemplate.delete(cacheKey);
      } catch (RuntimeException e) {
          log.warn("Redis evict failed for {}: {}", cacheKey, e.getMessage());
      }
  }
  ```
- 其它方法（`listByType` / `create` / `update` / `delete`）DB 行为不变；`create` / `update` 内部调用 `evictCache` 自动受益于上述兜底。
- 捕获 `RuntimeException` 而非 `Exception` 或具体 Redis 异常类型：Lettuce / Spring `RedisTemplate` 抛的所有异常（`RedisConnectionFailureException`、`RedisSystemException`、序列化异常、超时）都是 `RuntimeException` 子类；不捕获 `Exception` 避免吞掉 `InterruptedException` 等非业务异常。

### 5.3 零改动的文件

- `ExternalInboundController.java` —— 既有的 `if (!result.success()) → ApiResponse.builder().code(result.code()).errormsg(result.message()).data(sessionData).build()` (line 92-98) 已经能把 `InboundResult.error(503, ..., sessionId, welinkSessionId)` 组装成目标响应体，契约无需修改。
- `ApiResponse.java` / `ExternalInvokeRequest.java`
- `AssistantScopeStrategy.java` / `BusinessScopeStrategy.java` / `PersonalScopeStrategy.java` / `AssistantScopeDispatcher.java` / `GatewayApiClient.java` / `StreamMessageEmitter.java`
- `SysConfigMapper.xml` / `SysConfigMapper.java`
- 所有 migration（不新增 V11）

---

## 6 数据流

### 6.1 数据流（按 action 分三类）

#### 6.1.1 `chat` / `rebuild`（在线检查前置）

```
POST /api/external/invoke  (action=chat | rebuild)
  │
  ├─ ExternalInboundController → processingService.process{Chat|Rebuild}(...)
  │
  ├─ Step 1: resolverService.resolve(assistantAccount) → (ak, ownerWelinkId)
  │    └─ null → return error(404, "Invalid assistant account")
  │
  ├─ Step 2: checkAgentOnline(domain, sessionType, sessionId, ak, assistantAccount)
  │    ├─ !assistantIdProperties.isEnabled()                     → null (跳过)
  │    ├─ !scopeStrategy.requiresOnlineCheck()                   → null (业务助手)
  │    ├─ gatewayApiClient.getAgentByAk(ak) != null              → null (在线)
  │    └─ 离线 → findSession + handleAgentOffline + return error(503, msg, sid, wsid)
  │
  ├─ if (offline != null) return offline;
  │
  └─ Step 3+: 原有业务逻辑
       - chat:    contextInjection → findSession → forward to Gateway
       - rebuild: findSession → requestToolSession / createSessionAsync
```

#### 6.1.2 `question_reply` / `permission_reply`（保留 404 语义）

```
POST /api/external/invoke  (action=question_reply | permission_reply)
  │
  ├─ ExternalInboundController → processingService.process{QuestionReply|PermissionReply}(...)
  │
  ├─ Step 1: resolverService.resolve(assistantAccount) → (ak, ownerWelinkId)
  │    └─ null → return error(404, "Invalid assistant account")
  │
  ├─ Step 2: session = sessionManager.findSession(domain, type, sid, ak)
  │    └─ session == null || session.toolSessionId.isBlank()
  │           → return error(404, "Session not found or not ready", sid, str(session?.id))
  │
  ├─ Step 3: checkAgentOnline(domain, sessionType, sessionId, ak, assistantAccount)
  │    └─ 离线 → return error(503, msg, sid, wsid)
  │
  ├─ if (offline != null) return offline;
  │
  └─ Step 4: 原有业务逻辑 (send QUESTION_REPLY / PERMISSION_REPLY to Gateway)
```

### 6.2 Controller 层响应组装（零改动）

```
Controller (ExternalInboundController.java:84-100)
  result.success() == false
    → ResponseEntity.ok(ApiResponse.builder()
                            .code(result.code())
                            .errormsg(result.message())
                            .data({businessSessionId, welinkSessionId})
                            .build())
  result.success() == true
    → ResponseEntity.ok(ApiResponse.ok({businessSessionId, welinkSessionId}))
```

### 6.3 关键设计点

1. **Controller 零改动**：`InboundResult.error(503, ...)` 驱动出 `code=503` 响应体。HTTP 状态保持 200。
2. **仅离线分支查 session**：`checkAgentOnline` 在线时直接返回 null，不触发额外 `findSession`。对 `chat` / `rebuild`，离线分支里查一次；对 `q/p_reply`，外层已经查过一次（主流程 Step 2 里），离线分支里再查一次 —— 离线场景下这个多余查询可以接受（KISS，不传递 session 以保持 `checkAgentOnline` 签名简单）。
3. **"个人助手"范围天然覆盖**：`scopeStrategy.requiresOnlineCheck()` 对业务助手（`BusinessScopeStrategy.getScope() == "business"`）返回 `false`，直接跳过检查；只有个人助手（`PersonalScopeStrategy`）才进入离线分支。
4. **IM 渠道数据流不变**：仅把硬编码字符串替换为 `provider.get()`；副作用行为、HTTP 响应码全部保持。
5. **Redis 故障兜底在 `SysConfigService`**：`provider.get()` 侧不感知 Redis 异常；任意 Redis 故障时 `getValue` 仍返回 MySQL 的值（若存在）或 null。

---

## 7 接口契约

### 7.1 `POST /api/external/invoke` 响应矩阵

| 场景 | HTTP | `code` | `errormsg` | `data.businessSessionId` | `data.welinkSessionId` | 变化 |
|------|------|--------|------------|-------------------------|------------------------|------|
| 任意 action 成功 | 200 | `0` | _(省略)_ | 已有值 | 已有值/null | 不变 |
| `chat` 助理离线 | 200 | **`503`** | 配置文案 / fallback | 请求 sessionId | 既有 session id / null | **`0→503`** |
| `rebuild` 助理离线 | 200 | **`503`** | 配置文案 / fallback | 请求 sessionId | 既有 session id / null | **新增** |
| `question_reply` session 不存在（与 agent 状态无关） | 200 | `404` | `Session not found or not ready` | 请求 sessionId | null / 既有 id | **不变** |
| `question_reply` session 存在 + 助理离线 | 200 | **`503`** | 配置文案 / fallback | 请求 sessionId | 既有 session id | **新增** |
| `permission_reply` session 不存在（与 agent 状态无关） | 200 | `404` | `Session not found or not ready` | 请求 sessionId | null / 既有 id | **不变** |
| `permission_reply` session 存在 + 助理离线 | 200 | **`503`** | 配置文案 / fallback | 请求 sessionId | 既有 session id | **新增** |
| 助理账号无效 | 200 | `404` | `Invalid assistant account` | null | null | 不变 |
| 入参校验失败 | 400 | `400` | 具体原因 | null | null | 不变 |

**语义优先级**（`question_reply` / `permission_reply`）：`resolve 404 > session 不存在 404 > 离线 503`。`chat` / `rebuild` 没有"session 不存在 404"路径（session 不存在会异步创建），所以优先级是 `resolve 404 > 离线 503`。

`data` 字段通过 `@JsonInclude(NON_NULL)` 序列化：只含 `businessSessionId` 时 `welinkSessionId` 不出现；全空时 `data` 省略。

### 7.2 离线响应体示例

```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": 503,
  "errormsg": "任务下发失败，请检查助理是否离线，确保助理在线后重试",
  "data": {
    "businessSessionId": "ext-sess-123",
    "welinkSessionId": "8876543210987654321"
  }
}
```

### 7.3 `code` 语义汇总

- `0` = 成功
- `400` = 入参校验失败
- `404` = 资源缺失（助理账号 / session）
- **`503` = 助理离线**（新增统一语义）

### 7.4 上游推荐判定逻辑

```
if (resp.code == 0)       → 正常派发
if (resp.code == 404)     → 资源缺失（assistant 无效 / session 不存在），按 errormsg 区分
if (resp.code == 503)     → 助理离线，展示 resp.errormsg
                            可选根据 resp.data.welinkSessionId 决定是否关联到既有会话
else                      → 其他错误，按 resp.errormsg 处理
```

### 7.5 与 IM 渠道的语义对照（不变）

| 接口 | 路径 | 离线时返回 |
|------|------|------------|
| `POST /api/external/invoke` | `ExternalInboundController` | **（本次新增）** HTTP 200 + `code=503` + `errormsg=<配置文案>` |
| `POST /api/skill/sessions/{sessionId}/messages` | `SkillMessageController.sendMessage → routeToGateway` | 不回同步错误；靠 WS `type=error` 广播 + 系统消息持久化 |
| `POST /api/skill/sessions/{sessionId}/permissions/{permId}` | `SkillMessageController.replyPermission` | HTTP 200 + `code=503` + `errormsg=<配置文案>`（既有） |

---

## 8 错误处理与降级

### 8.1 配置读取（`AssistantOfflineMessageProvider.get`）

| 场景 | `SysConfigService.getValue` | `provider.get()` |
|------|-----------------------------|------------------|
| 配置记录不存在 | `null` | `DEFAULT_OFFLINE_MESSAGE` |
| `status=0` 禁用 | `null`（service 层处理） | `DEFAULT_OFFLINE_MESSAGE` |
| `config_value` 为空串 | `""` | `DEFAULT_OFFLINE_MESSAGE`（`isBlank()` 过滤） |
| 配置正常 | 具体字符串 | 具体字符串 |
| Redis 连接故障 | 捕获 → 降级查 MySQL | 正常值 |
| Redis 写缓存故障 | 捕获 → 不抛，本次请求仍返回 DB 值；下次请求继续直查 DB 并再试写缓存 | 正常值 |
| MySQL 故障 | 抛异常 | 不 catch，让异常冒泡至 500 |

### 8.2 `SysConfigService` Redis 兜底边界

- **兜底范围**：`getValue` 的读缓存 / 写缓存，`evictCache` 的删缓存。
- **不兜底**：MySQL 查询异常（`findByTypeAndKey` / `insert` / `update` / `deleteById`）—— 数据层故障直接冒泡；`@Transactional` 行为不变。
- **日志级别**：Redis 异常用 `log.warn(...)`，消息含 cacheKey 和异常 message（不含 stacktrace，避免 Redis 抖动时日志洪水）。

### 8.3 离线分支的副作用异常

沿用现有 `handleAgentOffline` (line 342-346) 的写法：`saveSystemMessage` 用 try/catch 吞日志；WS 广播异常由 emitter 自身处理。本次改造不修改这段逻辑。

### 8.4 `gatewayApiClient.getAgentByAk` 抛异常

现状：异常会向上冒泡至 Controller 500。本次不改 —— 把网络故障误判为"离线"会误导上游，让调用方 retry 或降级处理更合理。

### 8.5 并发：检查瞬间 agent 刚好离线/上线

不处理。在线检查是 best-effort，通过检查后瞬时离线由 Gateway 层拒收，属已有行为。

### 8.6 线程安全

- `AssistantOfflineMessageProvider`：无状态 Spring 单例。
- `SysConfigService`：Redis/MyBatis 自身线程安全；新增的 try/catch 不引入状态。

---

## 9 测试计划

### 9.1 新增 / 修改测试

| 文件 | 类型 | 内容 |
|------|------|------|
| `AssistantOfflineMessageProviderTest`（新增） | 单元 | ① 配置正常值返回该值；② `getValue` 返回 `null` → 返回 `DEFAULT_OFFLINE_MESSAGE`；③ `getValue` 返回空串 → 返回 `DEFAULT_OFFLINE_MESSAGE`；④ `getValue` 抛 `RuntimeException`（模拟 DB 故障） → 不 catch，异常冒泡（`assertThrows`） |
| `SysConfigServiceTest`（修改，**v2 新增**） | 单元 | ① 既有用例（缓存命中 / DB 查找 / status=0 / create / update / delete）保持绿；② **新增 4 个 case**：(a) `redisTemplate.opsForValue().get(...)` 抛 `RedisConnectionFailureException` → `getValue` 仍从 DB 查并返回值；(b) `redisTemplate.opsForValue().set(...)` 抛异常 → `getValue` 不抛，返回 DB 值；(c) `redisTemplate.delete(...)` 抛异常（走 `evictCache`） → `create` 不抛、DB 写入成功；(d) 同上场景下 `update` 不抛、DB 更新成功（`evictCache` 被 `create`/`update` 共用，两条路径都要覆盖） |
| `InboundProcessingServiceTest`（修改） | 单元 | ① 既有在线场景保持绿；② **新增 `chat` / `rebuild` 离线 case**：`gatewayApiClient.getAgentByAk(any()) == null` 时返回 `InboundResult.error(503, <provider mock>, sessionId, ...)`，且调用 `handleAgentOffline`；③ **新增 `question_reply` / `permission_reply` 离线 case**：session 存在 + agent 离线 → `error(503, ...)`；④ **新增 `question_reply` / `permission_reply` 优先级 case**：agent 离线 + session 不存在 → 仍返回 `error(404, "Session not found or not ready", ...)`（离线检查不抢在 404 前面）；⑤ **新增 1 个业务助手跳过 case**：`scopeStrategy.requiresOnlineCheck()=false` 时不查 agent、正常转发；⑥ 既有 `handleAgentOffline_ExternalWs_shouldRouteViaEmitter` 的 `.error(AGENT_OFFLINE_MESSAGE)` 断言改为 `.error(<provider mock 返回值>)` |
| `ExternalInboundControllerTest`（修改） | Controller | **新增 1 个 case**：mock `processingService.processChat` 返回 `InboundResult.error(503, "msg-x", "ext-sid", "123")`，断言 HTTP 200 + `code=503` + `errormsg="msg-x"` + `data.businessSessionId="ext-sid"` + `data.welinkSessionId="123"` |
| `SkillMessageControllerTest`（修改） | Controller | ① 构造注入 `AssistantOfflineMessageProvider`（mock）；② line 311 / 378 相关断言的文案从硬编码改为断言 `provider.get()` 的 mock 返回值；③ `replyPermission` 离线 `ApiResponse.error(503, ...)` 文案同改 |

### 9.2 回归验证点

- `handleAgentOffline_ExternalWs_shouldRouteViaEmitter` —— WS 广播路径不受影响。
- `processChatSessionReady` / `processQuestionReplyXxx` / `processPermissionReplyXxx` —— 默认 agent 在线，返回 `InboundResult.ok(...)`，行为不变。
- `SysConfigServiceTest` 既有 6 个用例 —— Redis 正常场景行为不变。

### 9.3 Lint / Typecheck

`mvn -pl skill-server compile test` 通过；新增 imports 符合既有命名和顺序。

### 9.4 手工验证步骤

1. 启动 skill-server，DB 无 `assistant_offline:message` 记录。
2. 制造 agent 离线（停掉对应 ai-gateway 客户端），调用 `POST /api/external/invoke` (`action=chat`)。
   - 断言响应：`{ "code": 503, "errormsg": "任务下发失败，请检查助理是否离线，确保助理在线后重试", "data": {...} }`。
3. 通过 `SysConfigController`（挂在 `/api/admin/configs`）录入：
   ```json
   POST /api/admin/configs
   { "configType": "assistant_offline", "configKey": "message", "configValue": "测试文案-xxx", "status": 1 }
   ```
   （若已存在该记录可用 `PUT /api/admin/configs/{id}`；`update` 会触发 `evictCache` 立即生效。）
4. 如果是 `POST` 新建（`create` 不清缓存），清 Redis key `ss:config:assistant_offline:message`（或等 30min TTL），再次调用 step 2。
   - 断言 `errormsg` 变为 `"测试文案-xxx"`。
5. 对 `rebuild` 重复 step 2 的离线断言（预期 503）。
6. 对 `question_reply` / `permission_reply`：
   - （a）先制造一个 session（先用 `chat` 建好），再制造 agent 离线，调用 q/p_reply → 断言 `code=503`。
   - （b）sessionId 填不存在的值，调用 q/p_reply → 断言 `code=404`（和 agent 是否离线无关，404 优先）。
7. 业务助手（后端返回 `scope=business` 的助手）离线场景 → 调用 `chat` → 断言响应 `code=0`（不走离线分支）。
8. **Redis 故障兜底**：重启/断开 Redis，调用 step 2 → 断言：仍返回 `code=503` + 正确文案（走 MySQL 直查）；日志里有 `Redis read failed ... falling back to DB` 的 warn。

---

## 10 回滚策略

- 全部改动集中在 4 个文件（新增 1、改 3），可 revert 单个 commit。
- 若需要保留在线检查扩展但回退文案配置化，只需把 `offlineMessageProvider.get()` 替换回两处常量字符串。
- 让 `sys_config` 的 `assistant_offline:message` 失效（回退到 `DEFAULT_OFFLINE_MESSAGE`）有两种并列方案：
  - **方案 A（推荐，立刻生效）**：通过 `SysConfigController` 调用 `update` 把该记录的 `status` 改为 `0`。`update` 会触发 `evictCache`，Redis key 立即被删；下次读取走 DB → 命中 `status=0` → 返回 null → provider 走 fallback。记录保留便于审计。
  - **方案 B（彻底删记录）**：直接 `DELETE FROM sys_config WHERE config_type='assistant_offline' AND config_key='message'`（或调用 `SysConfigController` 的 delete 接口）。**注意**：`SysConfigService.delete` 只删 DB 不清 Redis，最长 30min 内缓存仍返回旧值。**如需立刻生效**：手动 `DEL ss:config:assistant_offline:message` 这个 Redis key。

---

## 11 风险

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 上游旧调用方没处理 `code=503`，把任何非零都当成硬失败 | 中 | `code=0→503` 对 `chat` 是语义变化 | 在上线前通过 release note 通知消费方；`errormsg` 字段能让人类运营看明白；保持 HTTP 200 兼容对"只看 HTTP 层"的上游 |
| 运维录入的文案被用户可见，出现不雅/过长内容 | 低 | 体验问题 | `SysConfigController` 是内部接口；配置走 code review；`sys_config.config_value VARCHAR(512)` 天然限长 |
| `question_reply` / `permission_reply` 新增在线检查导致上游在 agent 离线时收到 503（不再静默失败） | 低 | 行为变化但合理 | 本次即为修复静默失败的 bug；在 release note 中明确新行为 |
| Redis 连接故障时 MySQL 被打穿（所有 `SysConfigService.getValue` 都 fallback 直查） | 低 | MySQL 负载瞬时上升 | 本项目 `SysConfigService` 主要读取路径只有两类（离线文案 + 云端请求策略），频次低；`findByTypeAndKey` 已有唯一索引 `uk_type_key` |
| `SysConfigService` 的 try/catch 吞掉真实的 Redis 配置错误（比如权限） | 低 | 故障诊断延迟 | `log.warn` 记录 cacheKey + 异常 message；运维监控应告警 warn 频次 |

---

## 12 实施范围清单

- [ ] 新增 `AssistantOfflineMessageProvider.java`
- [ ] 新增 `AssistantOfflineMessageProviderTest.java`
- [ ] 修改 `SysConfigService.java`：`getValue` 读/写缓存 try/catch；`evictCache` try/catch
- [ ] 更新 `SysConfigServiceTest.java`：新增 3 个 Redis 异常用例
- [ ] 修改 `InboundProcessingService.java`：删常量、注入 provider、抽 `checkAgentOnline`、按 action 分类插入检查（chat/rebuild 在 resolve 后、q/p_reply 在 findSession+404 后）、`handleAgentOffline` 文案来源替换
- [ ] 修改 `SkillMessageController.java`：删常量、注入 provider、三处替换
- [ ] 更新 `InboundProcessingServiceTest.java`：新增 4 个离线用例（含优先级 case）+ 1 个业务助手跳过用例 + 既有 handleAgentOffline 测试文案断言更新
- [ ] 更新 `ExternalInboundControllerTest.java`：新增 503 响应体断言用例
- [ ] 更新 `SkillMessageControllerTest.java`：注入 provider mock，文案断言切换
- [ ] `mvn -pl skill-server compile test` 绿
- [ ] 手工验证 step 1-8（含 Redis 故障兜底验证）

---

## 13 未覆盖 / 后续

- 不在本次范围：miniapp 前端针对 `code=503` 的 UI 处理（miniapp 仍走 WS `type=error`）。
- 不在本次范围：`POST /api/external/invoke` 之外的 external 渠道接口（如 WS 的 `ExternalStreamHandler`）—— 它们不走 `InboundProcessingService` 的 4 个 `process*` 方法。
- 未来若需要按 action / 业务域差异化文案，可在 `sys_config` 新增 `config_key`（如 `message:chat`, `message:permission_reply`），provider 加参数即可，无破坏性。
- 未来若 `SysConfigService` 的 Redis 兜底在其他场景需要不同行为（比如强一致要求的配置），可引入策略参数；当前"Redis 可选"对所有现有调用者（离线文案、云端请求策略）都适用。
