# OpenCode 协议 → 自定义协议映射总表

## 概述

本文档描述 OpenCode SDK 原始事件如何经过四层协议转换，最终到达 Miniapp 前端的完整映射关系。

```
OpenCode SDK 事件
  ↓ (1) Plugin: UpstreamEventExtractor
Plugin tool_event 消息
  ↓ (2) Gateway: 透传 + 注入路由上下文
Gateway tool_event 消息
  ↓ (3) Skill Server: OpenCodeEventTranslator
StreamMessage
  ↓ (4) Miniapp: StreamAssembler
MessagePart (UI 渲染)
```

---

## 一、完整映射链路

### 1.1 文本流（核心路径）

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

message.part.delta              tool_event             tool_event           text.delta              Part(text, streaming)
  properties:                     toolSessionId          +ak,userId           partId                  content += delta
    sessionID                     event: raw             +source              content = delta         isStreaming = true
    messageID                                            +traceId             messageId
    partID                                                                    role = assistant
    delta: "Hello "

message.part.delta              tool_event             tool_event           text.delta              Part(text, streaming)
  delta: "World"                                                             content = "World"       content += "World"

message.part.updated            tool_event             tool_event           text.done               Part(text, final)
  part:                                                                      partId                  content = full text
    type: "text"                                                             content = full text     isStreaming = false
    text: "Hello World"                                                      messageId

message.updated                 tool_event             tool_event           step.done               Message.meta
  info:                                                                      tokens                  tokens, cost, reason
    finish: "stop"                                                           cost
    role: "assistant"                                                        reason = "stop"

session.idle                    tool_event             tool_event           —                       —
                                + tool_done                                 session.status(idle)    finalizeAll()
                                  (ToolDoneCompat)                                                  isStreaming = false
```

### 1.2 思考过程（Extended Thinking）

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

message.part.delta              tool_event             tool_event           thinking.delta          Part(thinking, streaming)
  properties:                                                                content = delta
    partID (reasoning part)

message.part.updated            tool_event             tool_event           thinking.done           Part(thinking, final)
  part:
    type: "reasoning"
    text: "分析过程..."
```

### 1.3 工具执行

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

message.part.updated            tool_event             tool_event           tool.update(pending)    Part(tool)
  part:                                                                      toolName = "bash"       toolStatus = pending
    type: "tool"                                                             toolCallId              isStreaming = true
    tool: "bash"                                                             status = "pending"
    callID: "call-001"                                                       input = {command}
    state:
      status: "pending"
      input: {command: "ls"}

message.part.updated            tool_event             tool_event           tool.update(running)    Part(tool)
  state.status: "running"                                                    status = "running"      toolStatus = running

message.part.updated            tool_event             tool_event           tool.update(completed)  Part(tool)
  state:                                                                     status = "completed"    toolStatus = completed
    status: "completed"                                                      output = "file1\n..."   isStreaming = false
    output: "file1\nfile2"
    title: "Execute: ls"
```

### 1.4 交互式提问

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

question.asked                  tool_event             tool_event           question                Part(question)
  questions[0]:                                                              toolName = "question"   question = "确认？"
    requestID: "req-001"                                                     toolCallId              options = [...]
    tool.callID: "call-q1"                                                   status = "running"      answered = false
    question: "确认？"                                                        questionInfo = {
    options: ["是", "否"]                                                       header, question,
    header: "确认操作"                                                           options
                                                                             }

  ── 用户回答 ──

Miniapp: POST /messages {content:"是", toolCallId:"call-q1"}
  ↓
SkillServer: invoke(question_reply, {answer:"是", toolCallId:"call-q1", toolSessionId})
  ↓
Gateway → Plugin: invoke(question_reply)
  ↓
Plugin: QuestionReplyAction
  GET /question → 查找 req-001
  POST /question/req-001/reply {answers:[["是"]]}
  ↓
Plugin → Gateway: tool_done

message.part.updated            tool_event             tool_event           question(completed)     Part(question)
  part:                                                                      status = "completed"    answered = true
    tool: "question"                                                         output = "是"
    state.status: "completed"
    state.output: "是"
```

