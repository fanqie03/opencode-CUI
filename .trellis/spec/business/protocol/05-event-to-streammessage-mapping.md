# 全链路映射：agent/cloud event -> StreamMessage

本文件用于排查“某个上游事件最终会变成什么下行消息”。最终消费者只应依赖 `StreamMessage`。

## 字段归一总规则

| 字段 | 归一规则 |
| --- | --- |
| `welinkSessionId` | SS 推送前由内部 sessionId 兜底；最终 JSON 不暴露内部 `sessionId`。 |
| `seq` | SS 推送前按 `ss:stream-seq:{sessionId}` Redis INCR 分配。 |
| `messageId` | message/part 级事件应由上游提供；缺失时部分 translator warn 后继续。 |
| `sourceMessageId` | 缺失时由 SS 用 `messageId` 兜底。 |
| `role` | cloud 缺省为 `assistant`；OpenCode 来自 `message.updated` cache。 |
| `partId` | part 级事件应由上游提供；assistant_square 当前可缺失。 |
| `partSeq` | OpenCode 由 translator cache 生成；cloud 由 `session + partId` 生成；session idle 清理。 |
| `questionId` | OpenCode 为 `question.asked.properties.id`；cloud 为 `properties.questionId`。 |
| `permissionId` | OpenCode ask 用 `id`，reply 用 `requestID`；cloud 用 `permissionId`。 |
| `response` | 统一使用 `once/always/reject`。 |

## 一、本地 OpenCode/local -> StreamMessage

plugin -> gateway 的 schema 边界以“缺失 `protocol`”识别本地 OpenCode 协议；Skill Server personal-scope 内部兼容 `protocol:"opencode"`，但这是另一层分派规则。

| 上游 event | 条件 | StreamMessage.type | 字段来源 |
| --- | --- | --- | --- |
| `message.part.updated` | `part.type="text"`，非 user 忽略路径 | `text.done` | `messageId=part.messageID`, `partId=part.id`, `content=part.text`。 |
| `message.part.delta` | part type cache 为 `text` | `text.delta` | `messageId=properties.messageID`, `partId=properties.partID`, `content=delta`。 |
| `message.part.updated` | `part.type="reasoning"` | `thinking.done` | `content=part.text`。 |
| `message.part.delta` | part type cache 为 `reasoning` | `thinking.delta` | `content=delta`。 |
| `message.part.delta` | part type 未知 | 丢弃 | delta 必须先有 `message.part.updated` 建立 part type cache。 |
| `message.part.updated` | `part.type="tool"` 且 `part.tool!="question"` | `tool.update` | `toolName=part.tool`, `toolCallId=part.callID`, `status=state.status`, `input/output/error/title=state.*`。 |
| `message.part.updated` | `part.type="tool"`, `tool="question"`, `status=pending/running` | 丢弃 | running question 以 `question.asked` 为 source of truth。 |
| `message.part.updated` | `tool="question"`, `status=completed/error` | `question` | 用 cache 中 `callID -> questionPartId` 更新已有 question part。 |
| `question.asked` | 有 `questions[]` | `question` | `partId=questionId=properties.id`, `messageId=tool.messageID`, `toolCallId=tool.callID`, `status=running`。 |
| `question.replied/rejected` | 任意 | 丢弃 | 完成态由 question tool completed/error 覆盖，避免重复卡片。 |
| `permission.asked/updated` | 未 resolved | `permission.ask` | `permissionId=id`, `permType=type/permission`, `metadata`, `title`, `status=pending`。 |
| `permission.updated` | 有 response/decision/answer/reply 或 resolved/status 完成 | `permission.reply` | `response` 归一。 |
| `permission.replied` | 任意 | `permission.reply` | `permissionId=requestID`, `response=reply`。 |
| `message.part.updated` | `part.type="step-start"` | `step.start` | `messageId=part.messageID`。 |
| `message.part.updated` | `part.type="step-finish"` | `step.done` | `tokens/cost/reason=part.*`。 |
| `message.updated` | `role=user` 且已有 cached text | `text.done` | 解决 text part 先于 role 到达的乱序。 |
| `message.updated` | assistant/system/tool 且有 `info.finish` | `step.done` | `reason=info.finish.reason`。 |
| `message.part.updated` | `part.type="file"` | `file` | `fileName=filename`, `fileUrl=url`, `fileMime=mime`。 |
| `message.part.removed` | 任意 | 丢弃 | 只 evict part cache。 |
| `session.status` | `status.type=busy/active/running` | `session.status` | `sessionStatus=busy`。 |
| `session.status` | `status.type=idle/completed` | `session.status` | `sessionStatus=idle`，并清理 cache。 |
| `session.status` | `status.type=reconnecting/retry/recovering` | `session.status` | `sessionStatus=retry`。 |
| `session.idle` | 任意 | `session.status` | `sessionStatus=idle`，并清理 cache。 |
| `session.updated` | title present | `session.title` | `title=info.title/title`。 |
| `session.error` | 任意 | `session.error` | `error` 字符串或 JSON string。 |

