# 层② 接口协议：Skill Server ↔ AI-Gateway

> 版本：1.2  
> 日期：2026-03-08  
> 状态：待实现

---

## 全局约定

### 通信方式

| 项目          | 说明                                                              |
| ------------- | ----------------------------------------------------------------- |
| **WebSocket** | Skill Server 主动建联到 Gateway（`skill-server → ALB → gateway`） |
| **REST API**  | Skill Server 调用 Gateway 暴露的 HTTP 接口（备用/查询）           |
| **消息格式**  | JSON                                                              |

### 建联

```
ws://gateway-host/ws/skill?token=<internal-token>
```

Skill Server 启动时读取 `skill.gateway.ws-url` 配置，使用内部 Token 向 ALB 建立 WebSocket 长连接。ALB 将连接分配给某个健康的 Gateway 实例。

### 认证

#### WebSocket

建联时通过查询参数 `token` 校验：

```
ws://gateway-host/ws/skill?token=<internal-token>
```

#### REST API

请求头携带内部 Token：

```
Authorization: Bearer <internal-token>
```

无效 Token 响应：

```json
{
  "code": 401,
  "errormsg": "Invalid or missing internal token",
  "data": null
}
```

### ID 命名规范

| 名称              | 说明                                                                                     |
| ----------------- | ---------------------------------------------------------------------------------------- |
| `welinkSessionId` | Skill Server 内部分配的会话 ID。仅 Skill Server 感知，Gateway 原样透传不识别语义         |
| `toolSessionId`   | OpenCode SDK 分配的会话 ID。Gateway 和 PC Agent 均使用此 ID                              |
| `ak`              | Agent 的 Access Key。Skill Server 用 ak 定位 Agent，Gateway 内部维护 `ak → agentId` 映射 |

### REST API 响应格式

所有 REST 接口统一使用：

```json
{
  "code": 0,
  "errormsg": "",
  "data": { ... }
}
```

---

## 一、下行 WebSocket 消息（Skill Server → Gateway）

> 通过 Skill Server 主动建立的 WebSocket 长连接发送。

### 通用 invoke 格式

```json
{
  "type": "invoke",
  "ak": "<access_key>",
  "action": "<action_name>",
  "payload": { ... }
}
```

| 字段              | 类型   | 必填  | 说明                                                     |
| ----------------- | ------ | :---: | -------------------------------------------------------- |
| `type`            | String |   ✅   | 固定 `"invoke"`                                          |
| `ak`              | String |   ✅   | Agent 的 Access Key，Gateway 据此内部查找对应 Agent 连接 |
| `action`          | String |   ✅   | 执行动作名称                                             |
| `payload`         | Object |   ✅   | 动作参数                                                 |
| `welinkSessionId` | String |   ❌   | 仅 `create_session` 时携带，Gateway 原样透传             |

---

### 1. invoke.create_session — 创建 OpenCode 会话

```json
{
  "type": "invoke",
  "ak": "ak_xxxxxxxx",
  "welinkSessionId": "42",
  "action": "create_session",
  "payload": {
    "title": "帮我创建一个React项目"
  }
}
```

| payload 字段 | 类型   | 必填  | 说明     |
| ------------ | ------ | :---: | -------- |
| `title`      | String |   ❌   | 会话标题 |

---

### 2. invoke.chat — 发送用户消息

```json
{
  "type": "invoke",
  "ak": "ak_xxxxxxxx",
  "action": "chat",
  "payload": {
    "toolSessionId": "ses_abc",
    "text": "帮我创建一个React项目"
  }
}
```

| payload 字段    | 类型   | 必填  | 说明             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID |
| `text`          | String |   ✅   | 用户消息内容     |

---

### 3. invoke.abort_session — 中止当前执行

```json
{
  "type": "invoke",
  "ak": "ak_xxxxxxxx",
  "action": "abort_session",
  "payload": {
    "toolSessionId": "ses_abc"
  }
}
```

| payload 字段    | 类型   | 必填  | 说明             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID |

---

### 4. invoke.close_session — 关闭并删除会话