### 1.5 权限请求

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

permission.asked                tool_event             tool_event           permission.ask          Part(permission)
  properties:                                           或                   permissionId            permResolved = false
    sessionID                                          permission_request   permType
    permissionID                                                            title
    tool: "write"

  ── 用户授权 ──

Miniapp: POST /permissions/{permId} {response:"once"}
  ↓
SkillServer:
  invoke(permission_reply, {permissionId, response:"once", toolSessionId})
  + 推送 permission.reply StreamMessage 到前端
  ↓
Gateway → Plugin: invoke(permission_reply)
  ↓
Plugin: PermissionReplyAction
  POST /session/{id}/permissions/{permId} {response:"once"}
  ↓
Plugin → Gateway: tool_done

permission.updated              tool_event             tool_event           permission.reply        Part(permission)
  status: "granted"                                                          response = "once"       permResolved = true
  response: "once"                                                                                   permissionResponse = "once"
```

### 1.6 会话状态

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

session.status                  tool_event             tool_event           session.status          handleStreamMessage:
  status.type: "busy"                                                        sessionStatus = "busy"  isStreaming = true

session.idle                    tool_event             tool_event           session.status          finalizeAll:
                                + tool_done                                  sessionStatus = "idle"  isStreaming = false

session.error                   tool_event             tool_event           session.error           finalizeAll + setError
  error: "Context overflow"                                                  error = "..."

session.updated                 tool_event             tool_event           session.title           更新会话标题
  info.title: "新标题"                                                       title = "新标题"
```

### 1.7 Agent 状态（非事件驱动）

```
Plugin 连接/断开              Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

Plugin WS 连接成功              register               register             —                       —
                                                       ↓
                               register_ok             registerOk           agent_online            agentStatus = online
                                                       生成 agent_online    广播 agent.online

Plugin WS 断开                 —                       检测断开              agent_offline           agentStatus = offline
                                                       生成 agent_offline   广播 agent.offline

心跳超时 (90s)                  —                       检测超时              agent_offline           agentStatus = offline
                                                       生成 agent_offline   广播 agent.offline
```

### 1.8 步骤统计

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

message.part.updated            tool_event             tool_event           step.start              activeMessageIds.add
  part.type: "step-start"

message.part.updated            tool_event             tool_event           step.done               message.meta = {
  part:                                                                      tokens = {                tokens, cost, reason
    type: "step-finish"                                                        input: 1500,          }
    usage: {                                                                   output: 800,
      inputTokens: 1500,                                                       cache: {read, write}
      outputTokens: 800,                                                     }
      cacheReadInputTokens: 500,                                             cost = 0.0125
      cacheCreationInputTokens: 200                                          reason = "stop"
    }
    cost: 0.0125
    finishReason: "stop"
```

### 1.9 文件输出

```
OpenCode SDK                    Plugin                Gateway              Skill Server            Miniapp
──────────────────────────────────────────────────────────────────────────────────────────────────────────

message.part.updated            tool_event             tool_event           file                    Part(file)
  part:                                                                      fileName                fileName
    type: "file"                                                             fileUrl                 fileUrl
    fileName: "out.png"                                                      fileMime                fileMime
    fileUrl: "/files/out.png"
    fileMime: "image/png"
```

---

## 二、特殊协议转换

### 2.1 用户消息时序处理

**问题：** OpenCode 的 `message.part.updated`（用户文本）和 `message.updated`（含 role=user）到达顺序不确定。

**四层处理链：**

```
情况 A: message.part.updated 先到

  OpenCode:  message.part.updated (text, role未知)
  Plugin:    tool_event (原样透传)
  Gateway:   tool_event (透传)
  SkillSvr:  翻译器检查 → role 未缓存 → 缓存文本 → 返回 null（不推送前端）

  OpenCode:  message.updated (role=user)
  Plugin:    tool_event
  Gateway:   tool_event
  SkillSvr:  翻译器 → role=user → 从缓存取文本 → 发送 text.done → 清除缓存