### 本地 permission 一一对应

上游：

```json
{
  "type": "permission.replied",
  "properties": {
    "sessionID": "ses_permission_1",
    "requestID": "perm_fixture_1",
    "reply": "always"
  }
}
```

SS：

```json
{
  "type": "permission.reply",
  "permissionId": "perm_fixture_1",
  "response": "always",
  "status": "completed"
}
```

miniapp：

```text
主会话：不新建消息；按 permissionId 更新已有 permission part。
subagent：更新 SubtaskBlock 内匹配 permissionId 的 permission subPart。
```

## 二、插件云端 skill-provider -> StreamMessage

云端事件统一形态：

```json
{
  "protocol": "cloud",
  "type": "<event.type>",
  "properties": {}
}
```

插件 uplink 走 `gateway-schema` 时，`protocol:"cloud"` 当前白名单只覆盖下表这些 skill-provider 事件：

| skill-provider event | StreamMessage.type | 字段对应 |
| --- | --- | --- |
| `step.start` | `step.start` | `messageId`, `role`。不要求 `partId`。 |
| `step.done` | `step.done` | `messageId`, `tokens`, `cost`, `reason`。不要求 `partId`。 |
| `text.delta` | `text.delta` | `messageId`, `partId`, `content`, `role`。 |
| `text.done` | `text.done` | 同上。 |
| `thinking.delta` | `thinking.delta` | `messageId`, `partId`, `content`, `role`。 |
| `thinking.done` | `thinking.done` | 同上。 |
| `tool.update` | `tool.update` | `toolName`, `toolCallId`, `input`, `output`, `status`, `error`, `title`。 |
| `question` | `question` | `questionId`, `toolCallId`, `status`, `questions[]`, `extParam`；第一题会展开到 `header/question/options/multiSelect`。 |
| `permission.ask` | `permission.ask` | `permissionId`, `permType`, `metadata`, `title`, `status=pending` 缺省。 |
| `permission.reply` | `permission.reply` | `permissionId`, `permType`, `response`, `status=completed` 缺省。 |
| `session.status` | `session.status` | plugin schema 使用 `sessionStatus`；SS internal standard event 兼容 `status`。 |
| `session.title` | `session.title` | `title`。 |
| `session.error` | `session.error` | `error`。 |

`CloudEventTranslator` 还支持下列 standard 扩展事件，但它们不是当前插件 `gateway-schema` skill-provider 白名单的一部分；主要来自 assistant_square decoder 或 SS 内部已经归一过的 cloud event：

| standard extension event | StreamMessage.type | 字段对应 |
| --- | --- | --- |
| `planning.delta` | `planning.delta` | `messageId`, `partId`, `content`。 |
| `planning.done` | `planning.done` | `messageId`, `partId`, `content`。 |
| `file` | `file` | `fileName`, `fileUrl`, `fileMime`。 |
| `searching` | `searching` | `keywords`。 |
| `search_result` | `search_result` | `searchResults`。 |
| `reference` | `reference` | `references`。 |
| `ask_more` | `ask_more` | `askMoreQuestions`。 |

### 云端 question 一一对应

