# 协议报文类型生命周期全景文档

## 概述

本文档按消息/事件类型分类，描述每种报文从产生到消费的完整生命周期。涵盖三大协议层的全部消息类型：

- **A. GatewayMessage**（14 种）：组件间 WebSocket 通信的核心协议
- **B. OpenCode SDK 事件**（11 种）：OpenCode CLI 产生的原始事件
- **C. StreamMessage**（19 种）：Skill Server 推送给 Miniapp 的前端消息

```
OpenCode CLI → Plugin → AI Gateway → Skill Server → Miniapp
   (SDK事件)    (GatewayMessage)    (GatewayMessage)   (StreamMessage)   (MessagePart/UI)
```

---

# A. GatewayMessage 类型

---

## A.1 register

**概述：** Plugin 向 Gateway 注册设备信息，完成 Agent 上线流程。

**产生源头：**
- 组件：Message Bridge Plugin（GatewayConnection）
- 触发条件：WebSocket 握手成功（状态从 CONNECTING → CONNECTED）
- 生成方法：`GatewayConnection` 在连接建立后自动发送

**传播路径：**
Plugin → Gateway

**逐层处理：**

**Layer 1: Plugin（发送方）**
- 触发时机：WS 握手成功，AK/SK 认证通过
- 出站消息格式：
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

**Layer 2: Gateway（接收方，AgentWebSocketHandler.handleRegister）**
- 入站消息格式：同上
- 处理方法：
  1. `DeviceBindingService.validate(ak, macAddress, toolType)` — 设备绑定验证（默认未启用，fail-open）
  2. `EventRelayService.hasAgentSession(ak)` — 重复连接检查
  3. `AgentRegistryService.register(userId, ak, deviceName, macAddress, os, toolType, toolVersion)` — 数据库注册/更新
  4. `EventRelayService.registerAgentSession(ak, userId, session)` — 本地注册 + Redis 订阅
- 存储操作：
  - MySQL `agent_connection` 表：INSERT 或 UPDATE（status=ONLINE）
  - Redis：`SET gw:agent:user:{ak} = userId`
  - Redis：`SUBSCRIBE agent:{ak}`
- 出站消息：`register_ok`（发给 Plugin）+ `agent_online`（发给 Skill Server）

**完整报文示例：**

```
Plugin → Gateway:
{
  "type": "register",
  "deviceName": "My-Workstation",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "os": "linux",
  "toolType": "opencode",
  "toolVersion": "1.4.0"
}
```

**关联消息：**
- 前置：WebSocket 握手（AK/SK 签名认证）
- 后续：`register_ok`（成功）或 `register_rejected`（失败）；成功时还触发 `agent_online`

---

## A.2 register_ok

**概述：** Gateway 通知 Plugin 注册成功，Plugin 进入 READY 状态。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler）
- 触发条件：`register` 消息处理全部通过（设备绑定、重复检查、数据库注册）
- 生成方法：`handleRegister()` 成功后构造响应

**传播路径：**
Gateway → Plugin

**逐层处理：**

**Layer 1: Gateway（发送方）**
- 触发：`register` 处理成功
- 出站消息格式：
```json
{
  "type": "register_ok"
}
```

**Layer 2: Plugin（接收方，GatewayConnection.onMessage）**
- 入站消息格式：同上
- 处理方法：
  1. 状态从 CONNECTED → READY
  2. 启动心跳定时器（30s 间隔）
  3. 开始处理业务消息（invoke 等）
- 存储操作：无

**完整报文示例：**

```
Gateway → Plugin:
{
  "type": "register_ok"
}
```

**关联消息：**
- 前置：`register`
- 后续：Plugin 开始发送 `heartbeat`；Gateway 向 Skill Server 发送 `agent_online`

---

## A.3 register_rejected

**概述：** Gateway 通知 Plugin 注册失败，Plugin 停止重连。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler）
- 触发条件：设备绑定验证失败或同 AK 已有活跃连接
- 生成方法：`handleRegister()` 中验证不通过时构造

**传播路径：**
Gateway → Plugin

**逐层处理：**

**Layer 1: Gateway（发送方）**
- 触发：`register` 验证失败
- 出站消息格式：
```json
{
  "type": "register_rejected",
  "reason": "duplicate_connection"
}
```
- 附加动作：关闭 WebSocket（自定义关闭码 4403 或 4409）

**Layer 2: Plugin（接收方）**
- 入站消息格式：同上
- 处理方法：
  1. 关闭 WebSocket 连接
  2. 停止自动重连（该 AK 无法注册）
- 存储操作：无

**完整报文示例：**

```
Gateway → Plugin (设备绑定失败):
{
  "type": "register_rejected",
  "reason": "device_binding_failed"
}

Gateway → Plugin (重复连接):
{
  "type": "register_rejected",
  "reason": "duplicate_connection"
}
```

**关联消息：**
- 前置：`register`
- 后续：无（连接终止）

---

## A.4 heartbeat

**概述：** Plugin 定时向 Gateway 发送心跳，维持 Agent 在线状态。

**产生源头：**
- 组件：Message Bridge Plugin（GatewayConnection）
- 触发条件：READY 状态下每 30 秒触发一次
- 生成方法：定时器回调构造

**传播路径：**
Plugin → Gateway

**逐层处理：**

**Layer 1: Plugin（发送方）**
- 触发：30s 定时器（仅 READY 状态）
- 出站消息格式：
```json
{
  "type": "heartbeat",
  "timestamp": "2024-03-20T10:30:00.000Z"
}
```

**Layer 2: Gateway（接收方，AgentWebSocketHandler.handleTextMessage）**
- 入站消息格式：同上
- 处理方法：`agentRegistryService.heartbeat(agentId)`
- 存储操作：MySQL `UPDATE agent_connection SET last_seen_at = NOW() WHERE id = ?`
- 超时检测（定时任务每 30s）：扫描 `last_seen_at < NOW() - 90s AND status = 'ONLINE'` → 标记 OFFLINE → 通知 Skill Server `agent_offline`

**完整报文示例：**

```
Plugin → Gateway:
{
  "type": "heartbeat",
  "timestamp": "2024-03-20T10:30:00.000Z"
}
```

**关联消息：**
- 前置：`register_ok`（进入 READY 状态后才开始发送）
- 后续：无（正常情况）；心跳超时 90s → `agent_offline`

---

## A.5 invoke

invoke 是 Skill Server 向 Plugin 发送指令的统一消息类型，通过 `action` 字段区分 6 种子类型。

---

### A.5.1 invoke(create_session)

**概述：** 指示 Plugin 在 OpenCode 中创建新的 CLI 会话。

**产生源头：**
- 组件：Skill Server（GatewayRelayService）
- 触发条件：
  1. Miniapp 创建会话时（POST /api/skill/sessions）
  2. IM 入站消息需要新会话时
  3. Session 重建时（toolSessionId 丢失）
- 生成方法：`GatewayRelayService.buildInvokeMessage()`

**传播路径：**
Skill Server → Gateway → Plugin → OpenCode SDK

**逐层处理：**

**Layer 1: Skill Server（发送方，GatewayWSClient）**
- 触发：会话创建请求
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "create_session",
  "payload": "{\"title\":\"会话标题\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 1
}
```

**Layer 2: Gateway（中转，SkillWebSocketHandler → SkillRelayService）**
- 入站消息格式：同上
- 处理方法：
  1. 验证 `source` 匹配连接绑定的 source
  2. 验证 `userId` 匹配 Redis `gw:agent:user:{ak}`
  3. 绑定 agent source：Redis `SET gw:agent:source:{ak} = source`
  4. 剥离路由上下文：`message.withoutRoutingContext()`（移除 userId、source）
  5. Redis `PUBLISH agent:{ak}` = 剥离后的消息
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "create_session",
  "payload": "{\"title\":\"会话标题\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 1
}
```

**Layer 3: Plugin（执行方，CreateSessionAction）**
- 入站消息格式：同上
- 处理方法：
  1. `DownstreamMessageNormalizer.normalize(message)` — 验证消息格式
  2. `BridgeRuntime.handleDownstreamMessage()` — 分发到 CreateSessionAction
  3. `client.session.create({ body: payload })` — 调用 OpenCode SDK
  4. 提取 sessionId（优先级：response.sessionId → response.id → response.data.sessionId → response.data.id）
- 成功 → 发送 `session_created`
- 失败 → 发送 `tool_error`

**Layer 4: OpenCode SDK**
- 请求：POST `/session`
- 请求体：`{ "title": "会话标题" }`
- 响应：`{ "sessionId": "new-opencode-session-uuid" }`

**完整报文示例：**

```
Skill Server → Gateway:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "create_session",
  "payload": "{\"title\":\"会话标题\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 1
}

Gateway → Plugin (剥离 userId/source):
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "create_session",
  "payload": "{\"title\":\"会话标题\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 1
}

Plugin → OpenCode SDK:
POST /session
{ "title": "会话标题" }
```

**关联消息：**
- 前置：Miniapp POST /api/skill/sessions 或 IM 入站消息
- 后续：`session_created`（成功）或 `tool_error`（失败）

---

### A.5.2 invoke(chat)

**概述：** 指示 Plugin 向 OpenCode 会话发送用户消息。

**产生源头：**
- 组件：Skill Server（GatewayRelayService）
- 触发条件：
  1. 用户在 Miniapp 发送消息（无 toolCallId）
  2. IM 入站消息（经上下文注入后）
  3. Session 重建后消费缓存消息
- 生成方法：`GatewayRelayService.buildInvokeMessage()`

**传播路径：**
Skill Server → Gateway → Plugin → OpenCode SDK

