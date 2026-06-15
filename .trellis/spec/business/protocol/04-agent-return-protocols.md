# Layer 4：agent / cloud 返回协议

agent 返回侧当前有三套来源协议：

```text
1. 本地 OpenCode/local plugin event
2. 插件云端 skill-provider event（protocol: "cloud"）
3. assistant_square SSE

最终都必须归一为 StreamMessage，再给 miniapp / externalWs。
```

本文件只描述“返回协议”。请求 body 见 Layer 2/3，不要把 `msgBody/sendW3Account/topicId` 等请求字段当成返回事件。

## 一、本地 OpenCode/local plugin 协议

判定规则：在 plugin -> gateway 的 `gateway-schema` 边界，`tool_event.event` 中没有 `protocol` 字段时按 OpenCode/local 协议解析。

```json
{
  "type": "tool_event",
  "toolSessionId": "tool-local-1",
  "event": {
    "type": "message.part.updated",
    "properties": {
      "part": {
        "id": "prt_1",
        "sessionID": "ses_1",
        "messageID": "msg_1",
        "type": "text",
        "text": "hi"
      }
    }
  }
}
```

本地协议准入事件：

| event.type | properties 关键字段 | SS 处理 |
| --- | --- | --- |
| `message.part.updated` | `part.id`, `part.messageID`, `part.sessionID`, `part.type` | 按 part 类型转 `text.done/thinking.done/tool.update/step.start/step.done/file/question`。 |
| `message.part.delta` | `sessionID`, `messageID`, `partID`, `field:"text"`, `delta` | 必须先知道 part type；text -> `text.delta`，reasoning -> `thinking.delta`。 |
| `message.part.removed` | `sessionID`, `messageID`, `partID` | 只清理 SS translator cache，不下发。 |
| `message.updated` | `info.id`, `info.sessionID`, `info.role`, `info.finish` | 记录 role；user 文本乱序时补 `text.done`；assistant finish -> `step.done`。 |
| `session.status` | `sessionID`, `status.type` 或 `status` | 归一 `busy/idle/retry` 后下发 `session.status`。 |
| `session.idle` | `sessionID` | 下发 `session.status idle` 并清理 session cache。 |
| `session.updated` | `sessionID`, `info.title` 或 `title` | 下发 `session.title`。 |
| `session.error` | `sessionID`, `error` | 下发 `session.error`。 |
| `question.asked` | `id`, `sessionID`, `questions[]`, `tool.messageID`, `tool.callID` | 下发 `question`；`questionId == partId == properties.id`。 |
| `question.replied` / `question.rejected` | request/status | 不下发；完成态由 question tool completed/error 覆盖。 |
| `permission.asked` / `permission.updated` | `id`, `sessionID`, `messageID`, `type/permission`, `metadata`, `status` | 未 resolved -> `permission.ask`；resolved -> `permission.reply`。 |
| `permission.replied` | `requestID`, `reply` | 下发 `permission.reply`，`reply` 归一到 `once/always/reject`。 |

实测 validator 样例见 `.trellis/tasks/06-12-plugin-miniapp/research/local-run/plugin-local-opencode-capture.json`。关键结论：

- 本地协议 payload 不带 `protocol`。
- `message.updated.user.json` 中多余的 `agent` 字段会被 schema 剥离；canonical 只保留契约字段。
- `question.asked` 的 tool 引用优先来自 `properties.tool.messageID/callID`。
- `permission.replied` 使用 `requestID + reply`，不是 `permissionId + response`。

边界说明：Skill Server personal-scope 内部还有一层兼容分派，缺失 `protocol` 或 `protocol:"opencode"` 都走 `OpenCodeEventTranslator`，`protocol:"cloud"` 走 `CloudEventTranslator`，未知值 warn 后 fallback 到 OpenCode。这是 SS 内部兼容逻辑，不等同于 plugin `gateway-schema` 边界；plugin uplink 显式写非 `"cloud"` 的 protocol 会 fail-closed。

### 本地 text 示例

