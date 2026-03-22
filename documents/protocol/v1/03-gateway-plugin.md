# Layer 3：AI Gateway ↔ Message Bridge Plugin 协议详解

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

Step 3: AK 查表
  SQL: SELECT sk, user_id FROM ak_sk_credential WHERE ak = ? AND status = 'ACTIVE'
  → 未找到 → null

Step 4: 签名计算与比较
  expected = Base64(HMAC-SHA256(sk, ak + ts + nonce))
  MessageDigest.isEqual(expected, signature)  // 恒定时间比较，防时序攻击
  → 不匹配 → null

Step 5: 返回 userId
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
      │                                           │   验证 AK/SK 签名
      │                                           │   存储 userId, ak 到会话属性
      │                                           │   启动 10s 注册超时计时器
      │ WS Handshake OK                           │
      │←─────────────────────────────────────────│
      │                                           │
      │ {type: "register", deviceName, ...}       │
      │─────────────────────────────────────────→ │
      │                                           │ handleRegister():
      │                                           │   DeviceBindingService.validate()
      │                                           │   检查重复连接
      │                                           │   AgentRegistryService.register()
      │                                           │   EventRelayService.registerAgentSession()
      │                                           │   订阅 Redis agent:{ak}
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
| 4409 | CLOSE_DUPLICATE | 同 AK 已有连接 | 停止重连 |

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
| `toolType` | 配置 `gateway.channel` | 默认 "opencode" |
| `toolVersion` | package.json version | 插件版本 |

**Gateway 处理（handleRegister）：**

```
Step 1: 设备绑定验证
  DeviceBindingService.validate(ak, macAddress, toolType)
  ├── 未启用 (默认) → 通过
  ├── 服务不可用 → 通过 (fail-open)
  ├── valid=true → 通过
  └── valid=false → registerRejected("device_binding_failed") + close(4403)

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

Step 5: 响应客户端
  → {type: "register_ok"}

Step 6: 通知 Skill Server
  relayToSkillServer(ak, agentOnline(ak, toolType, toolVersion))
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
    → message.ensureTraceId()
    → 注入 userId (Redis: gw:agent:user:{ak})
    → 注入 source (Redis: gw:agent:source:{ak})
    → SkillRelayService.relayToSkill(message)
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
  "payload": "{\"text\":\"hello\",\"toolSessionId\":\"xxx\"}"
}
```

**注意：** Gateway 在下发前已 `withoutRoutingContext()`，剥离了 `userId` 和 `source`。

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
  String welinkSessionId;   // Skill 会话 ID
  String toolSessionId;     // OpenCode 会话 ID

  // 上下文（服务端注入/剥离）
  String userId;            // 用户标识（Gateway 注入，下行时剥离）
  String source;            // 来源服务（Gateway 注入，下行时剥离）

  // 追踪
  String traceId;           // UUID，自动生成如缺失
  Long sequenceNumber;      // 消息序号

  // register 专有
  String deviceName;
  String macAddress;
  String os;
  String toolType;
  String toolVersion;

  // invoke 专有
  String action;
  String payload;           // JSON 字符串

  // tool_event 专有
  Object event;             // OpenCode 原始事件 (JsonNode)

  // tool_done 专有
  Object usage;             // Token 统计

  // tool_error 专有
  String error;

  // register_rejected 专有
  String reason;

  // session_created 专有
  Object session;           // 会话对象

  // status_response 专有
  Boolean opencodeOnline;

  // 内部标识（不参与路由）
  String agentId;           // DB Long ID
}
```

### 4.2 各类型使用的字段

| type | ak | welinkSessId | toolSessId | userId | source | action | payload | event | error | usage | 其他 |
|------|----|-------------|-----------|--------|--------|--------|---------|-------|-------|-------|------|
| `register` | — | — | — | — | — | — | — | — | — | — | deviceName, macAddress, os, toolType, toolVersion |
| `register_ok` | — | — | — | — | — | — | — | — | — | — | — |
| `register_rejected` | — | — | — | — | — | — | — | — | — | — | reason |
| `heartbeat` | — | — | — | — | — | — | — | — | — | — | — |
| `invoke` | ✓ | ✓ | — | ✓ | ✓ | ✓ | ✓ | — | — | — | sequenceNumber |
| `tool_event` | — | — | ✓ | — | — | — | — | ✓ | — | — | traceId |
| `tool_done` | — | ✓? | ✓ | — | — | — | — | — | — | ✓ | traceId |
| `tool_error` | — | ✓? | ✓? | — | — | — | — | — | ✓ | — | reason, traceId |
| `session_created` | — | ✓ | ✓ | — | — | — | — | — | — | — | session, traceId |
| `agent_online` | ✓ | — | — | — | — | — | — | — | — | — | toolType, toolVersion |
| `agent_offline` | ✓ | — | — | — | — | — | — | — | — | — | — |
| `status_query` | — | — | — | — | — | — | — | — | — | — | — |
| `status_response` | — | — | — | — | — | — | — | — | — | — | opencodeOnline |
| `permission_request` | — | — | — | — | — | — | — | — | — | — | permissionId, command, metadata |

### 4.3 不可变操作方法

```java
// 注入路由上下文
withAk(String ak)
withUserId(String userId)
withSource(String source)
withTraceId(String traceId)
withSequenceNumber(Long seq)