**逐层处理：**

**Layer 1: Skill Server（发送方）**
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "chat",
  "payload": "{\"text\":\"你好，请帮我分析代码\",\"toolSessionId\":\"opencode-session-uuid\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 2
}
```
- 特殊处理：若 `toolSessionId` 为空 → 不发送 chat，触发 Session 重建流程

**Layer 2: Gateway（中转）**
- 处理方法：同 A.5.1，验证 + 剥离路由上下文 + Redis 发布
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "chat",
  "payload": "{\"text\":\"你好，请帮我分析代码\",\"toolSessionId\":\"opencode-session-uuid\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 2
}
```

**Layer 3: Plugin（执行方，ChatAction）**
- 入站消息格式：同上
- 处理方法：
  1. `ToolDoneCompat.handleInvokeStarted()` — 记录 pending 状态
  2. `client.session.prompt({ path: { id: toolSessionId }, body: { parts: [{ type: 'text', text }] } })`
  3. 成功 → `ToolDoneCompat.handleInvokeCompleted()` → 可能发送 `tool_done`
  4. 失败 → `ToolDoneCompat.handleInvokeFailed()` → 发送 `tool_error`

**Layer 4: OpenCode SDK**
- 请求：POST `/session/{id}/prompt`
- 请求体：
```json
{
  "parts": [{ "type": "text", "text": "你好，请帮我分析代码" }]
}
```
- 响应：异步，结果通过事件流（message.part.delta 等）返回

**完整报文示例：**

```
Skill Server → Gateway:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "chat",
  "payload": "{\"text\":\"你好，请帮我分析代码\",\"toolSessionId\":\"opencode-session-uuid\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 2
}

Gateway → Plugin:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "chat",
  "payload": "{\"text\":\"你好，请帮我分析代码\",\"toolSessionId\":\"opencode-session-uuid\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNumber": 2
}

Plugin → OpenCode SDK:
POST /session/opencode-session-uuid/prompt
{
  "parts": [{ "type": "text", "text": "你好，请帮我分析代码" }]
}
```

**关联消息：**
- 前置：Miniapp POST /api/skill/sessions/{id}/messages 或 IM 入站
- 后续：OpenCode 产生大量事件（message.part.delta、message.part.updated 等），最终 `tool_done`

---

### A.5.3 invoke(question_reply)

**概述：** 将用户对 OpenCode 交互提问的回答传递给 Plugin 执行。

**产生源头：**
- 组件：Skill Server（GatewayRelayService）
- 触发条件：用户在 Miniapp 回答 OpenCode 的交互提问（POST /api/skill/sessions/{id}/messages 带 toolCallId）
- 生成方法：`SkillMessageController` 检测到 `toolCallId` 后路由为 question_reply

**传播路径：**
Miniapp → Skill Server → Gateway → Plugin → OpenCode SDK

**逐层处理：**

**Layer 1: Skill Server（发送方）**
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "question_reply",
  "payload": "{\"answer\":\"是\",\"toolCallId\":\"call_q1\",\"toolSessionId\":\"opencode-session-uuid\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Layer 2: Gateway（中转）**
- 处理：验证 + 剥离路由上下文 + Redis 发布
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "question_reply",
  "payload": "{\"answer\":\"是\",\"toolCallId\":\"call_q1\",\"toolSessionId\":\"opencode-session-uuid\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Layer 3: Plugin（执行方，QuestionReplyAction）**
- 处理方法：
  1. GET `/question` — 获取所有待答问题列表
  2. 筛选 `sessionID === toolSessionId`
  3. 若有 `toolCallId` → 精确匹配 `tool.callID`；若无 → 仅唯一匹配时使用
  4. POST `/question/{requestID}/reply` — 提交答案
- SDK 调用：
```
GET /question
POST /question/{requestID}/reply { "answers": [["是"]] }
```

**Layer 4: OpenCode SDK**
- 请求：POST `/question/{requestID}/reply`
- 请求体：`{ "answers": [["是"]] }`
- 注意：`answers` 是二维数组

**完整报文示例：**

```
Miniapp → Skill Server:
POST /api/skill/sessions/12345/messages
{
  "content": "是",
  "toolCallId": "call_q1"
}

Skill Server → Gateway:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "question_reply",
  "payload": "{\"answer\":\"是\",\"toolCallId\":\"call_q1\",\"toolSessionId\":\"opencode-session-uuid\"}",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}

Gateway → Plugin:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "question_reply",
  "payload": "{\"answer\":\"是\",\"toolCallId\":\"call_q1\",\"toolSessionId\":\"opencode-session-uuid\"}"
}

Plugin → OpenCode SDK:
GET /question
POST /question/req-001/reply
{ "answers": [["是"]] }
```

**关联消息：**
- 前置：`question`（StreamMessage）→ 用户在 Miniapp 上回答
- 后续：`tool_done`（成功）或 `tool_error`（失败）

---

### A.5.4 invoke(permission_reply)

**概述：** 将用户对权限请求的决策传递给 Plugin 执行。

**产生源头：**
- 组件：Skill Server（SkillSessionController）
- 触发条件：用户在 Miniapp 点击"允许一次"/"始终允许"/"拒绝"（POST /api/skill/sessions/{id}/permissions/{permId}）
- 生成方法：控制器构造 invoke 命令

**传播路径：**
Miniapp → Skill Server → Gateway → Plugin → OpenCode SDK

**逐层处理：**

**Layer 1: Skill Server（发送方）**
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "permission_reply",
  "payload": "{\"permissionId\":\"perm_abc123\",\"response\":\"once\",\"toolSessionId\":\"opencode-session-uuid\"}"
}
```
- 附加动作：同时推送 `permission.reply` StreamMessage 到前端 WebSocket

**Layer 2: Gateway（中转）**
- 处理：验证 + 剥离路由上下文 + Redis 发布
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "permission_reply",
  "payload": "{\"permissionId\":\"perm_abc123\",\"response\":\"once\",\"toolSessionId\":\"opencode-session-uuid\"}"
}
```

**Layer 3: Plugin（执行方，PermissionReplyAction）**
- 处理方法：`client.postSessionIdPermissionsPermissionId()`
- SDK 调用：POST `/session/{id}/permissions/{permissionId}`

**Layer 4: OpenCode SDK**
- 请求：POST `/session/{id}/permissions/{permissionId}`
- 请求体：`{ "response": "once" }`

**完整报文示例：**

```
Miniapp → Skill Server:
POST /api/skill/sessions/12345/permissions/perm_abc123
{ "response": "once" }

Skill Server → Gateway:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "permission_reply",
  "payload": "{\"permissionId\":\"perm_abc123\",\"response\":\"once\",\"toolSessionId\":\"opencode-session-uuid\"}"
}

Gateway → Plugin:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "permission_reply",
  "payload": "{\"permissionId\":\"perm_abc123\",\"response\":\"once\",\"toolSessionId\":\"opencode-session-uuid\"}"
}

Plugin → OpenCode SDK:
POST /session/opencode-session-uuid/permissions/perm_abc123
{ "response": "once" }
```

**关联消息：**
- 前置：`permission.ask`（StreamMessage）→ 用户在 Miniapp 操作
- 后续：`tool_done`（成功）或 `tool_error`（失败）；OpenCode 随后产生 `permission.updated` 事件

---

### A.5.5 invoke(close_session)

**概述：** 指示 Plugin 在 OpenCode 中关闭指定会话。

**产生源头：**
- 组件：Skill Server（SkillSessionController）
- 触发条件：用户在 Miniapp 删除会话（DELETE /api/skill/sessions/{id}）
- 生成方法：控制器在更新会话状态为 CLOSED 后构造 invoke 命令

**传播路径：**
Miniapp → Skill Server → Gateway → Plugin → OpenCode SDK

**逐层处理：**

**Layer 1: Skill Server（发送方）**
- 前置处理：验证会话所有权，更新数据库状态为 CLOSED
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "close_session",
  "payload": "{\"toolSessionId\":\"opencode-session-uuid\"}"
}
```

**Layer 2: Gateway（中转）**
- 处理：验证 + 剥离路由上下文 + Redis 发布

**Layer 3: Plugin（执行方，CloseSessionAction）**
- 处理方法：`client.session.delete({ path: { id: toolSessionId } })`

**Layer 4: OpenCode SDK**
- 请求：DELETE `/session/{id}`

**完整报文示例：**

```
Miniapp → Skill Server:
DELETE /api/skill/sessions/12345

Skill Server → Gateway:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "close_session",
  "payload": "{\"toolSessionId\":\"opencode-session-uuid\"}"
}

Gateway → Plugin:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "close_session",
  "payload": "{\"toolSessionId\":\"opencode-session-uuid\"}"
}

Plugin → OpenCode SDK:
DELETE /session/opencode-session-uuid
```

**关联消息：**
- 前置：用户 Miniapp 删除会话操作
- 后续：`tool_done`（成功）或 `tool_error`（失败）

---

### A.5.6 invoke(abort_session)

**概述：** 指示 Plugin 中止 OpenCode 当前正在执行的任务（不关闭会话）。

**产生源头：**
- 组件：Skill Server（SkillSessionController）
- 触发条件：用户在 Miniapp 点击"停止"按钮（POST /api/skill/sessions/{id}/abort）
- 生成方法：控制器构造 invoke 命令

**传播路径：**
Miniapp → Skill Server → Gateway → Plugin → OpenCode SDK

**逐层处理：**

**Layer 1: Skill Server（发送方）**
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "abort_session",
  "payload": "{\"toolSessionId\":\"opencode-session-uuid\"}"
}
```

