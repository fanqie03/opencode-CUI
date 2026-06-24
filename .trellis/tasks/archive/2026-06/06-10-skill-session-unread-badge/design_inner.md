# Design (Inner): 未读消息小红点提醒与已读多端同步

## 1 需求价值和概述

skill-miniapp 会话列表侧边栏中展示未读消息红点，前端追踪已渲染的最大 `message_seq` 并上报已读，已读状态通过 IM API `/v1/app-notify` 跨设备实时同步。

**同步模式**：`skill.sync.mode=im`，多端同步走 IM API 通道。复用现有 `MultiDeviceSyncService` 通用基础设施（`06-15-multi-device-sync` 已构建），通过 `SyncRequest.syncMode` 路由，无需条件注入。

## 2 上下文分析

### 2.1 现有架构

- `skill_message.seq`：会话内严格递增序号（`uk_skill_message_session_seq`），天然适合做已读游标
- `ImOutboundService`：已有 `RestTemplate` 调 IM REST API 的基础设施
- `StreamMessageEmitter`：统一出站入口
- `ApplicationEventPublisher`：Spring 事件发布机制，可零侵入扩展现有逻辑
- 前端 `SessionSidebar` 已有渲染结构，扩展角标只需加 `unreadCount` 展示

### 2.2 需新增的能力

- `POST /unread`（sessionIds 可选）+ `POST /{id}/read` 端点（正式接口规格见 6.5.1）
- Lua `updateMaxSeq` + `markRead` 脚本（Redis Hash `ss:unread:{userId}:{assistantAccount}`，TTL 由配置注入）
- `ToolDoneEvent` / `ReadReportedEvent` 事件 + `UnreadManageListener`（统一监听三种事件）
- 前端 `useReadTracking` + `useUnreadBadge` 拆分

> **复用现有基础设施**：`MultiDeviceSyncService` 接口 + `CompositeMultiDeviceSyncService` + `ImMultiDeviceSyncService` + `WsMultiDeviceSyncService` + `SyncType.SESSION_UNREAD` 均由 `06-15-multi-device-sync` 构建，未读场景只需构建 `SyncRequest` 并调用 `push()`。

## 3 初始需求分析

### 3.1 初始化需求场景分析

| 场景 | 说明 |
|------|------|
| 用户不在看会话 S，S 收到新消息 | S 出现红点（所有设备） |
| 用户正在看会话 Y，Y 收到新消息 | 前端自行判断不显示角标（服务端照推） |
| 用户切换到会话 S | 前端渲染完成后上报 readSeq，角标消失 |
| 设备 A 上报已读 | 通过 IM API 广播，设备 B 角标同步消失 |
| 离线后打开应用 | `POST /unread` 拉取未读，前端 readMessageSeq 自行维护 |
| 流式进行中消息未渲染完成 | 不推进 readMessageSeq |
| 回到前台 / WS 重连 | 重新拉取 `POST /unread` |

### 3.2 结构化 IR

- **用户可见**：会话列表红点（二态：有未读显示红点，无未读不显示）
- **系统行为**：事件驱动推送、Lua 原子操作、IM API 多端同步
- **约束**：仅 miniapp 场景、im 模式走 IM API、不改动现有会话列表接口

## 4 需求影响分析

### 4.1 特性影响分析

| 模块 | 影响类型 | 说明 |
|------|----------|------|
| skill-server | 修改 + 新增 | Redis Lua、Controller、Event、Listener、IM API client（无 DB 变更、无 WS 改动） |
| skill-miniapp | 修改 | 类型、API、SessionSidebar、useReadTracking、useUnreadBadge、useSkillStream |
| ai-gateway | 无改动 | — |

## 5 系统用例分析

### 5.1 用例清单

| 编号 | 用例名称 | 说明 |
|------|----------|------|
| UC-01 | 未读角标展示 | 消息到达后，非活跃会话出现红点 |
| UC-02 | 端侧已读上报 | 前端渲染完成后 POST 上报 readSeq，Lua markRead 原子判断+清除 |
| UC-03 | 离线后恢复未读状态 | `POST /unread` 返回 unreadSessionCount + 详情列表 |
| UC-04 | 多端同步（inner） | IM API `/v1/app-notify` 广播未读变更 |

