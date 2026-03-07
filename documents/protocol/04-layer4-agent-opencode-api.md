# 层④ 接口协议：PC Agent ↔ OpenCode SDK

> 版本：1.0  
> 日期：2026-03-08  
> 状态：已实现（基于 OpenCode SDK）

---

## 全局约定

### 通信方式

| 项目         | 说明                                                |
| ------------ | --------------------------------------------------- |
| **下行**     | PC Agent 调用 OpenCode SDK 本地 HTTP REST API       |
| **上行**     | OpenCode 事件通过 Plugin Event Hook 推送给 PC Agent |
| **消息格式** | JSON                                                |
| **SDK**      | `@opencode-ai/sdk` (OpencodeClient)                 |

### 连接方式

PC Agent 作为 OpenCode Plugin 运行，启动时通过 Plugin Context 获取 `OpencodeClient` 实例。无需额外认证，通信均为本机进程间调用。

---

## 一、下行调用（PC Agent → OpenCode SDK）

PC Agent 通过 `OpencodeClient` 发起 SDK 调用，将 Gateway 下发的 invoke 指令转为 OpenCode 操作。

---

### 1. 创建会话

**SDK 调用**: `client.session.create()`

```typescript
const result = await client.session.create({
  body: {
    title: "帮我创建一个React项目"
  }
});
```

**请求参数**:

| 字段         | 类型   | 必填  | 说明     |
| ------------ | ------ | :---: | -------- |
| `body.title` | String |   ❌   | 会话标题 |

**响应**: `result.data` 为 Session 对象，包含 `id`（即 `toolSessionId`）。

**对应层③**: invoke.create_session → 本调用 → session_created 上报

---

### 2. 发送用户消息

**SDK 调用**: `client.session.prompt()`

```typescript
await client.session.prompt({
  path: { id: toolSessionId },
  body: {
    parts: [{ type: "text", text: "帮我创建一个React项目" }]
  }
});
```

**请求参数**:

| 字段         | 类型   | 必填  | 说明                                        |
| ------------ | ------ | :---: | ------------------------------------------- |
| `path.id`    | String |   ✅   | OpenCode 会话 ID（toolSessionId）           |
| `body.parts` | Array  |   ✅   | 消息内容，`[{ type: "text", text: "..." }]` |

**对应层③**: invoke.chat → 本调用

---

### 3. 回答 AI 提问（question_reply）

**SDK 调用**: `client.session.prompt()`

```typescript
// 层③收到 invoke.question_reply 后：
// { toolSessionId: "ses_abc", toolCallId: "call_2", answer: "Vite" }
//
// PC Agent 将 answer 作为普通 prompt 发送：
await client.session.prompt({
  path: { id: toolSessionId },
  body: {
    parts: [{ type: "text", text: "Vite" }]
  }
});
```

**请求参数**:

| 字段         | 类型   | 必填  | 说明                              |
| ------------ | ------ | :---: | --------------------------------- |
| `path.id`    | String |   ✅   | OpenCode 会话 ID（toolSessionId） |
| `body.parts` | Array  |   ✅   | 用户回答内容                      |

**对应层③**: invoke.question_reply → 本调用

> **说明**：`toolCallId` 在层①～层③用于标识回答的是哪个 question，但在 SDK 层面**不需要传递**。当 OpenCode 有一个处于 `running` 状态的 question tool 时，收到 `session.prompt()` 会自动将该消息识别为 question 的回答。

---

### 4. 中止执行

**SDK 调用**: `client.session.abort()`

```typescript
await client.session.abort({
  path: { id: toolSessionId }
});
```

**请求参数**:

| 字段      | 类型   | 必填  | 说明             |
| --------- | ------ | :---: | ---------------- |
| `path.id` | String |   ✅   | OpenCode 会话 ID |

**对应层③**: invoke.abort_session → 本调用

---

### 5. 关闭/删除会话

**SDK 调用**: `client.session.delete()`

```typescript
await client.session.delete({
  path: { id: toolSessionId }
});
```

**请求参数**:

| 字段      | 类型   | 必填  | 说明             |
| --------- | ------ | :---: | ---------------- |
| `path.id` | String |   ✅   | OpenCode 会话 ID |

**对应层③**: invoke.close_session → 本调用

---

### 6. 回复权限请求

**SDK 调用**: `client.postSessionIdPermissionsPermissionId()`

