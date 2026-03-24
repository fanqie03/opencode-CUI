# Layer 3：AI Gateway ↔ Message Bridge Plugin 协议详解（v3）

> **v3 变更：** 并发注册分布式锁；conn:ak 绑定/心跳刷新/Lua 条件删除；AkSk 认证重构为 GATEWAY/REMOTE 双模式；设备绑定校验优化；MDC 上下文保护。
> **v2 变更：** GatewayMessage 新增 `gatewayInstanceId` 字段；Lua 原子删除；SCAN 替代 KEYS；routeCache 定时清理。

## 概述

AI Gateway 与 Message Bridge Plugin 之间通过 WebSocket 双向通信。Plugin 作为客户端连接 Gateway 的 `/ws/agent` 端点。

```
┌──────────────┐                    ┌──────────────┐
│  AI Gateway  │   WebSocket        │  Message     │
│  :8081       │ ←─────────────────│  Bridge      │
│  /ws/agent   │ ─────────────────→│  Plugin      │
└──────────────┘                    └──────────────┘
  AgentWebSocketHandler             GatewayConnection
  (WS 服务端)                        (WS 客户端)
```

---

## 一、WebSocket 连接与认证

### 1.1 AK/SK 签名认证

**认证载荷结构：**
```json
{
  "ak": "agent-access-key",
  "ts": "1710000000",           // Unix 时间戳（秒）
  "nonce": "550e8400-e29b...",  // UUID v4
  "sign": "Base64(HMAC-SHA256)" // 签名
}
```

**签名算法：**
```
message = AK + ts + nonce
sign = Base64(HMAC-SHA256(SK, message))
```

**Plugin 侧实现（AkSkAuth.ts）：**
```typescript
generateAuthPayload(): AkSkAuthPayload {
  const ts = Math.floor(Date.now() / 1000).toString();
  const nonce = randomUUID();
  const message = `${ak}${ts}${nonce}`;
  const sign = Base64(HMAC-SHA256(sk, message));
  return { ak, ts, nonce, sign };
}
```

**Gateway 验证流程（AkSkAuthService.verify）：**

```
Step 1: 时间戳窗口验证
  |now - ts| ≤ 300 秒（5 分钟）
  → 超出 → null（认证失败）

Step 2: Nonce 重放检测
  Redis: SET NX gw:auth:nonce:{nonce} "1" EX 300
  → 已存在 → null（重放攻击）

Step 3: 身份解析（根据 gateway.auth.mode 分支）

  ┌─────────────────────────────────────────────────────────────────┐
  │ GATEWAY 模式（默认）                                             │
  │                                                                 │
  │  SQL: SELECT sk, user_id FROM ak_sk_credential                 │
  │       WHERE ak = ? AND status = 'ACTIVE'                       │
  │  → 未找到 → null                                                │
  │                                                                 │
  │  expected = Base64(HMAC-SHA256(sk, ak + ts + nonce))           │
  │  MessageDigest.isEqual(expected, signature)                     │
  │  → 不匹配 → null                                                │
  │  → 匹配 → 返回 userId                                           │
  └─────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────┐
  │ REMOTE 模式（外部认证）                                          │
  │                                                                 │
  │  L1: Caffeine 本地缓存                                          │
  │      Key: auth:identity:{ak}                                    │
  │      TTL: 300s，上限 10000 条                                    │
  │      → 命中 → 信任缓存的 userId（首次由 L3 验签）                  │
  │                                                                 │
  │  L2: Redis 缓存                                                 │
  │      Key: auth:identity:{ak}                                    │
  │      Value: {"userId":"...","level":"L3"}                       │
  │      TTL: 3600s                                                 │
  │      → 命中 → 信任缓存的 userId，回填 L1                          │
  │                                                                 │
  │  L3: 外部身份 API                                                │
  │      POST {base-url}/appstore/wecodeapi/open/identity/check    │
  │      Authorization: Bearer {bearer-token}                       │
  │      Body: {"ak":"...","timestamp":...,"nonce":"...","sign":""} │
  │      Response: {"code":"...","data":{"checkResult":bool,        │
  │                 "userId":"..."}}                                 │
  │      → checkResult=true → 返回 userId，回填 L1+L2               │
  │      → checkResult=false → 拒绝认证                              │
  │      → 网络/超时异常 → 拒绝认证                                    │
  │                                                                 │
  │  L4: 隐式拒绝（无本地 DB 降级）                                    │
  └─────────────────────────────────────────────────────────────────┘

Step 4: 返回 userId（null 表示认证失败）
```

### 1.2 WebSocket 子协议

**格式：**
```
Sec-WebSocket-Protocol: auth.{Base64URL(JSON)}
```

