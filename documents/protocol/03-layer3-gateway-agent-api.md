# 层③ 接口协议：AI-Gateway ↔ PC Agent

> 版本：1.1  
> 日期：2026-03-08  
> 状态：待实现

---

## 全局约定

### 通信方式

| 项目         | 说明                         |
| ------------ | ---------------------------- |
| **协议**     | WebSocket（JSON）            |
| **连接方向** | PC Agent 主动建联到 Gateway  |
| **端点**     | `ws://gateway-host/ws/agent` |

### 认证方式

AK/SK 签名握手，在 WebSocket 握手阶段通过查询参数传递：

```
ws://gateway-host/ws/agent?ak=<access_key>&ts=<timestamp>&nonce=<random>&sign=<signature>
```

| 参数    | 类型   | 必填  | 说明                                          |
| ------- | ------ | :---: | --------------------------------------------- |
| `ak`    | String |   ✅   | Agent 的 Access Key                           |
| `ts`    | String |   ✅   | 当前时间戳                                    |
| `nonce` | String |   ✅   | 随机数，防重放                                |
| `sign`  | String |   ✅   | HMAC 签名：`HMAC-SHA256(sk, ak + ts + nonce)` |

Gateway 调用 `AkSkAuthService.verify(ak, ts, nonce, sign)` 校验签名，返回 `userId`。校验失败则拒绝握手。

### ID 命名规范

| 名称              | 说明                                                                       |
| ----------------- | -------------------------------------------------------------------------- |
| `welinkSessionId` | Skill 会话 ID。PC Agent 不识别语义，来自 invoke.create_session，需原样回传 |
| `toolSessionId`   | OpenCode SDK 分配的会话 ID。PC Agent 和 OpenCode 均使用此 ID               |

---

## 一、下行消息（Gateway → PC Agent）

---

### 1. invoke.create_session — 创建 OpenCode 会话

```json
{
  "type": "invoke",
  "welinkSessionId": "42",
  "action": "create_session",
  "payload": {
    "title": "帮我创建一个React项目"
  }
}
```

| 字段              | 类型   | 必填  | 说明                                                   |
| ----------------- | ------ | :---: | ------------------------------------------------------ |
| `type`            | String |   ✅   | 固定 `"invoke"`                                        |
| `welinkSessionId` | String |   ✅   | Skill 会话 ID，PC Agent 需原样回传到 `session_created` |
| `action`          | String |   ✅   | 固定 `"create_session"`                                |
| `payload`         | Object |   ✅   | 创建参数                                               |

| payload 字段 | 类型   | 必填  | 说明     |
| ------------ | ------ | :---: | -------- |
| `title`      | String |   ❌   | 会话标题 |

**PC Agent 收到后**: 调用 `client.session.create()` 创建 OpenCode 会话，成功后发送 `session_created` 回传 `toolSessionId`。

---

### 2. invoke.chat — 发送用户消息

```json
{
  "type": "invoke",
  "action": "chat",
  "payload": {
    "toolSessionId": "ses_abc",
    "text": "帮我创建一个React项目"
  }
}
```

| 字段      | 类型   | 必填  | 说明            |
| --------- | ------ | :---: | --------------- |
| `type`    | String |   ✅   | 固定 `"invoke"` |
| `action`  | String |   ✅   | 固定 `"chat"`   |
| `payload` | Object |   ✅   | 消息参数        |

| payload 字段    | 类型   | 必填  | 说明             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID |
| `text`          | String |   ✅   | 用户消息内容     |

**PC Agent 收到后**: 调用 `client.session.prompt({ path: { id: toolSessionId }, body: { parts: [{ type: 'text', text }] } })`。

---

### 3. invoke.abort_session — 中止当前执行

```json
{
  "type": "invoke",
  "action": "abort_session",
  "payload": {
    "toolSessionId": "ses_abc"
  }
}
```

| payload 字段    | 类型   | 必填  | 说明             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID |

**PC Agent 收到后**: 调用 `client.session.abort({ path: { id: toolSessionId } })`。

---

### 4. invoke.close_session — 关闭并删除会话

```json
{
  "type": "invoke",
  "action": "close_session",
  "payload": {
    "toolSessionId": "ses_abc"
  }
}
```

| payload 字段    | 类型   | 必填  | 说明             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID |

**PC Agent 收到后**: 调用 `client.session.delete({ path: { id: toolSessionId } })`。

---

### 5. invoke.permission_reply — 回复权限请求

```json
{
  "type": "invoke",
  "action": "permission_reply",
  "payload": {
    "toolSessionId": "ses_abc",
    "permissionId": "perm_1",
    "response": "once"
  }
}
```

| payload 字段    | 类型   | 必填  | 说明                         |
| --------------- | ------ | :---: | ---------------------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID             |
| `permissionId`  | String |   ✅   | 权限请求 ID                  |
| `response`      | String |   ✅   | `once` / `always` / `reject` |

**PC Agent 收到后**: 调用 `client.postSessionIdPermissionsPermissionId({ body: { response }, path: { id: toolSessionId, permissionID: permissionId } })`。

---