### 5.2 UC-01 未读角标展示

#### 5.2.1 用例概述

用户不在看会话 S，S 收到新消息后，所有设备上 S 出现红点。

#### 5.2.2 用例流程

```mermaid
sequenceDiagram
    participant GW as Gateway
    participant SS as skill-server
    participant Redis as Redis
    participant DB as MySQL
    participant IM as IM API
    participant FE as 所有设备

    GW-->>SS: tool_done (sessionId=S)
    SS->>SS: publishEvent(ToolDoneEvent)（仅发布，不关心广播）
    SS->>SS: UnreadManageListener → domain 白名单校验 → DB 取 maxSeq → Lua updateMaxSeq
    Note over SS: return 1 → 实时推送
    SS->>SS: build SyncRequest(IM, SESSION_UNREAD, {maxSeq}, userId)
    SS->>SS: CompositeMultiDeviceSyncService → ImMultiDeviceSyncService [@Retryable]
    SS->>IM: POST /v1/app-notify (AppNotifyRequest)
    IM-->>FE: 广播 notify_data
    FE->>FE: 单调校验通过 → 会话S 显示红点
```

#### 5.2.3 影响的功能列表和需求分析

| 影响功能 | 说明 |
|----------|------|
| GatewayMessageRouter.handleToolDone | 末尾发布 ToolDoneEvent（+1 行） |
| UnreadManageListener.onToolDone | domain 白名单校验 → Lua updateMaxSeq → return 1 则推送 |
| ImMultiDeviceSyncService | 已存在，`@Retryable` 调用 IM API |
| 前端 SessionSidebar | 渲染红点 |

### 5.3 UC-02 端侧已读上报

#### 5.3.1 用例概述

前端消息完整渲染后，`readMessageSeq` 变化，debounce 500ms 后 POST 上报，服务端 Lua markRead 判断并清除。

#### 5.3.2 用例流程

```mermaid
sequenceDiagram
    participant FE as 设备A
    participant SS as skill-server
    participant DB as MySQL
    participant Redis as Redis
    participant IM as IM API
    participant FE2 as 设备B

    FE->>FE: text_done 渲染完成，readMessageSeq=15
    FE->>SS: POST /api/skill/sessions/S/read { readSeq: 15 }
    SS->>Redis: Lua markRead → readSeq>=maxSeq → HDEL → return 1
    SS->>SS: publishEvent(ReadReportedEvent(readSeq=15))
    SS->>SS: UnreadManageListener.onReadReported → build SyncRequest(IM, SESSION_READ, {readSeq:15, maxSeq:15}, userId)
    SS->>SS: CompositeMultiDeviceSyncService → ImMultiDeviceSyncService [@Retryable]
    SS->>IM: POST /v1/app-notify (AppNotifyRequest)
    IM-->>FE: 广播
    IM-->>FE2: 广播
    FE->>FE: maxSeq 15 >= 15 → 红点消失
    FE2->>FE2: maxSeq 15 >= 15 → 红点消失
```

#### 5.3.3 影响的功能列表和需求分析

| 影响功能 | 说明 |
|----------|------|
| 前端 useReadTracking | 维护 readMessageSeq，debounce 500ms → POST /{id}/read |
| SkillSessionController | 新增 `POST /{id}/read`（已读上报唯一通道） |
| SkillSessionService.reportRead | domain 白名单校验 → Lua markRead → return 1 则发布 ReadReportedEvent(readSeq) |
| UnreadManageListener.onReadReported | 推送 `session.read`，maxSeq=readSeq |

### 5.4 UC-03 离线后恢复已读状态

#### 5.4.1 用例概述

用户离线后重新进入应用，通过 `POST /unread`（传 assistantAccount）获取全部未读会话列表恢复未读状态。

#### 5.4.2 用例流程