**关键点：**
- 使用 **URL-safe Base64**（RFC 4648 §5），不含 `+`, `/`, `=`
- 原因：WebSocket 子协议头不支持标准 Base64 中的特殊字符
- Gateway 回显子协议到响应头（RFC 6455 要求）
- **Bun 客户端强制验证回显**，不匹配会重置连接

### 1.3 连接生命周期

```
┌────────────┐                              ┌────────────┐
│   Plugin   │                              │  Gateway   │
└─────┬──────┘                              └─────┬──────┘
      │ WS Connect (auth subprotocol)              │
      │─────────────────────────────────────────→ │
      │                                           │ beforeHandshake():
      │                                           │   验证 AK/SK 签名（GATEWAY/REMOTE 双模式）
      │                                           │   存储 userId, ak 到会话属性
      │                                           │   启动 10s 注册超时计时器
      │ WS Handshake OK                           │
      │←─────────────────────────────────────────│
      │                                           │
      │ {type: "register", deviceName, ...}       │
      │─────────────────────────────────────────→ │
      │                                           │ handleRegister():
      │                                           │   [v3] 获取分布式锁 gw:register:lock:{ak}
      │                                           │   DeviceBindingService.validate()
      │                                           │   检查重复连接
      │                                           │   AgentRegistryService.register()
      │                                           │   EventRelayService.registerAgentSession()
      │                                           │   订阅 Redis agent:{ak}
      │                                           │   [v3] 绑定 conn:ak:{ak} → instanceId
      │                                           │   [v3] 释放分布式锁（Lua 原子校验 owner）
      │                                           │
      │ {type: "register_ok"}                     │
      │←─────────────────────────────────────────│
      │                                           │ → Skill Server: agent_online
      │ 启动心跳 (30s 间隔)                        │
      │                                           │
      │ ═══════════ READY 状态 ═══════════        │
      │                                           │
      │ {type: "heartbeat"}    (每 30s)           │
      │─────────────────────────────────────────→ │
      │                                           │ heartbeat(agentId)
      │                                           │   → UPDATE last_seen_at = NOW()
      │                                           │   → [v3] EXPIRE conn:ak:{ak} 120
      │                                           │
      │ {type: "invoke", action, payload}          │
      │←─────────────────────────────────────────│
      │                                           │
      │ {type: "tool_event/done/error/...}        │
      │─────────────────────────────────────────→ │
      │                                           │
      │ ═══════════ 断开连接 ═══════════          │
      │                                           │ afterConnectionClosed():
      │                                           │   AgentRegistryService.markOffline()
      │                                           │   EventRelayService.removeAgentSession()
      │                                           │   [v3] Lua 条件删除 conn:ak:{ak}
      │                                           │   → Skill Server: agent_offline
      │                                           │
      │ Plugin 指数退避重连                         │
      │   delay = min(1000 * 2^(n-1), 30000) ms  │
```

### 1.4 自定义 WebSocket 关闭码

| 码 | 名称 | 原因 | Plugin 行为 |
|----|------|------|------------|
| 4403 | CLOSE_BINDING_FAILED | 设备绑定验证失败 | 停止重连 |
| 4408 | CLOSE_REGISTER_TIMEOUT | 10s 内未发送 register | 重连 |
| 4409 | CLOSE_DUPLICATE | 同 AK 已有连接 / 并发注册冲突 | 停止重连 |

### 1.5 Plugin 连接状态机

```
DISCONNECTED → CONNECTING → CONNECTED → READY
     ↑                                    │
     └────────────────────────────────────┘
         (close/error → 指数退避重连)
```

- **DISCONNECTED → CONNECTING：** 发起 WebSocket 连接
- **CONNECTING → CONNECTED：** WS 握手成功，发送 `register`
- **CONNECTED → READY：** 收到 `register_ok`，启动心跳
- **任何状态 → DISCONNECTED：** 连接关闭/错误

---

## 二、上行协议（Plugin → Gateway）

### 2.1 `register` — 设备注册

**消息格式：**
```json
{
  "type": "register",
  "deviceName": "My-Workstation",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "os": "linux",
  "toolType": "opencode",
  "toolVersion": "1.4.0"
}
```

**字段说明：**

| 字段 | 来源 | 说明 |
|------|------|------|
| `deviceName` | 系统 hostname | 设备名称标识 |
| `macAddress` | 网卡 MAC 地址 | 设备绑定验证用 |
| `os` | `process.platform` | linux/darwin/win32 |
| `toolType` | 配置 `gateway.channel` | Plugin 侧默认 "opencode"；Gateway 侧若收到 null 则默认 "channel" |
| `toolVersion` | package.json version | 插件版本 |

**Gateway 处理（handleRegister）— v3 完整流程：**

