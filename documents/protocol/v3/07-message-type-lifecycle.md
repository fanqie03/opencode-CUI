# 协议报文类型生命周期全景文档（v3）

> **v3 变更：** register 生命周期增加分布式锁和 conn:ak 绑定；heartbeat 增加 conn:ak TTL 刷新；agent_offline 增加 Lua 条件删除；register_rejected 新增 concurrent_registration 原因。

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
- 处理方法（v3 完整流程）：
  1. **[v3]** `Redis SET NX gw:register:lock:{ak}` — 获取分布式锁（TTL 10s）
  2. `DeviceBindingService.validate(ak, macAddress, toolType)` — 设备绑定验证（默认未启用，Fail-Open）
  3. `EventRelayService.hasAgentSession(ak)` — 重复连接检查
  4. `AgentRegistryService.register(userId, ak, ...)` — 数据库注册/更新
  5. `EventRelayService.registerAgentSession(ak, userId, session)` — 本地注册 + Redis 订阅
  6. **[v3]** `Redis SET conn:ak:{ak} = {gatewayInstanceId} EX 120` — 连接实例绑定
  7. 发送 `register_ok` 到 Plugin
  8. 发送 `agent_online` 到 Skill Server
  9. **[v3]** Lua 脚本释放分布式锁（校验 owner 后 DEL）
- 存储操作：
  - `[Redis]` gw:register:lock:{ak} (v3, TTL=10s)
  - `[MySQL:gw]` agent_connection INSERT/UPDATE
  - `[Redis]` gw:agent:user:{ak} = userId
  - `[Redis]` SUBSCRIBE agent:{ak}
  - `[Redis]` conn:ak:{ak} = gatewayInstanceId (v3, TTL=120s)
- 出站消息：`register_ok`（→ Plugin）+ `agent_online`（→ Skill Server）

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
- 前置：WebSocket 握手（AK/SK 签名认证，GATEWAY/REMOTE 双模式）
- 后续：`register_ok`（成功）或 `register_rejected`（失败）；成功时还触发 `agent_online`

---

## A.2 register_ok

**概述：** Gateway 通知 Plugin 注册成功，Plugin 进入 READY 状态。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler）
- 触发条件：`register` 消息处理全部通过（分布式锁、设备绑定、重复检查、数据库注册）
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
- 处理方法：
  1. 状态从 CONNECTED → READY
  2. 启动心跳定时器（30s 间隔）
  3. 开始处理业务消息（invoke 等）
- 存储操作：无

**关联消息：**
- 前置：`register`
- 后续：Plugin 开始发送 `heartbeat`；Gateway 向 Skill Server 发送 `agent_online`

---

## A.3 register_rejected

**概述：** Gateway 通知 Plugin 注册失败，Plugin 停止重连。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler）
- 触发条件：分布式锁获取失败、设备绑定验证失败、或同 AK 已有活跃连接
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
- `reason` 取值：
  - `"device_binding_failed"` — 设备绑定验证失败（关闭码 4403）
  - `"duplicate_connection"` — 已有同 AK 连接（关闭码 4409）
  - `"concurrent_registration"` — **[v3]** 并发注册冲突（关闭码 4409）
- 附加动作：关闭 WebSocket（自定义关闭码）

**Layer 2: Plugin（接收方）**
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

