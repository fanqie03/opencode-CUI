# Layer 2：miniapp / external REST ↔ Skill Server 入站协议

## 概述

入站协议分两类：

```text
miniapp REST:
  /api/skill/**
  由产品内 miniapp 前端调用，使用 cookie userId 做访问控制。

external REST:
  /api/external/invoke
  由外部 IM / CRM / 业务系统调用，使用信封字段表达业务域、会话和发送人。
```

入站 REST 和最终下行 `StreamMessage` 不是同一层协议。REST 负责用户动作输入；`StreamMessage` 负责 agent/cloud 结果输出。

## 一、统一响应信封

Skill Server REST 使用 `ApiResponse<T>`：

```json
{
  "code": 0,
  "errormsg": null,
  "data": {}
}
```

错误：

```json
{
  "code": 400,
  "errormsg": "Content is required"
}
```

注意：

- miniapp 多数 controller 使用 HTTP 200 + body `code` 表达业务错误。
- external envelope/payload 校验直接返回 HTTP 400 + body `ApiResponse.error(400, ...)`。

## 二、miniapp REST

### 2.1 创建会话

```text
POST /api/skill/sessions
```

请求体：

```json
{
  "ak": "agent-ak",
  "title": "会话标题",
  "businessSessionDomain": "miniapp",
  "businessSessionType": "direct",
  "businessSessionId": "optional-business-id",
  "assistantAccount": "assistant-account"
}
```

字段：

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `ak` | 条件必填 | 普通本地/云端助手需要；虚拟助手可由默认规则注入 |
| `assistantAccount` | 条件必填 | 普通助手需要；虚拟助手可由默认规则注入 |
| `title` | 可选 | 会话标题 |
| `businessSessionDomain` | 可选 | miniapp 默认 `miniapp` |
| `businessSessionType` | 可选 | miniapp 默认 `direct` |
| `businessSessionId` | 可选 | 业务上下文 |

处理规则：

```text
1. 从 cookie 解析 userId
2. 若未传 ak / assistantAccount，按 domain + type 查 default_assistant_rule
3. 创建 SkillSession
4. personal scope 发送 create_session 到 Gateway，等待 session_created
5. business/default scope 预生成 toolSessionId
```

### 2.2 查询/关闭/中止会话

| API | 用途 |
| --- | --- |
| `GET /api/skill/sessions` | 当前 cookie userId 的 session 列表 |
| `GET /api/skill/sessions/{id}` | 查询单个 session，并校验访问权 |
| `DELETE /api/skill/sessions/{id}` | close session |
| `POST /api/skill/sessions/{id}/abort` | abort 当前轮次，保留 session |

访问控制：

```text
cookie userId == SkillSession.userId
```

### 2.3 发送消息 / question reply

```text
POST /api/skill/sessions/{sessionId}/messages
```

请求体：

```json
{
  "content": "用户消息或问题答案",
  "toolCallId": "call-q1",
  "subagentSessionId": "sub-tool-session-id",
  "questionId": "opencode-question-id",
  "businessExtParam": {"foo": "bar"}
}
```

路由规则：

| 条件 | action |
| --- | --- |
| 无 `toolCallId` | `chat` |
| 有 `toolCallId` | `question_reply` |

约束：

- `content` 必填。
- `sessionId` 必须能解析成数字。
- session closed 返回 `409`。
- `questionId` 只在 personal scope 有快路径意义；缺失时 plugin 走 fallback。
- `subagentSessionId` 存在时优先作为目标 `toolSessionId`。
- `businessExtParam` 透传到云端 `extParameters.businessExtParam`。

### 2.4 历史记录

正式入口：

```text
GET /api/skill/sessions/{sessionId}/messages/history?beforeSeq=100&size=50
```

语义：

- 使用 cursor history。
- `size` 必须在允许范围内。
- WebSocket `resume` 只恢复 streaming state，不替代此接口。

旧 page/size 接口：

```text
GET /api/skill/sessions/{sessionId}/messages?page=0&size=50
```

该接口不是 miniapp 主流程入口。

### 2.5 permission reply

```text
POST /api/skill/sessions/{sessionId}/permissions/{permId}
```

请求体：

```json
{
  "response": "once",
  "subagentSessionId": "sub-tool-session-id",
  "businessExtParam": {"foo": "bar"}
}
```

`response` 只能是：

```text
once | always | reject
```

处理规则：

```text
1. 校验 response
2. 校验 sessionId 和 userId 访问权
3. 校验 session 未关闭
4. 要求 session.toolSessionId 存在
5. targetToolSessionId = subagentSessionId 或 session.toolSessionId
6. 发送 permission_reply 到 Gateway/cloud
7. 本地发布 permission.reply StreamMessage 更新权限卡
```

### 2.6 send-to-im