```
Step 0: 获取分布式锁 [v3 新增]
  Redis SET NX gw:register:lock:{ak} = "{instanceId}:{threadId}" EX 10
  → 获取失败 → registerRejected("concurrent_registration") + close(4409)

Step 1: 设备绑定验证 [v3 优化]
  DeviceBindingService.validate(ak, macAddress, toolType)
  ├── 未启用 (gateway.device-binding.enabled=false) → 通过
  ├── 查询 agent_connection 最新记录 (findLatestByAkId)
  ├── 无记录（首次注册） → 通过
  ├── MAC + toolType 匹配（忽略大小写） → 通过
  ├── 不匹配 → registerRejected("device_binding_failed") + close(4403)
  └── 异常 → 通过 (Fail-Open) + WARN 日志

Step 2: 重复连接检查
  EventRelayService.hasAgentSession(ak)
  └── 已存在 → registerRejected("duplicate_connection") + close(4409)

Step 3: 数据库注册
  AgentRegistryService.register(userId, ak, deviceName, macAddress, os, toolType, toolVersion)
  ├── 已有记录 (同 ak + toolType) → UPDATE status=ONLINE, 更新元数据
  └── 新记录 → INSERT (Snowflake ID)

Step 4: 本地注册
  sessionAkMap[wsSessionId] = ak
  session.attributes[ATTR_AGENT_ID] = agentId (Long)
  EventRelayService.registerAgentSession(ak, userId, session)
    → agentSessions[ak] = session
    → Redis: SET gw:agent:user:{ak} = userId
    → Redis: SUBSCRIBE agent:{ak}

Step 5: 连接注册 [v3 新增]
  Redis: SET conn:ak:{ak} = {gatewayInstanceId} EX 120

Step 6: 响应客户端
  → {type: "register_ok"}

Step 7: 通知 Skill Server
  relayToSkillServer(ak, agentOnline(ak, toolType, toolVersion))

Step 8: 释放分布式锁 [v3 新增]
  Lua 脚本: 校验 value == "{instanceId}:{threadId}" 后 DEL
  → 校验失败（已被超时清除或其他实例覆盖）→ 仅日志，不影响流程
```

**分布式锁 Lua 释放脚本：**
```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
else
  return 0
end
```

### 2.2 `heartbeat` — 保活心跳

**消息格式：**
```json
{
  "type": "heartbeat",
  "timestamp": "2024-03-20T10:30:00.000Z"
}
```

**发送间隔：** 30 秒（可配置 `gateway.heartbeatIntervalMs`）
**仅在 READY 状态发送**

**Gateway 处理：**
```java
Long agentId = session.getAttributes().get(ATTR_AGENT_ID);
agentRegistryService.heartbeat(agentId);
// → SQL: UPDATE agent_connection SET last_seen_at = NOW() WHERE id = ?

// [v3 新增] 刷新 conn:ak TTL
redisMessageBroker.refreshConnAkTtl(akId, 120);
// → Redis: EXPIRE conn:ak:{ak} 120
```

**超时检测（Gateway 定时任务）：**
```
每 30 秒扫描:
  SELECT * FROM agent_connection WHERE last_seen_at < NOW() - 90s AND status = 'ONLINE'
  → 标记 OFFLINE
  → 清理 WebSocket 会话
  → 通知 Skill Server: agent_offline
```

### 2.3 `tool_event` — OpenCode 事件转发

**消息格式：**
```json
{
  "type": "tool_event",
  "toolSessionId": "opencode-session-uuid",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "partID": "part-001",
      "delta": "Hello "
    }
  }
}
```

**Plugin 发送时机：** 每次 OpenCode SDK 触发支持的事件时
**转发的事件类型：** 11 种（详见 04-plugin-opencode.md）

**Gateway 处理：**
```
AgentWebSocketHandler.handleRelayToSkillServer(session, message)
  → ak = sessionAkMap[wsSessionId]
  → EventRelayService.relayToSkillServer(ak, message)
    → [v3] MdcHelper.snapshot() — 保存调用方 MDC
    → message.ensureTraceId()
    → 注入 ak, userId
    → SkillRelayService.relayToSkill(message)
    → [v3] MdcHelper.restore(previousMdc) — 恢复调用方 MDC
```

### 2.4 `tool_done` — 执行完成

**消息格式：**
```json
{
  "type": "tool_done",
  "toolSessionId": "opencode-session-uuid",
  "welinkSessionId": "12345",
  "usage": { /* token 统计 */ }
}
```

**Plugin 发送时机（ToolDoneCompat 决策）：**

| 场景 | 触发 | source 标记 |
|------|------|------------|
| chat Action 执行成功 | 立即发送 | `invoke_complete` |
| session.idle 事件（无 pending chat） | 兜底发送 | `session_idle` |
| session.idle（有 pending chat） | **不发送** | — |
| session.idle（已由 invoke_complete 发过） | **不发送** | — |

**Gateway 处理：** 同 `tool_event`，透传给 Skill Server

