# Layer 4：Message Bridge Plugin ↔ OpenCode SDK 协议详解

## 概述

Message Bridge Plugin 通过 OpenCode SDK 与本地运行的 OpenCode CLI 交互。Plugin 注册为 OpenCode 的 hook，接收事件并调用 SDK API 执行操作。

```
┌──────────────┐     Hook 回调        ┌──────────────┐
│  Message     │ ←──────────────────── │  OpenCode    │
│  Bridge      │                      │  CLI         │
│  Plugin      │ ─────────────────── →│  (本地运行)   │
└──────────────┘     SDK API 调用      └──────────────┘
   BridgeRuntime                        OpenCode Process
   SdkAdapter                           REST API server
```

---

## 一、OpenCode SDK 事件（Plugin 接收）

### 1.1 支持的事件类型

Plugin 通过 allowlist 机制过滤事件，仅转发以下 **11 种事件**：

| 事件类型 | 类别 | 触发时机 |
|---------|------|---------|
| `message.updated` | 消息 | 整条消息元数据更新（role、finish 等） |
| `message.part.updated` | 消息片段 | 消息片段完整内容更新 |
| `message.part.delta` | 消息片段 | 流式增量内容 |
| `message.part.removed` | 消息片段 | 消息片段被删除 |
| `session.status` | 会话 | 会话状态变更（busy/idle 等） |
| `session.idle` | 会话 | 会话进入空闲状态 |
| `session.updated` | 会话 | 会话元数据更新（标题等） |
| `session.error` | 会话 | 会话发生错误 |
| `permission.updated` | 权限 | 权限决策状态变更 |
| `permission.asked` | 权限 | 请求用户授权 |
| `question.asked` | 交互 | Agent 向用户提问 |

### 1.2 事件原始结构

OpenCode SDK 事件通用格式：
```typescript
interface OpenCodeEvent {
  type: string;                          // 事件类型
  properties: Record<string, unknown>;   // 事件属性（结构因类型而异）
}
```

#### `message.updated` — 消息更新

```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-001",
      "sessionID": "session-uuid",
      "role": "assistant",
      "finish": "stop"
    }
  }
}
```

**关键字段：**
- `info.id` → messageId
- `info.sessionID` → toolSessionId
- `info.role` → "user" | "assistant"
- `info.finish` → 完成原因（"stop" | "length" | "tool_calls"），仅最终更新时出现

#### `message.part.updated` — 消息片段完整更新

**文本片段：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-text-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "text",
      "text": "完整的回复内容..."
    }
  }
}
```

**推理片段：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-reasoning-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "reasoning",
      "text": "让我分析一下这个问题..."
    }
  }
}
```

