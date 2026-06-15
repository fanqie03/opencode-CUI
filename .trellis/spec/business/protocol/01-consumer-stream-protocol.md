# Layer 1：miniapp / externalWs 最终消费协议

miniapp 和 externalWs 最终收到的业务消息都是 `StreamMessage` JSON。
差异只在通道：miniapp 走 `/ws/skill/stream`，externalWs 走 `/ws/external/stream`。

## 通道对比

| 项 | miniapp | externalWs |
| --- | --- | --- |
| endpoint | `/ws/skill/stream` | `/ws/external/stream` |
| handler | `SkillStreamHandler` | `ExternalStreamHandler` |
| 认证 | Cookie `userId` | `Sec-WebSocket-Protocol: auth.<base64url-json>` |
| 客户端 action | `ping`, `resume` | `ping` |
| 下行业务 payload | `StreamMessage` JSON | `StreamMessage` JSON |
| `seq` 来源 | `RedisMessageBroker.nextStreamSeq(sessionId)` | `RedisMessageBroker.nextStreamSeq(sessionId)` |
| session id 暴露 | JSON 中暴露 `welinkSessionId`，内部 `sessionId` 被 `@JsonIgnore` | 同左 |

externalWs 子协议：

```text
Sec-WebSocket-Protocol: auth.<Base64URL(JSON)>
```

```json
{
  "token": "<skill.im.inbound-token>",
  "source": "im-or-business-domain",
  "instanceId": "external-client-instance"
}
```

miniapp resume：

```json
{
  "action": "resume",
  "sessionId": "9000000001"
}
```

## StreamMessage 顶层字段

`StreamMessage` 是扁平 JSON。Java DTO 中的 `ToolInfo`、`PermissionInfo`、`QuestionInfo`、`UsageInfo`、`FileInfo` 都通过 `@JsonUnwrapped` 展开到顶层。

| 字段 | 类型 | 来源/语义 |
| --- | --- | --- |
| `type` | string | 必填，见事件类型表。 |
| `seq` | number | SS 推送前按 session Redis INCR 分配；只表示传输顺序。 |
| `welinkSessionId` | string/number | 最终 JSON 使用的 session 标识；DTO 内部 `sessionId` 不序列化。 |
| `emittedAt` | string | SS translator 或业务代码生成的发送时间，部分 cloud 事件可为空。 |
| `raw` | unknown | 兼容字段，默认不要求消费端依赖。 |
| `messageId` | string | 一轮 agent/cloud 回复的 message 维度标识。 |
| `messageSeq` | number | 历史消息排序序号，不等于 `seq`。 |
| `role` | string | `assistant/user/system/tool`，cloud 缺省为 `assistant`。 |
| `sourceMessageId` | string | 缺省时由 SS 用 `messageId` 兜底。 |
| `partId` | string | 同一 message 下 UI part 标识。 |
| `partSeq` | number | SS 按 `session + partId` 生成；同一 part 稳定。 |
| `content` | string | text/thinking/planning/error 等文本内容。 |
| `status` | string | tool/question/permission 共享状态。 |
| `title` | string | tool/permission/session.title 共用标题。 |
| `error` | string | session/error/tool error 文本。 |
| `sessionStatus` | string | `busy/idle/retry/completed` 等。 |
| `messages` | array | `snapshot` 恢复 payload。 |
| `parts` | array | `streaming` 恢复 payload。 |
| `subagentSessionId` | string | 子 agent 虚拟消息归属。 |
| `subagentName` | string | 子 agent 展示名。 |

## 类型清单