```mermaid
sequenceDiagram
    participant FE as 前端
    participant SS as skill-server
    participant DB as MySQL

    FE->>SS: POST /api/skill/sessions/unread { assistantAccount }
    SS->>Redis: HGETALL ss:unread:{userId}:{assistantAccount}
    Redis-->>SS: S→maxSeq=10
    SS-->>FE: { unreadSessionCount: 1, unreadSessionList: [ { sessionId: "S", unreadCount: 1, maxSeq: 10 } ] }
    FE->>FE: 显示角标
```

### 5.5 UC-04 多端同步（inner）

#### 5.5.1 用例概述

im 模式下，多端同步通过 IM API `/v1/app-notify` 完成。

#### 5.5.2 用例流程

```
Listener 构建 SyncRequest(syncMode=IM, syncType=SESSION_UNREAD, syncContent={welinkSessionId, maxSeq, assistantAccount}, targetAccount=userId)
  → multiDeviceSyncService.push(request)
    → CompositeMultiDeviceSyncService 按 syncMode 路由至 ImMultiDeviceSyncService（已存在）
      → POST /v1/app-notify (AppNotifyRequest 类型化)
        → IM 平台广播 → 各设备前端收到 → 更新角标
```

## 6 功能设计

### 6.1 业界实现方案分析

会话级已读游标 + 事件驱动推送是业界常规方案（Slack、Discord、微信均采用会话级 last_read_id 模式）。miniapp 场景无需消息级已读追踪，会话级足够。

### 6.2 功能实现整体设计方案

```
┌──────────────────────────────────────────────────────────────┐
│                    事件驱动架构                                │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  handleToolDone ──→ ToolDoneEvent ──→ UnreadManageListener   │
│       (+1 行)                      │ domain 白名单校验         │
│                                    │ DB maxSeq → Lua          │
│                                    │ updateMaxSeq → 决定推送   │
│                                    ↓ (return 1)              │
│                              build SyncRequest               │
│                                → CompositeMultiDeviceSyncSvc  │
│                                  → ImMultiDeviceSyncSvc(已存在)│
│                                    └── POST /v1/app-notify   │
│                                       [@Retryable]           │
│                                                              │
│  reportRead ──→ Lua markRead ──→ return 1?                   │
│  (新方法)          │  return 0 → 不发布                       │
│                    ↓ return 1                                 │
│              ReadReportedEvent(readSeq) ──→ Listener          │
│                                    ↓ build SyncRequest        │
│                              push(SESSION_READ, maxSeq=readSeq)│
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 6.3 已读游标功能实现

#### 6.3.1 实现思路

前端自行追踪 `readMessageSeq`（内存变量），服务端不存储已读游标。已读判断完全由 Lua `markRead`（readSeq vs Hash maxSeq）原子完成。

#### 6.3.2 实现设计

- **游标来源**：前端 `readMessageSeq = max(已渲染完成的消息 seq)`
- **上报时机**：`readMessageSeq` 变化 → debounce 500ms → POST /api/skill/sessions/{id}/read
- **流式保护**：消息未完整渲染不推进
- **已读判断**：Lua `markRead` 原子比较 readSeq vs Hash maxSeq：readSeq == maxSeq → HDEL 发布清除；readSeq > maxSeq → 返回 -1 抛 400；readSeq < maxSeq → 不处理
- **未读追踪**：Redis Hash `ss:unread:{userId}:{assistantAccount}` 通过 Lua `updateMaxSeq`/`markRead` 原子维护，TTL 由配置注入（默认 7d），仅 `updateMaxSeq` 维护 TTL
- **同步路由**：`CompositeMultiDeviceSyncService`（已存在）通过 `getSyncMode()` 自注册所有实现，按 `SyncRequest.syncMode`（来自 `unreadProperties.syncMode`）路由到具体实现；未匹配时回退到 `MultiSyncProperties.mode` 默认模式
- **推送调用**：构建 `SyncRequest(unreadProperties.syncMode, SyncType.SESSION_UNREAD, syncContent Map, userId)` → `multiDeviceSyncService.push(request)`
- **推送决策**：`handleToolDone`/`handleToolError` 发布 `ToolDoneEvent`/`ToolErrorEvent`；`UnreadManageListener.onToolDone/onToolError` 内部 domain 白名单校验 + Lua `updateMaxSeq=1` 才推送；`reportRead` 内部 Lua `markRead=1` 才发布 `ReadReportedEvent(readSeq)`；`markRead=-1` 时抛 400 参数异常

#### 6.3.3 功能可靠性分析

| 风险 | 缓解 |
|------|------|
| Redis 完全不可用/flush | Hash 为空 → 新消息到达后 Lua 自愈重建 |
| REST 调用失败 | 前端重试 / 下次 debounce 自然重试 |
| IM API 调用失败 | `@Retryable` 自动重试（可配置），全部失败 `@Recover` 记 WARN 日志，前端 `POST /unread` 拉取兜底 |
| 前端 debounce 窗口内多次变化 | 最后一次覆盖 |
| 无效已读清除 | `markRead=0`（readSeq < maxSeq）不发布事件 |

#### 6.3.4 功能安全分析

| 安全点 | 措施 |
|--------|------|
| 越权修改已读游标 | `requireSessionAccess` 校验 cookie userId == session.userId |
| Redis 注入 | sessionId 为服务端 Long 值，无注入风险 |
| 未读信息泄漏 | 查询按 userId 隔离 |

### 6.4 架构元素影响列表

| 架构元素 | 影响 |
|----------|------|
| 数据流 | 新增 3 条：消息 → 推送 / 上报 → 同步 / 拉取 → 角标 |
| 接口 | 新增 `POST /unread`（sessionIds 可选）+ `POST /{id}/read` |
| 事件 | 新增 `ToolDoneEvent` + `ReadReportedEvent` |
| 外部依赖 | `ImMultiDeviceSyncService`（已存在）调用 IM API `/v1/app-notify` |
| 数据存储 | Redis Hash `ss:unread:{userId}:{assistantAccount}`（无 DB 变更） |

### 6.5 skill-server 架构元素实现设计

#### 6.5.1 接口设计

##### 6.5.1.1 REST API

###### 6.5.1.1.1 未读信息查询（sessionIds 可选）

```
POST /api/skill/sessions/unread
```

**请求体**（`assistantAccount` 必填，`sessionIds` 可选）:

| 属性名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| assistantAccount | String | Y | 助手账号，用于构造 Redis key |
| sessionIds | List\<String\> | N | 不传则返回所有未读会话详情；传入则仅返回指定会话详情 |

请求示例：
```http
POST /api/skill/sessions/unread
Content-Type: application/json

