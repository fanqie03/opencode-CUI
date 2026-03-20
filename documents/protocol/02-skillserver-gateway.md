# Layer 2：Skill Server ↔ AI Gateway 协议详解

## 概述

Skill Server 与 AI Gateway 之间通过 WebSocket 双向通信。Skill Server 作为客户端连接 Gateway 的 `/ws/skill` 端点。

```
┌──────────────┐                    ┌──────────────┐
│  Skill       │   WebSocket        │  AI Gateway  │
│  Server      │ ──────────────────→│  :8081       │
│  :8082       │ ←──────────────────│  /ws/skill   │
└──────────────┘                    └──────────────┘
    GatewayWSClient                 SkillWebSocketHandler
    (WS 客户端)                      (WS 服务端)
```

---

## 一、WebSocket 连接协议

### 1.1 连接建立

**地址：** `ws://{gateway-host}:8081/ws/skill`
**认证方式：** WebSocket 子协议 (Sec-WebSocket-Protocol)

**子协议格式：**
```
Sec-WebSocket-Protocol: auth.{Base64URL(JSON)}
```

**认证 JSON：**
```json
{
  "token": "sk-intl-9f2a7d3e4b1c",    // 内部 Token
  "source": "skill-server"             // 来源服务标识
}
```

**Gateway 验证逻辑（SkillWebSocketHandler.beforeHandshake）：**
1. 从 `Sec-WebSocket-Protocol` 头提取 `auth.` 前缀的值
2. Base64URL 解码 → JSON 解析
3. 验证 `token` 与配置的 `skill.gateway.internal-token` 匹配
4. 验证 `source` 非空
5. 将 `source` 存入 WebSocket 会话属性
6. 回显子协议到响应头（RFC 6455 要求）

### 1.2 连接管理

**GatewayWSClient（Skill Server 侧）：**
- `@PostConstruct init()` 时自动连接
- 连接成功 → 重置重连计数
- 连接关闭：
  - 原因为 "invalid internal token" → 停止重连
  - 其他原因 → 指数退避重连
- 重连策略：`delay = min(1000 × 2^(attempt-1), 30000)` ms
- `@PreDestroy destroy()` 优雅关闭

**SkillRelayService（Gateway 侧）：**
- 每个 `source` 维护一个连接池 `Map<linkId, WebSocketSession>`
- 每个 `source` 有一个 `defaultLink`（首选连接）
- 连接注册时：
  - 加入连接池
  - 设置为 defaultLink（若之前无连接）
  - 订阅 Redis relay channel
  - 在 Redis 注册 owner（心跳 TTL 30s）
- 连接移除时：
  - 从池中删除
  - 若为 defaultLink → 选择其他活跃连接替代
  - 若池为空 → 清除 Redis owner 注册

---

## 二、下行协议（Skill Server → Gateway）

### 2.1 invoke 消息格式

所有下行消息类型均为 `invoke`，通过 `action` 字段区分业务动作。

**完整字段：**

```json
{
  "type": "invoke",
  "ak": "agent-access-key",          // 必需：目标 Agent 标识
  "userId": "owner-welink-id",       // 必需：用户标识（Gateway 验证匹配）
  "welinkSessionId": "12345",        // Skill 会话 ID
  "source": "skill-server",          // 必需：来源服务标识
  "action": "<action_type>",         // 必需：动作类型
  "payload": "<json_string>",        // 动作参数（JSON 字符串）
  "traceId": "uuid",                 // 可选：链路追踪 ID
  "sequenceNumber": 1                // 可选：消息序号
}
```

### 2.2 Action 类型详解

#### `create_session` — 创建 OpenCode 会话

**触发场景：**
- Miniapp 创建会话时
- IM 入站消息需要新会话时
- Session 重建时（toolSessionId 丢失）

**Payload：**
```json
{
  "title": "会话标题"
}
```

