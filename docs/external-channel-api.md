# External Channel 接口文档

> ⚠️ **协议变更（v1.4.0 起生效）**：`senderUserAccount` 已从 `chat` 的 payload 移到信封层，且所有 action 必填。
> 调用方必须在信封层传该字段，否则返回 HTTP 400。

> 为 IM 及其他业务模块提供的 WebSocket 推送通道 + REST 入站接口。

---

## 概述

External Channel 提供两个接口：

| 接口 | 类型 | 用途 |
|------|------|------|
| `POST /api/external/invoke` | REST | 业务模块发送消息、回复 question/permission、重建 session |
| `ws://{host}/ws/external/stream` | WebSocket | 业务模块接收 AI 回复的实时流式推送 |

**数据流**：

```
业务模块                          Skill Server                    AI Agent
   │                                  │                              │
   ├── POST /api/external/invoke ──→  │  ── Gateway ──→              │
   │   (chat/question_reply/          │                              │
   │    permission_reply/rebuild)     │                              │
   │                                  │                              │
   │  ←── WS 推送 StreamMessage ───  │  ←── Gateway ──              │
   │   (text.delta/text.done/         │                              │
   │    permission.ask/question/...)  │                              │
```

---

## 1. REST 接口：POST /api/external/invoke

### 1.1 基本信息

| 项目 | 值 |
|------|------|
| URL | `POST /api/external/invoke` |
| Content-Type | `application/json` |
| 认证 | `Authorization: Bearer {token}` |
| Token 配置 | 服务端配置项 `skill.im.inbound-token`，与 IM Inbound 接口共用 |

### 1.2 请求格式

采用**固定信封 + 灵活 payload** 结构：

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "session-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": { ... }
}
```

#### 信封字段（所有 action 必填）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | String | 是 | 操作类型：`chat` / `question_reply` / `permission_reply` / `rebuild` |
| `businessDomain` | String | 是 | 业务域标识，需与 WS 连接时的 `source` 一致（如 `im`） |
| `sessionType` | String | 是 | 会话类型：`group`（群聊）/ `direct`（单聊） |
| `sessionId` | String | 是 | 业务侧会话 ID |
| `assistantAccount` | String | 是 | 助手账号（用于解析对应的 AK 和 Agent） |
| `senderUserAccount` | String | 是 | 本次消息/回复/重建的发起用户账号 |
| `payload` | Object | 是 | 各 action 的专属数据（JSON 对象） |

### 1.3 各 Action 的 Payload 定义

#### action = `chat`（发送消息）

发送用户消息给 AI Agent，触发 AI 回复。

**Payload 字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | String | 是 | 消息文本内容 |
| `msgType` | String | 否 | 消息类型，默认 `text`（当前仅支持 `text`） |
| `imageUrl` | String | 否 | 图片 URL（`msgType=image` 时使用，暂不支持） |
| `chatHistory` | Array | 否 | 聊天上下文历史（群聊场景使用，见下方格式） |

**chatHistory 数组元素格式：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `senderAccount` | String | 发送者账号 |
| `senderName` | String | 发送者显示名 |
| `content` | String | 消息内容 |
| `timestamp` | Long | 消息时间戳（毫秒） |

**请求示例：**

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "content": "帮我查一下今天的天气",
    "msgType": "text"
  }
}
```