```json
{
  "type": "invoke",
  "ak": "ak_xxxxxxxx",
  "action": "close_session",
  "payload": {
    "toolSessionId": "ses_abc"
  }
}
```

| payload 字段    | 类型   | 必填  | 说明             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   ✅   | OpenCode 会话 ID |

---

### 5. invoke.permission_reply — 回复权限请求

```json
{
  "type": "invoke",
  "ak": "ak_xxxxxxxx",
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

---

### 6. invoke.question_reply — 回答 AI 提问

```json
{
  "type": "invoke",
  "ak": "ak_xxxxxxxx",
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

---

## 二、上行 WebSocket 消息（Gateway → Skill Server）

> 通过同一条 WebSocket 长连接回传给 Skill Server。

---

### 1. session_created — 会话创建成功

PC Agent 在 OpenCode 上创建会话成功后，Gateway 透传回 `toolSessionId`。

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

**Skill Server 收到后**:

1. 更新 `SkillSession.toolSessionId = "ses_abc"`
2. 建立 `welinkSessionId ↔ toolSessionId` 双向映射

---

### 2. tool_event — OpenCode 事件透传

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

**Skill Server 收到后**: 通过 `toolSessionId → welinkSessionId` 映射定位会话，解析 `event` 翻译为 StreamMessage。

---

### 3. tool_done — 执行完成

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

### 4. tool_error — 执行错误

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

### 5. agent_online — Agent 上线

Gateway 自身生成。

```json
{
  "type": "agent_online",
  "ak": "ak_xxxxxxxx"
}
```

| 字段   | 类型   | 必填  | 说明                  |
| ------ | ------ | :---: | --------------------- |
| `type` | String |   ✅   | 固定 `"agent_online"` |
| `ak`   | String |   ✅   | Agent 的 Access Key   |

---

### 6. agent_offline — Agent 下线

Gateway 自身生成。

```json
{
  "type": "agent_offline",
  "ak": "ak_xxxxxxxx"
}
```

| 字段   | 类型   | 必填  | 说明                   |
| ------ | ------ | :---: | ---------------------- |
| `type` | String |   ✅   | 固定 `"agent_offline"` |
| `ak`   | String |   ✅   | Agent 的 Access Key    |

---

## 三、REST API（Skill Server → Gateway）

> 基础路径：`/api/gateway`  
> 认证方式：`Authorization: Bearer <internal-token>`

---

### 1. 查询在线 Agent 列表

**GET** `/api/gateway/agents`

#### 查询参数

| 参数 | 类型   | 必填  | 说明               |
| ---- | ------ | :---: | ------------------ |
| `ak` | String |   ❌   | 按 Access Key 过滤 |

#### 响应

```json
{
  "code": 0,
  "errormsg": "",
  "data": [
    {
      "ak": "ak_xxxxxxxx",
      "status": "ONLINE",
      "deviceName": "My-MacBook",
      "os": "macOS",
      "toolType": "opencode",
      "toolVersion": "0.5.0",
      "connectedAt": "2026-03-08T00:10:00"
    }
  ]
}
```

---

### 2. 查询 Agent 状态

**GET** `/api/gateway/agents/status`

#### 查询参数

| 参数 | 类型   | 必填  | 说明                |
| ---- | ------ | :---: | ------------------- |
| `ak` | String |   ✅   | Agent 的 Access Key |

#### 响应

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "ak": "ak_xxxxxxxx",
    "status": "ONLINE",
    "opencodeOnline": true,
    "activeSessionCount": 1
  }
}
```

---

### 3. 发送指令（REST 备用通道）

**POST** `/api/gateway/invoke`

当 WebSocket 长连接不可用时的备用方式。请求体与 WebSocket invoke 消息体一致。

#### 查询参数

| 参数 | 类型   | 必填  | 说明                |
| ---- | ------ | :---: | ------------------- |
| `ak` | String |   ✅   | Agent 的 Access Key |

#### 请求

```json
{
  "type": "invoke",
  "ak": "ak_xxxxxxxx",
  "action": "chat",
  "payload": {
    "toolSessionId": "ses_abc",
    "text": "帮我创建一个React项目"
  }
}
```

#### 响应

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "success": true,
    "message": "Command sent to agent"
  }
}
```
