# Layer① 协议：Miniapp ↔ Skill Server

> 本文档基于代码实现逐项核对，确保协议描述与实际行为一致。

## 概述

| 方向 | 传输方式 | 端点 |
|---|---|---|
| Miniapp → Skill Server | REST API (HTTP) | `/api/skill/**` |
| Skill Server → Miniapp | WebSocket | `ws://{host}/ws/skill/stream` |

**认证方式**：Cookie `userId`（所有接口统一）

> [!IMPORTANT]
> `welinkSessionId` 在所有 JSON 传输中必须编码为 **字符串**，禁止使用 JSON 数字类型。
> 后端通过 `@JsonSerialize(using = ToStringSerializer.class)` 保证；前端显式转为 string。

---

## 一、REST API（Miniapp → Skill Server）

### 通用响应包装

所有 REST API 返回 HTTP 200，通过 `ApiResponse` 包装：

```json
// 成功
{ "code": 0, "data": { ... } }
// 失败
{ "code": 400, "errormsg": "错误描述" }
```

---

### API-1：创建会话

```
POST /api/skill/sessions
```

**请求 Body**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `ak` | string | 否 | Agent 的 Access Key |
| `title` | string | 否 | 会话标题 |
| `imGroupId` | string | 否 | 关联的 IM 群组 ID |

**响应 `data`** — `SkillSession` 对象：

| 字段 | 类型 | 说明 |
|---|---|---|
| `welinkSessionId` | string | 会话 ID（Long → String 序列化） |
| `userId` | string | 用户 ID |
| `ak` | string | Agent AK |
| `toolSessionId` | string | OpenCode 侧的 session ID（初始为 null） |
| `title` | string | 会话标题 |
| `status` | string | `ACTIVE` / `IDLE` / `CLOSED` |
| `imGroupId` | string | IM 群组 ID |
| `createdAt` | string | 创建时间 |
| `updatedAt` | string | 最后活跃时间（Java 字段名 `lastActiveAt`） |

**副作用**：若 `ak` 非空，发送 `create_session` invoke 到 AI-Gateway。

**代码**：[SkillSessionController.java L53-79](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L53-L79)

---

### API-2：会话列表

```
GET /api/skill/sessions?page=0&size=20&status=ACTIVE&ak=xxx&imGroupId=yyy
```

**Query 参数**：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `page` | int | `0` | 页码 |
| `size` | int | `20` | 每页条数 |
| `status` | string | — | 按状态过滤 |
| `ak` | string | — | 按 Agent AK 过滤 |
| `imGroupId` | string | — | 按 IM 群组过滤 |

**响应 `data`** — `PageResult<SkillSession>`：

```json
{
  "content": [ /* SkillSession 对象数组 */ ],
  "totalElements": 100,
  "number": 0,
  "size": 20
}
```

**代码**：[SkillSessionController.java L81-123](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L81-L123)

---

### API-3：查询单个会话

```
GET /api/skill/sessions/{id}
```

**路径参数**：`id` — welinkSessionId

**响应 `data`** — `SkillSession` 对象（同 API-1）。

**代码**：[SkillSessionController.java](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java)

---

### API-4：关闭会话

```
DELETE /api/skill/sessions/{id}
```

**响应 `data`**：

```json
{ "status": "closed", "welinkSessionId": "123..." }
```

**副作用**：
1. 状态改为 `CLOSED`
2. 若 `ak != null && toolSessionId != null`，发送 `close_session` invoke 到 Gateway

**代码**：[SkillSessionController.java L125-163](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L125-L163)

---

### API-5：中止会话

```
POST /api/skill/sessions/{id}/abort
```

**响应 `data`**：

```json
{ "status": "aborted", "welinkSessionId": "123..." }
```

**错误**：`409` — 会话已关闭

**副作用**：若 `ak != null && toolSessionId != null`，发送 `abort_session` invoke 到 Gateway（不改变 session 状态）。

**代码**：[SkillSessionController.java L165-208](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L165-L208)

---

### API-6：发送消息

```
POST /api/skill/sessions/{sessionId}/messages
```