**群聊带历史消息示例：**

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "group",
  "sessionId": "grp-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "content": "总结一下上面的讨论",
    "msgType": "text",
    "chatHistory": [
      {"senderAccount": "user-A", "senderName": "张三", "content": "我觉得方案A好", "timestamp": 1713000000000},
      {"senderAccount": "user-B", "senderName": "李四", "content": "我支持方案B", "timestamp": 1713000060000}
    ]
  }
}
```

**处理逻辑：**
1. 解析 `assistantAccount` → 获取 AK 和 Agent 信息
2. 检查 Agent 是否在线（个人助手需要在线检查，业务助手跳过）
3. 群聊场景：将 `chatHistory` 拼接到 prompt 中
4. 查找或创建 Session：
   - Session 不存在 → 自动创建新 Session + 发送消息
   - Session 存在但 toolSessionId 未就绪 → 等待重建后自动发送
   - Session 就绪 → 直接发送给 AI Agent
5. AI 回复通过 WebSocket 推送（若已建立连接）或 IM REST 回调（降级）

---

#### action = `question_reply`（回复 Agent 提问）

当 AI Agent 通过 WebSocket 推送了 `question` 类型消息（询问用户选择），用此 action 回复。

**Payload 字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | String | 是 | 回答内容（选项文本或自由文本） |
| `toolCallId` | String | 是 | 对应 question 消息中的 `toolCallId` |
| `subagentSessionId` | String | 否 | 子 Agent session（多 Agent 场景路由用） |

**请求示例：**

```json
{
  "action": "question_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "content": "A",
    "toolCallId": "toolu_functions.question:0"
  }
}
```

**前置条件：** 必须先通过 WS 收到 `question` 类型的推送消息，从中提取 `toolCallId`。

---

#### action = `permission_reply`（回复权限请求）

当 AI Agent 通过 WebSocket 推送了 `permission.ask` 类型消息（请求执行工具的权限），用此 action 回复。

**Payload 字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `permissionId` | String | 是 | 对应 permission.ask 消息中的 `permissionId` |
| `response` | String | 是 | 授权结果，枚举值：`once` / `always` / `reject` |
| `subagentSessionId` | String | 否 | 子 Agent session（多 Agent 场景路由用） |

**response 枚举说明：**

| 值 | 含义 |
|------|------|
| `once` | 仅本次授权 |
| `always` | 本会话内永久授权（同类型工具不再询问） |
| `reject` | 拒绝授权（Agent 将取消本次工具调用） |

**请求示例：**

```json
{
  "action": "permission_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "permissionId": "per_d897d347c001WFYI2DbWVsuMuA",
    "response": "once"
  }
}
```

**前置条件：** 必须先通过 WS 收到 `permission.ask` 类型的推送消息，从中提取 `permissionId`。

---

#### action = `rebuild`（重建会话）

主动触发 Session 的 toolSession 重建。适用于 Session 异常需要恢复的场景。

**Payload 字段：** 无必填字段，传空对象 `{}` 即可。

**请求示例：**

```json
{
  "action": "rebuild",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {}
}
```

**处理逻辑：**
- Session 存在 → 清空 toolSessionId，重新请求 Gateway 创建
- Session 不存在 → 创建新 Session + toolSession

---

### 1.4 响应格式

所有响应均为 JSON，HTTP 状态码 + body 两层表示。

#### 成功响应

**HTTP 200**

```json
{
  "code": 0,
  "data": {
    "businessSessionId": "dm-001",
    "welinkSessionId": "2711748171393929216"
  }
}
```

| data 字段 | 类型 | 说明 |
|-----------|------|------|
| `businessSessionId` | String | 业务侧会话 ID（原样返回请求中的 sessionId） |
| `welinkSessionId` | String | Skill Server 内部会话 ID，**与 WS 推送消息信封中的 `sessionId` 一致**，用于关联推送消息 |

> - `code=0` 表示消息已接收，不代表 AI 已回复。AI 回复通过 WebSocket 异步推送。
> - 首次 chat 时 session 异步创建，`welinkSessionId` 可能为 `null`。后续 WS 推送消息中的 `sessionId` 就是该值。
> - 第二次及以后的 chat，`welinkSessionId` 一定有值。

#### 错误响应

**HTTP 400（信封/Payload 校验失败）**

校验阶段尚未进入 session 处理，不返回 data。

```json
{
  "code": 400,
  "errormsg": "具体错误信息"
}
```

| errormsg | 原因 |
|----------|------|
| `action is required` | 缺少 action 字段 |
| `Invalid action: xxx` | action 不是 chat/question_reply/permission_reply/rebuild |
| `businessDomain is required` | 缺少 businessDomain |
| `Invalid sessionType` | sessionType 不是 group/direct |
| `sessionId is required` | 缺少 sessionId |
| `assistantAccount is required` | 缺少 assistantAccount |
| `payload.content is required for chat` | chat 缺少 content |
| `payload.content is required for question_reply` | question_reply 缺少 content |
| `payload.toolCallId is required for question_reply` | question_reply 缺少 toolCallId |
| `payload.permissionId is required` | permission_reply 缺少 permissionId |
| `payload.response must be once/always/reject` | response 值不合法 |
| `senderUserAccount is required` | 缺少信封字段 senderUserAccount（任何 action） |

**HTTP 401（认证失败）**

```json
{
  "code": 401,
  "errormsg": "Missing token"
}
```

| errormsg | 原因 |
|----------|------|
| `Missing token` | 缺少 Authorization header 或格式不是 `Bearer xxx` |
| `Invalid token` | Token 不匹配 |
| `Inbound token is not configured` | 服务端未配置 token |

**HTTP 200 + 业务错误码**

业务错误也会尽量返回 session 信息（如果 session 已存在）。

```json
{
  "code": 404,
  "errormsg": "Session not found or not ready",
  "data": {
    "businessSessionId": "dm-001",
    "welinkSessionId": "2711748171393929216"
  }
}
```

| code | errormsg | 原因 | data |
|------|----------|------|------|
| 404 | `Invalid assistant account` | assistantAccount 解析失败 | 无 data |
| 404 | `Session not found or not ready` | question_reply/permission_reply 时 Session 不存在或未就绪 | 有 businessSessionId，welinkSessionId 可能有值 |

---

## 2. WebSocket 接口：/ws/external/stream

### 2.1 基本信息

| 项目 | 值 |
|------|------|
| URL | `ws://{host}:{port}/ws/external/stream` |
| 协议 | WebSocket (RFC 6455) |
| 认证 | Sec-WebSocket-Protocol 子协议 |
| 方向 | 服务端 → 客户端推送（客户端只发心跳） |

### 2.2 连接认证

通过 `Sec-WebSocket-Protocol` HTTP header 传递认证信息。