情况 B: message.updated 先到

  OpenCode:  message.updated (role=user)
  Plugin:    tool_event
  Gateway:   tool_event
  SkillSvr:  翻译器 → 缓存 role=user → 无缓存文本 → 返回 null

  OpenCode:  message.part.updated (text)
  Plugin:    tool_event
  Gateway:   tool_event
  SkillSvr:  翻译器 → 查询 role=user → 直接发送 text.done
```

### 2.2 tool_done 发送时序

**问题：** chat Action 完成和 session.idle 事件可能以任意顺序到达。

```
情况 A: chat 先完成

  Plugin 收到 invoke(chat)
    → ToolDoneCompat.handleInvokeStarted() → pending.add(sessionId)
  Plugin 执行 prompt() 成功
    → ToolDoneCompat.handleInvokeCompleted() → 发送 tool_done ✓
  OpenCode 发出 session.idle
    → ToolDoneCompat.handleSessionIdle() → 已在 awaiting 中 → 不发送 ✗

情况 B: session.idle 先到

  Plugin 收到 invoke(chat)
    → pending.add(sessionId)
  OpenCode 发出 session.idle（prompt 还在执行）
    → ToolDoneCompat.handleSessionIdle() → sessionId ∈ pending → 不发送 ✗
  Plugin prompt() 完成
    → ToolDoneCompat.handleInvokeCompleted() → 发送 tool_done ✓

情况 C: 非 chat 场景（如 session.idle 自然到来）

  OpenCode 发出 session.idle
    → ToolDoneCompat.handleSessionIdle() → 不在 pending 也不在 awaiting → 发送 tool_done ✓
```

### 2.3 completionCache 竞态防护

**问题：** Gateway 返回 `tool_done` 后，可能还有迟到的 `tool_event` 到达。

```
时间线:
  T+0: tool_event(text.delta)  → 翻译 → 推送 ✓
  T+1: tool_event(text.done)   → 翻译 → 推送 ✓
  T+2: tool_done               → completionCache.put(sessionId, 5s)
                                → 广播 session.status: idle ✓
  T+3: tool_event(text.delta)  → completionCache 命中 → 抑制 ✗
       （迟到的 delta，网络延迟导致）

  例外: tool_event(question/permission) → 不被抑制 ✓
        新的 chat invoke → 清除 completionCache
```

### 2.4 Session 重建协议

```
触发: 发消息时 toolSessionId 为空

Miniapp → SkillServer:  POST /messages {content}
SkillServer:            缓存消息 → 广播 session.status(retry)
SkillServer → Gateway:  invoke(create_session)
Gateway → Plugin:       invoke(create_session)
Plugin:                 CreateSessionAction → SDK create()
Plugin → Gateway:       session_created {welinkSessionId, toolSessionId}
Gateway → SkillServer:  session_created
SkillServer:            更新 toolSessionId
                        消费缓存消息 → invoke(chat)