```typescript
await client.postSessionIdPermissionsPermissionId({
  path: { id: toolSessionId, permissionID: permissionId },
  body: { response: "once" }
});
```

**请求参数**:

| 字段                | 类型   | 必填  | 说明                         |
| ------------------- | ------ | :---: | ---------------------------- |
| `path.id`           | String |   ✅   | OpenCode 会话 ID             |
| `path.permissionID` | String |   ✅   | 权限请求 ID                  |
| `body.response`     | String |   ✅   | `once` / `always` / `reject` |

**对应层③**: invoke.permission_reply → 本调用

---

### 7. 健康检查

**SDK 调用**: `client.app.health()`

```typescript
await client.app.health();
```

无参数。调用成功则 OpenCode 运行时在线。

**对应层③**: status_query → 本调用 → status_response 上报

---

## 二、上行事件（OpenCode SDK → PC Agent）

OpenCode 运行时产生的事件通过 Plugin Event Hook 推送给 PC Agent。PC Agent 在 `relayUpstream()` 中将事件包装为 `tool_event` 发给 Gateway。

### 事件格式

```typescript
interface OpenCodeEvent {
  type: string;          // 事件类型
  properties: {          // 事件属性
    sessionId?: string;  // 对应的 toolSessionId
    [key: string]: any;  // 其他属性
  };
}
```

### 主要事件类型（17 种）

| 事件类型               | 说明                               | Skill Server 翻译目标                              |
| ---------------------- | ---------------------------------- | -------------------------------------------------- |
| `message.part.updated` | 核心内容事件，含 12 种 Part 子类型 | `text.delta` / `thinking.delta` / `tool.update` 等 |
| `message.updated`      | 消息元数据（role, tokens）         | `step.done`（Token 统计）                          |
| `session.created`      | 会话创建成功                       | `session_created` 上报                             |
| `session.updated`      | 会话信息更新（标题等）             | `session.title`                                    |
| `session.status`       | 会话状态变化（idle/busy）          | `session.status`                                   |
| `session.idle`         | 全局空闲（流结束信号）             | `step.done`                                        |
| `session.error`        | 会话级错误                         | `session.error`                                    |
| `session.deleted`      | 会话关闭/清理                      | —                                                  |
| `session.diff`         | 文件变更                           | —                                                  |
| `session.compacted`    | 上下文压缩                         | —                                                  |
| `permission.updated`   | 权限请求                           | `permission.ask`                                   |
| `permission.replied`   | 权限响应                           | `permission.reply`                                 |
| `message.removed`      | 消息删除                           | —                                                  |
| `message.part.removed` | 消息 Part 删除                     | —                                                  |
| `file.edited`          | 文件编辑通知                       | —                                                  |
| `todo.updated`         | 任务清单更新                       | —                                                  |
| `command.executed`     | Shell 命令执行                     | —                                                  |

### Part 子类型（12 种）

`message.part.updated` 事件的 `properties.part.type` 决定具体内容类型：

| Part Type     | 说明                     | Skill Server 翻译目标              |
| ------------- | ------------------------ | ---------------------------------- |
| `text`        | AI 文本回复              | `text.delta` / `text.done`         |
| `reasoning`   | 思维链 / 思考过程        | `thinking.delta` / `thinking.done` |
| `tool`        | 工具调用及结果           | `tool.update`（含 question）       |
| `step-start`  | 推理步骤开始             | `step.start`                       |
| `step-finish` | 推理步骤结束（含 Token） | `step.done`                        |
| `file`        | 文件/图片附件            | `file`                             |
| `subtask`     | 子任务委派               | —                                  |
| `agent`       | 子 Agent 激活            | —                                  |
| `patch`       | Patch 应用               | —                                  |
| `snapshot`    | 状态快照                 | —                                  |
| `retry`       | API 重试通知             | —                                  |
| `compaction`  | 历史压缩通知             | —                                  |

### PC Agent 事件处理流程

```
OpenCode Event Hook
    │
    ▼
EventRelay.relayUpstream(event)
    │
    ├─ 提取 toolSessionId（from event.properties.sessionId）
    │
    ├─ 包装为 GatewayMessage:
    │   {
    │     type: "tool_event",
    │     toolSessionId: "ses_abc",
    │     event: { ... }
    │   }
    │
    └─ gateway.send(message) → 发送到 Gateway
```