**构造逻辑（GatewayRelayService.buildInvokeMessage）：**
```java
InvokeCommand cmd = new InvokeCommand(
    ak,                              // 从会话或请求获取
    ownerWelinkId,                   // 会话所有者
    String.valueOf(welinkSessionId), // 数值 ID 转字符串
    GatewayActions.CREATE_SESSION,   // "create_session"
    "{\"title\":\"...\"}"
);
```

**预期响应：** Gateway 返回 `session_created` 消息

---

#### `chat` — 发送对话消息

**触发场景：**
- 用户在 Miniapp 发送消息
- IM 入站消息（经过上下文注入后）

**Payload：**
```json
{
  "text": "用户消息内容（或注入上下文后的完整 prompt）",
  "toolSessionId": "opencode-session-uuid"
}
```

**构造逻辑：**
```java
// Miniapp 来源
payload = String.format("{\"text\":\"%s\",\"toolSessionId\":\"%s\"}",
    escapeJson(content), session.getToolSessionId());

// IM 来源（群聊经过上下文注入）
String prompt = contextInjectionService.resolvePrompt(sessionType, content, chatHistory);
payload = String.format("{\"text\":\"%s\",\"toolSessionId\":\"%s\"}",
    escapeJson(prompt), session.getToolSessionId());
```

**特殊处理：** 若 `toolSessionId` 为空 → 不发送 chat，而是触发 Session 重建：
1. 缓存消息到 `pendingRebuildMessages`（Caffeine, TTL 5min, max 1000）
2. 广播 `session.status: retry` 到前端
3. 发送 `create_session` 到 Gateway
4. 等待 `session_created` → 自动消费缓存消息

---

#### `question_reply` — 回答交互问题

**触发场景：** 用户回答 OpenCode 的交互提问（如文件操作确认）

**Payload：**
```json
{
  "answer": "用户的回答文本",
  "toolCallId": "call_abc123",
  "toolSessionId": "opencode-session-uuid"
}
```

---

#### `permission_reply` — 回复权限请求

**触发场景：** 用户在 Miniapp 上批准或拒绝权限请求

**Payload：**
```json
{
  "permissionId": "perm_abc123",
  "response": "once",
  "toolSessionId": "opencode-session-uuid"
}
```

**`response` 取值：**
- `"once"` — 本次允许
- `"always"` — 始终允许
- `"reject"` — 拒绝

---

#### `close_session` — 关闭会话

**触发场景：** 用户在 Miniapp 删除会话

**Payload：**
```json
{
  "toolSessionId": "opencode-session-uuid"
}
```

---

#### `abort_session` — 中止会话

**触发场景：** 用户点击"停止"按钮

**Payload：**
```json
{
  "toolSessionId": "opencode-session-uuid"
}
```

---

### 2.3 Gateway 接收 invoke 的处理

**SkillWebSocketHandler.handleTextMessage：**
1. 解析 JSON → GatewayMessage
2. 仅接受 `type=invoke`（其他类型忽略并警告）
3. 调用 `SkillRelayService.handleInvokeFromSkill(session, message)`

**SkillRelayService.handleInvokeFromSkill 验证流程：**

```
Step 1: 验证 source
  message.source != null  → 否则拒绝 (error: source_not_allowed)
  message.source == session.boundSource → 否则拒绝 (error: source_mismatch)

Step 2: 验证 ak
  message.ak != null && !blank → 否则忽略

Step 3: 验证 userId
  message.userId == Redis:gw:agent:user:{ak} → 否则拒绝

Step 4: 绑定 agent source
  Redis SET gw:agent:source:{ak} = message.source

Step 5: 发布到 Agent
  Redis PUBLISH agent:{ak} = message.withoutRoutingContext()
  （剥离 userId 和 source）
```

---

## 三、上行协议（Gateway → Skill Server）

### 3.1 消息类型总览