```text
POST /api/skill/sessions/{sessionId}/send-to-im
```

请求体：

```json
{"content": "要发回 IM 的文本"}
```

`businessSessionId` 格式：

```text
group_<targetId>_<senderAccount>
direct_<targetId>_<senderAccount>
```

校验：

| 场景 | code |
| --- | --- |
| `content` 空 | 400 |
| `content.length > 4000` | 400 |
| `sessionId` 非法 | 400 |
| cookie `userId` 缺失 | 400 |
| cookie `userId` 无 session 权限 | 403 |
| `businessSessionId` 格式非法 | 400 |
| cookie `userId` != senderAccount | 403 |
| IM 下游发送失败 | 500 |

## 三、external REST

统一入口：

```text
POST /api/external/invoke
```

请求体：

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "group",
  "sessionId": "group-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "businessExtParam": {"trace": "biz"},
  "payload": {
    "content": "hello",
    "msgType": "text",
    "chatHistory": []
  }
}
```

### 3.1 信封字段

| 字段 | 要求 | 说明 |
| --- | --- | --- |
| `action` | 必填 | `chat` / `question_reply` / `permission_reply` / `rebuild` |
| `businessDomain` | 必填 | external 业务域，也是 externalWs `source` |
| `sessionType` | 必填 | `direct` / `group` |
| `sessionId` | 必填 | 外部业务会话 ID；group 时为群 ID |
| `assistantAccount` | 必填 | 助手账号 |
| `senderUserAccount` | 必填 | 当前真实发送人 |
| `businessExtParam` | 可选 | 透传到云端扩展参数 |
| `payload` | action 专属 | JSON object |

### 3.2 action: chat

payload：

```json
{
  "content": "用户消息",
  "msgType": "text",
  "imageUrl": "https://...",
  "chatHistory": [
    {
      "senderAccount": "u1",
      "senderName": "张三",
      "content": "上一条消息",
      "timestamp": 1781260000
    }
  ]
}
```

校验：

- `payload.content` 必填。

处理分支：

| 条件 | 行为 |
| --- | --- |
| session 不存在 | 创建 SkillSession / 异步创建 tool session |
| session 存在但 `toolSessionId` 不 ready | 自动恢复或 pending retry |
| session ready | 构造 `chat` invoke 发 Gateway |

group 约束：

- `senderUserAccount` 必须是真实群成员。
- `imGroupId=sessionId`。
- `SkillSession.userId=null`，不能用它推断 sender。
- `chatHistory` 只在 group 场景注入 prompt。

### 3.3 action: question_reply

payload：

```json
{
  "content": "答案",
  "toolCallId": "call-q1",
  "subagentSessionId": "sub-tool-session-id"
}
```

校验：

- `payload.content` 必填。
- `payload.toolCallId` 必填。

边界：

- 只作用于已有 ready session。
- 不创建 session。
- 不触发 toolSessionId 自动恢复。
- 云端路径通过 `replyContext.type=question_reply` 进入云端请求。

### 3.4 action: permission_reply

payload：

```json
{
  "permissionId": "perm-001",
  "response": "once",
  "subagentSessionId": "sub-tool-session-id"
}
```

校验：

- `payload.permissionId` 必填。
- `payload.response` 必须是 `once` / `always` / `reject`。

边界：

- 只作用于已有 ready session。
- 不创建 session。
- 不触发 toolSessionId 自动恢复。
- 必须在 SS 本地更新权限卡状态。

### 3.5 action: rebuild

payload 无必填字段。

语义：

```text
外部系统主动要求刷新或重建 toolSessionId 链路。
它不是用户消息，不携带当前 message content。
```

## 四、错误矩阵

| 场景 | HTTP | body.code | errormsg |
| --- | --- | --- | --- |
| request body 为空 | 400 | 400 | `Request body is required` |
| action 缺失 | 400 | 400 | `action is required` |
| action 非法 | 400 | 400 | `Invalid action: <action>` |
| businessDomain 缺失 | 400 | 400 | `businessDomain is required` |
| sessionType 非法 | 400 | 400 | `Invalid sessionType` |
| sessionId 缺失 | 400 | 400 | `sessionId is required` |
| assistantAccount 缺失 | 400 | 400 | `assistantAccount is required` |
| senderUserAccount 缺失 | 400 | 400 | `senderUserAccount is required` |
| chat 缺 content | 400 | 400 | `payload.content is required for chat` |
| question_reply 缺 content | 400 | 400 | `payload.content is required for question_reply` |
| question_reply 缺 toolCallId | 400 | 400 | `payload.toolCallId is required for question_reply` |
| permission_reply 缺 permissionId | 400 | 400 | `payload.permissionId is required` |
| permission_reply response 非法 | 400 | 400 | `payload.response must be once/always/reject` |
