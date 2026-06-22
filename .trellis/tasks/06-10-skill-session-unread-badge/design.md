# Design (V2): 未读消息小红点提醒与已读多端同步

## 1 与方案1 的核心差异

| 维度 | 方案1 | 方案2 |
|------|-------|-------|
| 已读触发 | 聚焦时服务端取 MAX(seq) | 前端上报已渲染的最大 message_seq |
| 多端同步 | Redis pub/sub → WS | MultiDeviceSyncService 接口，按 env 注入 |
| 活跃会话追踪 | Redis Hash | 去掉，前端自行判断 |
| 免打扰判断 | 服务端 | 前端 |
| 流式保护 | 无 | 消息未完全渲染不推进已读游标 |

## 2 数据模型

### 2.1 MySQL

无 DDL 变更。`last_read_seq` 和 `max_seq` 均不入库，由 Redis Hash 全权维护。

### 2.2 Redis

**未读追踪 Hash**（唯一存储）:
```
Key: ss:unread:{userId}
Type: Hash
Field: sessionId → maxSeq (int)
TTL: 7d（每次 HSET/HDEL 时 EXPIRE 续期）
```

Hash 中存在某 sessionId 即表示该会话有未读。

**淘汰/过期自愈**：无需 DB 兜底，Lua 脚本自行处理 key 不存在：

| 操作 | key 不在时的行为 |
|------|-----------------|
| `updateMaxSeq` | 创建 key（EXPIRE 7d），HSET sessionId→newSeq，return 1 |
| `markRead` | 创建 key（EXPIRE 7d，空 Hash），return 1 |
| `POST /unread`（不传参） | `HLEN` 返回 0（无未读） |
| `POST /unread`（传参） | `HMGET` 均返回 nil |

**Lua 脚本：updateMaxSeq**（消息落库时，原子更新 + 自愈创建）:

```lua
-- KEYS[1] = ss:unread:{userId}
-- ARGV[1] = sessionId, ARGV[2] = newSeq, ARGV[3] = ttlSeconds
local field, newSeq, ttl = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])
local exists = redis.call('EXISTS', KEYS[1])

local current = redis.call('HGET', KEYS[1], field)
if not current then
    redis.call('HSET', KEYS[1], field, newSeq)
    if exists == 0 then redis.call('EXPIRE', KEYS[1], ttl) end
    return 1   -- 新增未读，需发布事件
end

if newSeq > tonumber(current) then
    redis.call('HSET', KEYS[1], field, newSeq)
    redis.call('EXPIRE', KEYS[1], ttl)  -- 续期
    return 2   -- 更新但已处未读态，无需重复发布
end

return 0       -- 无变更
```

**Lua 脚本：markRead**（前端上报已读时，原子判断 + 自愈创建）:

```lua
-- KEYS[1] = ss:unread:{userId}
-- ARGV[1] = sessionId, ARGV[2] = readSeq, ARGV[3] = ttlSeconds
local field, readSeq, ttl = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])
local exists = redis.call('EXISTS', KEYS[1])
local current = redis.call('HGET', KEYS[1], field)

if not current then
    if exists == 0 then redis.call('EXPIRE', KEYS[1], ttl) end  -- 创建空 key
    return 1   -- key 不存在/field 不存在 = 已读，需发布清除事件
end

if readSeq >= tonumber(current) then
    redis.call('HDEL', KEYS[1], field)
    redis.call('EXPIRE', KEYS[1], ttl)  -- 续期
    return 1   -- 全部已读，需发布清除事件
end

return 0       -- 仍有未读
```

### 2.3 POST /unread 查询设计

不传 `sessionIds`：
```
HLEN ss:unread:{userId}
```
→ 返回 `unreadSessionCount`。

传 `sessionIds`：
```
HLEN ss:unread:{userId}                          → unreadSessionCount
HMGET ss:unread:{userId} id1 id2 ...             → 各 session maxSeq
```
遍历 `HMGET` 结果：`maxSeq > 0 ? 1 : 0` → `unreadCount`。一次 `HLEN` + 一次 `HMGET`。

### 2.4 Listener 中判断

Lua 返回值决定是否推送（见 5.3）。推送 `unreadCount`（0 或 1）。

## 3 接口设计

### 3.1 前端 → 服务端：已读上报

**前端追踪**（`useSkillSession`）：

```
readMessageSeq 由前端自行维护（内存变量，不持久化）
消息渲染完成 (text_done / tool completed / error) → readMessageSeq = max(readMessageSeq, seq)
readMessageSeq 变化 → debounce 500ms → POST /api/skill/sessions/{id}/read
流式进行中不推进 readMessageSeq
```

**REST 接口**（唯一通道）：

```
POST /api/skill/sessions/{id}/read
```