{ "assistantAccount": "assistant_xxx" }

{ "assistantAccount": "assistant_xxx", "sessionIds": ["123", "456"] }
```

**响应体**（统一格式，不传 sessionIds 时返回全部有未读的会话）:

| 属性名 | 类型 | 说明 |
|--------|------|------|
| data.unreadSessionCount | int | 有未读的会话总数 |
| data.unreadSessionList[].sessionId | String | 会话 ID |
| data.unreadSessionList[].maxSeq | int | 当前最大 seq |

```json
{
  "code": 0,
  "data": {
    "unreadSessionCount": 2,
    "unreadSessionList": [
      { "sessionId": "123", "maxSeq": 15 },
      { "sessionId": "789", "maxSeq": 8 }
    ]
  }
}
```

实现：
- 不传 `sessionIds`：`HGETALL ss:unread:{userId}:{assistantAccount}` 返回所有 field-value 对
- 传 `sessionIds`：`HMGET ss:unread:{userId}:{assistantAccount} id1 id2...` 返回指定 field
Hash 中存在该 sessionId 即表示有未读。

###### 6.5.1.1.2 已读上报（唯一通道）

```
POST /api/skill/sessions/{id}/read
```

**请求体**:

| 属性名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| readSeq | int | Y | 前端已渲染的最大 message_seq |

**响应体**:

| 属性名 | 类型 | 说明 |
|--------|------|------|
| code | int | 0=成功 |
| data.welinkSessionId | String | 会话 ID |
| data.unreadCount | int | readSeq >= maxSeq 后为 0 |

```json
{ "code": 0, "data": { "welinkSessionId": "123", "unreadCount": 0 } }
```

**错误码**:

| HTTP Status | error.error_code | 说明 |
|-------------|------------------|------|
| 400 | BAD_REQUEST | readSeq 参数缺失或无效 |
| 401 | UNAUTHORIZED | 未登录或 cookie 失效 |
| 403 | FORBIDDEN | session 不属于当前用户 |
| 404 | NOT_FOUND | sessionId 不存在 |
| 500 | INTERNAL_ERROR | 服务内部异常 |

##### 6.5.1.2 WebSocket 协议

**客户端 → 服务端**：已读上报统一走 REST（见 6.5.1.1.2），不走 WS

**服务端 → 客户端：未读推送**:

未读推送示例：
```json
{
  "type": "session.unread",
  "welinkSessionId": "123456789",
  "maxSeq": 15,
  "readSeq": 0,
  "assistantAccount": "assistant_xxx",
  "emittedAt": "2026-06-12T10:30:00"
}
```

已读清除推送示例：
```json
{
  "type": "session.read",
  "welinkSessionId": "123456789",
  "maxSeq": 15,
  "readSeq": 15,
  "assistantAccount": "assistant_xxx",
  "emittedAt": "2026-06-12T10:30:05"
}
```

| 属性名 | 类型 | 说明 |
|--------|------|------|
| type | string | `"session.unread"`=有未读显示红点，`"session.read"`=已读清除红点 |
| welinkSessionId | string | 会话 ID |
| maxSeq | int | 当前会话最大 seq（unread）或 readSeq（read），前端单调校验用 |
| readSeq | int | 前端上报的已读游标（仅 `session.read` 有意义） |
| assistantAccount | string | 助手账号（nullable） |
| emittedAt | ISO-8601 | 推送时间 |

##### 6.5.1.3 IM AppNotify 接口（im 模式）

```
POST /v1/app-notify
```

**请求体**:

| 属性名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| client_notify_id | string | Y | 通知唯一标识（UUID） |
| notify_scope | int | Y | 接收者模式，固定 2 |
| notify_tenant | string | Y | 企业标识，配置项 |
| notify_accounts | List\<String\> | N | 接收通知的账号 |
| notify_module | string | Y | 归属应用模块，配置项 |
| notify_data | string | Y | JSON 字符串 |

`notify_data` 结构：

```json
{
  "notify_type": "session.unread",
  "notify_content": {
    "welinkSessionId": "...",
    "maxSeq": 15,
    "assistantAccount": "..."
  }
}
```

**响应体**:

| 属性名 | 类型 | 说明 |
|--------|------|------|
| error | ErrorInfo | 错误信息 |
| client_notify_id | string | 请求携带的 ID |
| server_notify_id | long | 服务端分配的 ID |
| Invalid_account | List\<String\> | 无效账号列表 |

#### 6.5.2 数据模型设计

##### 6.5.2.1 关系型数据库设计

无 DDL 变更。`last_read_seq` 和 `max_seq` 均不入库，由 Redis Hash `ss:unread:{userId}:{assistantAccount}` 全权维护（见 6.5.2.2）。

##### 6.5.2.2 Redis 缓存设计

**未读追踪 Hash**（唯一存储）：

| 属性 | 值 |
|------|-----|
| Key | `ss:unread:{userId}:{assistantAccount}` |
| Type | Hash |
| Field | sessionId → maxSeq (int) |
| TTL | `skill.unread.hash-ttl-seconds`（默认 604800 = 7d），**仅 `updateMaxSeq` 维护** |

Hash 中存在某 sessionId 即表示该会话有未读。

> **淘汰/过期自愈**：`updateMaxSeq` key 不存在→创建+HSET+EXPIRE return 1；`markRead` field 不存在→直接 return 1，**不维护 TTL**。

**Lua 脚本：updateMaxSeq**（消息落库时调用，TTL 由配置 `UnreadProperties.hashTtlSeconds` 注入）:

```lua
-- KEYS[1]=ss:unread:{userId}:{assistantAccount}, ARGV[1]=sessionId, ARGV[2]=newSeq, ARGV[3]=ttlSeconds
-- 返回: 1=需多端同步, 0=不处理
local field, newSeq, ttl = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])
local exists = redis.call('EXISTS', KEYS[1])
local current = redis.call('HGET', KEYS[1], field)
if not current then
    redis.call('HSET', KEYS[1], field, newSeq)
    if exists == 0 then redis.call('EXPIRE', KEYS[1], ttl) end
    return 1