**请求 Body**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | string | 是 | 消息文本 |
| `toolCallId` | string | 否 | 存在时走 `question_reply` 路由 |

**响应 `data`** — `ProtocolMessageView` 对象（见下方定义）。

**三条分支**：
1. `toolSessionId == null` → 触发 `rebuildToolSession()`
2. `toolCallId` 有值 → `question_reply` action
3. 否则 → `chat` action

**错误**：`400` content 为空 / `409` 会话已关闭

**代码**：[SkillMessageController.java L75-145](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L75-L145)

---

### API-7：消息历史

```
GET /api/skill/sessions/{sessionId}/messages?page=0&size=50
```

**响应 `data`** — `PageResult<ProtocolMessageView>`。

#### ProtocolMessageView 结构

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 消息 ID（优先 `messageId`，回退 DB `id`） |
| `welinkSessionId` | string | 会话 ID |
| `seq` | int | 序号 |
| `messageSeq` | int | 消息序号 |
| `role` | string | `user` / `assistant` |
| `content` | string | 消息文本 |
| `contentType` | string | `plain` / `markdown` |
| `createdAt` | string | 创建时间 |
| `meta` | object | 元信息（tokens、cost 等） |
| `parts` | array | `ProtocolMessagePart` 数组 |

#### ProtocolMessagePart 结构

基础字段（所有 type 共用）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `partId` | string | Part ID |
| `partSeq` | int | Part 序号 |
| `type` | string | `text` / `thinking` / `tool` / `question` / `permission` / `file` |
| `content` | string | 文本内容 |

按 type 扩展的字段：

| type | 额外字段 |
|---|---|
| `tool` | `toolName`, `toolCallId`, `status`, `input`, `output`, `error`, `title` |
| `question` | `toolCallId`, `status`, `input`, `header`, `question`, `options` |
| `permission` | `permissionId`, `permType`, `metadata`, `response`, `status` |
| `file` | `fileName`, `fileUrl`, `fileMime` |

> 使用 `@JsonInclude(NON_NULL)`，null 字段不输出。

**代码**：[SkillMessageController.java L147-184](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L147-L184)

---

### API-8：转发到 IM

```
POST /api/skill/sessions/{sessionId}/send-to-im
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | string | 是 | 要转发的文本 |
| `chatId` | string | 否 | IM 聊天 ID，空则回退到 `session.imGroupId` |

**响应 `data`**：`{ "success": true }`

**代码**：[SkillMessageController.java L186-235](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L186-L235)

---

### API-9：权限回复

```
POST /api/skill/sessions/{sessionId}/permissions/{permId}
```

**请求 Body**：

| 字段 | 类型 | 必填 | 合法值 |
|---|---|---|---|
| `response` | string | 是 | `once` / `always` / `reject` |

**响应 `data`**：

```json
{ "welinkSessionId": "123...", "permissionId": "perm-xxx", "response": "once" }
```

**副作用**：
1. 发送 `permission_reply` invoke 到 Gateway
2. 推送 `permission.reply` StreamMessage 到 WS

**代码**：[SkillMessageController.java L237-312](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L237-L312)

---

### API-10：在线 Agent 列表

```
GET /api/skill/agents
```

**响应 `data`** — `List<AgentSummary>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `ak` | string | Agent Access Key |
| `status` | string | `ONLINE` |
| `deviceName` | string | 设备名 |
| `os` | string | 操作系统 |
| `toolType` | string | 工具类型（小写，如 `opencode`） |
| `toolVersion` | string | 工具版本 |
| `connectedAt` | string | 连接时间 |

**代码**：[AgentQueryController.java](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/AgentQueryController.java)

---

## 二、WebSocket 协议（Skill Server → Miniapp）

### 连接

```
ws://{host}/ws/skill/stream
```

- **认证**：Cookie `userId`
- **订阅模型**：按 userId，一个用户所有 session 的事件推到同一连接
- **连接后**：自动推送所有 ACTIVE session 的 `snapshot` + `streaming`
- **客户端消息**：`{"action": "resume"}` — 重发 `snapshot` + `streaming`
- **重连**：指数退避，最大 30 秒

**代码**：[SkillStreamHandler.java](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java)