### 2.5 `tool_error` — 执行错误

**消息格式：**
```json
{
  "type": "tool_error",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "error": "session not found: opencode-session-uuid",
  "reason": "session_not_found"
}
```

**`reason` 推断逻辑（Plugin 侧）：**
```
错误信息包含以下关键词 → reason = "session_not_found":
  "not found", "404", "session_not_found",
  "unexpected eof", "json parse error"
其他 → reason = undefined
```

**Gateway 处理：** 同 `tool_event`，透传给 Skill Server

### 2.6 `session_created` — 会话创建结果

**消息格式：**
```json
{
  "type": "session_created",
  "welinkSessionId": "12345",
  "toolSessionId": "new-opencode-session-uuid",
  "session": {
    "sessionId": "new-opencode-session-uuid",
    "session": { /* 完整会话对象 */ }
  }
}
```

**Plugin 发送时机：** `create_session` Action 执行成功后

**sessionId 提取逻辑（CreateSessionAction）：**
```
优先级:
  response.sessionId → response.id →
  response.data.sessionId → response.data.id
```

**Gateway 处理：** 同 `tool_event`，透传给 Skill Server

### 2.7 `status_response` — 健康状态响应

**消息格式：**
```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

**Plugin 发送时机：** 收到 `status_query` 后

**Gateway 处理（handleStatusResponse）：**
```java
EventRelayService.recordStatusResponse(ak, opencodeOnline)
  → opencodeStatusCache[ak] = opencodeOnline
  → 完成 pendingStatusQueries[ak] 的 CompletableFuture
```

---

## 三、下行协议（Gateway → Plugin）

### 3.1 `register_ok` — 注册成功

**消息格式：**
```json
{
  "type": "register_ok"
}
```

**Plugin 处理：**
- 状态 CONNECTED → READY
- 启动心跳定时器（30s）
- 开始处理业务消息

### 3.2 `register_rejected` — 注册失败

**消息格式：**
```json
{
  "type": "register_rejected",
  "reason": "duplicate_connection"
}
```

**`reason` 取值：**
- `"device_binding_failed"` — 设备绑定验证失败
- `"duplicate_connection"` — 已有同 AK 连接
- `"concurrent_registration"` — **[v3 新增]** 并发注册冲突（分布式锁获取失败）

**Plugin 处理：**
- 关闭 WebSocket
- 停止重连（该 AK 无法注册）

### 3.3 `invoke` — 执行动作

**消息格式：**
```json
{
  "type": "invoke",
  "ak": "agent-key",
  "welinkSessionId": "12345",
  "action": "chat",
  "payload": {"text": "hello", "toolSessionId": "xxx"}
}
```

> **注意：** `payload` 字段是 Jackson `JsonNode` 类型，序列化后为嵌套 JSON 对象（不是转义字符串）。

**注意：** Gateway 在下发前已 `withoutRoutingContext()`，剥离了 `userId`、`source` 和 `gatewayInstanceId`。

**Plugin 处理流程：**

```
GatewayConnection.onMessage(raw)
  ↓
DownstreamMessageNormalizer.normalize(message)
  ├── 验证 type == "invoke"
  ├── 验证 action ∈ 支持的动作
  └── 验证 payload 字段类型
  ↓
BridgeRuntime.handleDownstreamMessage(normalized)
  ↓
根据 action 分发:

  action == "status_query":
    → StatusQueryAction.execute()
    → 发送 status_response

  action == "create_session":
    → CreateSessionAction.execute()
    → 成功 → 发送 session_created
    → 失败 → 发送 tool_error

  其他 action (chat, close_session, abort_session, permission_reply, question_reply):
    → ToolDoneCompat.handleInvokeStarted()  // chat 时记录 pending
    → ActionRouter.route(action, payload, context)
    → 成功:
        → ToolDoneCompat.handleInvokeCompleted()
        → 若 decision.emit → 发送 tool_done
    → 失败:
        → ToolDoneCompat.handleInvokeFailed()  // 清理 pending
        → 发送 tool_error
```

**Action 执行详解：**

| Action | SDK 调用 | 成功响应 | 失败处理 |
|--------|---------|---------|---------|
| `chat` | `client.session.prompt({path:{id}, body:{parts:[{type:'text', text}]}})` | `tool_done` | `tool_error`（含 reason 推断） |
| `create_session` | `client.session.create({body: payload})` | `session_created` | `tool_error` |
| `close_session` | `client.session.delete({path:{id}})` | `tool_done` | `tool_error` |
| `abort_session` | `client.session.abort({path:{id}})` | `tool_done` | `tool_error` |
| `permission_reply` | `client.postSessionIdPermissionsPermissionId(...)` | `tool_done` | `tool_error` |
| `question_reply` | 先 GET `/question` 查找 → 再 POST `/question/{id}/reply` | `tool_done` | `tool_error` |

### 3.4 `status_query` — 健康检查

**消息格式：**
```json
{
  "type": "status_query"
}
```

**Plugin 处理：**
```
StatusQueryAction.execute()
  → hostClient.global.health()
  → 返回 { opencodeOnline: healthy === true }
  → 发送 status_response