end
if newSeq > tonumber(current) then
    redis.call('HSET', KEYS[1], field, newSeq)
    redis.call('EXPIRE', KEYS[1], ttl)
    return 1
end
return 0
```

**Lua 脚本：markRead**（前端上报已读时调用，**不维护 TTL**）:

```lua
-- KEYS[1]=ss:unread:{userId}:{assistantAccount}, ARGV[1]=sessionId, ARGV[2]=readSeq
-- 返回: 1=已清除(需同步), 0=仍部分未读, -1=readSeq>maxSeq(参数非法)
local field, readSeq = ARGV[1], tonumber(ARGV[2])
local current = redis.call('HGET', KEYS[1], field)
if not current then
    return 1
end
if readSeq == tonumber(current) then
    redis.call('HDEL', KEYS[1], field)
    return 1
end
if readSeq > tonumber(current) then return -1 end
return 0
```

**updateMaxSeq 执行流程**:

```mermaid
flowchart TD
    A["Lua updateMaxSeq 入参<br/>KEYS[1]=ss:unread:{userId}:{assistantAccount}<br/>ARGV[1]=sessionId<br/>ARGV[2]=newSeq<br/>ARGV[3]=ttlSeconds"] --> B{"HGET KEYS[1] sessionId<br/>current = ?"}
    B -->|current = nil<br/>field 不存在| C["HSET KEYS[1] sessionId newSeq"]
    C --> D{"EXISTS KEYS[1] ?"}
    D -->|key 不存在| E["EXPIRE KEYS[1] ttl<br/>设置 Hash TTL（配置注入）"]
    D -->|key 已存在| F["直接返回"]
    E --> F
    F --> G["return 1<br/>✅ 新增未读<br/>→ 多端同步"]
    B -->|current 存在| H{"newSeq > current ?"}
    H -->|yes| I["HSET KEYS[1] sessionId newSeq<br/>EXPIRE KEYS[1] ttl 续期"]
    I --> J["return 1<br/>✅ 有更新<br/>→ 多端同步"]
    H -->|no| K["return 0<br/>❌ 无变更<br/>→ 不处理"]