**工具片段：**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-tool-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "tool",
      "tool": "bash",
      "callID": "call-001",
      "state": {
        "status": "completed",
        "input": { "command": "ls -la" },
        "output": "total 24\ndrwxr-xr-x ...",
        "error": null,
        "title": "Execute: ls -la"
      }
    }
  }
}
```

**工具状态取值：**
- `"pending"` — 排队等待
- `"running"` — 正在执行
- `"completed"` — 执行成功
- `"error"` — 执行失败

**Question 工具片段（特殊）：**
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

**Step 片段：**
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

**文件片段：**
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

#### `message.part.delta` — 流式增量

```json
{
  "type": "message.part.delta",
  "properties": {
    "sessionID": "session-uuid",
    "messageID": "msg-001",
    "partID": "part-text-001",
    "delta": "Hello "
  }
}
```

**关键字段：**
- `properties.sessionID` → toolSessionId
- `properties.messageID` → messageId
- `properties.partID` → partId
- `properties.delta` → 增量文本内容

#### `message.part.removed` — 片段移除

```json
{
  "type": "message.part.removed",
  "properties": {
    "sessionID": "session-uuid",
    "messageID": "msg-001",
    "partID": "part-001"
  }
}
```

#### `session.status` — 会话状态变更

```json
{
  "type": "session.status",
  "properties": {
    "sessionID": "session-uuid",
    "status": {
      "type": "busy"
    }
  }
}
```

**status.type 取值：** `"busy"`, `"idle"`, `"error"` 等

#### `session.idle` — 会话空闲

```json
{
  "type": "session.idle",
  "properties": {
    "sessionID": "session-uuid"
  }
}
```

**与 session.status(idle) 的区别：** `session.idle` 是专门的空闲事件，Plugin 使用它来触发 `tool_done` 的兜底发送逻辑。

#### `session.updated` — 会话元数据更新

```json
{
  "type": "session.updated",
  "properties": {
    "info": {
      "id": "session-uuid",
      "title": "讨论项目架构"
    }
  }
}
```

#### `session.error` — 会话错误

```json
{
  "type": "session.error",
  "properties": {
    "sessionID": "session-uuid",
    "error": "Context window exceeded"
  }
}
```

#### `question.asked` — Agent 提问

```json
{
  "type": "question.asked",
  "properties": {
    "sessionID": "session-uuid",
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

**注意：** `questions` 是数组但通常只有一个元素，Plugin 取 `questions[0]`。

#### `permission.asked` — 权限请求

```json
{
  "type": "permission.asked",
  "properties": {
    "sessionID": "session-uuid",
    "permissionID": "perm-001",
    "tool": "write",
    "path": "/src/main.ts",
    "operation": "write"
  }
}
```

#### `permission.updated` — 权限状态更新

```json
{
  "type": "permission.updated",
  "properties": {
    "sessionID": "session-uuid",
    "permissionID": "perm-001",
    "status": "granted",
    "response": "once"
  }
}
```

---

## 二、事件提取（UpstreamEventExtractor）

### 2.1 两层提取模型

```
OpenCode 原始事件
  ↓
extractCommon(event):
  eventType = event.type
  toolSessionId = 从 properties 提取（路径因事件类型而异）
  ↓
extractExtra(event, common):
  根据 eventType 提取特定字段
  ↓
NormalizedUpstreamEvent {
  common: { eventType, toolSessionId },
  extra: EventSpecificFields | undefined,
  raw: 原始事件（保留用于 tool_event 转发）
}
```

### 2.2 toolSessionId 提取路径

| 事件类型 | toolSessionId 路径 |
|---------|-------------------|
| `message.updated` | `properties.info.sessionID` |
| `message.part.updated` | `properties.part.sessionID` |
| `message.part.delta` | `properties.sessionID` |
| `message.part.removed` | `properties.sessionID` |
| `session.*` | `properties.sessionID` 或 `properties.info.id` |
| `permission.*` | `properties.sessionID` |
| `question.asked` | `properties.sessionID` |

### 2.3 事件特定字段

| 事件类型 | 额外提取字段 | 路径 |
|---------|------------|------|
| `message.updated` | `messageId, role` | `info.id, info.role` |
| `message.part.updated` | `messageId, partId` | `part.messageID, part.id` |
| `message.part.delta` | `messageId, partId` | `properties.messageID, properties.partID` |
| `message.part.removed` | `messageId, partId` | `properties.messageID, properties.partID` |
| `session.status` | `status` | `properties.status.type` |
| 其他 | — | — |

### 2.4 验证与错误处理

- 必需字段缺失 → `missing_required_field` 错误
- 字段类型错误 → `invalid_field_type` 错误
- 不支持的事件 → `unsupported_event` 错误
- 所有错误包含 `stage`（common/extra）用于定位

---

## 三、OpenCode SDK API 调用（Plugin 发出）

### 3.1 会话管理

#### 创建会话

```typescript
client.session.create({ body: payload })
```

**请求：** POST `/session`
**响应：** `{ sessionId?, id?, data?: { sessionId?, id? }, ... }`

**sessionId 提取优先级：**
1. `response.sessionId`
2. `response.id`
3. `response.data.sessionId`
4. `response.data.id`

#### 关闭会话

```typescript
client.session.delete({ path: { id: toolSessionId } })
```

**请求：** DELETE `/session/{id}`

#### 中止会话

```typescript
client.session.abort({ path: { id: toolSessionId } })
```

**请求：** POST `/session/{id}/abort`

### 3.2 消息发送

```typescript
client.session.prompt({
  path: { id: toolSessionId },
  body: {
    parts: [{ type: 'text', text: messageText }]
  }
})
```

**请求：** POST `/session/{id}/prompt`
**请求体：**
```json
{
  "parts": [
    { "type": "text", "text": "用户消息内容" }
  ]
}
```

**注意：** `prompt()` 是异步的，结果通过事件流返回（message.part.delta, message.updated 等）。

### 3.3 权限回复

```typescript
client.postSessionIdPermissionsPermissionId({
  path: { id: toolSessionId, permissionID: permissionId },
  body: { response: 'once' | 'always' | 'reject' }
})
```

**请求：** POST `/session/{id}/permissions/{permissionId}`
**请求体：**
```json
{
  "response": "once"
}
```

### 3.4 问题回复

**Step 1: 查询待答问题**

```typescript
const response = await client._client.get({ url: '/question' });
const questions = response.data;
```

**返回的问题列表结构：**
```json
[
  {
    "requestID": "req-001",
    "sessionID": "session-uuid",
    "tool": { "callID": "call-q1" },
    "question": "确认操作？",
    "options": ["是", "否"]
  }
]
```

**Step 2: 匹配目标问题**

```typescript
// 筛选条件
const matched = questions.filter(q => q.sessionID === toolSessionId);

// 若提供了 toolCallId
if (toolCallId) {
  return matched.find(q => q.tool?.callID === toolCallId);
}

// 若未提供 toolCallId，仅在唯一匹配时返回
if (matched.length === 1) return matched[0];
return null;  // 多个匹配 → 无法确定
```

**Step 3: 提交答案**

```typescript
client._client.post({
  url: `/question/${requestID}/reply`,
  body: { answers: [[answerText]] }
})
```

**请求：** POST `/question/{requestID}/reply`
**请求体：**
```json
{
  "answers": [["用户的回答文本"]]
}
```

**注意：** `answers` 是二维数组，第一层对应多个问题，第二层对应多个答案选项。

### 3.5 健康检查

```typescript
const result = await hostClient.global.health();
const isOnline = result?.healthy === true;
```

**请求：** GET `/health`（或 SDK 封装的 health endpoint）

---

## 四、Plugin 事件处理流程

### 4.1 事件接收到转发

```
OpenCode SDK 事件回调
  ↓
BridgeRuntime.handleEvent(rawEvent)
  ↓
Step 1: 事件提取
  UpstreamEventExtractor.extract(rawEvent)
  → NormalizedUpstreamEvent { common, extra, raw }
  → 提取失败 → 日志记录，忽略
  ↓
Step 2: 状态检查
  connectionState === READY ?
  → 否 → 日志记录，忽略
  ↓
Step 3: Allowlist 检查
  eventType ∈ configuredAllowlist ?
  → 否 → 日志 "event.rejected_allowlist"，忽略
  ↓
Step 4: 生成消息 ID
  bridgeMessageId = randomUUID()
  ↓
Step 5: 构建 tool_event 消息
  {
    type: 'tool_event',
    toolSessionId: common.toolSessionId,
    event: raw  // 原始事件完整透传
  }
  ↓
Step 6: 发送到 Gateway
  gatewayConnection.send(toolEventMessage)
  ↓
Step 7: 特殊事件处理
  若 eventType === 'session.idle':
    ToolDoneCompat.handleSessionIdle(toolSessionId)
    → 可能发送 tool_done（见 4.2）
```

### 4.2 ToolDoneCompat 状态机

**问题：** `tool_done` 何时发送？

OpenCode 的 `session.idle` 和 Plugin 的 `chat` Action 完成是两个独立事件，可能以任意顺序到达。ToolDoneCompat 确保 `tool_done` 只发送一次且时机正确。

**内部状态：**
```typescript
pendingPromptSessions: Set<string>                 // 正在执行 chat 的会话
completedSessionsAwaitingIdleDrop: Set<string>     // chat 完成但等待 session.idle 确认的会话
```

**状态转换表：**

```
事件                    条件                           动作
─────────────────────────────────────────────────────────────────────
invoke(chat).start     —                              pending.add(sessionId)

invoke(chat).success   sessionId ∈ pending             pending.delete(sessionId)
                                                      awaiting.add(sessionId)
                                                      → 发送 tool_done (source: invoke_complete)

invoke(chat).fail      sessionId ∈ pending             pending.delete(sessionId)
                                                      → 不发送 tool_done

session.idle           sessionId ∈ pending             → 不发送（chat 还在执行）

session.idle           sessionId ∈ awaiting            awaiting.delete(sessionId)
                                                      → 不发送（已由 invoke_complete 发过）

session.idle           sessionId ∉ pending ∧ awaiting  → 发送 tool_done (source: session_idle)
```

**设计意图：**
- chat 成功完成 → 立即发 `tool_done`（不等 session.idle）
- session.idle 到达时：
  - 如果 chat 还在执行 → 等 chat 完成
  - 如果 chat 已完成 → 不重复发送
  - 如果没有 pending chat → 兜底发送（非 chat 场景）

---

## 五、Plugin Action 执行详解

### 5.1 通用执行模式

```typescript
async execute(payload, context): Promise<ActionResult> {
  // 1. 状态检查
  if (context.connectionState !== 'READY') {
    return failure(stateToErrorCode(state));
  }

  // 2. 日志开始
  logger.info('action.{name}.started', { payload摘要 });

  // 3. SDK 调用
  try {
    const result = await sdkCall(payload);

    // 4. 验证结果
    if (hasError(result)) {
      return failure(errorCode, errorMessage);
    }

    // 5. 成功返回
    logger.info('action.{name}.completed', { latencyMs });
    return success(data);

  } catch (error) {
    // 6. 异常处理
    const mapped = errorMapper(error);
    logger.error('action.{name}.exception', { error, latencyMs });
    return failure(mapped.code, mapped.message);

  } finally {
    logger.debug('action.{name}.finished', { latencyMs });
  }
}
```

### 5.2 各 Action 详细流程

#### ChatAction

```
输入: { toolSessionId, text }
  ↓
client.session.prompt({
  path: { id: toolSessionId },
  body: { parts: [{ type: 'text', text }] }
})
  ↓
成功: ActionSuccess<void>
失败: 错误映射 →
  timeout/timed out → SDK_TIMEOUT
  unreachable/connect/connection → SDK_UNREACHABLE
  not found/session → INVALID_PAYLOAD
  abort/cancelled → INVALID_PAYLOAD
  其他 → SDK_UNREACHABLE
```

#### CreateSessionAction

```
输入: { sessionId?, metadata? }
  ↓
client.session.create({ body: payload })
  ↓
提取 sessionId:
  response.sessionId → response.id →
  response.data.sessionId → response.data.id
  ↓
成功: ActionSuccess<{ sessionId?, session? }>
失败: SDK_UNREACHABLE / SDK_TIMEOUT
```

#### CloseSessionAction

```
输入: { toolSessionId }
  ↓
client.session.delete({ path: { id: toolSessionId } })
  ↓
成功: ActionSuccess<{ sessionId, closed: true }>
```

#### AbortSessionAction

```
输入: { toolSessionId }
  ↓
client.session.abort({ path: { id: toolSessionId } })
  ↓
成功: ActionSuccess<{ sessionId, aborted: true }>
```

#### PermissionReplyAction

```
输入: { permissionId, toolSessionId, response: 'once'|'always'|'reject' }
  ↓
client.postSessionIdPermissionsPermissionId({
  path: { id: toolSessionId, permissionID: permissionId },
  body: { response }
})
  ↓
成功: ActionSuccess<{ permissionId, response, applied: true }>
```

#### QuestionReplyAction

```
输入: { toolSessionId, answer, toolCallId? }
  ↓
Step 1: GET /question → 获取所有待答问题
Step 2: 筛选 sessionID === toolSessionId
Step 3: 若有 toolCallId → 精确匹配 tool.callID
         若无 toolCallId → 仅唯一匹配时使用
Step 4: 未找到 → INVALID_PAYLOAD
  ↓
POST /question/{requestID}/reply { answers: [[answer]] }
  ↓
成功: ActionSuccess<{ requestId, replied: true }>
```

#### StatusQueryAction

```
输入: (无)
  ↓
hostClient.global.health()
  ↓
healthy === true → { opencodeOnline: true }
其他/异常 → { opencodeOnline: false }
```

---

## 六、Plugin 配置

### 6.1 配置优先级

```
环境变量 (BRIDGE_*) > 项目配置 (.opencode/message-bridge.json) > 用户配置 (~/.config/opencode/message-bridge.json) > 默认值
```

### 6.2 完整配置结构

```json
{
  "enabled": true,
  "debug": false,
  "config_version": 1,
  "gateway": {
    "url": "ws://localhost:8081/ws/agent",
    "channel": "opencode",
    "heartbeatIntervalMs": 30000,
    "reconnect": {
      "baseMs": 1000,
      "maxMs": 30000,
      "exponential": true
    },
    "ping": {
      "intervalMs": 30000
    }
  },
  "sdk": {
    "timeoutMs": 10000
  },
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  },
  "events": {
    "allowlist": [
      "message.updated",
      "message.part.updated",
      "message.part.delta",
      "message.part.removed",
      "session.status",
      "session.idle",
      "session.updated",
      "session.error",
      "permission.updated",
      "permission.asked",
      "question.asked"
    ]
  }
}
```

### 6.3 环境变量映射

| 环境变量 | 配置路径 | 默认值 |
|---------|---------|--------|
| `BRIDGE_ENABLED` | `enabled` | true |
| `BRIDGE_DEBUG` | `debug` | false |
| `BRIDGE_GATEWAY_URL` | `gateway.url` | ws://localhost:8081/ws/agent |
| `BRIDGE_GATEWAY_CHANNEL` | `gateway.channel` | opencode |
| `BRIDGE_GATEWAY_HEARTBEAT_INTERVAL_MS` | `gateway.heartbeatIntervalMs` | 30000 |
| `BRIDGE_GATEWAY_RECONNECT_BASE_MS` | `gateway.reconnect.baseMs` | 1000 |
| `BRIDGE_GATEWAY_RECONNECT_MAX_MS` | `gateway.reconnect.maxMs` | 30000 |
| `BRIDGE_SDK_TIMEOUT_MS` | `sdk.timeoutMs` | 10000 |
| `BRIDGE_AUTH_AK` / `BRIDGE_AK` | `auth.ak` | — |
| `BRIDGE_AUTH_SK` / `BRIDGE_SK` | `auth.sk` | — |
| `BRIDGE_EVENTS_ALLOWLIST` | `events.allowlist` | （逗号分隔） |

---

## 七、日志与追踪

### 7.1 Trace ID 传播

```
每条消息生成唯一标识:
  traceId: 跨服务追踪 (Gateway 消息的 traceId)
  bridgeMessageId: Plugin 内部消息 ID (UUID)
  runtimeTraceId: 运行时级追踪

传播路径:
  tool_event → Gateway → Skill Server (traceId 一致)
  invoke → Plugin → tool_done/tool_error (traceId 一致)
```

### 7.2 关键日志事件

| 日志事件 | 级别 | 说明 |
|---------|------|------|
| `gateway.connect.started` | info | 开始连接 |
| `gateway.register.sent` | info | 发送 register |
| `gateway.register.accepted` | info | 收到 register_ok |
| `gateway.ready` | info | 进入 READY 状态 |
| `gateway.close` | warn | 连接关闭 |
| `gateway.error` | error | 连接错误 |
| `gateway.heartbeat.sent` | debug | 发送心跳 |
| `event.received` | debug | 收到 SDK 事件 |
| `event.rejected_allowlist` | debug | 事件被 allowlist 拦截 |
| `event.forwarding` | debug | 开始转发事件 |
| `event.forwarded` | info | 事件已转发 |
| `runtime.invoke.received` | info | 收到 invoke 命令 |
| `runtime.invoke.completed` | info | invoke 执行完成 |
| `runtime.tool_done.sending` | info | 发送 tool_done |
| `runtime.tool_error.sending` | warn | 发送 tool_error |
| `action.{name}.started` | info | Action 开始执行 |
| `action.{name}.completed` | info | Action 执行成功 |
| `action.{name}.exception` | error | Action 执行异常 |
