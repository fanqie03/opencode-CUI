# StreamMessage 生命周期

本文件按 `StreamMessage.type` 说明消息从哪里来、miniapp 如何消费、修改协议时要同步哪些位置。事实来源是 `useSkillStream.ts` 和 `StreamAssembler.ts`。

## miniapp 总体消费流程

```text
WebSocket /ws/skill/stream
  -> JSON.parse
  -> normalizeIncomingStreamMessage
  -> 按 welinkSessionId/sessionId 过滤
  -> 历史未加载完成时进入 pending queue
  -> processStreamMessage
  -> StreamAssembler 或直接 patch React state
```

关键规则：

- `welinkSessionId` 必须能与当前 session 匹配，否则丢弃。
- 除 `agent.online/offline` 外，历史消息未加载完成时会先排队，历史加载后再 flush。
- 有 `subagentSessionId` 的事件走 subagent 分支，主会话不会直接渲染成普通 assistant message。
- `session.status=idle/completed` 会 finalize 所有 streaming message。
- `permission.reply` 在主会话不调用 `applyStreamedMessage`，避免创建“已处理”重复消息。

## 内容类

| type | 来源 | 前端处理 | 完成条件 |
| --- | --- | --- | --- |
| `text.delta` | OpenCode text delta、cloud text delta、assistant_square message(TEXT) | `StreamAssembler` 按 `partId` 追加到 text part，`isStreaming=true`。 | 等 `text.done` 或 `session.status idle/completed`。 |
| `text.done` | OpenCode text updated、cloud text.done、assistant_square type switch/flush | 替换/确认 text part 全量内容，part `isStreaming=false`。 | 当前 text part 完成。 |
| `thinking.delta` | OpenCode reasoning delta、cloud thinking delta、assistant_square think/processStep | 追加 thinking part。 | 等 `thinking.done` 或 session 收尾。 |
| `thinking.done` | OpenCode reasoning updated、cloud thinking.done、assistant_square switch/flush | 替换/确认 thinking part。 | thinking part 完成。 |
| `planning.delta` | assistant_square planning、cloud planning | 追加 planning part。 | 等 `planning.done` 或 session 收尾。 |
| `planning.done` | assistant_square switch/flush、cloud planning.done | 替换/确认 planning part。 | planning part 完成。 |

实现要点：

- 同一个 `partId` 的 delta/done 必须闭合到同一个 part。
- 缺 `partId` 时，`StreamAssembler` 会用当前 active part 或 fallback id，但上游规范仍要求能提供 `partId`。
- `session.status=idle/completed` 是最后兜底收尾，不能依赖所有上游都发送 `.done`。

## 工具与交互类

| type | 来源 | 关键字段 | 前端处理 |
| --- | --- | --- | --- |
| `tool.update` | OpenCode tool part、cloud tool.update | `partId`, `toolName`, `toolCallId`, `status`, `input`, `output`, `error`, `title` | 按 `partId` upsert tool part；`pending/running` 时 part streaming。 |
| `question` | OpenCode `question.asked`、cloud question、question tool completed/error | `partId`, `toolCallId`, `questionId`, `status`, `header`, `question`, `options` | 创建或更新 question part；completed/error 标记 answered。 |
| `permission.ask` | OpenCode permission、cloud permission.ask、gateway permission_request | `partId`, `permissionId`, `permType`, `title`, `metadata` | 创建 permission part，`permResolved=false`。 |
| `permission.reply` | 用户回复后的本地发布、cloud permission.reply、OpenCode permission.replied | `permissionId`, `response`, `status` | 主会话不创建新消息；更新已有 permission part。 |
| `file` | OpenCode file part、cloud file | `fileName`, `fileUrl`, `fileMime` | 创建 file part。 |

### question 完成态

question 有两类事件：

```text
question.asked / cloud question(status running)
  -> 创建 question card

OpenCode question tool completed/error 或 cloud question(status completed/error)
  -> 更新同一个 question card
```

miniapp 特殊处理：

- 如果 `question` 完成事件到达时 message 的 assembler 还 active，走 `applyStreamedMessage` 正常合并。
- 如果 `session.status idle` 已先到，assembler 已关闭，miniapp 会直接按 `partId` 或 `toolCallId` patch 旧 question part。
- 因此完成事件必须尽量保留原 `partId` 或 `toolCallId`，否则无法稳定更新。

### permission reply

用户点 permission 后，miniapp 会先乐观更新当前 state：