**Layer 2: Gateway（中转）**
- 处理：验证 + 剥离路由上下文 + Redis 发布

**Layer 3: Plugin（执行方，AbortSessionAction）**
- 处理方法：`client.session.abort({ path: { id: toolSessionId } })`

**Layer 4: OpenCode SDK**
- 请求：POST `/session/{id}/abort`

**完整报文示例：**

```
Miniapp → Skill Server:
POST /api/skill/sessions/12345/abort

Skill Server → Gateway:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "abort_session",
  "payload": "{\"toolSessionId\":\"opencode-session-uuid\"}"
}

Gateway → Plugin:
{
  "type": "invoke",
  "ak": "agent-access-key",
  "welinkSessionId": "12345",
  "action": "abort_session",
  "payload": "{\"toolSessionId\":\"opencode-session-uuid\"}"
}

Plugin → OpenCode SDK:
POST /session/opencode-session-uuid/abort
```

**关联消息：**
- 前置：用户 Miniapp 停止操作
- 后续：`tool_done`（成功）或 `tool_error`（失败）

---

## A.6 tool_event

**概述：** Plugin 将 OpenCode SDK 产生的原始事件封装后透传给 Gateway，再中继到 Skill Server。

**产生源头：**
- 组件：Message Bridge Plugin（BridgeRuntime）
- 触发条件：OpenCode SDK 触发 allowlist 内的 11 种事件之一
- 生成方法：`BridgeRuntime.handleEvent()` → `UpstreamEventExtractor.extract()` → 构造 tool_event

**传播路径：**
Plugin → Gateway → Skill Server

**逐层处理：**

**Layer 1: Plugin（发送方）**
- 处理方法：
  1. `UpstreamEventExtractor.extract(rawEvent)` — 提取 common（eventType, toolSessionId）和 extra 字段
  2. Allowlist 检查 — 不在列表内的事件被丢弃
  3. 构造 tool_event 消息
- 出站消息格式：
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
      "delta": "你好"
    }
  }
}
```

**Layer 2: Gateway（中转，AgentWebSocketHandler → EventRelayService → SkillRelayService）**
- 入站消息格式：同上
- 处理方法：
  1. `ak = sessionAkMap[wsSessionId]`
  2. `message.ensureTraceId()` — 若无 traceId 则生成 UUID
  3. 注入 userId（Redis `gw:agent:user:{ak}`）
  4. 注入 source（Redis `gw:agent:source:{ak}`）
  5. `SkillRelayService.relayToSkill(message)` — 通过本地 link 或 Redis 中继发送
- 出站消息格式：
```json
{
  "type": "tool_event",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "toolSessionId": "opencode-session-uuid",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "partID": "part-001",
      "delta": "你好"
    }
  }
}
```

**Layer 3: Skill Server（接收方，GatewayMessageRouter.handleToolEvent）**
- 入站消息格式：同上
- 处理方法：
  1. 通过 `toolSessionId` 或 `welinkSessionId` 查找 SkillSession
  2. 激活空闲会话（IDLE → ACTIVE）
  3. `OpenCodeEventTranslator.translate(event, sessionId)` — 翻译为 StreamMessage
  4. completionCache 检查 — 若 5s 内已 tool_done，抑制非 question/permission 事件
  5. `ActiveMessageTracker` 分配 messageId / messageSeq / role
  6. `MessagePersistenceService` 持久化（仅 final 状态）
  7. 广播 StreamMessage（本地 WS + Redis pub/sub）
  8. 若 IM 会话 → `ImOutboundService.sendTextToIm()`
- 存储操作：MySQL `skill_messages` / `skill_message_parts`（final 状态时）

**完整报文示例：**

```
Plugin → Gateway:
{
  "type": "tool_event",
  "toolSessionId": "opencode-session-uuid",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "partID": "part-001",
      "delta": "你好"
    }
  }
}

Gateway → Skill Server (注入路由上下文):
{
  "type": "tool_event",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "toolSessionId": "opencode-session-uuid",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "partID": "part-001",
      "delta": "你好"
    }
  }
}

Skill Server → Miniapp (翻译后的 StreamMessage):
{
  "type": "text.delta",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-001",
  "content": "你好",
  "role": "assistant",
  "seq": 1,
  "emittedAt": "2024-03-20T10:30:01.000Z"
}
```

**关联消息：**
- 前置：invoke(chat) 触发 OpenCode 处理后产生的事件
- 后续：翻译为各种 StreamMessage（text.delta、tool.update、question 等）

---

## A.7 tool_done

**概述：** Plugin 通知 Gateway 和 Skill Server 当前 invoke 执行已完成。

**产生源头：**
- 组件：Message Bridge Plugin（ToolDoneCompat）
- 触发条件：
  1. chat Action 执行成功后（source: `invoke_complete`）
  2. session.idle 事件且无 pending chat（source: `session_idle`，兜底机制）
- 生成方法：`ToolDoneCompat` 状态机决策

**传播路径：**
Plugin → Gateway → Skill Server

**逐层处理：**

**Layer 1: Plugin（发送方）**
- 出站消息格式：
```json
{
  "type": "tool_done",
  "toolSessionId": "opencode-session-uuid",
  "welinkSessionId": "12345",
  "usage": {
    "inputTokens": 1500,
    "outputTokens": 800,
    "totalTokens": 2300
  }
}
```

**Layer 2: Gateway（中转）**
- 处理：同 tool_event，注入路由上下文后透传给 Skill Server
- 出站消息格式：
```json
{
  "type": "tool_done",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "toolSessionId": "opencode-session-uuid",
  "welinkSessionId": "12345",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "usage": {
    "inputTokens": 1500,
    "outputTokens": 800,
    "totalTokens": 2300
  }
}
```

**Layer 3: Skill Server（接收方，GatewayMessageRouter.handleToolDone）**
- 入站消息格式：同上
- 处理方法：
  1. 查找 SkillSession
  2. `completionCache.put(sessionId, TTL=5s)` — 标记完成，抑制迟到事件
  3. 清除 `TranslatorSessionCache(sessionId)`
  4. `ActiveMessageTracker.removeAndFinalize(sessionId)` — 结束活跃 assistant 消息
  5. 广播 `StreamMessage(type=session.status, sessionStatus=idle)`
  6. 更新 `SkillSession.status = IDLE`
  7. 消费待处理消息（`rebuildService.consumePendingMessage`）→ 若有则自动发送 chat invoke
- 存储操作：MySQL 更新 `skill_sessions.status = 'IDLE'`

**完整报文示例：**

```
Plugin → Gateway:
{
  "type": "tool_done",
  "toolSessionId": "opencode-session-uuid",
  "welinkSessionId": "12345",
  "usage": {
    "inputTokens": 1500,
    "outputTokens": 800,
    "totalTokens": 2300
  }
}

Gateway → Skill Server:
{
  "type": "tool_done",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "toolSessionId": "opencode-session-uuid",
  "welinkSessionId": "12345",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "usage": {
    "inputTokens": 1500,
    "outputTokens": 800,
    "totalTokens": 2300
  }
}