**格式：** `auth.{base64url_encoded_json}`

**JSON 内容：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `token` | String | 是 | 认证 Token（与 REST 接口的 Token 相同） |
| `source` | String | 是 | 模块标识（如 `im`），需与 REST 请求的 `businessDomain` 一致 |
| `instanceId` | String | 是 | 实例标识（如 `im-node-1`），用于区分同一模块的多个实例 |

**构造示例（伪代码）：**

```javascript
const authPayload = JSON.stringify({
  token: "your-token",
  source: "im",
  instanceId: "im-node-1"
});
const subprotocol = "auth." + base64url_encode(authPayload);

// WebSocket 握手
ws = new WebSocket("ws://host:port/ws/external/stream", [subprotocol]);
```

**Java 示例：**

```java
String json = "{\"token\":\"xxx\",\"source\":\"im\",\"instanceId\":\"im-1\"}";
String protocol = "auth." + Base64.getUrlEncoder().withoutPadding()
    .encodeToString(json.getBytes(StandardCharsets.UTF_8));

WebSocketClient client = new StandardWebSocketClient();
WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
headers.setSecWebSocketProtocol(Collections.singletonList(protocol));
client.execute(handler, headers, URI.create("ws://host:port/ws/external/stream"));
```

**认证失败：** 握手被拒绝，HTTP 返回非 101 状态码。

| 失败原因 | 说明 |
|----------|------|
| 无 Sec-WebSocket-Protocol header | 连接拒绝 |
| Token 不匹配 | 连接拒绝 |
| 缺少 source | 连接拒绝 |
| 缺少 instanceId | 连接拒绝 |

### 2.3 心跳机制

| 方向 | 消息 | 间隔 |
|------|------|------|
| 客户端 → 服务端 | `{"action":"ping"}` | 建议每 **10~30 秒** 发送一次 |
| 服务端 → 客户端 | `{"action":"pong"}` | 收到 ping 后立即回复 |
| 服务端超时断开 | - | **60 秒**无任何客户端消息则断开连接 |

**重要：** 客户端必须定期发送 ping 保持连接活跃，否则将被服务端超时断开。

### 2.4 连接模型

**服务级连接**：一个 `source`（如 `im`）可以有多个实例（`instanceId`）连接。服务端维护连接池：

```
source → { instanceId → WebSocketSession }

示例：
"im"  → { "im-node-1" → ws1, "im-node-2" → ws2 }
"crm" → { "crm-node-1" → ws3 }
```

推送消息时，**同一 source 的所有实例都会收到**（广播），由业务方根据消息中的 `sessionId` 自行路由。

同一 `source+instanceId` 重连时，旧连接自动被替换。

### 2.5 推送消息格式

所有推送消息为 JSON 文本帧，**直接是 StreamMessage 对象**（与 miniapp 前端收到的格式完全一致，无信封包装）：

```json
{
  "type": "text.delta",
  "seq": 42,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:00.165Z",
  "role": "assistant",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "partId": "prt_xxx",
  "partSeq": 3,
  "content": "你好"
}
```

> **`welinkSessionId`** 即 REST 接口返回的 `welinkSessionId`，可用于关联同一会话的所有推送消息。

**StreamMessage 公共字段（所有类型均可能携带）：**

所有 null 字段在 JSON 中自动省略（`@JsonInclude(NON_NULL)`）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | **必有**。消息类型，见下方所有类型定义 |
| `seq` | Long | **传输序号**（每条 WS 推送递增，用于客户端排序和去重） |
| `welinkSessionId` | String | 会话标识（= REST 返回的 welinkSessionId） |
| `emittedAt` | String | 服务端发送时间（ISO-8601） |
| `role` | String | 角色：`assistant` / `user` |
| `messageId` | String | 消息 ID（同一轮回复的所有事件共享，如 thinking + text + step 属于同一个 messageId） |
| `messageSeq` | Integer | **消息在会话中的序号**（第 1 条消息=1，第 2 条=2...同一轮回复共享相同值） |
| `sourceMessageId` | String | 源消息 ID（通常与 messageId 相同） |
| `partId` | String | 部分 ID（同一 messageId 下不同部分各有不同 partId，如 thinking 部分和 text 部分） |
| `partSeq` | Integer | 部分序号（同一 messageId 下的排序：thinking=1, text=2, tool=3...） |
| `subagentSessionId` | String | 子 Agent 会话 ID（多 Agent 场景） |
| `subagentName` | String | 子 Agent 名称 |

> **统一协议：个人助手与业务助手格式完全一致。** 客户端只需一套解析逻辑，不区分 agent 类型。

### 2.6 StreamMessage 类型定义（共 26 种）

`type` 字段决定消息类型。以下为统一协议标准，**个人助手和业务助手完全一致**。