| 属性名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| Cookie: userId | string | Y | 用户身份 |
| body.readSeq | int | Y | 前端已渲染的最大 message_seq |

**响应体**:

| 属性名 | 类型 | 说明 |
|--------|------|------|
| code | int | 0=成功 |
| data.welinkSessionId | String | 会话 ID |
| data.unreadCount | int | readSeq >= maxSeq 后为 0 |

```json
{ "code": 0, "data": { "welinkSessionId": "123", "unreadCount": 0 } }
```

**错误码**:（同前）

**核心处理**（`SkillSessionService.reportRead`，新增方法）：

```java
public void reportRead(Long sessionId, String userId, int readSeq) {
    accessControl.requireSessionAccess(sessionId, userId);
    // Lua markRead：原子比较 readSeq >= maxSeq → HDEL
    Long result = unreadRedisService.markRead(userId, sessionId.toString(), readSeq, TTL_SECONDS);
    if (result != null && result == 1) {
        applicationEventPublisher.publishEvent(new ReadReportedEvent(sessionId, userId));
    }
}
```

### 3.2 前端拉取：未读信息查询（单一接口，sessionIds 可选）

```
POST /api/skill/sessions/unread
```

**请求体**（`sessionIds` 可选）:

| 属性名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionIds | List\<String\> | N | 不传则仅返回总数；传入则同时返回各会话详情 |

**响应体 — 不传 sessionIds**（启动/前台/重连，全局红点）:

| 属性名 | 类型 | 说明 |
|--------|------|------|
| data.unreadSessionCount | int | 有未读的会话总数 |

```json
{ "code": 0, "data": { "unreadSessionCount": 2 } }
```

实现：`HLEN ss:unread:{userId}`。

**响应体 — 传 sessionIds**（侧边栏渲染）:

| 属性名 | 类型 | 说明 |
|--------|------|------|
| data.unreadSessionCount | int | 有未读的会话总数 |
| data.unreadSessionList[].sessionId | String | 会话 ID |
| data.unreadSessionList[].unreadCount | int | 0=已读，>0=有未读 |
| data.unreadSessionList[].maxSeq | int | 当前最大 seq |

```json
{
  "code": 0,
  "data": {
    "unreadSessionCount": 2,
    "unreadSessionList": [
      { "sessionId": "123", "unreadCount": 1, "maxSeq": 15 },
      { "sessionId": "456", "unreadCount": 0, "maxSeq": 0 }
    ]
  }
}
```

实现：`HLEN` + `HMGET`（一次网络往返取全部 maxSeq），再逐个 `maxSeq > 0 ? 1 : 0`。纯 Redis 操作。

### 3.3 服务端 → 前端：未读推送

```json
{
  "type": "session.unread",
  "welinkSessionId": "123456789",
  "unreadCount": 1,
  "maxSeq": 15,
  "assistantAccount": "assistant_xxx",
  "emittedAt": "2026-06-12T10:30:00"
}
```

| 属性名 | 类型 | 说明 |
|--------|------|------|
| type | string | 固定 `"session.unread"` |
| welinkSessionId | string | 会话 ID |
| unreadCount | int | 0=已读（清除角标），>0=有未读（当前为 1，预留计数扩展） |
| maxSeq | int | 当前会话最大 seq，前端可据此自行判断 |
| assistantAccount | string | 助手账号（nullable） |
| emittedAt | ISO-8601 | 推送时间 |

## 4 MultiDeviceSyncService

```java
public interface MultiDeviceSyncService {
    void pushSessionUnread(String userId, String sessionId, int unreadCount,
                           int maxSeq, String assistantAccount);
}
```

**复合模式**：注册所有实现，按配置 `unread.sync-mode` 路由，调用方无需感知模式：

```java
@Component
public class CompositeMultiDeviceSyncService implements MultiDeviceSyncService {
    private final Map<String, MultiDeviceSyncService> services;
    private final String syncMode;

    public CompositeMultiDeviceSyncService(
            @Qualifier("ws") MultiDeviceSyncService wsService,
            @Qualifier("im") MultiDeviceSyncService imService,
            @Value("${unread.sync-mode:ws}") String syncMode) {
        this.services = Map.of("ws", wsService, "im", imService);
        this.syncMode = syncMode;
    }

    @Override
    public void pushSessionUnread(...) {
        services.getOrDefault(syncMode, services.get("ws")).pushSessionUnread(...);
    }
}
```

### 4.1 Ws 实现

```
Redis pub/sub user-stream:{userId}
  → SkillStreamHandler.handleUserBroadcast
    → StreamMessageEmitter.emitToClient(session.unread { sessionId, unreadCount, maxSeq, assistantAccount })
```

### 4.2 Im 实现

```
POST /v1/app-notify
```