Skill Server → Miniapp (广播):
{
  "type": "session.status",
  "welinkSessionId": "12345",
  "sessionStatus": "idle",
  "seq": 42,
  "emittedAt": "2024-03-20T10:30:15.000Z"
}
```

**关联消息：**
- 前置：invoke(chat) 执行完成或 session.idle 事件
- 后续：`session.status(idle)` StreamMessage → Miniapp 调用 `finalizeAllStreamingMessages()`

---

## A.8 tool_error

**概述：** Plugin 通知 Gateway 和 Skill Server 当前 invoke 执行失败。

**产生源头：**
- 组件：Message Bridge Plugin（BridgeRuntime）
- 触发条件：Action 执行抛出异常或返回错误
- 生成方法：`BridgeRuntime.handleDownstreamMessage()` 中捕获异常后构造

**传播路径：**
Plugin → Gateway → Skill Server

**逐层处理：**

**Layer 1: Plugin（发送方）**
- reason 推断逻辑：错误信息包含 "not found"/"404"/"session_not_found"/"unexpected eof"/"json parse error" → `reason = "session_not_found"`；其他 → `reason = undefined`
- 出站消息格式：
```json
{
  "type": "tool_error",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "error": "session not found: opencode-session-uuid",
  "reason": "session_not_found"
}
```

**Layer 2: Gateway（中转）**
- 处理：同 tool_event，注入路由上下文后透传

**Layer 3: Skill Server（接收方，GatewayMessageRouter.handleToolError）**
- 入站消息格式（含 Gateway 注入字段）：
```json
{
  "type": "tool_error",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "error": "session not found: opencode-session-uuid",
  "reason": "session_not_found",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```
- 处理方法（按 reason 分支）：
  - `reason == "session_not_found"` → 触发 Session 重建：`rebuildService.rebuildToolSession()` → 广播 `session.status: retry`
  - 其他错误 → 广播 `StreamMessage(type=session.error, error=errorMessage)` → `ActiveMessageTracker.removeAndFinalize()`

**完整报文示例：**

```
Plugin → Gateway:
{
  "type": "tool_error",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "error": "session not found: opencode-session-uuid",
  "reason": "session_not_found"
}

Gateway → Skill Server:
{
  "type": "tool_error",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "error": "session not found: opencode-session-uuid",
  "reason": "session_not_found",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}

Skill Server → Miniapp (session_not_found 时广播 retry):
{
  "type": "session.status",
  "welinkSessionId": "12345",
  "sessionStatus": "retry",
  "seq": 43,
  "emittedAt": "2024-03-20T10:30:16.000Z"
}

Skill Server → Miniapp (其他错误时广播 error):
{
  "type": "session.error",
  "welinkSessionId": "12345",
  "error": "Agent execution failed: timeout",
  "seq": 43,
  "emittedAt": "2024-03-20T10:30:16.000Z"
}
```

**关联消息：**
- 前置：任意 invoke 执行失败
- 后续：`session.status(retry)` + `invoke(create_session)`（session_not_found 时）；或 `session.error`（其他错误）

---

## A.9 session_created

**概述：** Plugin 通知 Gateway 和 Skill Server 已在 OpenCode 中成功创建新会话。

**产生源头：**
- 组件：Message Bridge Plugin（CreateSessionAction）
- 触发条件：`invoke(create_session)` 执行成功
- 生成方法：`CreateSessionAction.execute()` 成功后构造

**传播路径：**
Plugin → Gateway → Skill Server

**逐层处理：**

**Layer 1: Plugin（发送方）**
- sessionId 提取优先级：`response.sessionId → response.id → response.data.sessionId → response.data.id`
- 出站消息格式：
```json
{
  "type": "session_created",
  "welinkSessionId": "12345",
  "toolSessionId": "new-opencode-session-uuid",
  "session": {
    "sessionId": "new-opencode-session-uuid"
  }
}
```

**Layer 2: Gateway（中转）**
- 处理：注入路由上下文后透传给 Skill Server

**Layer 3: Skill Server（接收方，GatewayMessageRouter.handleSessionCreated）**
- 入站消息格式：
```json
{
  "type": "session_created",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "welinkSessionId": "12345",
  "toolSessionId": "new-opencode-session-uuid",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "session": {
    "sessionId": "new-opencode-session-uuid"
  }
}
```
- 处理方法：
  1. 查找 SkillSession（by welinkSessionId）
  2. `sessionService.updateToolSessionId(numericSessionId, toolSessionId)` — 更新 DB
  3. `rebuildService.consumePendingMessage(sessionId)` — 从 Caffeine 缓存取出待处理消息
  4. 若有 pendingMessage → 自动发送 `invoke(action=chat, payload={text, toolSessionId})`
  5. 广播 `session.status: idle`（重建完成标记）
- 存储操作：MySQL 更新 `skill_sessions.tool_session_id`

**完整报文示例：**

```
Plugin → Gateway:
{
  "type": "session_created",
  "welinkSessionId": "12345",
  "toolSessionId": "new-opencode-session-uuid",
  "session": {
    "sessionId": "new-opencode-session-uuid"
  }
}

Gateway → Skill Server:
{
  "type": "session_created",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "source": "skill-server",
  "welinkSessionId": "12345",
  "toolSessionId": "new-opencode-session-uuid",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "session": {
    "sessionId": "new-opencode-session-uuid"
  }
}
```

**关联消息：**
- 前置：`invoke(create_session)`
- 后续：若有缓存消息 → `invoke(chat)`；广播 `session.status(idle)`

---

## A.10 agent_online

**概述：** Gateway 通知 Skill Server 有 Agent 成功注册上线。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler）
- 触发条件：Agent `register` 处理全部成功后
- 生成方法：Gateway **自动生成**（非 Agent 透传），`handleRegister()` 最后一步调用 `relayToSkillServer()`

**传播路径：**
Gateway → Skill Server → Miniapp

**逐层处理：**

**Layer 1: Gateway（发送方）**
- 出站消息格式：
```json
{
  "type": "agent_online",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "toolType": "opencode",
  "toolVersion": "1.4.0",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Layer 2: Skill Server（接收方，GatewayMessageRouter.handleAgentOnline）**
- 入站消息格式：同上
- 处理方法：广播 `StreamMessage(type=agent.online)` 到该用户的所有 WebSocket 连接

**Layer 3: Miniapp（接收方，useSkillStream）**
- 入站消息格式：
```json
{
  "type": "agent.online"
}
```
- 处理方法：`setAgentStatus('online')`

**完整报文示例：**

```
Gateway → Skill Server:
{
  "type": "agent_online",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "toolType": "opencode",
  "toolVersion": "1.4.0",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}

Skill Server → Miniapp:
{
  "type": "agent.online",
  "seq": 1,
  "emittedAt": "2024-03-20T10:30:00.000Z"
}
```

**关联消息：**
- 前置：`register` → `register_ok`
- 后续：Miniapp 轮询 agents 列表时可看到该 Agent 为 ONLINE

---

## A.11 agent_offline

**概述：** Gateway 通知 Skill Server 某 Agent 已离线。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler / 定时任务）
- 触发条件：
  1. Agent WebSocket 连接断开（`afterConnectionClosed`）
  2. 心跳超时（90s 未收到心跳）
- 生成方法：Gateway **自动生成**

**传播路径：**
Gateway → Skill Server → Miniapp

**逐层处理：**

**Layer 1: Gateway（发送方）**
- 处理方法：
  1. `AgentRegistryService.markOffline(agentId)` — 数据库状态更新
  2. `EventRelayService.removeAgentSession(ak)` — 清理本地注册
  3. 发送 `agent_offline` 给 Skill Server
- 出站消息格式：
```json
{
  "type": "agent_offline",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Layer 2: Skill Server（接收方，GatewayMessageRouter.handleAgentOffline）**
- 处理方法：广播 `StreamMessage(type=agent.offline)` 到该用户的所有 WebSocket 连接

**Layer 3: Miniapp（接收方）**
- 入站消息格式：
```json
{
  "type": "agent.offline"
}
```
- 处理方法：`setAgentStatus('offline')`；`useAgentSelector` 切换到第一个在线 Agent 或清空

**完整报文示例：**

```
Gateway → Skill Server:
{
  "type": "agent_offline",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}

Skill Server → Miniapp:
{
  "type": "agent.offline",
  "seq": 2,
  "emittedAt": "2024-03-20T10:31:30.000Z"
}
```

**关联消息：**
- 前置：WebSocket 断开或心跳超时
- 后续：Miniapp 自动切换 Agent 选择

---

## A.12 status_query

**概述：** Gateway 向 Plugin 发起健康检查请求，确认 OpenCode CLI 是否在线。

**产生源头：**
- 组件：AI Gateway（EventRelayService）
- 触发条件：REST API `GET /api/gateway/agents/status?ak={ak}` 被调用
- 生成方法：`EventRelayService.requestAgentStatus(ak)`

**传播路径：**
Gateway → Plugin

**逐层处理：**

**Layer 1: Gateway（发送方）**
- 出站消息格式：
```json
{
  "type": "status_query"
}
```
- 等待逻辑：等待 Plugin 回复 `status_response`，超时 1.5s 返回缓存值

**Layer 2: Plugin（接收方，StatusQueryAction）**
- 入站消息格式：同上
- 处理方法：
  1. `hostClient.global.health()` — 调用 OpenCode SDK 健康检查
  2. 构造 `status_response` 发送回 Gateway

**完整报文示例：**

```
Gateway → Plugin:
{
  "type": "status_query"
}
```

**关联消息：**
- 前置：REST API 调用
- 后续：`status_response`

---

## A.13 status_response

**概述：** Plugin 回复 Gateway 的健康检查，报告 OpenCode CLI 在线状态。

**产生源头：**
- 组件：Message Bridge Plugin（StatusQueryAction）
- 触发条件：收到 `status_query` 后
- 生成方法：`StatusQueryAction.execute()` 调用 SDK health 后构造

**传播路径：**
Plugin → Gateway

**逐层处理：**

**Layer 1: Plugin（发送方）**
- 出站消息格式：
```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

**Layer 2: Gateway（接收方，AgentWebSocketHandler.handleStatusResponse）**
- 入站消息格式：同上
- 处理方法：`EventRelayService.recordStatusResponse(ak, opencodeOnline)`
- 存储操作：`opencodeStatusCache[ak] = opencodeOnline`；完成对应的 `CompletableFuture`

**完整报文示例：**

```
Plugin → Gateway:
{
  "type": "status_response",
  "opencodeOnline": true
}
```

**关联消息：**
- 前置：`status_query`
- 后续：Gateway REST API 返回 `AgentStatusResponse`

---

## A.14 permission_request

**概述：** Plugin 将 OpenCode 的权限请求透传给 Gateway，再中继到 Skill Server。

**产生源头：**
- 组件：Message Bridge Plugin（通过 tool_event 透传）或 Gateway 内部处理
- 触发条件：OpenCode 需要用户授权某个操作（如文件写入）
- 生成方法：Plugin 可能通过 `permission.asked` 事件触发

**传播路径：**
Plugin → Gateway → Skill Server → Miniapp

**逐层处理：**

**Layer 1: Plugin（发送方）**
- 事件来源：OpenCode `permission.asked` 事件
- 封装为 tool_event 透传（实际上 permission_request 作为独立 GatewayMessage 类型由 Gateway 转发）

**Layer 2: Gateway（中转）**
- 入站/出站消息格式：
```json
{
  "type": "permission_request",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "permissionId": "perm_abc123",
  "command": "write /src/main.ts",
  "metadata": {
    "path": "/src/main.ts",
    "operation": "write"
  },
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Layer 3: Skill Server（接收方，GatewayMessageRouter.handlePermissionRequest）**
- 处理方法：翻译为 `StreamMessage(type=permission.ask)`
  - `permissionId` = 从消息提取
  - `permType` = metadata.operation 或 command
  - `title` = command
  - `metadata` = 原样透传
- 广播到该会话的所有前端 WebSocket 连接

**Layer 4: Miniapp（接收方）**
- 入站消息格式：
```json
{
  "type": "permission.ask",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_perm_1",
  "permissionId": "perm_abc123",
  "permType": "write",
  "toolName": "write",
  "title": "write /src/main.ts",
  "metadata": {
    "path": "/src/main.ts",
    "operation": "write"
  },
  "seq": 10,
  "emittedAt": "2024-03-20T10:30:05.000Z"
}
```
- 处理方法（StreamAssembler）：创建 Part(type=permission)，`permResolved = false`

**完整报文示例：**

```
Gateway → Skill Server:
{
  "type": "permission_request",
  "ak": "agent-access-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "permissionId": "perm_abc123",
  "command": "write /src/main.ts",
  "metadata": {
    "path": "/src/main.ts",
    "operation": "write"
  },
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}

Skill Server → Miniapp:
{
  "type": "permission.ask",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_perm_1",
  "permissionId": "perm_abc123",
  "permType": "write",
  "toolName": "write",
  "title": "write /src/main.ts",
  "metadata": {
    "path": "/src/main.ts",
    "operation": "write"
  },
  "seq": 10,
  "emittedAt": "2024-03-20T10:30:05.000Z"
}
```

**关联消息：**
- 前置：OpenCode `permission.asked` SDK 事件
- 后续：用户操作 → `invoke(permission_reply)` → OpenCode `permission.updated` 事件

---

# B. OpenCode SDK 事件类型

所有 SDK 事件的通用传播路径：
```
OpenCode CLI → Plugin (UpstreamEventExtractor 提取) → 封装为 tool_event
→ Gateway (透传 + 注入路由上下文) → Skill Server (OpenCodeEventTranslator 翻译)
→ StreamMessage → Miniapp (StreamAssembler 处理) → MessagePart / UI 状态
```

---

## B.1 message.part.delta

**概述：** OpenCode 生成消息片段的流式增量内容（文本或推理过程）。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：AI 模型生成文本或推理内容的每个 token 增量
- 生成方法：OpenCode SDK 事件回调

**传播路径：**
OpenCode → Plugin → Gateway → Skill Server → Miniapp

**逐层处理：**

**Layer 1: OpenCode CLI（产生）**
- 出站事件格式：
```json
{
  "type": "message.part.delta",
  "properties": {
    "sessionID": "opencode-session-uuid",
    "messageID": "msg-001",
    "partID": "part-text-001",
    "delta": "你好"
  }
}
```

**Layer 2: Plugin（UpstreamEventExtractor 提取 + 封装）**
- 提取：`toolSessionId = properties.sessionID`，`messageId = properties.messageID`，`partId = properties.partID`
- 封装为 `tool_event`，`event` 字段包含原始事件
- 出站：
```json
{
  "type": "tool_event",
  "toolSessionId": "opencode-session-uuid",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "partID": "part-text-001",
      "delta": "你好"
    }
  }
}
```

**Layer 3: Gateway（透传 + 注入路由上下文）**
- 注入 ak、userId、source、traceId
- 透传到 Skill Server

**Layer 4: Skill Server（OpenCodeEventTranslator.translate）**
- 翻译规则：根据 partType 区分
  - text part → `text.delta`
  - reasoning part → `thinking.delta`
- 字段映射：`delta → content`
- 出站 StreamMessage：
```json
{
  "type": "text.delta",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "part-text-001",
  "partSeq": 0,
  "content": "你好",
  "seq": 5,
  "emittedAt": "2024-03-20T10:30:01.000Z"
}
```

**Layer 5: Miniapp（StreamAssembler）**
- 获取/创建 partId 对应的 MessagePart（type=text 或 thinking）
- 追加 content 到该 Part
- 标记 `isStreaming = true`
- 更新 `activeMessageIdsRef`

**完整报文示例：**

```
OpenCode → Plugin:
{ "type": "message.part.delta", "properties": { "sessionID": "opencode-session-uuid", "messageID": "msg-001", "partID": "part-text-001", "delta": "你好" } }