> **公共字段规范**：以下字段在所有消息类型中**必须存在**（服务端保证填充）：
> - `type` — 消息类型
> - `seq` — 传输序号（递增）
> - `welinkSessionId` — 会话 ID（用于关联 REST 返回的 welinkSessionId）
> - `emittedAt` — 服务端时间戳
>
> 以下字段在**有内容交互的消息类型**（文本/思考/工具/问答/权限/步骤）中必须存在：
> - `messageId` — 消息 ID（同一轮回复共享）
> - `sourceMessageId` — 源消息 ID
> - `messageSeq` — 消息序号
> - `role` — 角色（`assistant`）
>
> 以下字段在**部分级消息**（delta/done/tool.update/question/file）中必须存在：
> - `partId` — 部分 ID
> - `partSeq` — 部分序号
>
> 所有 null 字段在 JSON 中省略（`@JsonInclude(NON_NULL)`）。

---

### A. 文本消息（核心交互）

#### type = `text.delta`（流式文本片段）

AI 回复的流式文本片段，逐 token 推送。客户端应**追加**显示。同一轮回复的多个 delta 共享相同 `messageId`。

```json
{
  "type": "text.delta",
  "seq": 42,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:00Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 5,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 3,
  "content": "你好"
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `content` | String | 文本片段（追加显示） |

---

#### type = `text.done`（文本完成）

一轮文本回复完成。`content` = 所有同 `messageId` + `partId` 的 `text.delta.content` 拼接结果。

```json
{
  "type": "text.done",
  "seq": 55,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:01Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 5,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 3,
  "content": "你好，这是完整的回复内容。"
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `content` | String | **完整回复文本**（= 同 partId 的所有 delta 拼接） |

---

### B. 思考过程

#### type = `thinking.delta`（思考过程片段）

AI 的内部推理过程（流式）。可选择性展示。字段结构与 `text.delta` 一致。

```json
{
  "type": "thinking.delta",
  "seq": 30,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:00Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 5,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 2,
  "content": "让我分析一下这个问题..."
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `content` | String | 思考内容片段 |

---

#### type = `thinking.done`（思考过程完成）

字段结构与 `text.done` 一致。

```json
{
  "type": "thinking.done",
  "seq": 35,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:01Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 5,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 2,
  "content": "让我分析一下这个问题，需要考虑以下几个方面..."
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `content` | String | 完整思考内容 |

---

### C. 工具调用

#### type = `tool.update`（工具调用状态更新）

AI Agent 正在调用或已完成工具调用（bash、read、write、edit 等）。

```json
{
  "type": "tool.update",
  "seq": 35,
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:36:00Z",
  "messageId": "msg_xxx",
  "role": "assistant",
  "partId": "part_xxx",
  "partSeq": 2,
  "status": "running",
  "title": "Running bash command",
  "error": null,
  "toolName": "bash",
  "toolCallId": "toolu_xxx",
  "input": {"command": "echo hello", "description": "Print hello"},
  "output": "hello\n"
}
```

| 字段 | 类型 | 必有 | 说明 |
|------|------|------|------|
| `toolName` | String | 是 | 工具名称：`bash` / `read` / `write` / `edit` / `glob` / `grep` 等 |
| `toolCallId` | String | 是 | 工具调用 ID |
| `input` | Object | 否 | 工具输入参数（结构因工具而异） |
| `output` | String | 否 | 工具执行输出文本 |
| `status` | String | 否 | 状态：`running` / `completed` / `failed` |
| `title` | String | 否 | 工具调用描述 |
| `error` | String | 否 | 工具执行错误信息 |

---

#### type = `file`（文件消息）

Agent 产生了文件输出。

```json
{
  "type": "file",
  "messageId": "msg_xxx",
  "role": "assistant",
  "partId": "part_xxx",
  "fileName": "report.pdf",
  "fileUrl": "https://example.com/files/report.pdf",
  "fileMime": "application/pdf"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `fileName` | String | 文件名 |
| `fileUrl` | String | 文件下载 URL |
| `fileMime` | String | MIME 类型 |

---

### D. 权限交互 ⭐

#### type = `permission.ask`（权限请求）

AI Agent 请求执行工具的权限。**客户端必须通过 REST `permission_reply` 回复**，否则 Agent 阻塞等待。

```json
{
  "type": "permission.ask",
  "seq": 38,
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:56:17Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "role": "assistant",
  "status": "running",
  "title": "bash",
  "permissionId": "per_d897d347c001WFYI2DbWVsuMuA",
  "permType": "bash",
  "metadata": {
    "command": "echo hello world"
  }
}
```

| 字段 | 类型 | 必有 | 说明 |
|------|------|------|------|
| `permissionId` | String | **是** | **权限请求 ID — 回复时必须使用此值** |
| `permType` | String | 是 | 工具类型：`bash` / `write` / `edit` 等 |
| `title` | String | 是 | 工具名称/描述 |
| `metadata` | Object | 否 | 工具调用的详细参数（如 bash 的 command） |
| `status` | String | 否 | 通常为 `running` |
| `messageId` | String | 否 | 关联的消息 ID |

**收到后操作**：调用 REST `action=permission_reply`，payload 带 `permissionId` 和 `response`（once/always/reject）。

---

#### type = `permission.reply`（权限回复确认）

服务端确认已处理权限回复。

```json
{
  "type": "permission.reply",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "role": "assistant",
  "partId": "part_xxx",
  "partSeq": 2,
  "status": "completed",
  "title": "bash",
  "permissionId": "per_xxx",
  "permType": "bash",
  "response": "once",
  "subagentSessionId": null
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `permissionId` | String | 对应的权限请求 ID |
| `response` | String | 授权结果：`once` / `always` / `reject` |
| `permType` | String | 工具类型 |
| `status` | String | 通常为 `completed` |

---

### E. 问答交互 ⭐

#### type = `question`（Agent 提问）

AI Agent 向用户提出问题，等待回答。**客户端必须通过 REST `question_reply` 回复**。

```json
{
  "type": "question",
  "seq": 40,
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T01:38:51Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "role": "assistant",
  "partId": "que_xxx",
  "partSeq": 4,
  "status": "running",
  "toolName": "question",
  "toolCallId": "toolu_functions.question:0",
  "input": {
    "id": "que_xxx",
    "questions": [{"question": "Which approach?", "header": "Approach", "options": [...]}]
  },
  "output": null,
  "header": "Approach",
  "question": "Which approach: A or B?",
  "options": ["A (Recommended)", "B"]
}
```

| 字段 | 类型 | 必有 | 说明 |
|------|------|------|------|
| `toolCallId` | String | **是** | **问题调用 ID — 回复时必须使用此值** |
| `question` | String | 是 | 问题文本 |
| `header` | String | 否 | 问题标题/分类 |
| `options` | String[] | 否 | 选项列表（空表示开放式问题） |
| `status` | String | 是 | `running`=等待回答，`completed`=已回答 |
| `toolName` | String | 是 | 固定 `question` |
| `input` | Object | 否 | 问题详细数据（含原始 questions 数组） |
| `output` | String | 否 | 回答内容（completed 时有值） |

**收到后操作**：调用 REST `action=question_reply`，payload 带 `toolCallId` 和 `content`（回答）。

---

### F. 步骤生命周期

#### type = `step.start`（步骤开始）

Agent 开始一个新的处理步骤（一轮对话可能有多个步骤：思考 → 工具调用 → 回复）。

```json
{
  "type": "step.start",
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:36:00Z",
  "messageId": "msg_xxx",
  "role": "assistant"
}
```

---

#### type = `step.done`（步骤完成）

```json
{
  "type": "step.done",
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:36:05Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "role": "assistant",
  "tokens": {"total": 28870, "input": 4, "output": 112, "reasoning": 0, "cache": {"read": 28731, "write": 23}},
  "reason": "end_turn"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `tokens` | Object | Token 使用量 |
| `tokens.total` | Integer | 总 token 数 |
| `tokens.input` | Integer | 输入 token |
| `tokens.output` | Integer | 输出 token |
| `tokens.reasoning` | Integer | 推理 token |
| `tokens.cache` | Object | 缓存相关：`{read, write}` |
| `cost` | Double | 本步骤费用（USD，可能为 null） |
| `reason` | String | 结束原因：`end_turn`（正常结束）/ `max_tokens` / `stop_sequence` 等 |

---

### G. 会话状态

#### type = `session.status`（会话状态变更）

```json
{
  "type": "session.status",
  "seq": 60,
  "welinkSessionId": "ses_xxx",
  "sessionStatus": "busy"
}
```

| sessionStatus | 含义 |
|---------------|------|
| `busy` | Agent 正在处理中（可展示加载状态） |
| `idle` | Agent 处理完毕，等待新消息 |
| `retry` | Session 重建中，消息将自动重发 |

---

#### type = `session.title`（会话标题更新）

```json
{
  "type": "session.title",
  "welinkSessionId": "ses_xxx",
  "title": "天气查询对话"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 会话标题（由 Agent 自动生成） |

---

#### type = `session.error`（会话级错误）

不可恢复的会话错误，如上下文溢出。

```json
{
  "type": "session.error",
  "welinkSessionId": "ses_xxx",
  "error": "ContextOverflowError: 对话上下文已超出限制，已自动重置"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `error` | String | 错误描述 |

---

#### type = `error`（通用错误）

临时性错误，如 Agent 离线、工具执行异常。

```json
{
  "type": "error",
  "error": "任务下发失败，请检查助理是否离线，确保助理在线后重试"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `error` | String | 错误描述 |

---

### H. Agent 状态

#### type = `agent.online`（Agent 上线）

```json
{"type": "agent.online"}
```

#### type = `agent.offline`（Agent 下线）

```json
{"type": "agent.offline"}
```

> 仅有 `type` 字段，无其他负载。

---

### I. 用户消息同步

#### type = `message.user`（用户消息多端同步）

用户通过其他端（如 MiniApp）发送的消息同步推送。

```json
{
  "type": "message.user",
  "messageId": "msg_xxx",
  "messageSeq": 5,
  "role": "user",
  "content": "用户在其他端发送的消息",
  "welinkSessionId": "ses_xxx"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `messageId` | String | 消息 ID |
| `messageSeq` | Integer | 消息序号 |
| `role` | String | 固定 `user` |
| `content` | String | 消息内容 |

---

### J. 会话恢复（重连场景）

#### type = `snapshot`（会话快照）

WS 重连时推送的历史消息快照。

```json
{
  "type": "snapshot",
  "seq": 1,
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:36:00Z",
  "messages": [
    {"messageId": "msg_1", "role": "user", "content": "你好", "seq": 1},
    {"messageId": "msg_2", "role": "assistant", "content": "你好！", "seq": 2}
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `messages` | Array | 历史消息列表 |

---

#### type = `streaming`（当前流式状态）

WS 重连时推送的当前正在进行的流式内容。

```json
{
  "type": "streaming",
  "seq": 2,
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:36:00Z",
  "sessionStatus": "busy",
  "parts": [
    {"type": "text.delta", "content": "正在回复中..."},
    {"type": "tool.update", "toolName": "bash", "status": "running"}
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionStatus` | String | `busy` / `idle` |
| `parts` | Array | 当前正在进行的流式部分列表 |

---

### K. 云端扩展类型（业务助手专属）

以下类型仅在业务助手场景下出现。公共字段（`seq`、`welinkSessionId`、`emittedAt`、`messageId`、`sourceMessageId`、`messageSeq`、`role`、`partId`、`partSeq`）同样遵循统一规范。

#### type = `planning.delta`（规划过程片段）

```json
{
  "type": "planning.delta",
  "seq": 5,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:00Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 3,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 1,
  "content": "分析用户问题，准备搜索资料"
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `content` | String | 规划内容片段 |

#### type = `planning.done`（规划完成）

结构同 `planning.delta`，`content` 为完整规划文本。

---

#### type = `searching`（正在搜索）

```json
{
  "type": "searching",
  "seq": 8,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:01Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 3,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 2,
  "keywords": ["关键词1", "关键词2"]
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `keywords` | String[] | 搜索关键词列表 |

---

#### type = `search_result`（搜索结果）

```json
{
  "type": "search_result",
  "seq": 9,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:01Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 3,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 3,
  "searchResults": [
    {"index": "1", "title": "文章标题", "source": "来源"},
    {"index": "2", "title": "文章标题2", "source": "来源2"}
  ]
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `searchResults` | Array | `[{index, title, source}]` |

---

#### type = `reference`（引用来源）

```json
{
  "type": "reference",
  "seq": 10,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:02Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 3,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 4,
  "references": [
    {"index": "1", "title": "文章标题", "source": "来源", "url": "https://example.com", "content": "摘要内容"}
  ]
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `references` | Array | `[{index, title, source, url, content}]` |

---

#### type = `ask_more`（追问建议）

```json
{
  "type": "ask_more",
  "seq": 25,
  "welinkSessionId": "2711748171393929216",
  "emittedAt": "2026-04-14T00:36:05Z",
  "messageId": "msg_xxx",
  "sourceMessageId": "msg_xxx",
  "messageSeq": 3,
  "role": "assistant",
  "partId": "prt_xxx",
  "partSeq": 8,
  "askMoreQuestions": ["还想了解什么？", "需要更详细吗？"]
}
```

| 特有字段 | 类型 | 说明 |
|----------|------|------|
| `askMoreQuestions` | String[] | 建议的追问列表 |

---

### 类型速查表

| type | 分类 | 需要回复？ | 关键字段 |
|------|------|-----------|----------|
| `text.delta` | 文本 | 否 | `content`, `messageId` |
| `text.done` | 文本 | 否 | `content`, `messageId`, `partId` |
| `thinking.delta` | 思考 | 否 | `content` |
| `thinking.done` | 思考 | 否 | `content` |
| `tool.update` | 工具 | 否 | `toolName`, `toolCallId`, `input`, `output`, `status` |
| `file` | 工具 | 否 | `fileName`, `fileUrl`, `fileMime` |
| `permission.ask` | 权限 | **是** → `permission_reply` | `permissionId`, `permType`, `title`, `metadata` |
| `permission.reply` | 权限 | 否 | `permissionId`, `response` |
| `question` | 问答 | **是** → `question_reply` | `toolCallId`, `question`, `options`, `header` |
| `step.start` | 生命周期 | 否 | `messageId` |
| `step.done` | 生命周期 | 否 | `tokens`, `cost`, `reason` |
| `session.status` | 会话 | 否 | `sessionStatus` (busy/idle/retry) |
| `session.title` | 会话 | 否 | `title` |
| `session.error` | 错误 | 否 | `error` |
| `error` | 错误 | 否 | `error` |
| `agent.online` | Agent | 否 | (无) |
| `agent.offline` | Agent | 否 | (无) |
| `message.user` | 同步 | 否 | `content`, `messageId`, `messageSeq` |
| `snapshot` | 恢复 | 否 | `messages` |
| `streaming` | 恢复 | 否 | `sessionStatus`, `parts` |
| `planning.delta` | 云端 | 否 | `content` |
| `planning.done` | 云端 | 否 | `content` |
| `searching` | 云端 | 否 | `keywords` |
| `search_result` | 云端 | 否 | `searchResults` |
| `reference` | 云端 | 否 | `references` |
| `ask_more` | 云端 | 否 | `askMoreQuestions` |

---

## 3. 出站路由策略

当 AI Agent 产生回复时，系统按以下优先级选择出站方式：

| 优先级 | 条件 | 出站方式 |
|--------|------|----------|
| 1 | 该 `businessDomain` 有活跃 WS 连接 | 通过 WebSocket 推送 StreamMessage |
| 2 | 该 `businessDomain` = `im` 且无 WS 连接 | 通过 IM REST API 推送纯文本（仅 text.done 内容） |
| 3 | 其他 domain 无 WS 连接 | 不投递（消息丢失） |

**建议：** 业务方应始终保持 WS 连接，确保能接收所有类型的 StreamMessage。WS 断开后仅 IM 有 REST 降级兜底，且只推送最终文本，丢失流式过程、权限请求、提问等交互事件。

---

## 4. 接入指南

### 4.1 接入步骤

1. **获取 Token** — 向管理员申请 `skill.im.inbound-token`
2. **建立 WS 连接** — 连接 `/ws/external/stream`，握手带 `token` + `source` + `instanceId`
3. **启动心跳** — 每 10~30 秒发送 `{"action":"ping"}`
4. **发送消息** — 通过 `POST /api/external/invoke` 发送 chat
5. **接收推送** — 在 WS 上监听 StreamMessage，按 `type` 处理
6. **处理交互** — 收到 `permission.ask` / `question` 时，通过 REST 接口回复

### 4.2 接入时序图

```
IM Server                     Skill Server                    AI Agent
    │                              │                              │
    ├── WS Connect ──────────────→ │                              │
    │   (auth.{token,source,id})   │                              │
    │ ←── WS Connected ──────────  │                              │
    │                              │                              │
    ├── ping ─────────────────────→│                              │
    │ ←── pong ───────────────────  │                              │
    │                              │                              │
    ├── POST /invoke (chat) ─────→ │ ── create_session ─────────→ │
    │ ←── {"code":0} ────────────  │                              │
    │                              │ ←── session_created ────────  │
    │                              │ ── chat ────────────────────→ │
    │                              │                              │
    │ ←── WS: session.status=busy  │ ←── thinking.delta ────────  │
    │ ←── WS: thinking.delta ───── │                              │
    │ ←── WS: thinking.done ────── │                              │
    │                              │                              │
    │ ←── WS: permission.ask ───── │ ←── permission_request ────  │
    │     (permissionId=per_xxx)   │                              │
    │                              │                              │
    ├── POST /invoke ────────────→ │                              │
    │   (permission_reply,         │ ── permission_reply ───────→ │
    │    permissionId=per_xxx,     │                              │
    │    response=once)            │                              │
    │ ←── {"code":0} ────────────  │                              │
    │                              │                              │
    │ ←── WS: tool.update ──────── │ ←── tool_event ────────────  │
    │ ←── WS: text.delta ───────── │ ←── tool_event ────────────  │
    │ ←── WS: text.delta ───────── │                              │
    │ ←── WS: text.done ────────── │ ←── tool_event ────────────  │
    │ ←── WS: session.status=idle  │ ←── tool_done ─────────────  │
    │                              │                              │
```

### 4.3 客户端代码示例（Python）

```python
import websocket
import json
import base64
import threading
import requests

# 配置
SS_URL = "http://localhost:8082"
WS_URL = "ws://localhost:8082/ws/external/stream"
TOKEN = "your-token"
SOURCE = "im"
INSTANCE_ID = "im-node-1"

# 1. 建立 WS 连接
auth = json.dumps({"token": TOKEN, "source": SOURCE, "instanceId": INSTANCE_ID})
protocol = "auth." + base64.urlsafe_b64encode(auth.encode()).decode().rstrip("=")

ws = websocket.WebSocket()
ws.settimeout(3)
ws.connect(WS_URL, subprotocols=[protocol])
print("WS connected")

# 2. 心跳线程
def heartbeat():
    while ws.connected:
        try:
            ws.send(json.dumps({"action": "ping"}))
            time.sleep(15)
        except:
            break

threading.Thread(target=heartbeat, daemon=True).start()

# 3. 发送消息
resp = requests.post(f"{SS_URL}/api/external/invoke",
    headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
    json={
        "action": "chat",
        "businessDomain": SOURCE,
        "sessionType": "direct",
        "sessionId": "my-session-001",
        "assistantAccount": "my-assistant",
        "senderUserAccount": "user-001",
        "payload": {"content": "你好", "msgType": "text"}
    })
print(f"Invoke: {resp.json()}")

# 4. 接收推送
while True:
    try:
        raw = ws.recv()
        msg = json.loads(raw)
        msg_type = msg.get("message", {}).get("type", "")

        if msg.get("action") == "pong":
            continue

        if msg_type == "text.delta":
            print(msg["message"]["content"], end="", flush=True)
        elif msg_type == "text.done":
            print(f"\n[完成] {msg['message']['content']}")
        elif msg_type == "permission.ask":
            perm_id = msg["message"]["permissionId"]
            print(f"\n[权限请求] {msg['message']['title']}, permissionId={perm_id}")
            # 回复权限
            requests.post(f"{SS_URL}/api/external/invoke",
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                json={
                    "action": "permission_reply",
                    "businessDomain": SOURCE,
                    "sessionType": "direct",
                    "sessionId": "my-session-001",
                    "assistantAccount": "my-assistant",
                    "senderUserAccount": "user-001",
                    "payload": {"permissionId": perm_id, "response": "once"}
                })
        elif msg_type == "question":
            tool_call_id = msg["message"]["toolCallId"]
            print(f"\n[提问] {msg['message']['question']}")
            print(f"  选项: {msg['message'].get('options', [])}")
            answer = input("请回答: ")
            requests.post(f"{SS_URL}/api/external/invoke",
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                json={
                    "action": "question_reply",
                    "businessDomain": SOURCE,
                    "sessionType": "direct",
                    "sessionId": "my-session-001",
                    "assistantAccount": "my-assistant",
                    "senderUserAccount": "user-001",
                    "payload": {"content": answer, "toolCallId": tool_call_id}
                })
        elif msg_type == "session.status":
            print(f"\n[状态] {msg['message'].get('sessionStatus')}")

    except websocket.WebSocketTimeoutException:
        continue
    except KeyboardInterrupt:
        break

ws.close()
```

---

## 附录 A：实际 WS 推送报文参考

以下是 WS 推送的**实际 JSON 格式**。直接是 StreamMessage 对象，无信封包装，与 miniapp 前端收到的格式完全一致。

> 以下报文基于真实 E2E 测试验证，个人助手和云端助手格式一致（均经过 enrichStreamMessage 统一填充公共字段）。

**session.title**
```json
{"type":"session.title","seq":1,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:16:47.512Z","title":"im-direct-session-001"}
```

**session.status**
```json
{"type":"session.status","seq":2,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:16:47.526Z","sessionStatus":"busy"}
```

**step.start**
```json
{"type":"step.start","seq":3,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:17:02.417Z","messageId":"msg_xxx","messageSeq":5,"role":"assistant","sourceMessageId":"msg_xxx"}
```

**thinking.delta**
```json
{"type":"thinking.delta","seq":4,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:17:02.447Z","messageId":"msg_xxx","messageSeq":5,"role":"assistant","sourceMessageId":"msg_xxx","partId":"prt_xxx","partSeq":2,"content":"The user is asking..."}
```

**thinking.done**
```json
{"type":"thinking.done","seq":15,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:17:02.432Z","messageId":"msg_xxx","messageSeq":5,"role":"assistant","sourceMessageId":"msg_xxx","partId":"prt_xxx","partSeq":2,"content":"The user is asking about... (full thinking text)"}
```

**text.delta**
```json
{"type":"text.delta","seq":16,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:17:41.397Z","messageId":"msg_xxx","messageSeq":5,"role":"assistant","sourceMessageId":"msg_xxx","partId":"prt_xxx","partSeq":3,"content":"你好"}
```

**text.done**
```json
{"type":"text.done","seq":22,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:17:41.387Z","messageId":"msg_xxx","messageSeq":5,"role":"assistant","sourceMessageId":"msg_xxx","partId":"prt_xxx","partSeq":3,"content":"你好，这是完整回复。"}
```

**step.done**
```json
{"type":"step.done","seq":23,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:17:41.743Z","messageId":"msg_xxx","messageSeq":5,"role":"assistant","sourceMessageId":"msg_xxx","tokens":{"total":28870,"input":4,"output":112,"reasoning":0,"cache":{"read":28731,"write":23}},"reason":"end_turn"}
```

**permission.ask**
```json
{"type":"permission.ask","seq":10,"welinkSessionId":"2713883991789801472","emittedAt":"2026-04-14T00:56:17.278Z","role":"assistant","title":"bash","permissionId":"per_d897d347c001WFYI2DbWVsuMuA","permType":"bash","metadata":{}}
```

**question**
```json
{"type":"question","seq":8,"welinkSessionId":"2714124836824457216","emittedAt":"2026-04-14T01:38:51.252Z","messageId":"msg_xxx","role":"assistant","sourceMessageId":"msg_xxx","partId":"que_xxx","partSeq":4,"status":"running","toolName":"question","toolCallId":"toolu_functions.question:0","input":{"id":"que_xxx","questions":[{"question":"Which approach: A or B?","header":"Approach","options":[{"label":"A","description":"minimal"},{"label":"B","description":"full refactor"}]}]},"header":"Approach","question":"Which approach: A or B?","options":["A","B"]}
```

**session.status (idle)**
```json
{"type":"session.status","seq":24,"welinkSessionId":"2714513567289184256","emittedAt":"2026-04-14T06:17:42.000Z","sessionStatus":"idle"}
```

**error**
```json
{"type":"error","seq":5,"welinkSessionId":"2714513567289184256","error":"任务下发失败，请检查助理是否离线，确保助理在线后重试"}
```