### 6. invoke.question_reply — 回答 AI 提问

```json
{
  "type": "invoke",
  "action": "question_reply",
  "payload": {
    "toolSessionId": "ses_abc",
    "toolCallId": "call_2",
    "answer": "Vite"
  }
}
```

| payload 字段    | 类型   | 必填  | 说明                        |
| --------------- | ------ | :---: | --------------------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID            |
| `toolCallId`    | String |   ✅   | 对应 question 的工具调用 ID |
| `answer`        | String |   ✅   | 用户的回答内容              |

**PC Agent 收到后**: 调用 `client.session.prompt()` 将答案作为消息发送。

---

### 7. status_query — 状态查询

```json
{
  "type": "status_query"
}
```

| 字段   | 类型   | 必填  | 说明                  |
| ------ | ------ | :---: | --------------------- |
| `type` | String |   ✅   | 固定 `"status_query"` |

**PC Agent 收到后**: 调用 `client.app.health()` 检测 OpenCode 运行时，返回 `status_response`。

---

## 二、上行消息（PC Agent → Gateway）

---

### 1. register — Agent 注册

建联成功后 PC Agent 发送的**第一条消息**，包含设备和工具信息。

```json
{
  "type": "register",
  "deviceName": "My-MacBook",
  "os": "macOS",
  "toolType": "opencode",
  "toolVersion": "0.5.0"
}
```

| 字段          | 类型   | 必填  | 说明                        |
| ------------- | ------ | :---: | --------------------------- |
| `type`        | String |   ✅   | 固定 `"register"`           |
| `deviceName`  | String |   ❌   | 设备名称                    |
| `os`          | String |   ❌   | 操作系统                    |
| `toolType`    | String |   ❌   | 工具类型，默认 `"opencode"` |
| `toolVersion` | String |   ❌   | 工具版本                    |

**Gateway 收到后**:

1. 调用 `AgentRegistryService.register()` 注册 Agent（含踢除同 AK 旧连接）
2. 分配 `agentId`，存入 session attributes
3. 注册 WebSocket session 到 `EventRelayService`
4. 通知 Skill Server `agent_online`

---

### 2. heartbeat — 心跳

```json
{
  "type": "heartbeat"
}
```

| 字段   | 类型   | 必填  | 说明               |
| ------ | ------ | :---: | ------------------ |
| `type` | String |   ✅   | 固定 `"heartbeat"` |

**Gateway 收到后**: 调用 `AgentRegistryService.heartbeat()` 更新 `last_seen_at`。

---

### 3. session_created — 会话创建成功

```json
{
  "type": "session_created",
  "welinkSessionId": "42",
  "toolSessionId": "ses_abc"
}
```

| 字段              | 类型   | 必填  | 说明                                   |
| ----------------- | ------ | :---: | -------------------------------------- |
| `type`            | String |   ✅   | 固定 `"session_created"`               |
| `welinkSessionId` | String |   ✅   | 原样回传（来自 invoke.create_session） |
| `toolSessionId`   | String |   ✅   | OpenCode 分配的会话 ID                 |

**Gateway 收到后**: 透传给 Skill Server（层②上行）。

---

### 4. tool_event — OpenCode 事件透传

```json
{
  "type": "tool_event",
  "toolSessionId": "ses_abc",
  "event": {
    "type": "message.part.updated",
    "properties": {
      "sessionId": "ses_abc",
      "part": { "type": "text", "text": "好的，" }
    }
  }
}
```

| 字段            | 类型   | 必填  | 说明                   |
| --------------- | ------ | :---: | ---------------------- |
| `type`          | String |   ✅   | 固定 `"tool_event"`    |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID       |
| `event`         | Object |   ✅   | 原始 OpenCode 事件对象 |

**Gateway 收到后**: 根据 `toolSessionId` 路由，透传给对应 Skill Server。

---

### 5. tool_done — 执行完成

```json
{
  "type": "tool_done",
  "toolSessionId": "ses_abc",
  "usage": {
    "inputTokens": 5000,
    "outputTokens": 200,
    "reasoningTokens": 800,
    "cost": 0.01
  }
}
```

| 字段            | 类型   | 必填  | 说明               |
| --------------- | ------ | :---: | ------------------ |
| `type`          | String |   ✅   | 固定 `"tool_done"` |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID   |
| `usage`         | Object |   ❌   | Token 使用统计     |

---

### 6. tool_error — 执行错误

```json
{
  "type": "tool_error",
  "toolSessionId": "ses_abc",
  "error": "session.prompt failed: connection refused"
}
```

| 字段            | 类型   | 必填  | 说明                |
| --------------- | ------ | :---: | ------------------- |
| `type`          | String |   ✅   | 固定 `"tool_error"` |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID    |
| `error`         | String |   ✅   | 错误描述            |

---

### 7. status_response — 状态响应

```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

| 字段             | 类型    | 必填  | 说明                     |
| ---------------- | ------- | :---: | ------------------------ |
| `type`           | String  |   ✅   | 固定 `"status_response"` |
| `opencodeOnline` | Boolean |   ✅   | OpenCode 运行时是否在线  |