Gateway → Plugin:       invoke(chat)
Plugin:                 ChatAction → SDK prompt()
...正常事件流...
```

---

## 三、协议字段在各层的名称对应

### 3.1 会话 ID

| 概念 | OpenCode SDK | Plugin | Gateway | Skill Server | Miniapp |
|------|-------------|--------|---------|-------------|---------|
| OpenCode 会话 | sessionID | toolSessionId | toolSessionId | toolSessionId | — |
| Skill 会话 | — | welinkSessionId | welinkSessionId | welinkSessionId / sessionId | id |
| Agent 标识 | — | — | ak | ak | ak |
| 用户标识 | — | — | userId | userId | userId |

### 3.2 消息标识

| 概念 | OpenCode SDK | Plugin | Gateway | Skill Server | Miniapp |
|------|-------------|--------|---------|-------------|---------|
| 消息 ID | info.id / messageID | — | — | messageId | messageId |
| 片段 ID | part.id / partID | — | — | partId | partId |
| 工具调用 ID | callID / tool.callID | toolCallId | — | toolCallId | toolCallId |
| 权限 ID | permissionID | permissionId | — | permissionId | permissionId |
| 问题请求 ID | requestID | requestId | — | — | — |

### 3.3 事件/消息类型

| OpenCode 事件 | Gateway 消息 | StreamMessage | 前端 Part |
|-------------|-------------|--------------|----------|
| message.part.delta (text) | tool_event | text.delta | text (streaming) |
| message.part.updated (text) | tool_event | text.done | text (final) |
| message.part.delta (reasoning) | tool_event | thinking.delta | thinking (streaming) |
| message.part.updated (reasoning) | tool_event | thinking.done | thinking (final) |
| message.part.updated (tool) | tool_event | tool.update | tool |
| message.part.updated (step-start) | tool_event | step.start | — |
| message.part.updated (step-finish) | tool_event | step.done | — (meta) |
| message.part.updated (file) | tool_event | file | file |
| message.updated (user) | tool_event | text.done | text (final) |
| message.updated (finish) | tool_event | step.done | — (meta) |
| message.part.removed | tool_event | — (缓存清除) | — |
| session.status | tool_event | session.status | — (状态控制) |
| session.idle | tool_event + tool_done | session.status(idle) | — (finalize) |
| session.updated | tool_event | session.title | — (标题更新) |
| session.error | tool_event | session.error | — (错误) |
| question.asked | tool_event | question | question |
| permission.asked | tool_event | permission.ask | permission |
| permission.updated | tool_event | permission.ask/reply | permission |
| — (Agent 注册) | agent_online | agent.online | — (状态) |
| — (Agent 断开) | agent_offline | agent.offline | — (状态) |

---

## 四、各层协议封装/解封装

### 4.1 上行方向（OpenCode → Miniapp）

```
Layer 0: OpenCode SDK 事件
  { type: "message.part.delta", properties: { sessionID, messageID, partID, delta } }

Layer 1: Plugin 封装为 GatewayMessage
  { type: "tool_event", toolSessionId, event: <Layer0原始事件> }

Layer 2: Gateway 注入路由上下文
  { type: "tool_event", ak, userId, source, traceId, toolSessionId, event }

Layer 3: Skill Server 翻译为 StreamMessage
  { type: "text.delta", welinkSessionId, messageId, partId, content, role, seq, emittedAt }

Layer 4: Miniapp StreamAssembler → MessagePart
  { partId, type: "text", content: "累积文本", isStreaming: true }
```

### 4.2 下行方向（Miniapp → OpenCode）

```
Layer 4: Miniapp 用户操作
  POST /sessions/{id}/messages { content: "hello" }

Layer 3: Skill Server 构造 invoke
  { type: "invoke", ak, userId, source, welinkSessionId, action: "chat",
    payload: '{"text":"hello","toolSessionId":"xxx"}' }

Layer 2: Gateway 验证并转发
  Redis PUBLISH agent:{ak} = { type: "invoke", action: "chat", payload, welinkSessionId }
  （已剥离 userId, source）

Layer 1: Plugin 执行 Action
  ChatAction → client.session.prompt({ path: { id: toolSessionId },
                body: { parts: [{ type: 'text', text: 'hello' }] } })

Layer 0: OpenCode SDK 调用
  POST /session/{id}/prompt { parts: [{ type: "text", text: "hello" }] }
```

---

## 五、协议版本与兼容性

### 5.1 当前版本

| 组件 | 协议版本 | 说明 |
|------|---------|------|
| GatewayMessage | v1 | 14 种消息类型 |
| StreamMessage | v1 | 19 种消息类型 |
| OpenCode SDK 事件 | v1+v2 混合 | v1: info/part 嵌套, v2: 扁平 properties |
| Plugin Config | v1 | config_version: 1 |

### 5.2 OpenCode SDK 事件版本差异

**v1 风格**（嵌套对象）:
```json
{
  "type": "message.updated",
  "properties": {
    "info": { "id": "...", "sessionID": "...", "role": "..." }
  }
}
```

**v2 风格**（扁平属性）:
```json
{
  "type": "message.part.delta",
  "properties": {
    "sessionID": "...",
    "messageID": "...",
    "partID": "...",
    "delta": "..."
  }
}
```

Plugin 的 UpstreamEventExtractor 同时支持两种风格，通过事件类型确定提取路径。