| type | 来源 | 关键字段 | 处理方法 |
|------|------|----------|---------|
| `tool_event` | Agent → Gateway 透传 | `ak, userId, welinkSessionId, toolSessionId, event` | `handleToolEvent()` |
| `tool_done` | Agent → Gateway 透传 | `ak, userId, welinkSessionId, toolSessionId, usage` | `handleToolDone()` |
| `tool_error` | Agent → Gateway 透传 | `ak, userId, welinkSessionId, toolSessionId, error, reason` | `handleToolError()` |
| `session_created` | Agent → Gateway 透传 | `ak, userId, welinkSessionId, toolSessionId, session` | `handleSessionCreated()` |
| `agent_online` | Gateway 自身生成 | `ak, userId, toolType, toolVersion` | `handleAgentOnline()` |
| `agent_offline` | Gateway 自身生成 | `ak, userId` | `handleAgentOffline()` |
| `permission_request` | Agent → Gateway 透传 | `ak, userId, welinkSessionId, permissionId, command, metadata` | `handlePermissionRequest()` |

### 3.2 各消息类型详解

#### `tool_event` — OpenCode 事件透传

**消息格式：**
```json
{
  "type": "tool_event",
  "ak": "agent-key",
  "userId": "user-123",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "traceId": "trace-uuid",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "...",
      "messageID": "msg-001",
      "partID": "part-001",
      "delta": "你好"
    }
  }
}
```

**`event` 字段包含的 OpenCode 事件类型（11 种）：**

| event.type | 含义 | 翻译结果 |
|-----------|------|---------|
| `message.part.delta` | 消息片段增量 | `text.delta` 或 `thinking.delta` |
| `message.part.updated` | 消息片段完整更新 | 根据 partType 翻译（见下文） |
| `message.part.removed` | 消息片段移除 | 清除缓存（不推送前端） |
| `message.updated` | 整条消息更新 | `text.done`（user）或 `step.done`（finish） |
| `session.status` | 会话状态变更 | `session.status` |
| `session.idle` | 会话空闲 | `session.status(idle)` |
| `session.updated` | 会话元数据更新 | `session.title` |
| `session.error` | 会话错误 | `session.error` |
| `question.asked` | Agent 提问 | `question` |
| `permission.asked` | 权限请求 | `permission.ask` |
| `permission.updated` | 权限决策变更 | `permission.ask` 或 `permission.reply` |

**Skill Server 处理流程（GatewayMessageRouter.handleToolEvent）：**

```
1. 通过 toolSessionId 或 welinkSessionId 查找 SkillSession
2. 激活空闲会话（若 session.status == IDLE → ACTIVE）
3. 调用 OpenCodeEventTranslator.translate(event, sessionId)
4. 翻译结果为 null → 跳过
5. 翻译结果为 StreamMessage → 广播
   ├── completionCache 检查：若 5s 内已 tool_done，抑制非 question/permission 事件
   ├── ActiveMessageTracker 分配 messageId/messageSeq/role
   ├── MessagePersistenceService 持久化到 DB（仅 final 状态）
   ├── SkillStreamHandler.pushStreamMessage() → 本地 WS
   ├── RedisMessageBroker.publishToUser() → 跨实例 WS
   └── 若 IM 会话 → ImOutboundService.sendTextToIm()
```

---

#### `tool_done` — Agent 执行完成

**消息格式：**
```json
{
  "type": "tool_done",
  "ak": "agent-key",
  "userId": "user-123",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "traceId": "trace-uuid",
  "usage": {
    "inputTokens": 1500,
    "outputTokens": 800,
    "totalTokens": 2300
  }
}
```

**Skill Server 处理流程（handleToolDone）：**