| type | 必要字段 | 说明 |
| --- | --- | --- |
| `text.delta` | `messageId`, `partId`, `content` | 文本增量。 |
| `text.done` | `messageId`, `partId`, `content` | 文本 part 完成。 |
| `thinking.delta` | `messageId`, `partId`, `content` | 思考增量。 |
| `thinking.done` | `messageId`, `partId`, `content` | 思考 part 完成。 |
| `planning.delta` | `messageId`, `partId`, `content` | assistant_square/cloud planning 增量。 |
| `planning.done` | `messageId`, `partId`, `content` | planning part 完成。 |
| `tool.update` | `messageId`, `partId`, `toolName`, `toolCallId`, `status` | 工具状态、输入、输出、错误。 |
| `question` | `messageId`, `partId`, `questionId`, `toolCallId`, `status` | 问题卡创建或状态更新。 |
| `permission.ask` | `messageId`, `partId`, `permissionId`, `permType`, `status` | 权限请求卡。 |
| `permission.reply` | `permissionId`, `response`, `status` | 权限请求已回复；主会话前端不新建卡片。 |
| `file` | `messageId`, `partId`, `fileName/fileUrl/fileMime` | 文件附件。 |
| `searching` | `messageId`, `partId`, `keywords` | 云端搜索中。 |
| `search_result` | `messageId`, `partId`, `searchResults` | 云端搜索结果。 |
| `reference` | `messageId`, `partId`, `references` | 云端引用资料。 |
| `ask_more` | `messageId`, `partId`, `askMoreQuestions` | 云端追问建议。 |
| `step.start` | `messageId` | 一轮 agent step 开始。 |
| `step.done` | `messageId`, `tokens/cost/reason` | 一轮 agent step 完成。 |
| `session.status` | `sessionStatus` | 会话 busy/idle/retry/completed。 |
| `session.title` | `title` | 会话标题更新。 |
| `session.error` | `error` | 会话错误并收尾。 |
| `message.user` | `messageId`, `content` | 用户消息多端同步。 |
| `agent.online` | 可无业务字段 | agent 上线。 |
| `agent.offline` | 可无业务字段 | agent 离线。 |
| `snapshot` | `messages` | WS 恢复全量状态。 |
| `streaming` | `parts`, `sessionStatus` | WS 恢复流式状态。 |
| `error` | `error` | SS 通用错误。 |

## 实测下行样例

抓包文件：`.trellis/tasks/06-12-plugin-miniapp/research/local-run/miniapp-ws-capture.jsonl`。

### text.delta / text.done

```json
{
  "type": "text.delta",
  "seq": 9,
  "messageId": "msg-local-1",
  "role": "assistant",
  "sourceMessageId": "msg-local-1",
  "partId": "text-1",
  "partSeq": 1,
  "content": "hello ",
  "welinkSessionId": "9000000001"
}
```

```json
{
  "type": "text.done",
  "seq": 10,
  "messageId": "msg-local-1",
  "role": "assistant",
  "sourceMessageId": "msg-local-1",
  "partId": "text-1",
  "partSeq": 1,
  "content": "hello world",
  "welinkSessionId": "9000000001"
}
```

### question

```json
{
  "type": "question",
  "seq": 11,
  "messageId": "msg-local-1",
  "role": "assistant",
  "sourceMessageId": "msg-local-1",
  "partId": "question-1",
  "partSeq": 2,
  "status": "running",
  "toolCallId": "call-q-1",
  "header": "Choose",
  "question": "Pick one?",
  "options": ["A", "B"],
  "questionId": "question-1",
  "welinkSessionId": "9000000001"
}
```

### permission.ask / permission.reply

```json
{
  "type": "permission.ask",
  "seq": 12,
  "messageId": "msg-local-1",
  "role": "assistant",
  "sourceMessageId": "msg-local-1",
  "partId": "perm-1",
  "partSeq": 3,
  "status": "pending",
  "title": "Run command",
  "permissionId": "perm-1",
  "permType": "command",
  "metadata": {"command": "pwd"},
  "welinkSessionId": "9000000001"
}
```

```json
{
  "type": "permission.reply",
  "seq": 13,
  "messageId": "msg-local-1",
  "role": "assistant",
  "sourceMessageId": "msg-local-1",
  "partId": "perm-1",
  "partSeq": 3,
  "status": "completed",
  "permissionId": "perm-1",
  "permType": "command",
  "response": "once",
  "welinkSessionId": "9000000001"
}
```

### planning / searching / session.status

```json
{
  "type": "planning.delta",
  "seq": 14,
  "messageId": "msg-local-2",
  "role": "assistant",
  "sourceMessageId": "msg-local-2",
  "partId": "planning-1",
  "partSeq": 1,
  "content": "plan",
  "welinkSessionId": "9000000001"
}
```

```json
{
  "type": "searching",
  "seq": 15,
  "messageId": "msg-local-2",
  "role": "assistant",
  "sourceMessageId": "msg-local-2",
  "partId": "searching-1",
  "partSeq": 2,
  "keywords": ["JDK8"],
  "welinkSessionId": "9000000001"
}
```

```json
{
  "type": "session.status",
  "seq": 16,
  "sessionStatus": "idle",
  "welinkSessionId": "9000000001"
}
```

## 消费端约束

- `seq` 是传输顺序，不是业务排序；消息内 part 排序优先看 `partSeq`。
- `session.status=idle/completed` 要收尾所有 active streaming message。
- `permission.reply` 在主会话只更新已有 permission part，不创建新的消息卡片。
- `question` 的 `completed/error` 更新如果到达时 assembler 已关闭，miniapp 会按 `partId/toolCallId` patch 已有 question part。
- 未识别的 `type` 不应导致客户端崩溃；应忽略或进入 fallback 渲染。