```

**Gateway 发送时机：**
- REST API `GET /api/gateway/agents/status?ak={ak}` 被调用时
- `EventRelayService.requestAgentStatus(ak)` 内部调用
- 等待 1.5s，超时返回缓存值

---

## 四、GatewayMessage 完整字段定义

### 4.1 核心字段

```java
class GatewayMessage {
  // 类型
  String type;              // 14 种消息类型

  // 路由（核心）
  String ak;                // Agent Access Key（路由主键）
  String welinkSessionId;   // Skill 会话 ID（String 类型，防 JS 精度丢失）
  String toolSessionId;     // OpenCode 会话 ID

  // 上下文（服务端注入/剥离）
  String userId;            // 用户标识（Gateway 注入，下行时剥离）
  String source;            // 来源服务（Gateway 注入，下行时剥离）
  String gatewayInstanceId; // [v2+] Gateway 实例 ID（内部路由，下行时剥离）

  // 追踪
  String traceId;           // UUID，自动生成如缺失
  Long sequenceNumber;      // 消息序号（多实例协调）

  // register 专有
  String deviceName;
  String macAddress;
  String os;
  String toolType;
  String toolVersion;

  // invoke 专有
  String action;
  JsonNode payload;         // JSON 对象（Jackson JsonNode，序列化后为嵌套 JSON）

  // tool_event 专有
  JsonNode event;           // OpenCode 原始事件（完整透传）

  // tool_done 专有
  JsonNode usage;           // Token 统计（嵌套 JSON 对象）

  // tool_error 专有
  String error;

  // register_rejected 专有
  String reason;

  // session_created 专有
  JsonNode session;         // 会话对象（嵌套 JSON）

  // status_response 专有
  Boolean opencodeOnline;

  // 内部标识（不参与路由）
  String agentId;           // DB Long ID（遗留字段）
}
```

### 4.2 各类型使用的字段

| type | ak | welinkSessId | toolSessId | userId | source | gwInstId | action | payload | event | error | usage | 其他 |
|------|----|-------------|-----------|--------|--------|----------|--------|---------|-------|-------|-------|------|
| `register` | — | — | — | — | — | — | — | — | — | — | — | deviceName, macAddress, os, toolType, toolVersion |
| `register_ok` | — | — | — | — | — | — | — | — | — | — | — | — |
| `register_rejected` | — | — | — | — | — | — | — | — | — | — | — | reason |
| `heartbeat` | — | — | — | — | — | — | — | — | — | — | — | — |
| `invoke` | ✓ | ✓ | — | ✓ | ✓ | — | ✓ | ✓ | — | — | — | sequenceNumber |
| `tool_event` | — | — | ✓ | — | — | — | — | — | ✓ | — | — | traceId |
| `tool_done` | — | ✓? | ✓ | — | — | — | — | — | — | — | ✓ | traceId |
| `tool_error` | — | ✓? | ✓? | — | — | — | — | — | — | ✓ | — | reason, traceId |
| `session_created` | — | ✓ | ✓ | — | — | — | — | — | — | — | — | session, traceId |
| `agent_online` | ✓ | — | — | — | — | — | — | — | — | — | — | toolType, toolVersion |
| `agent_offline` | ✓ | — | — | — | — | — | — | — | — | — | — | — |
| `status_query` | — | — | — | — | — | — | — | — | — | — | — | — |
| `status_response` | — | — | — | — | — | — | — | — | — | — | — | opencodeOnline |
| `permission_request` | — | — | — | — | — | — | — | — | — | — | — | permissionId, command, metadata |

### 4.3 不可变操作方法

```java
// 构建器
toBuilder()                       // 创建可修改副本

// 注入路由上下文
withAk(String ak)
withUserId(String userId)
withSource(String source)
withTraceId(String traceId)
withSequenceNumber(Long seq)
withGatewayInstanceId(String id)  // [v2+] 设置 Gateway 实例 ID

// 剥离路由上下文
withoutUserId()                   // 下发给 Agent 时
withoutSource()                   // 下发给 Agent 时
withoutRoutingContext()           // 同时剥离 userId + source + gatewayInstanceId

// 工具方法
ensureTraceId()                   // 若无 traceId → 生成 UUID