```
1. 查找 SkillSession
2. 标记 completionCache(sessionId, TTL=5s)
   └── 防止竞态：后续迟到的 tool_event 被抑制
3. 清除 TranslatorSessionCache(sessionId)
4. ActiveMessageTracker.removeAndFinalize(sessionId)
   └── 结束当前活跃 assistant 消息
5. 广播 StreamMessage(type=session.status, sessionStatus=idle)
6. 更新 SkillSession.status = IDLE
7. 若有 pending 消息（rebuildService.consumePendingMessage）
   └── 自动发送 chat invoke
```

**completionCache 竞态防护机制：**
- `tool_done` 后 5 秒内的 `tool_event` 被自动抑制
- **例外：** `question` 和 `permission` 类事件不被抑制（允许交互）
- 新的 chat invoke 会清除 completionCache

---

#### `tool_error` — Agent 执行错误

**消息格式：**
```json
{
  "type": "tool_error",
  "ak": "agent-key",
  "userId": "user-123",
  "welinkSessionId": "12345",
  "toolSessionId": "opencode-session-uuid",
  "error": "session not found",
  "reason": "session_not_found"
}
```

**Skill Server 处理流程（handleToolError）：**

```
根据 reason 分支处理：

reason == "session_not_found":
  → 触发 Session 重建
  → rebuildService.rebuildToolSession(sessionId, session, null)
  → 广播 session.status: retry

其他错误:
  → 广播 StreamMessage(type=session.error, error=errorMessage)
  → ActiveMessageTracker.removeAndFinalize(sessionId)
```

---

#### `session_created` — 会话创建成功

**消息格式：**
```json
{
  "type": "session_created",
  "ak": "agent-key",
  "userId": "user-123",
  "welinkSessionId": "12345",
  "toolSessionId": "new-opencode-session-uuid",
  "traceId": "trace-uuid",
  "session": {
    "sessionId": "new-opencode-session-uuid"
  }
}
```

**Skill Server 处理流程（handleSessionCreated）：**

```
1. 查找 SkillSession (by welinkSessionId)
2. 更新 toolSessionId:
   sessionService.updateToolSessionId(numericSessionId, toolSessionId)
3. 消费待处理消息:
   pendingMessage = rebuildService.consumePendingMessage(sessionId)
   └── Caffeine 缓存中取出（最多缓存 5min, 1000 条）
4. 若有 pendingMessage:
   └── 自动发送 invoke(action=chat, payload={text, toolSessionId})
5. 广播 session.status: idle（重建完成标记）
```

---

#### `agent_online` — Agent 上线通知

**消息格式：**
```json
{
  "type": "agent_online",
  "ak": "agent-key",
  "userId": "user-123",
  "toolType": "opencode",
  "toolVersion": "1.0.0",
  "traceId": "trace-uuid"
}
```

**注意：** 此消息由 Gateway 在 Agent 注册成功后**自动生成**，不是从 Agent 透传。

**Skill Server 处理流程（handleAgentOnline）：**
```
广播 StreamMessage(type=agent.online) 到该用户的所有 WebSocket 连接
```

---

#### `agent_offline` — Agent 离线通知

**消息格式：**
```json
{
  "type": "agent_offline",
  "ak": "agent-key",
  "userId": "user-123",
  "traceId": "trace-uuid"
}
```

**注意：** 此消息由 Gateway 在 Agent 断开连接时**自动生成**。

**Skill Server 处理流程（handleAgentOffline）：**
```
广播 StreamMessage(type=agent.offline) 到该用户的所有 WebSocket 连接
```

---

#### `permission_request` — 权限请求透传

**消息格式：**
```json
{
  "type": "permission_request",
  "ak": "agent-key",
  "userId": "user-123",
  "welinkSessionId": "12345",
  "permissionId": "perm_abc123",
  "command": "write /src/main.ts",
  "metadata": {
    "path": "/src/main.ts",
    "operation": "write"
  },
  "traceId": "trace-uuid"
}
```

**Skill Server 处理流程（handlePermissionRequest）：**
```
翻译为 StreamMessage:
  type = permission.ask
  permissionId = 从消息提取
  permType = metadata.operation 或 command
  title = command
  metadata = 原样透传

广播到该会话的所有前端 WebSocket 连接
```