| 属性名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| client_notify_id | string | Y | UUID |
| notify_scope | int | Y | 固定 2 |
| notify_tenant | string | Y | 配置项 |
| notify_accounts | List\<String\> | N | 接收账号 |
| notify_module | string | Y | 配置项 |
| notify_data | string | Y | JSON，格式见下 |

`notify_data`：

```json
{
  "notify_type": "session.unread",
  "notify_content": {
    "welinkSessionId": "...",
    "unreadCount": 1,
    "maxSeq": 15,
    "assistantAccount": "..."
  }
}
```

配置：

```yaml
unread:
  sync-mode: ws  # ws | im，默认 ws
im:
  api-url: ${IM_API_URL}
  token: ${IM_TOKEN}
  app-notify:
    tenant: ${IM_APP_NOTIFY_TENANT}
    module: ${IM_APP_NOTIFY_MODULE}
    scope: 2
```

## 5 事件驱动架构

遵循开闭原则，现有代码仅发布事件，新增逻辑全部放在监听器中。

### 5.1 事件定义

```java
// 新增：tool_done 完成后发布（handleToolDone 仅发布事件，不关心广播决策）
public record ToolDoneEvent(Long sessionId, String userId, SkillSession session, String traceId) {}

// 新增：全部已读后发布（markRead return 1 时）
public record ReadReportedEvent(Long sessionId, String userId) {}
```

### 5.2 发布点（现有代码最小改动）

**`GatewayMessageRouter.handleToolDone`** 末尾追加一行：

```java
// 原有逻辑保持不变...
rebuildService.clearPendingMessages(sessionId);

// +++ 仅新增这一行（不关心是否广播，由 Listener 内部闭环判断）：
applicationEventPublisher.publishEvent(new ToolDoneEvent(numericId, userId, session, traceId));
```

**`SkillSessionService.reportRead`**（新增方法）末尾：Lua markRead（见 3.1），return 1 才发布 `ReadReportedEvent`。

### 5.3 新增监听器（零侵入现有代码）

```java
@Component
public class UnreadPushListener {

    private static final int TTL_SECONDS = 7 * 24 * 3600;

    // 广播决策在 Listener 内部闭环，handleToolDone 不关心
    @EventListener
    public void onToolDone(ToolDoneEvent event) {
        Long sessionId = event.sessionId();
        String userId = event.userId();

        // 1. 从 DB 读取当前 maxSeq（消息刚落库）
        int maxSeq = skillMessageRepository.findMaxSeqBySessionId(sessionId);

        // 2. Lua 原子更新 + 判断
        Long result = unreadRedisService.updateMaxSeq(userId, sessionId.toString(), maxSeq, TTL_SECONDS);
        if (result == null || result != 1) return;  // 2=已处未读态, 0=无变更

        // 3. return 1 = 新增未读 → 实时推送
        multiDeviceSyncService.pushSessionUnread(
            userId, sessionId.toString(), 1, maxSeq,
            event.session().getAssistantAccount());
    }
}

@Component
public class ReadReportedListener {
    @EventListener
    public void onReadReported(ReadReportedEvent event) {
        multiDeviceSyncService.pushSessionUnread(
            event.userId(), event.sessionId().toString(), 0, 0, null);
    }
}
```

**广播决策闭环**：

| 位置 | 做什么 | 不做什么 |
|------|--------|---------|
| `handleToolDone` | 发布 ToolDoneEvent（+1 行） | 不调 Lua，不判断 |
| `UnreadPushListener` | Lua updateMaxSeq → 决定推不推 | — |
| `reportRead` | Lua markRead → 决定发不发事件 | — |

**发布决策矩阵**：

| Lua return | 发布事件? | 说明 |
|------------|-----------|------|
| updateMaxSeq=1 | 发布 ToolDoneEvent | 新增未读，UnreadPushListener 实时推送 |
| updateMaxSeq=2 | 不发布 | 已处未读态，无需重复推送 |
| updateMaxSeq=0 | 不发布 | 无变更 |
| markRead=1 | 发布 ReadReportedEvent | 全部已读，立即推送清除 |
| markRead=0 | 不发布 | 仍有未读 |


## 6 前端

### 6.1 已读上报逻辑

```
每个会话维护 readMessageSeq = max(已渲染完成的消息 seq)
当 readMessageSeq 变化 → debounce 500ms → POST /api/skill/sessions/{id}/read
流式进行中：仅消息完整渲染后更新 readMessageSeq（text_done / tool completed / error）
切换会话：不额外上报，流式未完成不推进 readMessageSeq
```

### 6.2 未读信息拉取

```
进入应用 / 回到前台 / WS 重连
  → POST /api/skill/sessions/unread（不传 sessionIds）
  → unreadSessionCount > 0 → 显示全局红点

侧边栏渲染
  → POST /api/skill/sessions/unread { sessionIds: [...] }
  → 各会话角标更新
  → 后续增量由 session.unread 推送覆盖
```