Plugin → Gateway:
{ "type": "tool_event", "toolSessionId": "opencode-session-uuid", "event": { "type": "message.part.delta", "properties": { "sessionID": "opencode-session-uuid", "messageID": "msg-001", "partID": "part-text-001", "delta": "你好" } } }

Gateway → Skill Server:
{ "type": "tool_event", "ak": "agent-access-key", "userId": "owner-welink-id", "source": "skill-server", "toolSessionId": "opencode-session-uuid", "traceId": "uuid-trace", "event": { "type": "message.part.delta", "properties": { "sessionID": "opencode-session-uuid", "messageID": "msg-001", "partID": "part-text-001", "delta": "你好" } } }

Skill Server → Miniapp:
{ "type": "text.delta", "welinkSessionId": "12345", "messageId": "msg-001", "messageSeq": 2, "role": "assistant", "partId": "part-text-001", "partSeq": 0, "content": "你好", "seq": 5, "emittedAt": "2024-03-20T10:30:01.000Z" }
```

**关联消息：**
- 前置：`invoke(chat)` 触发 OpenCode 处理
- 后续：更多 `message.part.delta` → 最终 `message.part.updated`（text/reasoning）

---

## B.2 message.part.updated

message.part.updated 根据 `part.type` 有 7 种子类型，分别描述。

### B.2.1 message.part.updated (partType=text)

**概述：** 一个文本片段的完整内容已确定。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：文本生成完成，完整内容确定

**翻译结果：** `text.done` StreamMessage

**OpenCode 原始事件：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-text-001",
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "type": "text",
      "text": "你好，我是 AI 助手。很高兴为你服务。"
    }
  }
}
```

**Skill Server 翻译：** `part.text → content`，type → `text.done`

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "text.done",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-text-001",
  "partSeq": 0,
  "content": "你好，我是 AI 助手。很高兴为你服务。",
  "seq": 12,
  "emittedAt": "2024-03-20T10:30:05.000Z"
}
```

**Miniapp 处理：** 替换为完整内容，`isStreaming = false`

**关联消息：**
- 前置：多个 `message.part.delta`
- 后续：`message.updated`（finish），`tool_done`

---

### B.2.2 message.part.updated (partType=reasoning)

**概述：** 一个推理/思考片段的完整内容已确定。

**翻译结果：** `thinking.done` StreamMessage

**OpenCode 原始事件：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-reasoning-001",
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "type": "reasoning",
      "text": "让我分析一下这个问题，用户需要..."
    }
  }
}
```

**Skill Server 翻译：** `part.text → content`，type → `thinking.done`

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "thinking.done",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-reasoning-001",
  "partSeq": 0,
  "content": "让我分析一下这个问题，用户需要...",
  "seq": 8,
  "emittedAt": "2024-03-20T10:30:03.000Z"
}
```

**关联消息：**
- 前置：多个 `message.part.delta`（reasoning）
- 后续：通常在文本输出之前完成

---

### B.2.3 message.part.updated (partType=tool)

**概述：** 工具（bash、read、write 等）执行状态变更。

**翻译结果：** `tool.update` StreamMessage（toolName != "question" 时）

**OpenCode 原始事件：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-tool-001",
      "sessionID": "opencode-session-uuid",
      "messageID": "msg-001",
      "type": "tool",
      "tool": "bash",
      "callID": "call-001",
      "state": {
        "status": "completed",
        "input": { "command": "ls -la" },
        "output": "total 24\ndrwxr-xr-x  6 user ...",
        "error": null,
        "title": "Execute: ls -la"
      }
    }
  }
}
```

**Skill Server 翻译：**
```
toolName = part.tool = "bash"
toolCallId = part.callID = "call-001"
status = state.status = "completed"
input = state.input
output = state.output
title = state.title
partId = part.id
```

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "tool.update",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-tool-001",
  "partSeq": 1,
  "toolName": "bash",
  "toolCallId": "call-001",
  "status": "completed",
  "input": { "command": "ls -la" },
  "output": "total 24\ndrwxr-xr-x  6 user ...",
  "title": "Execute: ls -la",
  "seq": 10,
  "emittedAt": "2024-03-20T10:30:04.000Z"
}
```

**关联消息：**
- 前置：工具调用请求
- 后续：通常跟随更多文本输出

---

### B.2.4 message.part.updated (partType=tool, toolName=question)

**概述：** Question 工具状态变更（特殊处理逻辑）。

**翻译规则：**
- `status = pending/running` → **返回 null（跳过）**，等待 `question.asked` 事件
- `status = completed/error` → 翻译为 `question` StreamMessage

**OpenCode 原始事件（completed 状态）：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "type": "tool",
      "tool": "question",
      "callID": "call-q1",
      "state": {
        "status": "completed",
        "input": { "question": "确认删除？", "options": ["是", "否"] },
        "output": "是"
      }
    }
  }
}
```

**Skill Server 翻译：**
- type → `question`
- status → "completed"
- output → `normalizeQuestionAnswerOutput(state.output, input)`
- questionInfo → 从缓存获取（question.asked 时已缓存）
- partId → 从缓存 callId→partId 映射获取

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "question",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_question_1",
  "toolName": "question",
  "toolCallId": "call-q1",
  "status": "completed",
  "output": "是",
  "header": "确认操作",
  "question": "确认删除？",
  "options": ["是", "否"],
  "seq": 15,
  "emittedAt": "2024-03-20T10:30:08.000Z"
}
```

**关联消息：**
- 前置：`question.asked` 事件（提供问题内容）
- 后续：用户回答后 question 状态从 running → completed

---

### B.2.5 message.part.updated (partType=step-start)

**概述：** 一个处理步骤开始。

**翻译结果：** `step.start` StreamMessage

**OpenCode 原始事件：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "type": "step-start"
    }
  }
}
```

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "step.start",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "seq": 3,
  "emittedAt": "2024-03-20T10:30:00.500Z"
}
```

**Miniapp 处理：** 不创建 MessagePart；将 messageId 加入 `activeMessageIdsRef`，设 `isStreaming = true`

---

### B.2.6 message.part.updated (partType=step-finish)

**概述：** 一个处理步骤完成，包含 Token 使用统计。

**翻译结果：** `step.done` StreamMessage

**OpenCode 原始事件：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "type": "step-finish",
      "usage": {
        "inputTokens": 1500,
        "outputTokens": 800,
        "cacheReadInputTokens": 500,
        "cacheCreationInputTokens": 200
      },
      "cost": 0.0125,
      "finishReason": "stop"
    }
  }
}
```