```text
replyPermission(permissionId, response)
  -> 立即把匹配 permission part 标记 resolved
  -> 调 REST API 通知后端
  -> 后续收到 permission.reply 时保持已处理态
```

主会话收到 `permission.reply` 时：

```text
case "permission.reply":
  break;
```

也就是说它不会调用 `applyStreamedMessage`。真正的视觉更新来自：

- `replyPermission` 的本地乐观更新；
- `StreamAssembler.resolvePermission(permissionId, response)` 保持 assembler 同步；
- subagent 分支会 patch SubtaskBlock 内的 permission subPart。

协议要求：

- `permission.ask` 和 `permission.reply` 必须共用同一个 `permissionId`。
- cloud reply 最好带回 ask 阶段的 `messageId/partId`，方便其他消费端原地更新。
- `response` 只使用 `once/always/reject`。

## step / session 类

| type | 来源 | 前端处理 |
| --- | --- | --- |
| `step.start` | OpenCode step-start、cloud step.start、assistant_square 首事件补齐 | 把 `messageId` 加入 active set，`isStreaming=true`。 |
| `step.done` | OpenCode step-finish/message finish、cloud step.done、assistant_square flush | 更新 message meta：`tokens/cost/reason`。不单独 finalize，最终以 session.status 收尾。 |
| `session.status` | OpenCode session.status/idle、cloud session.status、assistant_square busy/idle 补齐 | `idle/completed` -> finalize all；`busy/retry` -> `isStreaming=true`。 |
| `session.title` | OpenCode session.updated、cloud session.title | 调 `onSessionTitleUpdate` 更新标题。 |
| `session.error` | OpenCode/cloud error | finalize all，并设置 error。 |
| `error` | SS 通用错误 | finalize all，并设置 error。 |
| `agent.online` | Gateway agent online | 更新 agentStatus。 |
| `agent.offline` | Gateway agent offline | 更新 agentStatus。 |

`session.status` 语义：

| status | 含义 | 前端行为 |
| --- | --- | --- |
| `busy` | 当前轮次处理中 | `isStreaming=true` |
| `retry` | rebuild/retry/recovering | `isStreaming=true` |
| `idle` | 当前轮次结束，会话可复用 | finalize all |
| `completed` | 当前处理完成 | finalize all |

`idle != toolSessionId 失效`。不要用 idle 判断 agent session 不可继续。

## 云端扩展类

| type | 来源 | 前端 part 类型 | 字段 |
| --- | --- | --- | --- |
| `searching` | assistant_square/cloud | `searching` | `keywords` |
| `search_result` | assistant_square/cloud | `search_result` | `searchResults` |
| `reference` | assistant_square/cloud | `reference` | `references` |
| `ask_more` | assistant_square/cloud | `ask_more` | `askMoreQuestions` |

这些类型走 `applyStreamedMessage`，会作为当前 assistant message 的 part 合并。新增云端扩展类型时必须同时更新：

- `StreamMessage.Types`
- `skill-miniapp/src/protocol/types.ts`
- `StreamAssembler.handleMessage`
- `useSkillStream.processStreamMessage`
- `CloudEventTranslator`
- 如来自 assistant_square，还要更新 `StandardProtocolHandler`

## 恢复类

| type | 来源 | 前端处理 |
| --- | --- | --- |
| `message.user` | SS 保存用户消息后的多端同步 | 如果本端已有 optimistic user message，跳过；否则 upsert user message。 |
| `snapshot` | WS 连接建立/resume | merge `messages` 到当前历史状态。 |
| `streaming` | WS 连接建立/resume | 恢复进行中的 parts；如果 idle 且无 parts，则 finalize all。 |

恢复约束：

- miniapp 历史主体来自 HTTP `/messages/history`。
- `snapshot/streaming` 只用于实时状态恢复，不是完整历史 API 替代。
- 如果 snapshot 中的 parts 与当前 streaming parts 重复，前端会尝试按 part identity 去重。

## 修改检查表

新增或修改 `StreamMessage.type` 时必须同步：

- [ ] `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`
- [ ] `skill-miniapp/src/protocol/types.ts`
- [ ] `skill-miniapp/src/protocol/StreamAssembler.ts`
- [ ] `skill-miniapp/src/hooks/useSkillStream.ts`
- [ ] `OpenCodeEventTranslator` 或 `CloudEventTranslator`
- [ ] 如涉及 plugin uplink，更新 `gateway-schema` schema 和 runtime projector
- [ ] 如涉及 assistant_square，更新 `StandardProtocolHandler`
- [ ] 更新本协议文档和对应单测/抓包样例