---

## 四、OpenCodeEventTranslator 翻译规则详解

这是 Skill Server 中最核心的协议翻译组件，负责将 OpenCode 原始事件转换为前端 StreamMessage。

### 4.1 事件翻译总表

| OpenCode event.type | partType | 输出 StreamMessage.type | 关键映射 |
|--------------------|----------|----------------------|---------|
| `message.part.delta` | text | `text.delta` | `delta → content` |
| `message.part.delta` | reasoning | `thinking.delta` | `delta → content` |
| `message.part.updated` | text | `text.done` | `part.text → content` |
| `message.part.updated` | reasoning | `thinking.done` | `part.text → content` |
| `message.part.updated` | tool（非 question） | `tool.update` | `state.{status,input,output,error,title}` |
| `message.part.updated` | tool（question, completed/error） | `question` | `state.input → questionInfo` |
| `message.part.updated` | tool（question, pending/running） | null（跳过） | 等待 question.asked 事件 |
| `message.part.updated` | step-start | `step.start` | — |
| `message.part.updated` | step-finish | `step.done` | `usage → tokens, cost, reason` |
| `message.part.updated` | file | `file` | `fileName, fileUrl, fileMime` |
| `message.part.removed` | — | null | 清除缓存 |
| `message.updated` | role=user（有缓存文本） | `text.done` | 从缓存获取内容 |
| `message.updated` | role=user（无缓存文本） | null（等待 part） | 缓存 role |
| `message.updated` | finish 字段 | `step.done` | `finish → reason` |
| `session.status` | — | `session.status` | `status.type → sessionStatus` |
| `session.idle` | — | `session.status` | 硬编码 `sessionStatus=idle` |
| `session.updated` | — | `session.title` | `info.title → title` |
| `session.error` | — | `session.error` | `error → error` |
| `question.asked` | — | `question` | `questions[0] → questionInfo` |
| `permission.asked` | — | `permission.ask` | 提取权限字段 |
| `permission.updated` | 根据状态 | `permission.ask` / `permission.reply` | 状态决定类型 |

### 4.2 TranslatorSessionCache 时序处理

**问题：** `message.part.updated`（用户文本）和 `message.updated`（含 role=user）到达顺序不确定。

**解决方案——双缓存机制：**

```
场景 A：message.part.updated 先到
  ① part.updated 到达，part.type=text, role 未知
  ② 缓存文本: cache.rememberMessageText(sessionId, messageId, text)
  ③ 返回 null（等待 message.updated 确认 role）
  ④ message.updated 到达, role=user
  ⑤ 从缓存读取文本: cache.getMessageText(sessionId, messageId)
  ⑥ 发送 text.done，清除缓存

场景 B：message.updated 先到
  ① message.updated 到达, role=user
  ② 缓存 role: cache.rememberMessageRole(sessionId, messageId, "user")
  ③ 无缓存文本 → 返回 null
  ④ message.part.updated 到达, part.type=text
  ⑤ 查询 role 缓存 → user
  ⑥ 直接发送 text.done

场景 C：正常顺序（assistant）
  ① message.updated 到达, role=assistant
  ② 缓存 role
  ③ message.part.delta 到达
  ④ 查询 role → assistant → 发送 text.delta
```

### 4.3 Tool Part 翻译详细逻辑

**普通工具（toolName != "question"）：**

```
event.type = "message.part.updated"
  properties.part = {
    id: "part-001",
    type: "tool",
    tool: "bash",
    callID: "call-001",
    state: {
      status: "completed",     // pending | running | completed | error
      input: { "command": "ls" },
      output: "file1.txt\nfile2.txt",
      error: null,
      title: "Execute: ls"
    }
  }

翻译为 StreamMessage:
  type = "tool.update"
  toolName = "bash"
  toolCallId = "call-001"
  status = "completed"
  input = { "command": "ls" }
  output = "file1.txt\nfile2.txt"
  title = "Execute: ls"
  partId = "part-001"
```