**Skill Server 翻译字段映射：**
```
usage.inputTokens → tokens.input
usage.outputTokens → tokens.output
usage.cacheReadInputTokens → tokens.cache.read
usage.cacheCreationInputTokens → tokens.cache.write
cost → cost
finishReason → reason
```

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "step.done",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "tokens": {
    "input": 1500,
    "output": 800,
    "reasoning": 0,
    "cache": { "read": 500, "write": 200 }
  },
  "cost": 0.0125,
  "reason": "stop",
  "seq": 14,
  "emittedAt": "2024-03-20T10:30:06.000Z"
}
```

**Miniapp 处理：** 不创建 MessagePart；更新 `message.meta` 中的 tokens、cost、reason

**存储操作：** MySQL `skill_message_parts` 保存 step-finish part（含 tokens_in、tokens_out、cost、finish_reason）

---

### B.2.7 message.part.updated (partType=file)

**概述：** 文件附件输出。

**翻译结果：** `file` StreamMessage

**OpenCode 原始事件：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "type": "file",
      "fileName": "output.png",
      "fileUrl": "/files/output.png",
      "fileMime": "image/png"
    }
  }
}
```

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "file",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_file_1",
  "fileName": "output.png",
  "fileUrl": "/files/output.png",
  "fileMime": "image/png",
  "seq": 11,
  "emittedAt": "2024-03-20T10:30:04.500Z"
}
```

**Miniapp 处理：** 创建 Part(type=file)，`isStreaming = false`

---

## B.3 message.part.removed

**概述：** OpenCode 移除了一个消息片段。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：消息片段被删除（如撤销、重新生成等）

**OpenCode 原始事件：**
```json
{
  "type": "message.part.removed",
  "properties": {
    "sessionID": "opencode-session-uuid",
    "messageID": "msg-001",
    "partID": "part-001"
  }
}
```

**Skill Server 翻译：** 返回 null（不推送前端），仅清除 `TranslatorSessionCache` 中相关缓存

**Miniapp 处理：** 无（不会收到此事件）

**关联消息：**
- 前置：某个 message.part.updated 或 message.part.delta
- 后续：无

---

## B.4 message.updated

**概述：** 整条消息元数据更新（包含 role、finish 等信息）。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：消息完整性确定（role 确认、finish 原因确定等）

**OpenCode 原始事件：**
```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-001",
      "sessionID": "opencode-session-uuid",
      "role": "assistant",
      "finish": "stop"
    }
  }
}
```

**Skill Server 翻译规则（复杂的时序处理）：**

| 场景 | 翻译结果 |
|------|---------|
| `role=user` 且有缓存文本 | `text.done`（从缓存获取内容） |
| `role=user` 且无缓存文本 | null（缓存 role，等待 part） |
| 有 `finish` 字段 | `step.done`（`finish → reason`） |

**TranslatorSessionCache 双缓存机制：**
- `message.part.updated`(text) 先到 → 缓存文本 → 等 `message.updated` 确认 role
- `message.updated`(role=user) 先到 → 缓存 role → 等 `message.part.updated` 提供文本
- 详见 A.6 中 Skill Server 的翻译规则

**Miniapp 出站 StreamMessage（assistant finish 场景）：**
```json
{
  "type": "step.done",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "reason": "stop",
  "seq": 13,
  "emittedAt": "2024-03-20T10:30:05.500Z"
}
```

**关联消息：**
- 前置：多个 message.part.delta / message.part.updated
- 后续：`tool_done` / `session.idle`

---

## B.5 session.status

**概述：** OpenCode 会话整体状态变更（busy/idle 等）。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：会话从空闲变为忙碌、从忙碌变为空闲等

**OpenCode 原始事件：**
```json
{
  "type": "session.status",
  "properties": {
    "sessionID": "opencode-session-uuid",
    "status": {
      "type": "busy"
    }
  }
}
```

**Skill Server 翻译：** `status.type → sessionStatus`，type → `session.status`

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "session.status",
  "welinkSessionId": "12345",
  "sessionStatus": "busy",
  "seq": 4,
  "emittedAt": "2024-03-20T10:30:00.200Z"
}
```

**Miniapp 处理：**
- `busy` → `setIsStreaming(true)`
- `idle` → `finalizeAllStreamingMessages()`
- `retry` → `setIsStreaming(true)`
- `completed` → 同 `idle`

---

## B.6 session.idle

**概述：** OpenCode 会话进入空闲状态（专门的空闲事件）。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：会话处理完成，进入空闲

**OpenCode 原始事件：**
```json
{
  "type": "session.idle",
  "properties": {
    "sessionID": "opencode-session-uuid"
  }
}
```

**Plugin 特殊处理：**
1. 正常封装为 `tool_event` 发送
2. 额外触发 `ToolDoneCompat.handleSessionIdle(toolSessionId)` — 可能发送 `tool_done`（兜底机制）

**Skill Server 翻译：** 硬编码 `sessionStatus = "idle"`，type → `session.status`

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "session.status",
  "welinkSessionId": "12345",
  "sessionStatus": "idle",
  "seq": 20,
  "emittedAt": "2024-03-20T10:30:10.000Z"
}
```

**关联消息：**
- 前置：所有消息/工具处理完成
- 后续：Miniapp `finalizeAllStreamingMessages()`

---

## B.7 session.updated

**概述：** OpenCode 会话元数据更新（如标题自动生成）。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：会话标题等元数据变更

**OpenCode 原始事件：**
```json
{
  "type": "session.updated",
  "properties": {
    "info": {
      "id": "opencode-session-uuid",
      "title": "讨论项目架构设计"
    }
  }
}
```

**Skill Server 翻译：** `info.title → title`，type → `session.title`

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "session.title",
  "welinkSessionId": "12345",
  "title": "讨论项目架构设计",
  "seq": 6,
  "emittedAt": "2024-03-20T10:30:02.000Z"
}
```

---

## B.8 session.error

**概述：** OpenCode 会话发生错误（如上下文溢出）。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：会话级别错误（上下文窗口溢出、SDK 异常等）

**OpenCode 原始事件：**
```json
{
  "type": "session.error",
  "properties": {
    "sessionID": "opencode-session-uuid",
    "error": "Context window exceeded"
  }
}
```

**Skill Server 翻译：** `error → error`，type → `session.error`

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "session.error",
  "welinkSessionId": "12345",
  "error": "Context window exceeded",
  "seq": 21,
  "emittedAt": "2024-03-20T10:30:11.000Z"
}
```

**Miniapp 处理：** `finalizeAllStreamingMessages()` + `setError(msg.error)`

---

## B.9 question.asked

**概述：** OpenCode Agent 向用户提出交互式问题。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：Agent 需要用户确认或输入（如文件操作确认）

**OpenCode 原始事件：**
```json
{
  "type": "question.asked",
  "properties": {
    "sessionID": "opencode-session-uuid",
    "questions": [{
      "requestID": "req-001",
      "tool": { "callID": "call-q1" },
      "question": "你确定要删除这个文件吗？",
      "options": ["是", "否"],
      "header": "确认操作"
    }]
  }
}
```

**Skill Server 翻译：** 取 `questions[0]`，type → `question`
- `toolCallId = tool.callID`
- `status = "running"`（等待回答）
- `questionInfo = { header, question, options }`
- 缓存 questionInfo 供后续 tool/question completed 使用

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "question",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_question_1",
  "toolName": "question",
  "toolCallId": "call-q1",
  "status": "running",
  "header": "确认操作",
  "question": "你确定要删除这个文件吗？",
  "options": ["是", "否"],
  "seq": 9,
  "emittedAt": "2024-03-20T10:30:03.500Z"
}
```

**Miniapp 处理（StreamAssembler）：**
1. 创建 Part(type=question)
2. 提取 header, question, options
3. `content = question` 文本
4. `isStreaming = false`（问题立即显示完整）
5. `answered = false`

**关联消息：**
- 前置：工具执行需要用户确认
- 后续：用户回答 → `invoke(question_reply)` → `message.part.updated(tool/question, completed)`

---

## B.10 permission.asked

**概述：** OpenCode 请求用户授权某个操作。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：Agent 需要执行需要授权的操作（如文件写入、命令执行）

**OpenCode 原始事件：**
```json
{
  "type": "permission.asked",
  "properties": {
    "sessionID": "opencode-session-uuid",
    "permissionID": "perm-001",
    "tool": "write",
    "path": "/src/main.ts",
    "operation": "write"
  }
}
```

**Skill Server 翻译：** type → `permission.ask`
- `permissionId = properties.permissionID`
- `permType = properties.operation`
- `toolName = properties.tool`

**Miniapp 出站 StreamMessage：**
```json
{
  "type": "permission.ask",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_perm_1",
  "permissionId": "perm-001",
  "permType": "write",
  "toolName": "write",
  "title": "请求写入文件: /src/main.ts",
  "metadata": {
    "path": "/src/main.ts",
    "operation": "write"
  },
  "seq": 7,
  "emittedAt": "2024-03-20T10:30:02.500Z"
}
```