```

**markRead 执行流程**（不维护 TTL）:

```mermaid
flowchart TD
    M["Lua markRead 入参<br/>KEYS[1]=ss:unread:{userId}:{assistantAccount}<br/>ARGV[1]=sessionId<br/>ARGV[2]=readSeq"] --> N{"HGET KEYS[1] sessionId<br/>current = ?"}
    N -->|current = nil<br/>field 不存在| O["return 1<br/>✅ 无记录=已读<br/>→ 多端同步清除"]
    N -->|current 存在| P{"readSeq >= current ?"}
    P -->|yes| Q["HDEL KEYS[1] sessionId"]
    Q --> R["return 1<br/>✅ 全部已读，已 HDEL<br/>→ 多端同步清除"]
    P -->|no| S["return 0<br/>❌ readSeq < maxSeq<br/>仍有未读 → 不处理"]
```

**Lua 返回决策矩阵**：

| Lua return | 行为 | 推送内容 |
|------------|------|----------|
| updateMaxSeq=1 | 推送 `session.unread` | `maxSeq=<newSeq>` |
| updateMaxSeq=0 | 不处理 | — |
| markRead=-1 | 抛 ProtocolException(400)，参数非法 | — |
| markRead=1 | 发布 ReadReportedEvent → 推送 `session.read` | `readSeq=<readSeq>, maxSeq=<readSeq>` |
| markRead=0 | 不处理 | — |

**两脚本协同流程**:

```mermaid
sequenceDiagram
    participant GW as Gateway
    participant MQ as MessageRouter
    participant L1 as UnreadManageListener
    participant Redis as Redis (Lua)
    participant SYNC as MultiDeviceSyncService
    participant FE as 前端

    Note over GW,FE: === 消息到达 → 红点出现 ===
    GW->>MQ: handleToolDone(sessionId=S)
    MQ->>MQ: 原有逻辑...
    MQ->>MQ: publishEvent(ToolDoneEvent)
    MQ->>L1: @EventListener onToolDone
    L1->>L1: domain 白名单校验 + DB 取 maxSeq
    L1->>Redis: EVAL updateMaxSeq(sessionId=S, newSeq=15)
    Redis->>Redis: field 不存在 → HSET + EXPIRE
    Redis-->>L1: return 1
    L1->>SYNC: push(SESSION_UNREAD, maxSeq=15)
    SYNC->>FE: WS/IM 广播 session.unread
    FE->>FE: 单调校验通过 → 会话S 显示红点

    Note over GW,FE: === 前端上报已读 → 红点消失 ===
    FE->>MQ: POST /api/skill/sessions/S/read { readSeq: 15 }
    MQ->>Redis: EVAL markRead(sessionId=S, readSeq=15)
    Redis->>Redis: readSeq(15) >= current(15) → HDEL
    Redis-->>MQ: return 1
    MQ->>MQ: publishEvent(ReadReportedEvent(readSeq=15))
    MQ->>L1: @EventListener onReadReported
    L1->>SYNC: push(SESSION_READ, readSeq=15, maxSeq=15)
    SYNC->>FE: WS/IM 广播 session.read(readSeq=15, maxSeq=15)
    FE->>FE: maxSeq 15 >= 15 → 红点消失

    Note over GW,FE: === readSeq 不够 → 红点保持 ===
    FE->>MQ: POST /api/skill/sessions/S/read { readSeq: 12 }
    MQ->>Redis: EVAL markRead(sessionId=S, readSeq=12)
    Redis->>Redis: readSeq(12) < current(16) → 不操作
    Redis-->>MQ: return 0
    MQ->>MQ: 不发布事件, 红点保持