原始/准入事件：

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt_text_1",
      "sessionID": "ses_1",
      "messageID": "msg_1",
      "type": "text",
      "text": "hi"
    }
  }
}
```

SS 输出：

```json
{
  "type": "text.done",
  "messageId": "msg_1",
  "sourceMessageId": "msg_1",
  "partId": "prt_text_1",
  "partSeq": 1,
  "role": "assistant",
  "content": "hi"
}
```

### 本地 question 示例

```json
{
  "type": "question.asked",
  "properties": {
    "id": "question_fixture_1",
    "sessionID": "ses_question_1",
    "questions": [
      {
        "question": "Choose a framework",
        "header": "Framework",
        "options": [{"label": "Vite"}, {"label": "CRA"}]
      }
    ],
    "tool": {
      "messageID": "msg_question_1",
      "callID": "call_question_1"
    }
  }
}
```

SS 输出要点：

```json
{
  "type": "question",
  "messageId": "msg_question_1",
  "partId": "question_fixture_1",
  "toolName": "question",
  "toolCallId": "call_question_1",
  "status": "running",
  "header": "Framework",
  "question": "Choose a framework",
  "options": ["Vite", "CRA"],
  "questionId": "question_fixture_1"
}
```

## 二、插件云端 skill-provider 协议

判定规则：`tool_event.event.protocol == "cloud"`。只接受 `protocol: "cloud"`，其他显式 protocol fail-closed，不能回退到本地协议。

插件运行时由 `DefaultFactToSkillEventProjector` 把 provider facts 投影成 skill-provider event。实测 uplink 抓包见 `.trellis/tasks/06-12-plugin-miniapp/research/local-run/plugin-uplink-capture.json`。

### 云端 transport envelope

```json
{
  "type": "tool_event",
  "toolSessionId": "tool-local-1",
  "event": {
    "protocol": "cloud",
    "type": "text.delta",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "text-1",
      "content": "he"
    }
  }
}
```

### 云端准入事件

| event.type | properties 必要字段 | properties 可选字段 | SS 输出 |
| --- | --- | --- | --- |
| `step.start` | `messageId` | `role` | `step.start` |
| `step.done` | `messageId` | `tokens`, `cost`, `reason`, `role` | `step.done` |
| `text.delta` | `messageId`, `partId`, `content` | `role` | `text.delta` |
| `text.done` | `messageId`, `partId`, `content` | `role` | `text.done` |
| `thinking.delta` | `messageId`, `partId`, `content` | `role` | `thinking.delta` |
| `thinking.done` | `messageId`, `partId`, `content` | `role` | `thinking.done` |
| `tool.update` | `messageId`, `partId`, `toolName`, `toolCallId`, `status` | `title`, `input`, `output`, `error` | `tool.update` |
| `question` | `messageId`, `partId`, `questionId`, `questions[]` | `toolCallId`, `status`, `extParam` | `question` |
| `permission.ask` | `partId`, `permissionId`, `permType` | `messageId`, `title`, `metadata`, `status` | `permission.ask` |
| `permission.reply` | `permissionId`, `response` | `messageId`, `partId`, `permType`, `status` | `permission.reply` |
| `session.status` | `sessionStatus` | - | `session.status` |
| `session.title` | `title` | - | `session.title` |
| `session.error` | `error` | - | `session.error` |

注意：

- schema 中 `tool.update.input/output` 当前以 string 为主；`CloudEventTranslator` 对 `input` 更宽松，能透传 JSON object。写上游协议时优先按 schema 发送。
- `question` 必须是 `questions[] + questionId`，不要发送本地扁平 `question/header/options` 作为唯一形态。
- `permission.ask` 不接受 OpenCode 的 `toolCallId` 作为必填语义；云端权限卡主键是 `permissionId`，展示 part 是 `partId`。
- `permission.reply` projector 会尽量恢复 ask 阶段的 `messageId/partId`，前端才能原地更新 permission part。
- plugin `gateway-schema` 的 cloud `session.status` 字段是 `sessionStatus`；SS `CloudEventTranslator` 对内部 standard event 额外兼容 `status`。

全量 JSON 示例见 [08-all-event-examples.md](./08-all-event-examples.md)。

### 云端实测序列

```json
{
  "protocol": "cloud",
  "type": "permission.ask",
  "properties": {
    "messageId": "msg-cloud-1",
    "partId": "permission-display-1",
    "permissionId": "permission-target-1",
    "permType": "command",
    "title": "Run command",
    "metadata": {"command": "pwd"}
  }
}
```

```json
{
  "protocol": "cloud",
  "type": "permission.reply",
  "properties": {
    "permissionId": "permission-target-1",
    "response": "once",
    "permType": "command",
    "messageId": "msg-cloud-1",
    "partId": "permission-display-1"
  }
}
```

这两个事件在最终下行中应保持同一个 `permissionId`，且最好保持同一个 `partId`，否则 miniapp 可能无法原地更新已有 permission card。

## 三、assistant_square SSE 协议

assistant_square 不直接给 miniapp 协议。Gateway 先把 assistant_square SSE 转成 standard cloud event，再交给 `CloudEventTranslator`。

```text
cloudProfile=assistant_square
  -> AssistantSquareSseEventDecoder
  -> StandardProtocolHandler
  -> GatewayMessage(type=tool_event, event.type=<standard cloud event>)
  -> CloudEventTranslator
  -> StreamMessage