插件 uplink：

```json
{
  "protocol": "cloud",
  "type": "question",
  "properties": {
    "messageId": "msg-cloud-1",
    "partId": "question-display-1",
    "questionId": "question-target-1",
    "toolCallId": "question-target-1",
    "questions": [
      {
        "question": "Pick one?",
        "header": "Choose",
        "options": [
          {"label": "A"},
          {"label": "B", "description": "Beta"}
        ]
      }
    ]
  }
}
```

SS 下行：

```json
{
  "type": "question",
  "messageId": "msg-cloud-1",
  "partId": "question-display-1",
  "toolCallId": "question-target-1",
  "status": "running",
  "header": "Choose",
  "question": "Pick one?",
  "options": ["A", "B"],
  "questions": [
    {
      "header": "Choose",
      "question": "Pick one?",
      "options": ["A", "B"]
    }
  ],
  "questionId": "question-target-1"
}
```

## 三、assistant_square -> standard cloud -> StreamMessage

assistant_square 的第一段映射在 Gateway 内完成：

| assistant_square 输入 | Gateway standard event | 后续 StreamMessage |
| --- | --- | --- |
| 首个事件 | `session.status busy`, `step.start` | `session.status`, `step.start` |
| `eventType=message`, `messageType=TEXT` | `text.delta` | `text.delta` |
| text 流切换或 flush | `text.done` | `text.done` |
| `eventType=think` | `thinking.delta` | `thinking.delta` |
| thinking 流切换或 flush | `thinking.done` | `thinking.done` |
| `eventType=planning`, `messageType` 缺失或 `PLANNING` | `planning.delta` | `planning.delta` |
| planning 流切换或 flush | `planning.done` | `planning.done` |
| `eventType=searching` | `searching` | `searching` |
| `eventType=searchResult` | `search_result` | `search_result` |
| `eventType=reference` | `reference` | `reference` |
| `eventType=askMore` | `ask_more` | `ask_more` |
| `eventType=error` | `GatewayMessage.Type.TOOL_ERROR` | SS error path，而不是普通 `tool_event`。 |
| `FINISH` / `[DONE]` | open part `.done`, `step.done`, `session.status idle` | 对应下行事件。 |
| 未知 protocolType | 丢弃 | 无下行。 |
| 不支持 messageType | 丢弃该业务事件 | 可能已有 `session.status busy` / `step.start`。 |

### assistant_square 示例

SSE data：

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

Gateway 第一次看到该 stream 时可能依次输出：

```json
{"type": "session.status", "properties": {"status": "busy", "sessionStatus": "busy", "messageId": "m1"}}
```

```json
{"type": "step.start", "properties": {"role": "assistant", "messageId": "m1"}}
```

```json
{"type": "text.delta", "properties": {"messageId": "m1", "content": "hello"}}
```

SS 再转为：

```json
{"type": "text.delta", "messageId": "m1", "role": "assistant", "sourceMessageId": "m1", "content": "hello"}
```

当前 assistant_square delta 没有 `partId`，因此最终可能没有 `partSeq`；miniapp 会用 fallback part id 聚合。

## 四、丢弃/不下发规则

| 来源 | 事件 | 原因 |
| --- | --- | --- |
| OpenCode | `message.part.delta` part type 未缓存 | 不知道 delta 属于 text 还是 reasoning。 |
| OpenCode | `message.part.removed` | 只清理 translator cache。 |
| OpenCode | `question.replied/rejected` | 避免重复 question 卡片。 |
| OpenCode | question tool running/pending | `question.asked` 已负责创建卡片。 |
| OpenCode | user role 的 assistant 流式路径 | user 文本按特殊乱序逻辑补 `text.done`。 |
| cloud | 未知 event.type | `CloudEventTranslator` 返回 null。 |
| cloud | 显式 `protocol` 不是 `"cloud"` | gateway-schema fail-closed。 |
| assistant_square | heartbeat/ping | 忽略。 |
| assistant_square | 未知 `protocolType` | fallback handler 丢弃。 |
| assistant_square | 不支持 `messageType` | 丢弃业务事件。 |
