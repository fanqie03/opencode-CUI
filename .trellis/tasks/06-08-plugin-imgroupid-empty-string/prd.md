# plugin imGroupId 空值改为空字符串

## Goal

给 plugin 的下行报文中，`imGroupId` 字段在没有群聊 ID 时从 JSON `null` 统一改为 `""`，避免 plugin 或原始报文观察方收到空值语义不一致的字段。

## Requirements

* 群聊场景继续发送真实 `imGroupId`。
* 非群聊 / 缺失场景，下行 payload 的 `imGroupId` 必须发送为空字符串 `""`，不能发送 JSON `null`。
* 保持内部 pending/rebuild 语义不变：`PendingChatRequest.imGroupId` 仍可为 `null`，只在发给 gateway/plugin 的 payload 边界归一。
* 覆盖同步 chat、pending retry chat、业务会话首次立即 chat，以及 question/permission reply 的原始下发 payload 组包点。

## Acceptance Criteria

* [x] direct chat payload 包含 `"imGroupId": ""`。
* [x] pending retry direct chat payload 包含 `"imGroupId": ""`。
* [x] group chat payload 仍包含真实群聊 ID。
* [x] question_reply / permission_reply 原始下发 payload 的空 `imGroupId` 为 `""`。
* [x] 相关单元测试通过。

## Definition of Done

* Tests added/updated for changed payload shape.
* Targeted Maven tests pass.
* `gitnexus_detect_changes()` confirms affected scope matches this task.
* Branch `codex/plugin-imgroupid-empty-string` contains the implementation.

## Technical Approach

只在具体 producer 上把 `imGroupId` 出站值从 `null` 改成 `""`。不修改 `PayloadBuilder.buildPayloadWithObjects`，因为 GitNexus impact 显示该共享 helper 影响 12 个直接调用者、6 条执行流，风险为 CRITICAL。

## Decision (ADR-lite)

**Context**: `imGroupId` 是下行 payload 的协议字段，生产点分散在 sync chat、retry chat、question reply、permission reply 和 business immediate chat。

**Decision**: 在各 producer 的 payload 组装边界本地归一 `imGroupId`，保持内部模型和 platformExtParam 的既有 null 语义。

**Consequences**: 改动范围小，避免共享 helper 行为变化；代价是多个 producer 需要同时更新并由测试锁住字段一致性。

## Out of Scope

* 不修改 plugin normalizer 对 `imGroupId` 的 optional/blank 处理规则。
* 不修改 `platformExtParam.businessSessionId` 的 null 语义。
* 不修改 `PendingChatRequest` 的持久化/Redis 兼容格式。

## Technical Notes

* Branch: `codex/plugin-imgroupid-empty-string`
* GitNexus impact:
  * `dispatchChatToGateway`: MEDIUM, direct caller `processChat`
  * `processQuestionReply`: MEDIUM, external invoke path
  * `processPermissionReply`: MEDIUM, external invoke path
  * `retryPendingMessages`: LOW, direct caller `handleSessionCreated`
  * `sendBusinessChatImmediately`: LOW, direct caller `initializeToolSession`
  * `buildPayloadWithObjects`: CRITICAL, avoided
* Main files expected:
  * `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java`
  * `skill-server/src/test/java/com/opencode/cui/skill/service/*Test.java`