// 剥离路由上下文
withoutUserId()             // 下发给 Agent 时
withoutSource()             // 下发给 Agent 时
withoutRoutingContext()     // 同时剥离 userId + source

// 工具方法
ensureTraceId()             // 若无 traceId → 生成 UUID
```

**userId/source 注入与剥离规则：**

```
上行消息 (Agent → Gateway):
  Agent 不携带 userId, source
  Gateway 注入:
    userId ← Redis gw:agent:user:{ak}
    source ← Redis gw:agent:source:{ak} 或 message.source

下行消息 (Gateway → Agent):
  Gateway 剥离: withoutRoutingContext()
  Agent 收到的 invoke 只有 ak, action, payload, welinkSessionId 等业务字段
```

---

## 五、Gateway 多实例路由

### 5.1 Redis 频道与 Key 设计

| Key/Channel | 类型 | 用途 | TTL |
|-------------|------|------|-----|
| `agent:{ak}` | Pub/Sub | Agent 消息投递（invoke 下发） | — |
| `gw:relay:{instanceId}` | Pub/Sub | 跨 Gateway 实例消息中继 | — |
| `gw:agent:user:{ak}` | String | AK → userId 映射 | 无 |
| `gw:agent:source:{ak}` | String | AK → source 映射 | 无 |
| `gw:source:owner:{source}:{instanceId}` | String | 实例 ownership 心跳 | 30s |
| `gw:source:owners:{source}` | Set | 集群级 source owner 注册表 | 无 |
| `gw:auth:nonce:{nonce}` | String | Nonce 防重放 | 300s |

### 5.2 下行路由（Skill → Agent）

```
Skill Server 发送 invoke 到 Gateway-B
  ↓
SkillRelayService.handleInvokeFromSkill(session, message):
  验证 source 匹配
  验证 userId 匹配
  绑定 agent source: gw:agent:source:{ak} = source
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
    → 注入 userId, source, traceId
    → SkillRelayService.relayToSkill(message)
  ↓
SkillRelayService.relayToSkill(message):

  Step 1: 尝试本地默认 link
    sendViaDefaultLink(source, message)
    → 成功 → return true (local_link)

  Step 2: 本地无连接，选择 owner
    selectOwner(source, message.type):
      owners = Redis SMEMBERS gw:source:owners:{source}
      过滤存活: EXISTS gw:source:owner:{source}:{ownerKey}
      Rendezvous Hash: max(hash(type + "|" + ownerKey))
      → 返回 instanceId

  Step 3: 本地 owner → 重试本地
    instanceId == this → sendViaDefaultLink (local_fallback)

  Step 4: 远程 owner → Redis 中继
    Redis PUBLISH gw:relay:{remoteInstanceId} = message
    远程实例收到 → handleRelayedMessage() → sendViaDefaultLink()
```

**Rendezvous Hashing 算法：**
```java
long rendezvousScore(String key, String ownerKey) {
  String stableKey = key != null ? key : "default";
  return Integer.toUnsignedLong((stableKey + "|" + ownerKey).hashCode());
}
// 选择得分最高的 ownerKey → 解析出 instanceId
```

**作用：** 同一 message type 的消息始终路由到同一 owner instance，减少跨实例 relay。

### 5.4 Owner 心跳机制

```
注册:
  每 10s: SET gw:source:owner:{source}:{instanceId} "alive" EX 30
          SADD gw:source:owners:{source} "{source}:{instanceId}"

清理:
  无连接时: DEL gw:source:owner:{source}:{instanceId}
            SREM gw:source:owners:{source} "{source}:{instanceId}"

查询:
  SMEMBERS gw:source:owners:{source}
  → 过滤: 仅保留 EXISTS gw:source:owner:{source}:{ownerKey} 的条目
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
  "payload": "{\"text\":\"hello\",\"toolSessionId\":\"xxx\"}",
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
  user_id     BIGINT NOT NULL,
  description VARCHAR(200),
  status      ENUM('ACTIVE','DISABLED') DEFAULT 'ACTIVE',
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME
);
```

### agent_connection 表

```sql
CREATE TABLE agent_connection (
  id            BIGINT PRIMARY KEY,     -- Snowflake ID
  user_id       BIGINT NOT NULL,
  ak_id         VARCHAR(64) NOT NULL,
  device_name   VARCHAR(100),
  mac_address   VARCHAR(20),
  os            VARCHAR(50),
  tool_type     VARCHAR(50) NOT NULL DEFAULT 'channel',
  tool_version  VARCHAR(50),
  status        ENUM('ONLINE','OFFLINE') DEFAULT 'OFFLINE',
  last_seen_at  DATETIME,
  created_at    DATETIME NOT NULL
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
| 注册超时 | close(4408)，Plugin 自动重连 |
| Relay 目标不存在 | 日志记录，消息丢失 |
| Skill link 全部不可用 | 日志记录，消息丢失 |
| 心跳超时（90s） | 标记 OFFLINE + 通知 Skill |

### 8.3 设备绑定的 Fail-Open 策略

```
验证服务未启用 → 允许
验证服务 URL 未配置 → 允许 + 警告日志
验证服务调用超时/异常 → 允许 + 警告日志
验证服务返回 valid=false → 拒绝
```

设计理念：外部依赖故障不应阻止核心功能。