**Question 工具（toolName == "question"）：**

```
状态为 pending/running → 返回 null（信息由 question.asked 事件提供）

状态为 completed/error →
  翻译为 StreamMessage:
    type = "question"
    toolName = "question"
    toolCallId = "call-q1"
    status = "completed"
    input = state.input（原始 JSON）
    output = normalizeQuestionAnswerOutput(state.output, input)
    questionInfo = 从缓存获取（question.asked 时已缓存）
    partId = 从缓存 callId→partId 映射获取（防重复创建）
```

### 4.4 question.asked 事件翻译

```
event = {
  type: "question.asked",
  properties: {
    sessionID: "...",
    questions: [{
      requestID: "req-001",
      tool: { callID: "call-q1" },
      question: "你确定要删除这个文件吗？",
      options: ["是", "否"],
      header: "确认操作"
    }]
  }
}

翻译为 StreamMessage:
  type = "question"
  toolName = "question"
  toolCallId = "call-q1"
  status = "running"（等待回答）
  questionInfo = {
    header: "确认操作",
    question: "你确定要删除这个文件吗？",
    options: ["是", "否"]
  }
```

### 4.5 step.done 事件翻译

```
event = {
  type: "message.part.updated",
  properties: {
    part: {
      type: "step-finish",
      usage: {
        inputTokens: 1500,
        outputTokens: 800,
        cacheReadInputTokens: 500,
        cacheCreationInputTokens: 200
      },
      cost: 0.0125,
      finishReason: "stop"
    }
  }
}

翻译为 StreamMessage:
  type = "step.done"
  tokens = {
    input: 1500,
    output: 800,
    cache: { read: 500, write: 200 }
  }
  cost = 0.0125
  reason = "stop"
```

---

## 五、消息广播机制

### 5.1 广播流程

```
GatewayMessageRouter.broadcastStreamMessage(sessionId, userId, streamMessage)
  ↓
enrichStreamMessage():
  msg.sessionId = sessionId
  msg.welinkSessionId = sessionId（JSON 序列化名）
  msg.emittedAt = ISO8601 now()
  msg.seq = per-session AtomicLong.incrementAndGet()
  ↓
序列化为 JSON
  ↓
包装为 Redis 信封:
  {
    "sessionId": "12345",
    "userId": "user-123",
    "message": { /* StreamMessage JSON */ }
  }
  ↓
推送路径:
  ├── SkillStreamHandler.pushStreamMessage(sessionId, msg)
  │   └── 查找本实例中该会话所有者的所有 WS 连接
  │   └── 逐个连接发送（session.sendMessage(TextMessage)）
  │
  └── RedisMessageBroker.publishToUser(userId, envelope)
      └── Redis PUBLISH user-stream:{userId} envelope
      └── 其他实例的 SkillStreamHandler subscriber 收到
      └── 推送到该实例的 WS 连接
```

### 5.2 跨实例推送

**Redis 频道：** `user-stream:{userId}`

**订阅生命周期：**
- 用户首个 WS 连接建立 → subscribe
- 用户最后一个 WS 连接断开 → unsubscribe

**这解决了什么问题？**
- 用户 WS 连在 SkillServer-A
- Gateway 事件到达 SkillServer-B
- B 通过 Redis pub/sub 将 StreamMessage 发给 A
- A 推送到用户的 WS 连接

---

## 六、IM 入站/出站协议

### 6.1 IM 入站

**端点：** `POST /api/inbound/messages`
**认证：** IP 白名单 + ImTokenAuthInterceptor