// 静态工厂
register(), registerOk(), registerRejected(), heartbeat()
toolEvent(), toolDone(), toolError()
sessionCreated(), agentOnline(), agentOffline()
invoke(), statusQuery()
```

**userId/source/gatewayInstanceId 注入与剥离规则：**

```
上行消息 (Agent → Gateway):
  Agent 不携带 userId, source, gatewayInstanceId
  Gateway 注入:
    ak ← sessionAkMap[wsSessionId]
    userId ← Redis gw:agent:user:{ak}
    gatewayInstanceId ← 当前实例 ID（内部路由用）

下行消息 (Gateway → Agent):
  Gateway 剥离: withoutRoutingContext()
  → 同时移除 userId, source, gatewayInstanceId
  Agent 收到的 invoke 只有 ak, action, payload, welinkSessionId 等业务字段
```

---

## 五、Gateway 多实例路由

### 5.1 Redis 频道与 Key 设计

| Key/Channel | 类型 | 用途 | TTL |
|-------------|------|------|-----|
| `agent:{ak}` | Pub/Sub | Agent 消息投递（invoke 下发） | — |
| `gw:relay:{instanceId}` | Pub/Sub | 跨 Gateway 实例消息中继（Legacy） | — |
| `gw:instance:{instanceId}` | String | Gateway 实例自注册 | 30s |
| `gw:agent:user:{ak}` | String | AK → userId 映射 | 无 |
| `conn:ak:{ak}` | String | **[v3]** Agent 连接实例绑定 | 120s |
| `gw:register:lock:{ak}` | String | **[v3]** 并发注册分布式锁 | 10s |
| `auth:identity:{ak}` | String | **[v3]** REMOTE 认证 L2 缓存 | 3600s |
| `gw:auth:nonce:{nonce}` | String | Nonce 防重放 | 300s |
| `gw:source:owner:{source}:{instanceId}` | String | Legacy 实例 ownership 心跳 | 30s |
| `gw:source:owners:{source}` | Set | Legacy 集群级 source owner 注册表 | 无 |

### 5.2 下行路由（Skill → Agent）

```
Skill Server 发送 invoke 到 Gateway-B
  ↓
SkillRelayService.handleInvokeFromSkill(session, message):
  验证 source 匹配
  验证 userId 匹配
  [Mesh] learnRoute(toolSessionId, welinkSessionId, ssSession)
  ↓
Redis PUBLISH agent:{ak} = message.withoutRoutingContext()
  ↓
Gateway-A 订阅了 agent:{ak}:
  EventRelayService.sendToLocalAgent(ak, message):
    session = agentSessions[ak]
    synchronized(session) {
      session.sendMessage(TextMessage(json))
    }
  ↓
Plugin 收到 invoke 消息
```

### 5.3 上行路由（Agent → Skill）

```
Plugin 发送 tool_event 到 Gateway-A
  ↓
AgentWebSocketHandler.handleRelayToSkillServer(session, message)
  → EventRelayService.relayToSkillServer(ak, message)
    → [v3] MdcHelper.snapshot()
    → 注入 ak, userId, traceId
    → SkillRelayService.relayToSkill(message)
    → [v3] MdcHelper.restore(previousMdc)
  ↓
SkillRelayService.relayToSkill(message):

  [Mesh 策略]:
    routeCache[toolSessionId] → 命中 → 发送 + 学习 welinkSessionId 路由
    routeCache["w:" + welinkSessionId] → 命中 → 发送 + 学习 toolSessionId 路由
    全部未命中 → 广播到同 sourceType 所有 Mesh 实例

  [Legacy 策略]:
    sendViaDefaultLink(source, message) → 本地成功 → return
    selectOwner(source, message.type) → Rendezvous Hash
    本地 owner → 重试本地
    远程 owner → Redis PUBLISH gw:relay:{remoteInstanceId}
```

---

## 六、Gateway REST API

### 6.1 认证

所有端点需要 `Authorization: Bearer {internal-token}` 头。

### 6.2 端点

#### 查询 Agent 列表

```
GET /api/gateway/agents
  ?ak={ak}           → 按 AK 精确查询
  ?userId={userId}    → 按用户查询所有 Agent
  (无参数)            → 返回所有在线 Agent