**Miniapp 处理：** 创建 Part(type=permission)，`permResolved = false`，`isStreaming = false`

**关联消息：**
- 前置：Agent 执行需授权的操作
- 后续：用户操作 → `invoke(permission_reply)` → `permission.updated`

---

## B.11 permission.updated

**概述：** 权限决策状态变更。

**产生源头：**
- 组件：OpenCode CLI
- 触发条件：用户授权或拒绝权限请求后，OpenCode 更新权限状态

**OpenCode 原始事件：**
```json
{
  "type": "permission.updated",
  "properties": {
    "sessionID": "opencode-session-uuid",
    "permissionID": "perm-001",
    "status": "granted",
    "response": "once"
  }
}
```

**Skill Server 翻译：** 根据状态决定类型
- 待决定状态 → `permission.ask`
- 已决定状态（granted/rejected）→ `permission.reply`

**Miniapp 出站 StreamMessage（已决定时）：**
```json
{
  "type": "permission.reply",
  "welinkSessionId": "12345",
  "permissionId": "perm-001",
  "response": "once",
  "seq": 16,
  "emittedAt": "2024-03-20T10:30:09.000Z"
}
```

**Miniapp 处理：** 查找已存在的权限 Part → `permResolved = true`，`permissionResponse = response`

---

# C. StreamMessage 类型

所有 StreamMessage 的通用传播路径：
```
Skill Server (OpenCodeEventTranslator / GatewayMessageRouter)
  → 广播 (SkillStreamHandler 本地 WS + RedisMessageBroker 跨实例)
  → Miniapp WebSocket (/ws/skill/stream)
  → StreamAssembler → MessagePart / UI 状态变更
```

---

## C.1 text.delta

**概述：** 流式文本增量，AI 回复内容的逐步推送。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `message.part.delta`（text 类型 part）
- 生成方法：`translate()` 将 `delta` 映射为 `content`

**传播路径：**
Skill Server → WebSocket/Redis → Miniapp StreamAssembler

**Skill Server 出站：**
```json
{
  "type": "text.delta",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "part-text-001",
  "partSeq": 0,
  "content": "你好，",
  "seq": 5,
  "emittedAt": "2024-03-20T10:30:01.000Z"
}
```

**Miniapp 处理（StreamAssembler）：**
1. 获取/创建 `partId` 对应的 MessagePart（type=text）
2. 追加 `content` 到该 Part（`part.content += msg.content`）
3. 标记 `isStreaming = true`
4. 更新 `activeMessageIdsRef`
5. 触发 UI 重渲染

**存储操作：** 无（仅 final 状态持久化）

**关联消息：**
- 前置：`invoke(chat)` → OpenCode `message.part.delta`
- 后续：更多 `text.delta` → `text.done`

---

## C.2 text.done

**概述：** 文本片段完成，包含完整的最终内容。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `message.part.updated`(type=text) 或 `message.updated`(role=user + 缓存文本)

**Skill Server 出站：**
```json
{
  "type": "text.done",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-text-001",
  "partSeq": 0,
  "content": "你好，我是 AI 助手。很高兴为你服务。",
  "seq": 12,
  "emittedAt": "2024-03-20T10:30:05.000Z"
}
```

**Miniapp 处理（StreamAssembler）：**
1. 若 `msg.content` 存在 → 替换为完整内容（否则保持增量拼接结果）
2. 标记 `isStreaming = false`
3. 从 `activeMessageIdsRef` 移除（若无其他活跃 Part）

**存储操作：** MySQL `skill_message_parts` 保存 text part

**关联消息：**
- 前置：多个 `text.delta`
- 后续：`step.done`，`session.status(idle)`

---

## C.3 thinking.delta

**概述：** 思考/推理过程的流式增量（Claude Extended Thinking）。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `message.part.delta`（reasoning 类型 part）

**Skill Server 出站：**
```json
{
  "type": "thinking.delta",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-reasoning-001",
  "partSeq": 0,
  "content": "让我分析一下这个问题...",
  "seq": 3,
  "emittedAt": "2024-03-20T10:30:00.500Z"
}
```

**Miniapp 处理：** 与 `text.delta` 相同，但 Part type 为 `thinking`

**关联消息：**
- 前置：`invoke(chat)` → OpenCode 触发 Extended Thinking
- 后续：更多 `thinking.delta` → `thinking.done`

---

## C.4 thinking.done

**概述：** 思考/推理片段完成。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `message.part.updated`(type=reasoning)

**Skill Server 出站：**
```json
{
  "type": "thinking.done",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-reasoning-001",
  "partSeq": 0,
  "content": "让我分析一下这个问题，用户需要一个文件操作的确认流程...",
  "seq": 8,
  "emittedAt": "2024-03-20T10:30:03.000Z"
}
```

**Miniapp 处理：** 与 `text.done` 相同，Part type 为 `thinking`

**存储操作：** MySQL `skill_message_parts` 保存 reasoning part

---

## C.5 tool.update

**概述：** 工具执行状态变更通知（pending → running → completed/error）。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `message.part.updated`(type=tool, tool != "question")

**Skill Server 出站（各状态示例）：**

pending（排队中）：
```json
{
  "type": "tool.update",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part-tool-001",
  "partSeq": 1,
  "toolName": "bash",
  "toolCallId": "call-001",
  "status": "pending",
  "title": "执行命令: ls -la",
  "input": { "command": "ls -la" },
  "seq": 7,
  "emittedAt": "2024-03-20T10:30:02.500Z"
}
```

running（执行中）：
```json
{
  "type": "tool.update",
  "welinkSessionId": "12345",
  "toolCallId": "call-001",
  "status": "running",
  "seq": 8,
  "emittedAt": "2024-03-20T10:30:03.000Z"
}
```

completed（完成）：
```json
{
  "type": "tool.update",
  "welinkSessionId": "12345",
  "toolCallId": "call-001",
  "status": "completed",
  "output": "total 24\ndrwxr-xr-x  6 user ...",
  "title": "执行命令: ls -la",
  "seq": 9,
  "emittedAt": "2024-03-20T10:30:04.000Z"
}
```

error（错误）：
```json
{
  "type": "tool.update",
  "welinkSessionId": "12345",
  "toolCallId": "call-001",
  "status": "error",
  "content": "Command timed out after 120000ms",
  "seq": 9,
  "emittedAt": "2024-03-20T10:30:04.000Z"
}
```

**Miniapp 处理（StreamAssembler）：**
1. 创建/获取 Part（type=tool）
2. 更新字段：toolName, toolCallId, toolStatus, toolTitle, toolInput, toolOutput
3. `isStreaming = (status === 'pending' || status === 'running')`
4. 若 error 存在 → 存入 content

**UI 渲染（ToolUseRenderer）：**
- 标题格式：`"Tool: {toolName} [{状态标签}]"`
- 状态标签：pending→等待中，running→运行中...，completed→完成，error→错误
- 内容：优先显示 output（JSON 美化），其次 input

**存储操作：** MySQL `skill_message_parts` 保存 tool part（仅 completed/error 状态）

**关联消息：**
- 前置：Agent 调用工具
- 后续：通常跟随更多文本输出或新的工具调用

---

## C.6 question

**概述：** Agent 向用户提出交互式问题，等待回答。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：
  1. 翻译 OpenCode `question.asked` 事件（status=running，等待回答）
  2. 翻译 OpenCode `message.part.updated`(tool=question, status=completed/error)（已回答）

**Skill Server 出站（等待回答）：**
```json
{
  "type": "question",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_question_1",
  "toolName": "question",
  "toolCallId": "call-q1",
  "status": "running",
  "header": "确认操作",
  "question": "你确定要删除这个文件吗？",
  "options": ["是", "否"],
  "seq": 9,
  "emittedAt": "2024-03-20T10:30:03.500Z"
}
```

**Skill Server 出站（已回答）：**
```json
{
  "type": "question",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_question_1",
  "toolName": "question",
  "toolCallId": "call-q1",
  "status": "completed",
  "output": "是",
  "header": "确认操作",
  "question": "你确定要删除这个文件吗？",
  "options": ["是", "否"],
  "seq": 18,
  "emittedAt": "2024-03-20T10:30:10.000Z"
}
```

**Miniapp 处理（StreamAssembler）：**
1. 创建 Part（type=question）
2. 提取 header, question, options
3. `content = question` 文本
4. `isStreaming = false`（问题立即显示完整）
5. 若 `status=completed/error` → `answered = true`

**用户回复流程：**
- 用户选择/输入后 → `sendMessage(answer, { toolCallId: "call-q1" })`
- POST `/sessions/{id}/messages` 带 `toolCallId`
- Skill Server 路由为 `question_reply` 动作

**存储操作：** MySQL `skill_message_parts` 保存 tool part（toolName=question，completed/error 状态）

**关联消息：**
- 前置：工具执行需要用户输入
- 后续：用户回答 → `invoke(question_reply)` → Agent 继续执行

---

## C.7 file

**概述：** 文件附件输出。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `message.part.updated`(type=file)

**Skill Server 出站：**
```json
{
  "type": "file",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_file_1",
  "fileName": "output.png",
  "fileUrl": "/files/output.png",
  "fileMime": "image/png",
  "seq": 11,
  "emittedAt": "2024-03-20T10:30:04.500Z"
}
```

**Miniapp 处理：** 创建 Part（type=file），`isStreaming = false`

**存储操作：** MySQL `skill_message_parts` 保存 file part

---

## C.8 step.start

**概述：** 一个处理步骤开始标记。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `message.part.updated`(type=step-start)