---

### StreamMessage 通用字段

所有 WS 事件共享以下基础字段（`@JsonInclude(NON_NULL)`，null 不输出）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | string | 事件类型 |
| `seq` | long | 传输序号（per-session 递增） |
| `welinkSessionId` | string | 会话 ID |
| `emittedAt` | string | 发射时间（ISO 8601） |

---

### 事件类型总览

| 类型 | 用途 | 基于 Builder |
|---|---|---|
| `text.delta` | 文本增量 | partBuilder |
| `text.done` | 文本完成 | partBuilder |
| `thinking.delta` | 思考增量 | partBuilder |
| `thinking.done` | 思考完成 | partBuilder |
| `tool.update` | 工具调用状态 | partBuilder |
| `question` | 交互式提问 | partBuilder |
| `file` | 文件附件 | partBuilder |
| `permission.ask` | 权限请求 | messageBuilder |
| `permission.reply` | 权限回复 | 直接构造 |
| `step.start` | 步骤开始 | messageBuilder |
| `step.done` | 步骤完成（含 tokens/cost） | messageBuilder |
| `session.status` | 会话状态变更 | baseBuilder |
| `session.title` | 会话标题更新 | baseBuilder |
| `session.error` | 会话错误 | baseBuilder |
| `agent.online` | Agent 上线 | 直接构造 |
| `agent.offline` | Agent 下线 | 直接构造 |
| `snapshot` | 历史消息快照 | 直接构造 |
| `streaming` | 当前流式状态 | 直接构造 |
| `error` | 通用错误 | 直接构造 |

---

### WS-1/2：text.delta / text.done

```json
{
  "type": "text.delta",
  "seq": 42,
  "welinkSessionId": "123...",
  "emittedAt": "2026-03-12T12:00:00Z",
  "messageId": "msg-xxx",
  "sourceMessageId": "msg-xxx",
  "role": "assistant",
  "partId": "part-uuid",
  "partSeq": 1,
  "content": "增量文本 / 完整文本"
}
```

- `text.delta`：`content` 为增量片段
- `text.done`：`content` 为 part 的完整文本

**来源**：`OpenCodeEventTranslator.translateTextPart()` / `translatePartDelta()`

---

### WS-3/4：thinking.delta / thinking.done

结构与 text.delta/done **完全一致**，只是 type 不同。OpenCode partType `"reasoning"` 映射为 `"thinking"`。

---

### WS-5：tool.update

```json
{
  "type": "tool.update",
  "seq": 45,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "messageId": "msg-xxx",
  "sourceMessageId": "msg-xxx",
  "role": "assistant",
  "partId": "part-uuid",
  "partSeq": 2,
  "toolName": "bash",
  "toolCallId": "call_xxx",
  "status": "running",
  "input": { "command": "ls -la" },
  "output": "file1.txt",
  "error": null,
  "title": "执行命令"
}
```

| 字段 | 来源 |
|---|---|
| `toolName` | `part.tool` |
| `toolCallId` | `part.callID` |
| `status` | `part.state.status` |
| `input` | `part.state.input` |
| `output` | `part.state.output` |
| `error` | `part.state.error` |
| `title` | `part.state.title` |

> 当 `toolName == "question"` 且 `status == "running"` 时，转为 `question` 类型。

---

### WS-6：question

```json
{
  "type": "question",
  "seq": 46,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "messageId": "msg-xxx",
  "sourceMessageId": "msg-xxx",
  "role": "assistant",
  "partId": "part-uuid",
  "partSeq": 3,
  "toolName": "question",
  "toolCallId": "call_yyy",
  "status": "running",
  "input": { "questions": [...] },
  "header": "确认操作",
  "question": "是否继续执行？",
  "options": ["是", "否", "取消"]
}
```

**两条来源路径**：
- `translateQuestion()`：`tool` part 中 `toolName == "question"` 且 `status == "running"`
- `translateQuestionAsked()`：OpenCode `question.asked` 事件

**options 提取**：每个 option 先取 `.label`，无则取文本值。

---

### WS-7：file