```

**响应：** `AgentSummaryResponse[]`
```json
[{
  "ak": "agent-key",
  "status": "ONLINE",
  "deviceName": "My-PC",
  "os": "linux",
  "toolType": "opencode",
  "toolVersion": "1.4.0",
  "connectedAt": "2024-03-20T10:30:00"
}]
```

> **注意：** `AgentSummaryResponse` 是 record 类型，没有 `id` 字段，使用 `ak` 作为主标识。`status` 为枚举 `AgentStatus`（ONLINE/OFFLINE）。

#### 查询 Agent 状态（含 OpenCode 在线检查）

```
GET /api/gateway/agents/status?ak={ak}
```

**响应：** `AgentStatusResponse`
```json
{
  "ak": "agent-key",
  "status": "ONLINE",
  "opencodeOnline": true,
  "wsSessionCount": 1
}
```

**处理逻辑：**
1. 发送 `status_query` 到 Agent
2. 等待 `status_response`（1.5s 超时）
3. 超时 → 返回缓存值（`opencodeStatusCache[ak]`）

#### 调用 Agent

```
POST /api/gateway/invoke
```

**请求体：** GatewayMessage
```json
{
  "ak": "agent-key",
  "action": "chat",
  "payload": {"text": "hello", "toolSessionId": "xxx"},
  "welinkSessionId": "12345"
}
```

**处理流程：**
1. 验证 Agent 存在且 ONLINE
2. 验证 WS 连接活跃
3. `eventRelayService.relayToAgent(ak, message)`
4. 通过 Redis `agent:{ak}` 发布

**响应：** `InvokeResult`
```json
{ "success": true, "message": "Message sent" }
```

---

## 七、数据库

### ak_sk_credential 表

```sql
CREATE TABLE ak_sk_credential (
  id          BIGINT PRIMARY KEY,
  ak          VARCHAR(64) NOT NULL UNIQUE,
  sk          VARCHAR(128) NOT NULL,
  user_id     VARCHAR(128) NOT NULL,     -- [v2+] 从 BIGINT 改为 VARCHAR(128)
  description VARCHAR(200),
  status      ENUM('ACTIVE','DISABLED') DEFAULT 'ACTIVE',
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME
);
```

> **注意：** GATEWAY 模式下仍使用此表；REMOTE 模式下此表保留但不参与认证流程。

### agent_connection 表

```sql
CREATE TABLE agent_connection (
  id            BIGINT PRIMARY KEY,     -- Snowflake ID
  user_id       VARCHAR(128) NOT NULL,  -- [v2+] 从 BIGINT 改为 VARCHAR(128)
  ak_id         VARCHAR(64) NOT NULL,
  device_name   VARCHAR(100),
  mac_address   VARCHAR(20),
  os            VARCHAR(50),
  tool_type     VARCHAR(50) NOT NULL DEFAULT 'channel',
  tool_version  VARCHAR(50),
  status        ENUM('ONLINE','OFFLINE') DEFAULT 'OFFLINE',
  last_seen_at  DATETIME,
  created_at    DATETIME NOT NULL,
  UNIQUE INDEX uk_ak_tooltype (ak_id, tool_type)  -- [v2+] 唯一约束
);
```

---

## 八、错误处理与容错

### 8.1 Plugin 侧错误码

| 错误码 | 含义 | 触发场景 |
|--------|------|---------|
| `SDK_TIMEOUT` | OpenCode SDK 超时 | prompt/create 等操作超时 |
| `SDK_UNREACHABLE` | OpenCode SDK 不可达 | 连接错误 |
| `INVALID_PAYLOAD` | 负载格式错误 | 缺少字段、session not found |
| `AGENT_NOT_READY` | 连接未就绪 | 非 READY 状态收到 invoke |
| `UNSUPPORTED_ACTION` | 不支持的动作 | 未知 action 类型 |

### 8.2 Gateway 侧错误处理

| 场景 | 处理 |
|------|------|
| AK/SK 签名无效 | 握手失败，无 WS 连接 |
| 设备绑定失败 | register_rejected + close(4403) |
| 重复连接 | register_rejected + close(4409) |
| **[v3]** 并发注册冲突 | register_rejected("concurrent_registration") + close(4409) |
| 注册超时 | close(4408)，Plugin 自动重连 |
| Relay 目标不存在 | 日志记录，消息丢失 |
| Skill link 全部不可用 | 日志记录，消息丢失 |
| 心跳超时（90s） | 标记 OFFLINE + 通知 Skill |

### 8.3 设备绑定的 Fail-Open 策略

```
验证功能未启用 (enabled=false) → 允许
查询异常/超时 → 允许 + WARN 日志
首次注册（无历史记录） → 允许
MAC/toolType 匹配 → 允许
MAC/toolType 不匹配 → 拒绝
```

设计理念：外部依赖故障不应阻止核心功能。

---

## 九、配置参数

```yaml
gateway:
  instance-id: ${GATEWAY_INSTANCE_ID:${HOSTNAME:gateway-local}}

  agent:
    heartbeat-timeout-seconds: 90       # Agent 心跳超时
    heartbeat-check-interval-seconds: 30 # 超时检查间隔
    register-timeout-seconds: 10        # 注册超时（握手后未发 register）

  auth:
    mode: ${AUTH_MODE:gateway}          # gateway | remote
    timestamp-tolerance-seconds: 300    # ±5 分钟
    nonce-ttl-seconds: 300              # Nonce 过期时间
    identity-api:
      base-url: ${IDENTITY_API_BASE_URL:}      # REMOTE 模式外部 API
      bearer-token: ${IDENTITY_API_BEARER_TOKEN:}
      timeout-ms: 3000
    identity-cache:
      l1-ttl-seconds: 300              # Caffeine 本地缓存 TTL
      l1-max-size: 10000               # Caffeine 本地缓存上限
      l2-ttl-seconds: 3600             # Redis 缓存 TTL

  device-binding:
    enabled: false                      # [v3] 设备绑定开关

  websocket:
    max-text-message-buffer-size-bytes: 1048576  # 1MB