Gateway → Plugin (并发注册，v3 新增):
{
  "type": "register_rejected",
  "reason": "concurrent_registration"
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

**Layer 2: Gateway（接收方，AgentWebSocketHandler.handleHeartbeat）**
- 处理方法：
  1. `AgentRegistryService.heartbeat(agentId)` — 更新数据库 last_seen_at
  2. **[v3]** `redisMessageBroker.refreshConnAkTtl(akId, 120)` — 刷新 conn:ak TTL
- 存储操作：
  - `[MySQL:gw]` UPDATE agent_connection SET last_seen_at = NOW()
  - `[Redis]` EXPIRE conn:ak:{ak} 120 (v3)

**超时逻辑：**
Gateway 定时任务（30s 间隔）扫描 `last_seen_at < NOW() - 90s AND status = 'ONLINE'` 的记录，触发离线处理。

**关联消息：**
- 前置：`register_ok`
- 后续：无（单向保活）；超时时触发 `agent_offline`

---

## A.5 invoke

**概述：** Skill Server 向 Agent 发送执行命令。

**产生源头：**
- 组件：Skill Server（GatewayRelayService）
- 触发条件：用户操作（发消息、创建/关闭/中止会话、权限/问题回复）
- 生成方法：`GatewayRelayService.sendInvokeToGateway()`

**传播路径：**
Skill Server → Gateway → Plugin

**逐层处理：**

**Layer 1: Skill Server（发送方）**
- 出站消息格式：
```json
{
  "type": "invoke",
  "ak": "agent-key",
  "userId": "owner-welink-id",
  "welinkSessionId": "12345",
  "source": "skill-server",
  "action": "chat",
  "payload": "{\"text\":\"你好\",\"toolSessionId\":\"xxx\"}"
}
```

**Layer 2: Gateway（中转，SkillRelayService）**
- 处理方法：
  1. 验证 source 和 userId
  2. [Mesh] `learnRoute()` 被动学习路由
  3. `Redis PUBLISH agent:{ak} = message.withoutRoutingContext()`
- 存储操作：`[Redis]` PUBLISH agent:{ak}

**Layer 3: Plugin（接收方，BridgeRuntime）**
- 处理方法：
  1. `DownstreamMessageNormalizer.normalize(message)` — 验证格式
  2. 根据 `action` 分发到对应 Action
  3. chat → `ToolDoneCompat.handleInvokeStarted()` + `ChatAction.execute()`
  4. 成功 → `tool_done`（若 chat 且 ToolDoneCompat 决定发送）
  5. 失败 → `tool_error`

**关联消息：**
- 前置：Miniapp REST API 调用
- 后续：`tool_event`, `tool_done`, `tool_error`, `session_created`

---

## A.6 tool_event

**概述：** Plugin 将 OpenCode SDK 事件透传给 Skill Server。

**产生源头：**
- 组件：Message Bridge Plugin（BridgeRuntime）
- 触发条件：OpenCode SDK 触发 allowlist 中的事件（11 种）
- 生成方法：`BridgeRuntime.handleEvent()` → 封装为 `tool_event`

**传播路径：**
Plugin → Gateway → Skill Server

**逐层处理：**

**Layer 1: Plugin（发送方）**
- 处理方法：
  1. `UpstreamEventExtractor.extract(rawEvent)` — 提取 common/extra
  2. 检查连接状态 READY、事件在 allowlist
  3. 构建 `{type: "tool_event", toolSessionId, event: raw}`
- 出站消息格式：
```json
{
  "type": "tool_event",
  "toolSessionId": "session-uuid",
  "event": { "type": "message.part.delta", "properties": { ... } }
}
```

**Layer 2: Gateway（中转，EventRelayService）**
- 处理方法：
  1. **[v3]** `MdcHelper.snapshot()` — 保存调用方 MDC
  2. `message.ensureTraceId()` — 补充 traceId
  3. 注入 ak、userId
  4. `SkillRelayService.relayToSkill(message)` — Mesh/Legacy 路由
  5. **[v3]** `MdcHelper.restore(previousMdc)` — 恢复调用方 MDC
- 存储操作：无

**Layer 3: Skill Server（接收方，GatewayMessageRouter）**
- 处理方法：
  1. `OpenCodeEventTranslator.translate(event, sessionId)` — 翻译为 StreamMessage
  2. 广播 StreamMessage 到 Miniapp WebSocket + Redis PUBLISH
- 存储操作：视翻译结果而定（部分消息触发状态持久化）

**关联消息：**
- 前置：OpenCode SDK 事件
- 后续：对应的 StreamMessage（text.delta, thinking.delta, tool.update 等）

---

## A.7 tool_done

**概述：** Plugin 通知 Skill Server 某次执行已完成。

**产生源头：**
- 组件：Message Bridge Plugin（ToolDoneCompat）
- 触发条件：chat Action 成功完成 或 session.idle 兜底
- 生成方法：`ToolDoneCompat` 状态机决策

**传播路径：**
Plugin → Gateway → Skill Server

**ToolDoneCompat 决策表：**

| 场景 | 触发 | 是否发送 |
|------|------|---------|
| chat 执行成功 | `handleInvokeCompleted()` | ✓ (source: invoke_complete) |
| session.idle, 无 pending chat | `handleSessionIdle()` | ✓ (source: session_idle) |
| session.idle, 有 pending chat | `handleSessionIdle()` | ✗ |
| session.idle, 已由 invoke_complete 发过 | `handleSessionIdle()` | ✗ |

**Skill Server 处理：**
1. `completionCache.put(sessionId, 5s TTL)` — 抑制迟到的 tool_event
2. 广播 `session.status: idle` → Miniapp `finalizeAllStreamingMessages()`

**关联消息：**
- 前置：`invoke(chat)` 或 OpenCode `session.idle`
- 后续：`session.status(idle)` StreamMessage

---

## A.8 tool_error

**概述：** Plugin 通知 Skill Server 某次执行发生错误。

**产生源头：**
- 组件：Message Bridge Plugin（ActionRouter）
- 触发条件：SDK 调用失败、超时、或返回错误
- 生成方法：Action 执行异常时构造

**出站消息格式：**
```json
{
  "type": "tool_error",
  "welinkSessionId": "12345",
  "toolSessionId": "session-uuid",
  "error": "session not found: session-uuid",
  "reason": "session_not_found"
}
```

**reason 推断规则：**
- 错误信息含 "not found", "404", "session_not_found", "unexpected eof", "json parse error" → `"session_not_found"`
- 其他 → undefined

**Skill Server 处理：** 广播 `session.error` StreamMessage

**关联消息：**
- 前置：`invoke`
- 后续：`session.error` StreamMessage

---

## A.9 session_created

**概述：** Plugin 通知 Skill Server 远端会话创建成功。

**产生源头：**
- 组件：Message Bridge Plugin（CreateSessionAction）
- 触发条件：`create_session` Action 执行成功

**出站消息格式：**
```json
{
  "type": "session_created",
  "welinkSessionId": "12345",
  "toolSessionId": "new-session-uuid",
  "session": { "sessionId": "new-session-uuid" }
}
```

**Skill Server 处理：**
1. 更新 SkillSession 的 toolSessionId
2. 检查 pendingRebuildMessages → 若有 → 消费并 invoke(chat)
3. 广播 `session.status: idle`

**关联消息：**
- 前置：`invoke(create_session)`
- 后续：`session.status(idle)` StreamMessage；若有 pending 消息 → `invoke(chat)`

---

## A.10 agent_online

**概述：** Gateway 通知 Skill Server 某个 Agent 上线。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler）
- 触发条件：`register` 处理成功后自动生成（非 Agent 透传）

**出站消息格式：**
```json
{
  "type": "agent_online",
  "ak": "agent-key",
  "userId": "user-123",
  "toolType": "opencode",
  "toolVersion": "1.4.0"
}
```

**Skill Server 处理：** 广播 `agent.online` StreamMessage

**Miniapp 处理：** `setAgentStatus('online')`

**关联消息：**
- 前置：`register` + `register_ok`
- 后续：`agent.online` StreamMessage

---

## A.11 agent_offline

**概述：** Gateway 通知 Skill Server 某个 Agent 离线。

**产生源头：**
- 组件：AI Gateway（AgentWebSocketHandler / AgentRegistryService）
- 触发条件：WebSocket 连接断开 或 心跳超时（90s）

**v3 处理流程（afterConnectionClosed）：**
1. `AgentRegistryService.markOffline(agentId)` → `[MySQL:gw]` UPDATE status=OFFLINE
2. `EventRelayService.removeAgentSession(ak)` → 清理本地 Map + Redis UNSUBSCRIBE
3. **[v3]** `redisMessageBroker.conditionalRemoveConnAk(ak, gatewayInstanceId)` → Lua 条件删除
4. `relayToSkillServer(ak, agentOffline(ak))` → 通知 Skill Server

**出站消息格式：**
```json
{
  "type": "agent_offline",
  "ak": "agent-key"
}
```

**Skill Server 处理：** 广播 `agent.offline` StreamMessage

**Miniapp 处理：** `setAgentStatus('offline')`

**关联消息：**
- 前置：WebSocket 连接断开 / 心跳超时
- 后续：`agent.offline` StreamMessage

---

## A.12 status_query

**概述：** Gateway 向 Agent 发送健康检查请求。

**产生源头：**
- 组件：AI Gateway（EventRelayService）
- 触发条件：REST API `GET /api/gateway/agents/status?ak={ak}` 被调用

**传播路径：**
Gateway → Plugin

**Plugin 处理：**
1. `StatusQueryAction.execute()` → `hostClient.global.health()`
2. 返回 `{ opencodeOnline: healthy === true }`
3. 发送 `status_response`

**关联消息：**
- 后续：`status_response`

---

## A.13 status_response

**概述：** Plugin 向 Gateway 报告 OpenCode CLI 的健康状态。

**产生源头：**
- 组件：Message Bridge Plugin（StatusQueryAction）
- 触发条件：收到 `status_query` 后

**出站消息格式：**
```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

**Gateway 处理：**
1. `opencodeStatusCache[ak] = opencodeOnline`
2. 完成 `pendingStatusQueries[ak]` 的 CompletableFuture

**关联消息：**
- 前置：`status_query`

---

## A.14 permission_request

**概述：** Agent 透传权限请求到 Skill Server（与 tool_event 中的 permission.asked 功能重叠）。

**传播路径：**
Plugin → Gateway → Skill Server

**处理：** 同 tool_event 路径，Gateway 透传。

---

# B. OpenCode SDK 事件

---

## B.1 message.updated

**触发时机：** 整条消息元数据更新（role、finish 等）

**原始结构：**
```json
{
  "type": "message.updated",
  "properties": {
    "info": { "id": "msg-001", "sessionID": "session-uuid", "role": "assistant", "finish": "stop" }
  }
}
```

**传播链路：**
- Plugin → `tool_event` → Gateway → Skill Server
- Skill Server 翻译规则：
  - `role=user` → `text.done`（配合用户消息时序处理）
  - `finish` 存在 → `step.done`（tokens/cost/reason）

---

## B.2 message.part.updated

**触发时机：** 消息片段完整内容更新

**原始结构（按 part.type 分类）：**

| part.type | 翻译为 | 关键字段 |
|-----------|--------|---------|
| `text` | `text.done` | part.text |
| `reasoning` | `thinking.done` | part.text |
| `tool` | `tool.update` | part.tool, part.callID, part.state |
| `step-start` | `step.start` | — |
| `step-finish` | `step.done` | part.usage, part.cost, part.finishReason |
| `file` | `file` | part.fileName, part.fileUrl, part.fileMime |

---

## B.3 message.part.delta

**触发时机：** 流式增量内容

**翻译规则：**
- part 类型为 text → `text.delta`
- part 类型为 reasoning → `thinking.delta`

---

## B.4 message.part.removed

**触发时机：** 消息片段被删除

**Skill Server 处理：** 清除相关缓存，不推送到前端

---

## B.5 session.status

**触发时机：** 会话状态变更（busy/idle/error）

**翻译为：** `session.status` StreamMessage

---

## B.6 session.idle

**触发时机：** 会话进入空闲状态

**特殊处理：**
- Plugin 转发为 `tool_event` + ToolDoneCompat 判断是否发送 `tool_done`
- Skill Server 通过 `tool_done` 触发 `session.status(idle)`

---

## B.7 session.updated

**触发时机：** 会话元数据更新（标题等）

**翻译为：** `session.title` StreamMessage

---

## B.8 session.error

**触发时机：** 会话发生错误

**翻译为：** `session.error` StreamMessage

---

## B.9 permission.asked

**触发时机：** 请求用户授权

**翻译为：** `permission.ask` StreamMessage

---

## B.10 permission.updated

**触发时机：** 权限决策状态变更

**翻译为：** `permission.ask`（状态更新）或 `permission.reply` StreamMessage

---

## B.11 question.asked

**触发时机：** Agent 向用户提问

**翻译为：** `question` StreamMessage

---

# C. StreamMessage 类型

---

## C.1 text.delta — 流式文本增量

**来源：** `message.part.delta (text)` → `tool_event` → 翻译
**Miniapp 处理：** StreamAssembler → Part(text, isStreaming=true) → content += delta

---

## C.2 text.done — 文本完成

**来源：** `message.part.updated (text)` 或 `message.updated (role=user)` → 翻译
**Miniapp 处理：** Part(text, isStreaming=false) → content = 完整文本

---

## C.3 thinking.delta — 思考过程增量

**来源：** `message.part.delta (reasoning)` → 翻译
**Miniapp 处理：** Part(thinking, isStreaming=true) → content += delta

---

## C.4 thinking.done — 思考完成

**来源：** `message.part.updated (reasoning)` → 翻译
**Miniapp 处理：** Part(thinking, isStreaming=false) → content = 完整文本

---

## C.5 tool.update — 工具状态变更

**来源：** `message.part.updated (tool)` → 翻译
**状态流转：** pending → running → completed/error
**Miniapp 处理：** Part(tool) → toolStatus, toolInput, toolOutput 更新

---

## C.6 question — 交互式提问

**来源：** `question.asked` → 翻译
**Miniapp 处理：** Part(question) → header, question, options
**用户回复：** `POST /messages {content, toolCallId}` → `question_reply` 动作

---

## C.7 file — 文件附件

**来源：** `message.part.updated (file)` → 翻译
**Miniapp 处理：** Part(file) → fileName, fileUrl, fileMime

---

## C.8 step.start — 步骤开始

**来源：** `message.part.updated (step-start)` → 翻译
**Miniapp 处理：** 不创建 Part，仅标记 activeMessageIds + isStreaming=true

---

## C.9 step.done — 步骤完成

**来源：** `message.part.updated (step-finish)` 或 `message.updated (finish)` → 翻译
**Miniapp 处理：** 不创建 Part，更新 message.meta (tokens, cost, reason)

---

## C.10 session.status — 会话状态变更

**来源：** `session.status` / `tool_done` → 翻译

| sessionStatus | 含义 | Miniapp 处理 |
|--------------|------|-------------|
| `busy` | Agent 处理中 | isStreaming=true |
| `idle` | Agent 处理完毕 | finalizeAllStreamingMessages() |
| `retry` | Session 重建中 | isStreaming=true |
| `completed` | 会话结束 | 同 idle |

---

## C.11 session.title — 标题更新

**来源：** `session.updated` → 翻译
**Miniapp 处理：** 更新会话标题

---

## C.12 session.error — 会话错误

**来源：** `session.error` / `tool_error` → 翻译
**Miniapp 处理：** finalizeAllStreamingMessages() + setError(error)

---

## C.13 permission.ask — 权限请求

**来源：** `permission.asked` → 翻译
**Miniapp 处理：** Part(permission, permResolved=false)

---

## C.14 permission.reply — 权限已回复

**来源：** Skill Server 收到 permission_reply invoke 后主动推送
**Miniapp 处理：** 查找已有权限 Part → permResolved=true, permissionResponse=response

---

## C.15 agent.online — Agent 上线

**来源：** Gateway `agent_online` → Skill Server 翻译
**Miniapp 处理：** setAgentStatus('online')

---

## C.16 agent.offline — Agent 离线

**来源：** Gateway `agent_offline` → Skill Server 翻译
**Miniapp 处理：** setAgentStatus('offline')

---

## C.17 error — 流错误

**来源：** Skill Server 内部错误
**Miniapp 处理：** finalizeAllStreamingMessages() + setError(error)

---

## C.18 snapshot — 全量消息快照

**来源：** WebSocket 连接建立 / 重连
**Miniapp 处理：** 重置状态 → normalizeHistoryMessage() 规范化 → 替换消息列表

---

## C.19 streaming — 恢复流式状态

**来源：** 重连时有消息正在流式传输
**Miniapp 处理：** parts 数组转换回 StreamMessage → 重建 StreamAssembler → 恢复 isStreaming 状态