**Skill Server 出站：**
```json
{
  "type": "step.start",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "seq": 3,
  "emittedAt": "2024-03-20T10:30:00.500Z"
}
```

**Miniapp 处理：**
- **不创建 MessagePart**
- 将 `messageId` 加入 `activeMessageIdsRef`
- 设置 `isStreaming = true`

**关联消息：**
- 前置：新的 AI 处理步骤开始
- 后续：多个 text.delta、tool.update 等 → `step.done`

---

## C.9 step.done

**概述：** 一个处理步骤完成，包含 Token 使用统计和费用。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：
  1. 翻译 OpenCode `message.part.updated`(type=step-finish)
  2. 翻译 OpenCode `message.updated`(有 finish 字段)

**Skill Server 出站：**
```json
{
  "type": "step.done",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "tokens": {
    "input": 1500,
    "output": 800,
    "reasoning": 2000,
    "cache": { "read": 500, "write": 200 }
  },
  "cost": 0.0125,
  "reason": "stop",
  "seq": 14,
  "emittedAt": "2024-03-20T10:30:06.000Z"
}
```

**Miniapp 处理：**
- **不创建 MessagePart**
- 更新 `message.meta` 中的 tokens, cost, reason

**存储操作：** MySQL `skill_message_parts` 保存 step-finish part（tokens_in, tokens_out, cost, finish_reason）

**关联消息：**
- 前置：`step.start` + 一系列处理事件
- 后续：新的 `step.start` 或 `session.status(idle)`

---

## C.10 session.status

**概述：** 会话整体状态变更，控制 Miniapp 的流式状态。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator / GatewayMessageRouter）
- 触发条件：
  1. 翻译 OpenCode `session.status` 事件
  2. 翻译 OpenCode `session.idle` 事件（硬编码 idle）
  3. `handleToolDone()` 广播 idle
  4. Session 重建时广播 retry

**Skill Server 出站：**
```json
{
  "type": "session.status",
  "welinkSessionId": "12345",
  "sessionStatus": "idle",
  "seq": 20,
  "emittedAt": "2024-03-20T10:30:10.000Z"
}
```

**sessionStatus 取值及 Miniapp 处理：**

| 值 | 含义 | 处理 |
|----|------|------|
| `busy` | Agent 正在处理 | `setIsStreaming(true)` |
| `idle` | Agent 处理完毕 | `finalizeAllStreamingMessages()` — 所有 Part `isStreaming=false`，清空 `activeMessageIdsRef`，`setIsStreaming(false)` |
| `retry` | Session 重建中 | `setIsStreaming(true)` |
| `completed` | 会话彻底结束 | 同 idle |

**关联消息：**
- 前置：`invoke(chat)` 执行或 `tool_done` 到达
- 后续：idle 后 Miniapp 进入等待输入状态

---

## C.11 session.title

**概述：** 会话标题更新。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator）
- 触发条件：翻译 OpenCode `session.updated`（info.title 变更）

**Skill Server 出站：**
```json
{
  "type": "session.title",
  "welinkSessionId": "12345",
  "title": "讨论项目架构设计",
  "seq": 6,
  "emittedAt": "2024-03-20T10:30:02.000Z"
}
```

**Miniapp 处理：** 更新会话列表中对应会话的标题显示

---

## C.12 session.error

**概述：** 会话级别错误通知。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator / GatewayMessageRouter）
- 触发条件：
  1. 翻译 OpenCode `session.error` 事件
  2. `handleToolError()` 处理非 session_not_found 错误

**Skill Server 出站：**
```json
{
  "type": "session.error",
  "welinkSessionId": "12345",
  "error": "Context window exceeded",
  "seq": 21,
  "emittedAt": "2024-03-20T10:30:11.000Z"
}
```

**Miniapp 处理：** `finalizeAllStreamingMessages()` + `setError(msg.error)`

---

## C.13 permission.ask

**概述：** 权限请求通知，要求用户授权操作。

**产生源头：**
- 组件：Skill Server（OpenCodeEventTranslator / GatewayMessageRouter）
- 触发条件：
  1. 翻译 OpenCode `permission.asked` 事件
  2. 翻译 OpenCode `permission.updated`（待决定状态）
  3. 处理 GatewayMessage `permission_request`

**Skill Server 出站：**
```json
{
  "type": "permission.ask",
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "partId": "part_perm_1",
  "permissionId": "perm_abc123",
  "permType": "write",
  "toolName": "write",
  "title": "请求写入文件: /src/main.ts",
  "metadata": {
    "path": "/src/main.ts",
    "operation": "write"
  },
  "seq": 7,
  "emittedAt": "2024-03-20T10:30:02.500Z"
}
```

**Miniapp 处理（StreamAssembler）：**
1. 创建 Part（type=permission）
2. 设置 permissionId, permType, toolName
3. `content = msg.title ?? msg.content`
4. `permResolved = false`
5. `isStreaming = false`

**用户响应流程：**
- 点击"允许一次"/"始终允许"/"拒绝"
- 调用 `replyPermission(permId, 'once'|'always'|'reject')`
- POST `/sessions/{id}/permissions/{permId}`

**存储操作：** MySQL `skill_message_parts` 保存 permission part

**关联消息：**
- 前置：Agent 执行需授权操作
- 后续：`permission.reply`

---

## C.14 permission.reply

**概述：** 权限已回复通知，更新权限 Part 状态。

**产生源头：**
- 组件：Skill Server
- 触发条件：
  1. 用户提交权限回复后，Skill Server 同时推送到前端
  2. 翻译 OpenCode `permission.updated`（已决定状态）

**Skill Server 出站：**
```json
{
  "type": "permission.reply",
  "welinkSessionId": "12345",
  "permissionId": "perm_abc123",
  "response": "once",
  "seq": 16,
  "emittedAt": "2024-03-20T10:30:09.000Z"
}
```

**Miniapp 处理（StreamAssembler）：**
1. 查找已存在的权限 Part（按 permissionId）
2. 更新：`permResolved = true`，`permissionResponse = response`
3. `isStreaming = false`

**存储操作：** MySQL 更新 permission part

---

## C.15 agent.online

**概述：** Agent 上线通知。

**产生源头：**
- 组件：Skill Server（GatewayMessageRouter.handleAgentOnline）
- 触发条件：收到 Gateway 的 `agent_online` GatewayMessage

**Skill Server 出站：**
```json
{
  "type": "agent.online",
  "seq": 1,
  "emittedAt": "2024-03-20T10:30:00.000Z"
}
```

**Miniapp 处理：** `setAgentStatus('online')`

---

## C.16 agent.offline

**概述：** Agent 离线通知。

**产生源头：**
- 组件：Skill Server（GatewayMessageRouter.handleAgentOffline）
- 触发条件：收到 Gateway 的 `agent_offline` GatewayMessage

**Skill Server 出站：**
```json
{
  "type": "agent.offline",
  "seq": 2,
  "emittedAt": "2024-03-20T10:31:30.000Z"
}
```

**Miniapp 处理：** `setAgentStatus('offline')`；`useAgentSelector` 切换到第一个在线 Agent 或清空选择

---

## C.17 error

**概述：** 流级别错误通知。

**产生源头：**
- 组件：Skill Server
- 触发条件：内部处理异常或 Gateway 连接异常

**Skill Server 出站：**
```json
{
  "type": "error",
  "error": "Internal server error",
  "seq": 22,
  "emittedAt": "2024-03-20T10:30:12.000Z"
}
```

**Miniapp 处理：** `finalizeAllStreamingMessages()` + `setError(msg.error)`

---

## C.18 snapshot

**概述：** 全量消息历史快照，用于 WebSocket 连接建立/重连时的状态恢复。

**产生源头：**
- 组件：Skill Server（SkillStreamHandler）
- 触发条件：WebSocket 连接建立或重连时自动发送
- 生成方法：从数据库加载该用户所有活跃会话的消息历史

**Skill Server 出站：**
```json
{
  "type": "snapshot",
  "messages": [
    {
      "id": "msg_1",
      "role": "user",
      "content": "你好",
      "createdAt": "2024-03-20T10:29:50.000Z"
    },
    {
      "id": "msg_2",
      "role": "assistant",
      "content": "你好！有什么可以帮你的吗？",
      "parts": [
        {
          "partId": "part-text-001",
          "type": "text",
          "content": "你好！有什么可以帮你的吗？"
        }
      ],
      "createdAt": "2024-03-20T10:29:55.000Z"
    }
  ]
}
```

**Miniapp 处理：**
1. 重置所有状态（assemblers, activeMessageIds, isStreaming）
2. 用 `normalizeHistoryMessage()` 规范化每条消息
3. 替换整个消息列表

---

## C.19 streaming

**概述：** 重连时恢复正在进行的流式传输状态。

**产生源头：**
- 组件：Skill Server（SkillStreamHandler）
- 触发条件：WebSocket 重连时有消息正在流式传输

**Skill Server 出站：**
```json
{
  "type": "streaming",
  "messageId": "msg-003",
  "parts": [
    {
      "partId": "text_1",
      "type": "text",
      "content": "正在回答...",
      "isStreaming": true
    },
    {
      "partId": "tool_1",
      "type": "tool",
      "toolName": "bash",
      "status": "running"
    }
  ]
}
```

**Miniapp 处理：**
1. 将 parts 数组转换回 StreamMessage 流
2. 重建对应的 StreamAssembler
3. 恢复 `activeMessageIdsRef` 和 `isStreaming` 状态

**关联消息：**
- 前置：`snapshot`（先发送历史，再发送流式状态）
- 后续：恢复后继续接收正常的 StreamMessage 流