**请求体：**
```json
{
  "businessDomain": "im",
  "sessionType": "group",
  "sessionId": "im-chat-12345",
  "assistantAccount": "ai-bot-account",
  "content": "今天天气如何？",
  "msgType": "text",
  "chatHistory": [
    {
      "senderAccount": "user001",
      "senderName": "张三",
      "content": "上午好",
      "timestamp": 1710000000
    }
  ]
}
```

**完整处理流程：**

```
ImInboundController.receiveMessage()
  ↓
Step 1: 参数验证
  businessDomain == "im" ✓
  sessionType ∈ ["group", "direct"] ✓
  content 非空 ✓
  msgType == "text"（仅支持文本）✓
  ↓
Step 2: 解析助手账号
  AssistantAccountResolverService.resolve(assistantAccount)
    → Redis 缓存查询（TTL 30min）
    → 缓存未命中 → HTTP GET {resolveUrl}?partnerAccount={account}
    → 返回 (ak, ownerWelinkId)
  ↓
Step 3: 上下文注入
  ContextInjectionService.resolvePrompt(sessionType, content, chatHistory)
    ├── direct 会话 → 原样返回 content
    └── group 会话:
        → 加载模板 classpath:templates/group-chat-prompt.txt
        → 格式化历史（最近 20 条）:
          "[2024-03-20 10:30:00] 张三: 上午好"
          （自动检测秒级/毫秒级时间戳，Asia/Shanghai 时区）
        → 替换占位符 {{chatHistory}} + {{currentMessage}}
        → 返回完整 prompt
  ↓
Step 4: 查找/创建会话
  ImSessionManager.findSession(domain, type, sessionId, ak)
    ├── 找到且有 toolSessionId → 直接使用
    ├── 找到但无 toolSessionId → requestToolSession（重建）
    └── 未找到 → createSessionAsync:
        → Redis 分布式锁: skill:session:create:{domain}:{type}:{sessionId}:{ak}
          TTL=15s, 重试间隔=100ms
        → 二次检查（防并发）
        → 创建 SkillSession(userId=ownerWelinkId, domain=im, type=group/direct)
        → 缓存 pending 消息
        → 发送 create_session 到 Gateway
  ↓
Step 5: [仅 direct] 消息持久化
  MessagePersistenceService.finalizeActiveAssistantTurn()
  SkillMessageService.saveUserMessage(sessionId, content)
  ↓
Step 6: 发送 invoke(chat) 到 Gateway
  GatewayRelayService.sendInvokeToGateway(cmd)
  ↓
Step 7: 返回 200 OK（异步处理）
```

### 6.2 IM 出站

**触发：** `GatewayMessageRouter` 收到 `tool_event` 且会话为 IM 域

**出站逻辑：**
```
判断 session.businessSessionDomain == "im"
  ↓
ImOutboundService.sendTextToIm(sessionType, businessSessionId, content, assistantAccount)
  ↓
根据 sessionType 选择端点:
  group → POST /v1/welinkim/im-service/chat/app-group-chat
  direct → POST /v1/welinkim/im-service/chat/app-user-chat
  ↓
请求体:
{
  "appMsgId": "uuid",
  "senderAccount": "ai-bot-account",
  "sessionId": "im-chat-12345",
  "contentType": 13,              // TEXT
  "content": "AI 回复内容",
  "clientSendTime": 1710000000000
}
认证: Authorization: Bearer {im-token}
```

---

## 七、Session 重建机制详解

### 触发条件

1. 发消息时 `toolSessionId` 为空（首次创建尚未就绪）
2. Gateway 返回 `tool_error(reason=session_not_found)`
3. OpenCode 上下文溢出

### 重建流程

