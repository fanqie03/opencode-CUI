# 端到端流程

## 概述

本文件按场景描述协议从入口到下行的完整路径。若排查线上问题，先定位场景，再对照本文件确认每层应该出现的报文。

## 一、miniapp chat

```text
miniapp
  -> POST /api/skill/sessions/{sessionId}/messages
  -> SkillMessageController
  -> SkillMessageFlowService
  -> GatewayMessage invoke(chat)
  -> AI Gateway
  -> local agent 或 cloud
  -> GatewayMessage tool_event/tool_done/tool_error
  -> OpenCodeEventTranslator 或 CloudEventTranslator
  -> StreamMessage
  -> MiniappDeliveryStrategy
  -> Redis user stream
  -> /ws/skill/stream
  -> miniapp
```

关键报文：

```json
{
  "content": "hello",
  "businessExtParam": {"foo": "bar"}
}
```

下发 Gateway：

```json
{
  "type": "invoke",
  "action": "chat",
  "welinkSessionId": "12345",
  "payload": {
    "text": "hello",
    "toolSessionId": "987654321",
    "sendUserAccount": "cookie-userId",
    "assistantAccount": "assistant",
    "messageId": "msg-001"
  }
}
```

## 二、external direct chat

```text
external system
  -> POST /api/external/invoke action=chat sessionType=direct
  -> ExternalInboundController
  -> InboundProcessingService
  -> GatewayMessage invoke(chat)
  -> AI Gateway
  -> agent/cloud
  -> GatewayMessage upstream
  -> StreamMessage
  -> ExternalWsDeliveryStrategy
  -> /ws/external/stream source=businessDomain
```

关键约束：

- `businessDomain` 必须等于 externalWs `source`。
- `senderUserAccount` 是本次真实发送人。
- direct 不设置 `imGroupId`。

## 三、external group chat

```text
external IM group
  -> external trigger filtering
  -> POST /api/external/invoke action=chat sessionType=group
  -> ContextInjectionService 可注入 chatHistory
  -> GatewayMessage invoke(chat, imGroupId=sessionId)
  -> agent/cloud
  -> StreamMessage
  -> externalWs
```

关键约束：

- SS 不判断是否 @ 机器人；external 系统入站前已完成触发过滤。
- group session 的 `SkillSession.userId=null`。
- sender 必须来自 `senderUserAccount`。
- retry pending 必须保留 `senderUserAccount`、`imGroupId`、`businessExtParam`、`domainType=group`。
- `suppressReply` 只在 group 场景可能出现。

## 四、question reply

### miniapp

```text
miniapp question card
  -> POST /api/skill/sessions/{sessionId}/messages
     { content, toolCallId, questionId?, subagentSessionId? }
  -> action=question_reply
  -> targetToolSessionId = subagentSessionId 或 session.toolSessionId
  -> Gateway/cloud replyContext
```

### external

```text
external system
  -> POST /api/external/invoke action=question_reply
     payload.content + payload.toolCallId
  -> 要求已有 ready session
  -> 不创建 session，不自动 rebuild
```

云端：

```json
{
  "replyContext": {
    "type": "question_reply",
    "toolCallId": "call-q1",
    "answers": ["answer"]
  }
}
```

## 五、permission reply

### miniapp

```text
miniapp permission card
  -> POST /api/skill/sessions/{sessionId}/permissions/{permId}
     { response, subagentSessionId?, businessExtParam? }
  -> Gateway/cloud permission_reply
  -> 本地发布 permission.reply StreamMessage
```

### external

```text
external system
  -> POST /api/external/invoke action=permission_reply
     payload.permissionId + payload.response
  -> 要求已有 ready session
  -> 本地更新权限卡状态
```

云端：

```json
{
  "replyContext": {
    "type": "permission_reply",
    "permissionId": "perm-1",
    "response": "once"
  }
}
```

## 六、cloud default

```text
Skill Server
  -> DefaultCloudRequestStrategy
  -> GatewayMessage invoke payload.cloudProfile=default
  -> AI Gateway cloud callback
  -> DefaultSseEventDecoder
  -> GatewayMessage tool_event
  -> CloudEventTranslator
  -> StreamMessage
```

请求字段：

```json
{
  "type": "text",
  "content": "hello",
  "assistantAccount": "assistant",
  "sendUserAccount": "user",
  "imGroupId": "group-id",
  "clientLang": "zh",
  "clientType": "...",
  "topicId": "987654321",
  "messageId": "msg-001",
  "extParameters": {}
}
```

## 七、assistant_square

```text
Skill Server
  -> AssistantSquareCloudRequestStrategy
  -> GatewayMessage invoke payload.cloudProfile=assistant_square
  -> AI Gateway cloud callback
  -> AssistantSquareSseEventDecoder
  -> StandardProtocolHandler
  -> standard stream event
  -> CloudEventTranslator
  -> StreamMessage
```

请求字段：

```json
{
  "assistantAccount": "dig_30051824",
  "sendW3Account": "user",
  "msgBody": "hello",
  "clientLang": "zh",
  "imGroupId": "group-id",
  "topicId": 987654321,
  "extParameters": {}
}
```

关键约束：

- `topicId` 必须是 Long，所以云端/虚拟 agent 的 `toolSessionId` 必须是数字字符串。
- `businessExtParam` / `platformExtParam` 如果是字符串，会被解析成 JSON。

## 八、toolSessionId rebuild / retry

触发：

```text
chat 入站
  -> session 存在
  -> toolSessionId 缺失或不可用
```

流程：

```text
1. 保存当前用户消息上下文
2. 尝试 scope strategy 自愈生成 toolSessionId
3. 若不能直接生成，requestToolSession
4. 等 session_created
5. 绑定 SkillSession.toolSessionId
6. retry pending message
```

边界：

- external reply 类 action 不触发自动恢复。
- miniapp `/messages` 可触发恢复。
- miniapp permission reply 不触发恢复，缺 `toolSessionId` 时失败。