```

##### 6.5.2.3 配置项设计

```yaml
skill:
  sync:
    mode: im  # im | ws（SyncProperties 已存在）
    im:
      app-notify:
        tenant: ${IM_APP_NOTIFY_TENANT}
        module: ${IM_APP_NOTIFY_MODULE}
        scope: 2
      retry:
        max-attempts: 5             # IM 调用失败重试次数
        delay-ms: 1000              # 初始延迟 ms
        multiplier: 2               # 退避乘数（1s→2s→4s→8s→16s）
  unread:
    session-domain-whitelist: miniapp  # 逗号分隔，仅白名单 domain 触发未读逻辑
    hash-ttl-seconds: 604800           # Hash TTL，默认 7d
    async:
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 100
```

### 6.6 skill-miniapp 架构元素实现设计

| 文件 | 改动 |
|------|------|
| `protocol/types.ts` | `UnreadInfo { sessionId, unreadCount, maxSeq }`；`StreamMessageType` 新增 `session.unread` |
| `utils/api.ts` | `fetchUnreadSessions(assistantAccount, sessionIds?)` + `reportRead(sessionId, readSeq)` |
| `hooks/useReadTracking.ts` | **新文件**：`readMessageSeq` 追踪、debounce 500ms → REST 上报、流式保护 |
| `hooks/useUnreadBadge.ts` | **新文件**：启动/前台/重连拉取 `POST /unread`、侧边栏渲染拉详情、`session.unread` 推送处理 + 单调校验 |
| `hooks/useSkillStream.ts` | 流式状态追踪（供 useReadTracking 判断） |
| `components/SessionSidebar.tsx` | 红点（二态：有未读显示红点，无未读不显示） |
| `index.css` | `.session-badge` 样式 |

## 7 系统级非功能性设计

### 7.1 系统级的 FMEA 影响分析

| 故障模式 | 影响 | 检测 | 缓解 |
|----------|------|------|------|
| IM API 不可用 | 多端同步中断，本端红点不受影响 | `@Retryable` 重试失败后 `@Recover` 记 WARN 日志 | IM API 恢复后下次推送自愈，前端 `POST /unread` 拉取兜底 |
| Redis 不可用 | Lua 执行失败，未读状态不可用 | Redis 连接异常日志 | 恢复后 Lua 自愈重建 |
| MySQL 不可用 | 消息持久化受阻 | 异常日志 | 等待恢复后重试 |
| ToolDoneEvent 丢失 | 该次推送遗漏 | — | 前端 `POST /unread` 定时/前台拉取兜底 |

### 7.2 系统级安全影响分析

- 未读信息按 `userId` 隔离，不跨用户泄漏
- 已读上报需 `requireSessionAccess` 校验
- IM API 调用使用现有 IM token 鉴权

### 7.3 兼容性

#### 7.3.1 后向兼容性确认

- 无 DB 变更，存量会话无影响
- 现有会话列表 API 不受影响，未读独立查询

#### 7.3.2 前向兼容性确认

- `StreamMessage.SESSION_UNREAD` 为新增类型，旧客户端忽略即可
- `POST /unread`（sessionIds 可选）为全新端点

### 7.4 可运维

- `skill.sync.mode`（SyncProperties 已存在）控制同步方式，CompositeMultiDeviceSyncService（已存在）按 `SyncRequest.syncMode` 自注册路由，无需条件注入
- 新增配置项均有默认值
- `client_notify_id` 使用 UUID，可追踪链路

### 7.5 资料

- IM API 文档：`.trellis/tasks/06-10-skill-session-unread-badge/im-mulit-client-sync-api.md`

## 8 CheckList

### 8.1 设计自检清单

- [ ] `UnreadRedisService`：`updateMaxSeq(uid, sid, seq, ttl)` + `markRead(uid, sid, readSeq)` + `removeField(uid, sid)` + `getMaxSeq(uid, sid)` + `getUnread(uid, sessionIds)`
- [x] `MultiDeviceSyncService` 接口 + `CompositeMultiDeviceSyncService`（自注册路由）+ `ImMultiDeviceSyncService` + `WsMultiDeviceSyncService`（已存在，`06-15-multi-device-sync` 构建）
- [x] `SyncType.SESSION_UNREAD("session.unread")`（已存在）
- [x] `SyncProperties` 配置（`skill.sync.mode`，已存在）
- [ ] `ToolDoneEvent` + `UnreadManageListener`（统一监听 ToolDoneEvent + ReadReportedEvent + SessionDeletedEvent）
- [ ] `ReadReportedEvent(readSeq)`（markRead return 1 时发布）
- [ ] `UnreadSyncKeys` 常量类
- [ ] `UnreadProperties`（`session-domain-whitelist` + `hash-ttl-seconds` + `async.*`）
- [ ] `SyncImRetryConfig`（`skill.sync.im.retry.*`）
- [ ] `UnreadAsyncConfig`（`@EnableAsync` + `unreadExecutor` 线程池，`skill.unread.async.*` 配置注入）
- [ ] `UnreadManageListener.onToolDone` 加 `@Async("unreadExecutor")`
- [ ] `SkillSessionService.reportRead`（domain 白名单校验 + Lua markRead + 发布 ReadReportedEvent）
- [ ] `GatewayMessageRouter.handleToolDone` 末尾：发布 ToolDoneEvent（+1 行）
- [ ] `ImMultiDeviceSyncService`：`@Retryable` + `@Recover` 注解
- [ ] `SkillSessionController` 新增 `POST /unread`（sessionIds 可选）+ `POST /{id}/read`
- [ ] `StreamMessage.SESSION_UNREAD` 类型 + `sessionUnread(...)` 工厂方法
- [ ] 前端 `useReadTracking` + `useUnreadBadge`（拆分）+ 单调校验
- [ ] SessionSidebar 红点 + CSS 样式
- [ ] 边缘情况覆盖：流式保护、离线恢复、domain 白名单、IM 重试、硬删除清理
