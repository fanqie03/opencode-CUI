# 统一 SS 云端与本地协议处理

## Goal

调查并修复 skill-server 对云端协议与本地 OpenCode 协议在 `question` 和 `permission` 事件处理后的历史记录报文不一致问题，确保同一类交互经过 SS 翻译、持久化、历史读取后返回稳定一致的 `ProtocolMessagePart` 形态。

## What I already know

* 用户观察到 SS 对云端协议和本地协议的事件处理后，通过历史记录拿到的报文不一致，重点怀疑 `question` 和 `permission`。
* 用户明确要求本任务不修改 plugin 代码。
* 当前工作分支：`codex/ss-protocol-question-permission`。
* 相关历史问题显示 `tool_event` 与 `tool_done` envelope 曾因 `welinkSessionId` / `toolSessionId` 字段差异导致 GW/SS 路由不对称。
* 初步搜索显示相关代码分布在：
  * `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/CloudRequestBuilder.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/scope/*ScopeStrategy.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/controller/*Controller.java`

## Assumptions (temporary)

* MVP 重点是 skill-server 内部的事件翻译、持久化模型和历史记录映射归一，不主动扩展 ai-gateway、miniapp 或 plugin 协议。
* 云端事件翻译和本地事件翻译可能对 `question` / `permission` 的 `partId`、`toolCallId`、`questionId`、`permissionId`、`status`、`input/metadata/output` 做了不同映射，导致历史 API 输出差异。

## Open Questions

* 暂无阻塞问题；先从 SS 内部历史记录链路定位和修复。

## Requirements (evolving)

* 对比 `question` 和 `permission` 在云端协议与本地协议的事件翻译、持久化、历史读取路径。
* 找到实际不一致点后，用集中化协议映射或清晰的本地/云端分支模型修复，避免零散 `ak == null` 式补丁。
* 覆盖本地协议事件与云端协议事件落库后历史 API 报文字段一致性的测试。

## Acceptance Criteria (evolving)

* [x] 明确列出云端与本地路径在 `question` / `permission` 上的差异与修复点。
* [x] 本地 OpenCode 事件与云端事件经过 SS 历史记录链路后生成的 `ProtocolMessagePart` 字段语义一致，差异有明确注释或测试说明。
* [x] `question` 历史 part 的 `partId/toolCallId/status/header/question/options/input/output` 等关键字段按同一契约返回。
* [x] `permission.ask` / `permission.reply` 历史 part 的 `permissionId/permType/metadata/response/status/content` 等关键字段按同一契约返回。
* [x] 相关单元测试或集成测试覆盖本次修复。

## Definition of Done

* Tests added/updated where behavior changes.
* Lint/typecheck/test command for touched package passes or failure is documented.
* `gitnexus_impact` is run before modifying target symbols.
* `gitnexus_detect_changes()` confirms changed scope before finishing.
* Rollback path is clear: revert this branch/PR to restore previous protocol behavior.

## Out of Scope

* 不重构整个 Gateway/SS 路由模型，除非 `question` / `permission` 修复必须触碰。
* 不改变外部云端协议字段命名，除非现有实现已经偏离既有测试或契约。
* 不修改 plugin 代码或 message-bridge normalizer/contract。
* 不处理下游 invoke payload 字段对齐，除非它直接影响 SS 历史记录链路。

## Technical Approach

Root cause:

* 本地 OpenCode `question.asked` 会生成 `StreamMessage.Types.QUESTION`，并在 `tool.toolName` 写入 `question`，`tool.input` 保存 question payload；落库为 `SkillMessagePart(partType=tool, toolName=question)` 后，`ProtocolMessageMapper.normalizePartType(...)` 能把历史 part 还原成 `type=question`。
* 云端 `question` 也生成 `StreamMessage.Types.QUESTION`，但只带 `toolCallId` 和 `questionInfo`，没有 `toolName=question` / `tool.input`；落库后历史 mapper 只能看到 `partType=tool, toolName=null`，于是历史记录变成普通 `tool`，且丢失 `header/question/options`。
* 云端 `permission.ask` / `permission.reply` 可能不带 `status`，落库历史 part 的 `status` 为空；本地 OpenCode permission 事件通常通过 `status.type` 或事件类型保留 pending/resolved 语义。
* 更关键的 reply-side 根因：`SkillMessageFlowService.sendMessage(...toolCallId...)` 发出 `question_reply` 后，只保存用户回答消息并转发 Gateway，没有把原 `question` part 标记为 completed / 写入 answer；`replyPermission(...)` 和 `InboundProcessingService.processPermissionReply(...)` 发出 `permission_reply` 后只广播实时协议消息，广播缓冲不会写 MySQL 历史。
* 云端和本地协议还可能出现 `partId` 与业务 ID (`toolCallId` / `permissionId`) 不一致：只按 `partId` 更新会漏掉云端 pending part，导致历史里保留 running/pending 的原始卡片，或后续事件形态与本地不同。

