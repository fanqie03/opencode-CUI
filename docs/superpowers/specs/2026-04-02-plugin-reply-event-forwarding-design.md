# 插件 reply 事件转发缺失修复方案

## 问题描述

message-bridge 插件的事件白名单缺少 `permission.replied`、`question.replied`、`question.rejected` 三个事件类型，导致 OpenCode 处理完权限审批/问题回答后的确认事件被丢弃，无法传回 gateway → skill-server 进行持久化。

### 影响

- **权限审批**：单个 tool 需要多个 permission 时（如 write 需要 external_directory + edit），`synthesize` 兜底机制的 `LIMIT 1` 只能覆盖最后一个，前面的 permission 在数据库中永远是 `tool_status=NULL, tool_output=NULL`，刷新后显示为待确认
- **问题回答**：`question.replied` / `question.rejected` 无法持久化，刷新后状态丢失

### 根因链路

1. OpenCode 内部发布 `permission.replied` / `question.replied` 到事件总线 ✓
2. message-bridge 插件收到事件 → `isSupportedUpstreamEventType` 检查失败 → WARN `Unsupported upstream event type` → 事件丢弃 ✗
3. gateway / skill-server 从未收到这些事件 → 数据库未更新

### 日志证据

```
WARN service=message-bridge eventType=permission.replied errorCode=unsupported_event
     message=Unsupported upstream event type: permission.replied event.extraction_failed

WARN service=message-bridge eventType=question.replied errorCode=unsupported_event
     message=Unsupported upstream event type: question.replied event.extraction_failed
```

## SDK 类型参考

三个事件在 `@opencode-ai/sdk/v2` 中均有类型定义：

```ts
EventPermissionReplied = {
  type: "permission.replied";
  properties: { sessionID: string; requestID: string; reply: "once" | "always" | "reject" }
}

EventQuestionReplied = {
  type: "question.replied";
  properties: { sessionID: string; requestID: string; answers: QuestionAnswer[] }
}

EventQuestionRejected = {
  type: "question.rejected";
  properties: { sessionID: string; requestID: string }
}
```

## 修复方案

### 核心思路

纯透传：插件只负责把事件加入白名单并转发原始事件给 gateway，翻译/持久化逻辑全在服务端。和现有 `permission.asked` / `question.asked` 完全一致的模式。

### 改动 1：插件白名单扩展

**文件**：`plugins/agent-plugin/plugins/message-bridge/src/contracts/upstream-events.ts`

`SUPPORTED_UPSTREAM_EVENT_TYPES` 数组追加三个事件：

```ts
'permission.replied',
'question.replied',
'question.rejected',
```

同时追加类型导入（从 `@opencode-ai/sdk/v2`）和 `SupportedUpstreamEvent` union 扩展：

```ts
import type {
  EventMessagePartDelta,
  EventPermissionAsked,
  EventQuestionAsked,
  EventPermissionReplied,   // 新增
  EventQuestionReplied,     // 新增
  EventQuestionRejected,    // 新增
} from '@opencode-ai/sdk/v2' with { 'resolution-mode': 'import' };
```

### 改动 2：插件 Extractor 注册

**文件**：`plugins/agent-plugin/plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts`

1. `UpstreamExtraByType` 追加三个条目（均为 `undefined`，无需 extra extraction）
2. `UPSTREAM_EVENT_EXTRACTORS` 追加三个 extractor

三个事件结构相同（都有 `properties.sessionID`），提取逻辑和 `permission.asked` / `question.asked` 一致：从 `properties.sessionID` 提取 `toolSessionId`，无 extra。

### 改动 3：服务端 translator 对 question.replied / question.rejected 返回 null

**文件**：`skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`

`translate` 方法 switch 追加，但返回 `null`（不生成 StreamMessage）：

```java
case "question.replied", "question.rejected" -> null;
```

**设计决策**：Question 的完成状态已由 `message.part.updated`（`tool=question, status=completed`）完整覆盖。该事件天然携带 `partId`/`messageId`/`partSeq`，前端能精确匹配已有卡片。若用 `question.replied` 生成 StreamMessage 会因缺少 part 上下文而产生重复卡片。插件侧继续转发这两个事件（保持协议完整性），但 translator 不消费。

### 改动 4：服务端 translator 修复 permission.replied 字段提取

**文件**：`skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`

当前 `translatePermission` 存在两个字段名不匹配的问题：

1. **response 字段**：`normalizePermissionResponse` 只从 `response` / `decision` / `answer` 提取，但 `EventPermissionReplied` 的字段名是 `reply`

修复：在提取链中追加 `props.path("reply")`

```java
String raw = ProtocolUtils.firstNonBlank(
    props.path("response").asText(null),
    props.path("decision").asText(null),
    props.path("reply").asText(null));  // 新增
```

2. **permissionId 字段**：当前从 `props.path("id")` 提取，但 `EventPermissionReplied` 的字段名是 `requestID`

修复：追加 fallback

```java
.permissionId(ProtocolUtils.firstNonBlank(
    props.path("id").asText(null),
    props.path("requestID").asText(null)))  // 新增
```

### 不需要改动的部分

- **`BridgeRuntime.ts`**：转发逻辑对通过 extraction 的事件统一处理，无需改动
- **`MessagePersistenceService.java`**：`persistIfFinal` 已覆盖 `PERMISSION_REPLY` 和 `QUESTION` 类型
- **`GatewayMessageRouter.java`**：`handleAssistantToolEvent` → `routeAssistantMessage` → `persistIfFinal` 链路已完整

## 修复后完整链路

### Permission

1. 用户点击审批 → Controller 发 invoke → agent 处理
2. OpenCode 发布 `permission.replied` 事件（含 `reply: "once"/"always"/"reject"`）
3. 插件转发 `tool_event` → gateway → skill-server
4. translator `translatePermission` 翻译为 `PERMISSION_REPLY`（提取 `requestID` → `permissionId`，`reply` → `response`）
5. `handleAssistantToolEvent` → `routeAssistantMessage` → `persistIfFinal` → `persistPermissionPart` → upsert `tool_status=completed, tool_output=response`
6. 刷新后 `normalizePart` 读到 `response` → `permResolved=true`

### Question

1. 用户回答问题 → Controller 发 invoke → agent 处理
2. OpenCode 发布 `question.replied`（含 answers）或 `question.rejected`
3. 插件转发 → gateway → skill-server
4. translator 翻译为 `QUESTION`（`status=completed/error`）
5. `persistIfFinal` → 持久化
6. 刷新后正确恢复状态

## 影响范围

| 文件 | 改动 |
|------|------|
| `upstream-events.ts`（插件） | 白名单 +3 项、类型导入 +3、union +3 |
| `UpstreamEventExtractor.ts`（插件） | ExtraByType +3、Extractors +3、提取函数 +3 |
| `OpenCodeEventTranslator.java`（服务端） | translate switch +2、新增翻译方法 +2、字段提取修复 +2 |

不影响现有事件的处理路径。新增事件走和 `permission.asked` / `question.asked` 完全一致的转发-翻译-持久化链路。

## 验证方式

1. 主会话触发需要多个 permission 的 tool（如 write 到外部目录）→ 审批后刷新 → 所有 permission 显示"已处理"
2. 主会话触发 question → 回答后刷新 → question 显示已回答
3. 后端日志确认 `Persisted permission part` 和 `Persisted question part`（从 gateway relay 路径）
4. 插件日志无 `unsupported_event` WARN for `permission.replied` / `question.replied` / `question.rejected`
5. subagent 场景回归：权限审批、问题回答行为不受影响
