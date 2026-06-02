# 修复 question 历史重复 part

## Goal

修复 skill-server 查询历史记录时，同一次 OpenCode/cloud question 交互在同一个 assistant message 下出现两条 `type=question` part 的回归。历史接口应恢复为一个 question part：原始问题卡在用户回复后更新为 completed/answered，并带上答案，而不是再追加一个只有 output 的 question part。

## What I already know

* 用户观察到历史记录里的 question 已经变成两条；以前是一个 part。
* 已直接调用本地接口：
  * `GET http://127.0.0.1:8082/api/skill/sessions/2998424057281515520/messages/history?size=50`
  * Cookie: `userId=1`
* 实际返回中 assistant message `seq=4` 有两个 question part：
  * `partId=prt_40efba601b5749f599519224da5ae678`：原始 question ask，带 `input/header/question/options/questionId`，已被 `recordQuestionReply` 更新为 `completed` + `output=重口味`。
  * `partId=prt_e864c7698001kZLoHoSE5HdZKN`：后续 `tool.update(toolName=question)`，只有 `status=completed/output`，也被历史 mapper 显示为 `type=question`。
* 关键日志顺序：
  * `tool.update` pending/running：`partId=prt_e864c7698001kZLoHoSE5HdZKN`, `toolCallId=call_GEkSa31PZAGniqZIrCrG1pDl`
  * `question`：`partId=prt_40efba601b5749f599519224da5ae678`, `questionId=que_e864c80b8001Tff24HkexbwLz1`
  * 用户回复后：`MessagePersistenceService.updateQuestionReplyPart` 成功更新 `prt_40ef...`
  * GW 又发 `tool.update completed` 到 `prt_e864...`
* `ProtocolMessageMapper.normalizePartType` 会把任意 `partType=tool && toolName=question` 映射为历史 `type=question`。
* `MessagePersistenceService.persistToolPartIfFinal` 会持久化 completed/error 的 `TOOL_UPDATE`，因此 question 工具的 completed `tool.update` 成了第二个历史 question part。
* 本地 OpenCodeEventTranslator 已有防重思路：running 的 question tool update 跳过，completed 时用缓存的原始 question partId 更新原卡，避免重复。

## Assumptions

* 当前 plugin main 会同时投递 OpenCode question 的工具生命周期 `tool.update` 和 dedicated `question` event。
* 对 miniapp 历史而言，dedicated `question` event 是 question 卡片的 source of truth。
* `tool.update(toolName=question)` 在 dedicated question 已存在时不应该新增一条可见 question 历史 part。

## Requirements

* 查询历史接口中，一个 cloud/OpenCode question ask + reply 交互最终只返回一个 `type=question` part。
* 原始 question part 继续保留 `input/header/question/options/questionId/toolCallId` 等可交互字段。
* 用户回复后原 question part 变为 `status=completed`、`answered=true`，并保留归一化答案 output。
* `tool.update(toolName=question)` 不能在已有 dedicated question part 时新增第二个 question part。
* 不修改 plugin 代码。
* 不影响普通 tool.update、permission、text history。

## Acceptance Criteria

* [ ] 用单元测试覆盖 cloud/OpenCode 形状：`tool.update(question)` + `question` + `question_reply` 后历史 mapper 只得到一个 question part。
* [ ] `MessagePersistenceServiceTest` 覆盖重复 part 防护。
* [ ] 现有 `CloudEventTranslatorTest` / `OpenCodeEventTranslatorTest` / `MessagePersistenceServiceTest` 通过。
* [ ] `skill-server` 相关测试通过，必要时跑全量 `mvn test`。
* [ ] `git diff --check` 通过。

## Definition of Done

* GitNexus impact analysis 已在修改目标 symbol 前执行。
* 代码、测试、spec 如需更新则同步完成。
* 查询历史接口的实际形状重新验证。
* 回滚路径清晰：回滚本任务提交恢复之前持久化行为。

## Out of Scope

* 不改 plugin/message-bridge。
* 不重构云端协议字段。
* 不处理 permission 之外的新交互类型。
* 不改变普通 tool 的历史展示。

## Technical Notes

* 重点文件：
  * `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/ProtocolMessageMapper.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java`
  * `skill-server/src/test/java/com/opencode/cui/skill/service/MessagePersistenceServiceTest.java`
* 初步修复方向：
  * 在持久化边界识别 `TOOL_UPDATE + toolName=question`。
  * 如果该消息已经有 dedicated question part，后续 question tool.update 不应新增一个 part。
  * completed tool.update 如果能可靠定位原 question，则更新原 question；否则至少不把缺少 question input 的工具生命周期 part 暴露成第二个 question。
* 需要小心：`ProtocolMessageMapper` 当前只看 `toolName=question` 就转成 question；如果只改 mapper，会隐藏不完整 part 但 DB 仍可能多一条，且分页/排序/未来消费者仍受影响。优先在持久化层防重复。