Decision:

* 在 `MessagePersistenceService` 的落库边界做归一：这正是实时 `StreamMessage` 进入 MySQL 历史模型的单一边界，能同时覆盖本地和云端路径，不需要改变 translator、miniapp、GW 或 plugin 协议。
* 对 `StreamMessage.Types.QUESTION`，如果 `toolName` 缺失则落库为 `question`，如果 `tool.input` 缺失则从 `questionInfo` 构造 canonical question input，供 `ProtocolMessageMapper` 历史还原使用。
* 对 `permission.ask` / `permission.reply`，如果 status 缺失则分别落库为 `pending` / `completed`，保留已有显式 status。
* 新增 `MessagePersistenceService.recordQuestionReply(...)` / `recordPermissionReply(...)`，在 reply 发送成功后回填原始历史 part；question 优先按 `questionId/partId` 精确命中，再按 `toolCallId` 找 pending question；permission 优先按 `permissionId` 作为 partId 命中，再按 `toolCallId` 找 pending permission。
* `SkillMessageFlowService` 覆盖前端/会话 API 的 question_reply 与 permission_reply；`InboundProcessingService` 覆盖外部/IM 入站 question_reply 与 permission_reply。持久化失败只记录 warn，不阻断 reply 主流程。

Validation:

* `mvn -Dtest=MessagePersistenceServiceTest clean test`
* `mvn "-Dtest=MessagePersistenceServiceTest,CloudEventTranslatorTest,OpenCodeEventTranslatorTest" test`
* `mvn "-Dtest=MessagePersistenceServiceTest,SkillMessageControllerTest,InboundProcessingServiceTest" test`
* `mvn "-Dtest=MessagePersistenceServiceTest,CloudEventTranslatorTest,OpenCodeEventTranslatorTest,SkillMessageControllerTest,InboundProcessingServiceTest" test`
* `mvn test` in `skill-server` (1053 tests, 0 failures, 0 errors, 0 skipped)
* `git diff --check`
* `gitnexus_detect_changes(scope=all)` reports critical risk because the touched symbols are core message persistence/send/reply flows; affected processes match the intended `sendMessage`, `replyPermission`, `routeToGateway`, inbound invoke, and history persistence scope.

## Technical Notes

* Memory-derived prior context: `tool_done.welinkSessionId` comes from SS downstream invoke envelope, while `tool_event` normally carries only `toolSessionId + event`; this can create route asymmetry.
* Search hits show existing tests already cover parts of `question_reply` / `permission_reply`: `SkillMessageControllerTest`, `ExternalInboundControllerTest`, `DefaultAssistantScopeStrategyTest`, `BusinessScopeStrategyTest`, `CloudRequestBuilderTest`, `CloudEventTranslatorTest`, `OpenCodeEventTranslatorTest`, and `PersonalScopeCloudProtocolIntegrationTest`.
* `OpenCodeEventTranslator.translateQuestionAsked(...)` emits `StreamMessage.questionId` from `question.asked.properties.id`; this field may be lost when persisted as `SkillMessagePart` and reconstructed through `ProtocolMessageMapper`.
* `CloudEventTranslator.handleQuestion(...)` currently emits `question` with `toolCallId`, `messageId`, `partId`, `status`, and question text/options, but does not emit `questionId`. That may be correct for pure cloud replies, but personal-scope cloud events need an explicit decision if they reuse OpenCode fast-path semantics.
* `OpenCodeEventTranslator.translatePermission(...)` accepts broader local shapes (`id` / `requestID`, `response` / `decision` / `answer` / `reply`, resolved flags/statuses). `CloudEventTranslator` expects canonical cloud fields (`permissionId`, `permType`, `response`).
* Confirmed fix layer: `MessagePersistenceService` -> `SkillMessagePart` -> `ProtocolMessageMapper`, plus reply entry points in `SkillMessageFlowService` / `InboundProcessingService`; downstream invoke handling and plugin code remain unchanged.
* `MessagePersistenceService` canonicalizes `QUESTION` and permission status before writing `SkillMessagePart`, and reply entry points update the original pending card instead of relying on realtime broadcast buffers as durable history.