```json
{
  "type": "file",
  "seq": 47,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "messageId": "msg-xxx",
  "sourceMessageId": "msg-xxx",
  "role": "assistant",
  "partId": "part-uuid",
  "partSeq": 4,
  "fileName": "result.png",
  "fileUrl": "https://...",
  "fileMime": "image/png"
}
```

---

### WS-8：permission.ask

```json
{
  "type": "permission.ask",
  "seq": 48,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "messageId": "msg-xxx",
  "sourceMessageId": "msg-xxx",
  "role": "assistant",
  "permissionId": "perm-uuid",
  "permType": "file-edit",
  "title": "edit /src/main.ts",
  "metadata": { "path": "/src/main.ts" }
}
```

**两条来源**：
- `translatePermission()`：来自 OpenCode `permission.updated`/`permission.asked` 事件
- `translatePermissionFromGateway()`：来自 Gateway 中转的 `permission_request`

---

### WS-9：permission.reply

```json
{
  "type": "permission.reply",
  "welinkSessionId": "123...",
  "role": "assistant",
  "permissionId": "perm-uuid",
  "response": "once"
}
```

> 无 `emittedAt`（直接 `StreamMessage.builder()` 构造）。
> 在用户通过 REST API-9 回复权限时推送。

---

### WS-10：step.start

```json
{
  "type": "step.start",
  "seq": 50,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "messageId": "msg-xxx",
  "sourceMessageId": "msg-xxx",
  "role": "assistant"
}
```

仅基础字段，无额外数据。

---

### WS-11：step.done

```json
{
  "type": "step.done",
  "seq": 51,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "messageId": "msg-xxx",
  "sourceMessageId": "msg-xxx",
  "role": "assistant",
  "tokens": { "input": 1000, "output": 500 },
  "cost": 0.015,
  "reason": "stop"
}
```

- `tokens`/`cost` 仅在 `step-finish` part 路径中有
- `message.updated`（带 `finish`）路径只有 `reason`

---

### WS-12：session.status

```json
{
  "type": "session.status",
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "sessionStatus": "idle"
}
```

**状态映射**：

| OpenCode 原始值 | 映射后 |
|---|---|
| `idle` / `completed` | `idle` |
| `active` / `running` / `busy` | `busy` |
| `reconnecting` / `retry` / `recovering` | `retry` |

---

### WS-13：session.title

```json
{
  "type": "session.title",
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "title": "AI 自动生成的会话标题"
}
```

---

### WS-14：session.error

```json
{
  "type": "session.error",
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "error": "Something went wrong"
}
```

---

### WS-15/16：agent.online / agent.offline

```json
{ "type": "agent.online",  "seq": 52, "welinkSessionId": "123..." }
{ "type": "agent.offline", "seq": 53, "welinkSessionId": "123..." }
```

- 构造时仅设 `type`，`seq` 和 `welinkSessionId` 由广播路径动态注入
- 无 `emittedAt`、`role` 等字段
- 广播给该 `ak` 关联的所有 session（通过 `sessionService.findByAk(ak)` 查询）

---

### WS-17：snapshot

```json
{
  "type": "snapshot",
  "seq": 1,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "messages": [ /* ProtocolMessageView 数组 */ ]
}
```

- `messages` 格式同 REST API-7 的消息结构
- 触发时机：WS 连接建立 / 客户端 `resume`

---

### WS-18：streaming

```json
{
  "type": "streaming",
  "seq": 2,
  "welinkSessionId": "123...",
  "emittedAt": "...",
  "sessionStatus": "busy",
  "messageId": "msg-xxx",
  "messageSeq": 1,
  "role": "assistant",
  "parts": [ /* ProtocolMessagePart 数组 */ ]
}
```

- `sessionStatus`：`"busy"` 或 `"idle"`
- `parts` 格式同 ProtocolMessagePart
- `messageId`/`messageSeq`/`role` 仅当 parts 非空时存在

---

### WS-19：error

```json
{
  "type": "error",
  "seq": 54,
  "welinkSessionId": "123...",
  "error": "AI session expired and cannot be rebuilt"
}
```

- 来源：Gateway 回报 `tool_error` / rebuild 失败
- 无 `emittedAt`