```
SessionRebuildService.rebuildToolSession(sessionId, session, pendingMessage)

Step 1: 缓存待发消息
  pendingRebuildMessages.put(sessionId, pendingMessage)
  └── Caffeine: TTL=5min, maxSize=1000

Step 2: 广播 "retry" 状态
  broadcastStreamMessage(sessionId, userId, StreamMessage(session.status, retry))
  └── 前端显示 "正在重连..."

Step 3: 发送 create_session 到 Gateway
  invoke(action=create_session, payload={title})

Step 4: 等待 Gateway 回复 session_created
  └── GatewayMessageRouter.handleSessionCreated()

Step 5: 更新 toolSessionId
  sessionService.updateToolSessionId(numericId, newToolSessionId)

Step 6: 消费缓存消息
  pendingMessage = consumePendingMessage(sessionId)
  if (pendingMessage != null) {
    invoke(action=chat, payload={text: pendingMessage, toolSessionId: newToolSessionId})
  }

Step 7: 广播 idle 状态
  broadcastStreamMessage(sessionId, userId, StreamMessage(session.status, idle))
```

---

## 八、数据持久化

### 8.1 SkillSession 表

```sql
CREATE TABLE skill_sessions (
  id                      BIGINT PRIMARY KEY,     -- Snowflake ID
  user_id                 VARCHAR(128) NOT NULL,
  ak                      VARCHAR(256),
  tool_session_id         VARCHAR(256),            -- OpenCode 会话 ID
  title                   VARCHAR(512),
  status                  VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE/IDLE/CLOSED
  business_session_domain VARCHAR(20) DEFAULT 'miniapp', -- miniapp/im
  business_session_type   VARCHAR(20),                   -- group/direct
  business_session_id     VARCHAR(256),                  -- IM 会话 ID
  assistant_account       VARCHAR(256),
  created_at              TIMESTAMP DEFAULT NOW(),
  last_active_at          TIMESTAMP DEFAULT NOW()
);
```

### 8.2 SkillMessage 表

```sql
CREATE TABLE skill_messages (
  id           BIGINT PRIMARY KEY,     -- Snowflake ID
  message_id   VARCHAR(256) NOT NULL,  -- OpenCode messageId
  session_id   BIGINT NOT NULL,
  seq          INT NOT NULL,
  message_seq  INT NOT NULL,
  role         VARCHAR(20),            -- USER/ASSISTANT/SYSTEM/TOOL
  content      LONGTEXT,
  content_type VARCHAR(20),            -- MARKDOWN/CODE/PLAIN
  created_at   TIMESTAMP DEFAULT NOW(),
  meta         LONGTEXT                -- JSON 元数据
);
```

### 8.3 SkillMessagePart 表

```sql
CREATE TABLE skill_message_parts (
  id            BIGINT PRIMARY KEY,
  message_id    VARCHAR(256) NOT NULL,
  session_id    BIGINT NOT NULL,
  part_id       VARCHAR(256) NOT NULL,
  seq           INT NOT NULL,
  part_type     VARCHAR(20),          -- text/reasoning/tool/file/permission/step-finish
  content       LONGTEXT,
  tool_name     VARCHAR(100),
  tool_call_id  VARCHAR(256),
  tool_status   VARCHAR(20),
  tool_input    LONGTEXT,             -- JSON
  tool_output   LONGTEXT,
  tool_error    LONGTEXT,
  tool_title    VARCHAR(512),
  file_name     VARCHAR(256),
  file_url      VARCHAR(1024),
  file_mime     VARCHAR(100),
  tokens_in     INT,
  tokens_out    INT,
  cost          DOUBLE,
  finish_reason VARCHAR(50)
);
```

### 8.4 持久化时机

| StreamMessage type | 持久化动作 |
|-------------------|-----------|
| `text.done` | 保存 text part |
| `thinking.done` | 保存 reasoning part |
| `tool.update`（completed/error） | 保存 tool part |
| `question`（completed/error） | 保存 tool part（toolName=question） |
| `permission.ask` | 保存 permission part |
| `permission.reply` | 更新 permission part |
| `file` | 保存 file part |
| `step.done` | 保存 step-finish part（含 tokens/cost） |
| `session.status(idle)` | 结束活跃 assistant 消息 |