### 6.3 角标显示

```
收到 session.unread → 更新对应 session unreadCount
若当前正查看该会话 → 不显示角标（前端自行判断）
角标 > 99 → 显示 99+
```

### 6.3 改动清单

| 文件 | 改动 |
|------|------|
| `protocol/types.ts` | `SessionUnread { sessionId, unreadCount, maxSeq }`；`StreamMessageType` 新增 `session.unread` |
| `utils/api.ts` | `fetchUnreadSessions(sessionIds?)` + `reportRead(sessionId, readSeq)` |
| `hooks/useSkillSession.ts` | 维护 `readMessageSeq`，变化时 debounce 上报 REST；启动/前台/重连时调 `unread`（不传参）；侧边栏渲染时调 `unread(sessionIds)` |
| `hooks/useSkillStream.ts` | 处理 `session.unread`；流式状态追踪 |
| `components/SessionSidebar.tsx` | 数字角标渲染 |
| `index.css` | `.session-badge` 样式 |

## 7 skill-server 改动清单

| 层 | 文件 | 改动 | 侵入程度 |
|----|------|------|----------|
| Event | `ToolDoneEvent.java` | 新增 record | 新文件 |
| Event | `ReadReportedEvent.java` | 新增 record | 新文件 |
| Listener | `UnreadPushListener.java` | 新增，监听 ToolDoneEvent，实时推送 | 新文件 |
| Listener | `ReadReportedListener.java` | 新增，监听 ReadReportedEvent，立即推送 | 新文件 |
| Redis | `UnreadRedisService.java` | 封装 Lua `updateMaxSeq` + `markRead`（含 EXPIRE 7d + 自愈创建），Hash 操作 | 新文件 |
| Service | `MultiDeviceSyncService.java` | 接口 | 新文件 |
| Service | `CompositeMultiDeviceSyncService.java` | 复合实现，按 `unread.sync-mode` 路由 | 新文件 |
| Service | `WsMultiDeviceSyncService.java` | ws 实现 | 新文件 |
| Service | `ImMultiDeviceSyncService.java` | im 实现 | 新文件 |
| Service | `SkillSessionService.java` | `reportRead()`（Lua markRead）+ `getUnreadSessions()`（纯 Hash） | 新方法 |
| Controller | `SkillSessionController.java` | `POST /unread`（sessionIds 可选）+ `POST /{id}/read` | 新端点 |
| Model | `StreamMessage.java` | `SESSION_UNREAD` + `sessionUnread(unreadCount, maxSeq, assistantAccount)` | 最小 |
| WS | `SkillStreamHandler.java` | 无改动（已读上报统一走 REST） | 无 |
| Service | `GatewayMessageRouter.java` | `handleToolDone` 末尾发布 ToolDoneEvent（1 行） | **极轻** |
| Config | IM AppNotify 配置 | `@ConfigurationProperties` | 新文件 |

## 8 边缘情况

| 场景 | 处理 |
|------|------|
| Hash key 过期/淘汰（7d TTL） | Lua 自愈：`updateMaxSeq` 创建 key+HSET return 1；`markRead` 创建空 key return 1；查询侧 HLEN=0/HEXISTS=false |
| Redis 完全不可用 | Lua 执行失败，记录日志；`POST /unread` 返回 unreadSessionCount=0；前端进入降级模式 |
| 前端 debounce 窗口内多次上报 | 最后一次覆盖 |
| 角标显示 | 二态：有未读显示红点，无未读不显示 |
| IM API 调用失败 | 日志告警，不影响本地推送 |
| sync-mode 切换 | 重启生效，复合 service 按配置路由 |
| 离线后进入应用 | `POST /unread` 拉取（不传参→总数，传参→详情） |
| Lua updateMaxSeq=2（已处未读态） | 不发布 ToolDoneEvent，避免重复推送 |
| Lua markRead=0（readSeq < maxSeq） | 不发布 ReadReportedEvent，角标保持 |
| Lua markRead=1（readSeq >= maxSeq） | HDEL + 发布 ReadReportedEvent(unreadCount=0) |
| 硬删除会话 | Hash 残留 field 无影响 |

## 9 验证

1. **单设备角标**：消息到达 → 非活跃会话出现角标 → 前端渲染完成后上报已读 → 角标消失
2. **多端同步**：设备 A 上报已读 → 设备 B 角标同步消失
3. **流式保护**：流式进行中切换会话 → 前端 readMessageSeq 不推进
4. **IM API 调用**：im 模式 → `/v1/app-notify` 正确调用
5. **WS 广播**：ws 模式 → WS 广播正常