```

---

## 十、术语表

本节解释协议文档中出现的技术概念，帮助理解设计意图。

### Base64URL 编码

标准 Base64 使用 `+`、`/`、`=` 三个特殊字符，但 WebSocket 子协议头（`Sec-WebSocket-Protocol`）不允许这些字符。Base64URL 是 RFC 4648 §5 定义的变体：
- `+` 替换为 `-`
- `/` 替换为 `_`
- 去掉末尾的 `=` 填充

Plugin 侧实现：先标准 Base64 编码，再正则替换三种字符。

### Snowflake ID

分布式唯一 ID 生成算法，由 Twitter 设计。本系统中每条数据库记录（agent_connection、skill_session 等）的主键都使用 Snowflake ID，格式为 64 位整数，内嵌时间戳、服务编码、工作节点和序列号。Gateway 的 service-code=2，Skill Server 的 service-code=1，确保两个服务生成的 ID 不冲突。

### Fail-Open 策略

当外部依赖（如设备绑定验证服务）不可用时，选择 **允许通过**（而非拒绝）。设计理念：核心功能（Agent 注册）不应被非关键外部依赖的故障阻塞。反之的策略叫 Fail-Closed（故障时拒绝），适用于安全敏感场景。

### TOCTOU 竞态（Time-of-Check-to-Time-of-Use）

在"检查条件"和"执行操作"之间存在时间窗口，其他线程/进程可能改变条件。例如：
- 先 `GET conn:ak:agent-1`（值为 gateway-A）
- 在 DEL 之前，Agent 重连到 gateway-B，Redis 值被更新为 gateway-B
- 此时 gateway-A 执行 `DEL conn:ak:agent-1`，删掉了 gateway-B 的有效绑定

**解决方案：** 使用 Lua 脚本在 Redis 服务端原子执行"检查+删除"（CAS 操作），不给其他操作插入的机会。

### MDC（Mapped Diagnostic Context）

Java 日志框架（SLF4J/Logback）提供的线程局部键值存储。请求进入时在 MDC 中设置 `traceId`、`sessionId`、`ak`，后续该线程的所有日志自动附带这些字段，无需每次手动传参。本系统的日志格式 `[SERVICE] [traceId] [sessionId] [ak] class.method - message` 就依赖 MDC。

**MDC 上下文保护**：当消息转发需要临时设置新的 MDC 值时，先 `snapshot()` 保存、操作完毕后 `restore()` 还原，防止清除调用方的追踪字段。

### Rendezvous Hashing（最高随机权重哈希）

一种确定性路由算法。对每个候选节点计算 `hash(key + nodeId)` 得分，选择得分最高的节点。优点：增减节点时，只有少量 key 被重新分配（比简单取模好）。本系统 Legacy 路由中用于为同一 message type 选择固定的 Owner 实例。

### 乐观更新（Optimistic Update）

前端发送请求 **之前** 就先在 UI 上显示预期结果（如立即显示用户消息），请求成功后用真实数据替换临时数据，失败则回滚。好处：用户感知零延迟。Miniapp 发送消息时先创建临时 ID 的消息对象，POST 成功后替换为服务端返回的真实 `messageId`。

### Mesh/Legacy 双策略

本系统支持两种 Skill Server → Agent 的消息路由策略，自动选择：

- **Mesh 策略**（新版 SS）：连接握手时携带 `instanceId` → Gateway 识别为 Mesh 客户端 → 使用 `routeCache`（被动学习的 session→SS 映射）精确路由，未命中时广播到同类型所有 SS 实例
- **Legacy 策略**（旧版 SS）：连接握手时无 `instanceId` → Gateway 识别为 Legacy 客户端 → 使用 Owner 心跳 + Rendezvous Hash + Redis 中继实现跨实例转发

两种策略可以在同一 Gateway 集群中共存，无需手动切换。当所有 SS 升级完成后，Legacy 路径可逐步废弃。

### completionCache 抑制机制

`tool_done` 到达后，Skill Server 在 Caffeine 缓存中标记该 session 已完成（5 秒 TTL）。在此窗口内到达的迟到 `tool_event`（如网络延迟导致的旧 delta）会被静默丢弃，防止已结束的会话再次进入 streaming 状态。但 question 和 permission 类型的事件不受此抑制。