```

### SSE 行规则

| 输入 | 行为 |
| --- | --- |
| `FINISH` | terminator，flush 当前 part，补 `step.done` 和 `session.status idle`。 |
| `[DONE]` | 同上。 |
| 包含 `"eventType":"ping"` | heartbeat，忽略。 |
| 空行/解析失败 | warn 并丢弃，不中断整条流。 |
| root 有 `data` object | 使用内层 `data`；root 的 `eventType/protocolType/code/message/error` 会补进内层。 |

### protocolType

| protocolType | handler | 行为 |
| --- | --- | --- |
| 缺失/空 | `standard` | 支持。 |
| `"standard"` | `standard` | 支持。 |
| `"5"` | `standard` | 支持。 |
| 其他，例如 `athena/uniknow/agentmaker` | fallback | 丢弃，不报错。 |

### standard event 映射

| assistant_square `eventType` | `messageType` | 内容字段优先级 | standard event |
| --- | --- | --- | --- |
| `planning` | 缺失或 `PLANNING` | `planning`, `messageBody`, `text` | `planning.delta` |
| `think` | 任意 | `think`, `thinking`, `messageBody`, `text` | `thinking.delta` |
| `processStep` | 任意 | `processStep`, `message`, `messageEn`, `text` | `thinking.delta` |
| `message` | `TEXT` | `messageBody`, `text`, `content` | `text.delta` |
| `searching` | 任意 | `searching`, `keywords` | `searching` |
| `searchResult` | 任意 | `searchResult`, `results` | `search_result` |
| `reference` | 任意 | `reference`, `references` | `reference` |
| `askMore` | 任意 | `askMore`, `askMoreQuestions`, `questions` | `ask_more` |
| `error` | 任意 | `message`, `error`, `messageEn`, `errorEn` | 顶层 `tool_error` |

不支持并丢弃的 `messageType`：`HTML`、`IMAGE-IM`、`FILE-IM`、card、`TEXT_LIST`、`SLOT`、`WeLink-CARD` 等。注意：`StandardProtocolHandler` 会在第一个事件前先补 `session.status busy` 和 `step.start`，所以即使第一个业务事件被丢弃，也可能已经出现 step 边界。

### assistant_square 状态机

```text
first supported/seen event
  -> session.status busy
  -> step.start

streaming event(text/thinking/planning)
  -> <type>.delta
  -> 累积 open part content

stream type 或 messageId 切换
  -> previous <type>.done
  -> open new part

single event(searching/reference/ask_more)
  -> 如有 open part，先补 previous <type>.done
  -> emit single event

flush(FINISH/[DONE])
  -> emit remaining <type>.done
  -> step.done
  -> session.status idle
```

示例：protocol 5 嵌套消息。

```json
{
  "code": "200",
  "eventType": "message",
  "protocolType": "5",
  "data": {
    "messageId": "m1",
    "messageType": "TEXT",
    "messageBody": {"text": "hello"}
  }
}
```

Gateway standard event：

```json
{
  "type": "text.delta",
  "properties": {
    "messageId": "m1",
    "content": "hello"
  }
}
```

说明：assistant_square decoder 目前生成的 delta/done 不带 `partId`；`CloudEventTranslator` 会 warn，但仍下发。miniapp 会用本地 fallback partId 合并流式内容。
