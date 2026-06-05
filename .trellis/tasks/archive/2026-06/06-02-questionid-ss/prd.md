# 插件传入 questionId 溯源与 SS 处理审计

## Goal

从 plugin 开始追踪 `questionId` 的产生、投影、回复消费、经过 ai-gateway/SS 后的字段保留与历史持久化行为，判断当前代码是否还有协议或历史报文不一致问题。

## What I already know

* 用户关注的是 plugin 传入的 `questionId`，要求从 plugin 开始分析，不先改代码。
* plugin SDK 搜索结果显示 `questionId` 被定义为 question reply target，`toolCallId` 只是兼容字段。
* SS `SkillMessageController.SendMessageRequest` 已有 `questionId` 字段，并注释为新版 plugin 直接回复使用。

## Requirements

* 追踪 plugin `question.ask` fact -> projected skill event -> question reply command 的字段语义。
* 追踪 SS 接收 question ask、历史持久化、历史查询、miniapp 回复 question 的字段语义。
* 判断当前代码是否存在 `questionId` 和 `toolCallId/partId` 混用导致的实际问题。
* 不修改 plugin 代码；本轮先给出根因级结论和必要修复建议。

## Acceptance Criteria

* [ ] 给出 `questionId` 的真实来源和每一跳字段名。
* [ ] 说明 plugin 当前是否需要 `questionId` 才能回复。
* [ ] 说明 SS 当前历史记录是否能把 `questionId` 暴露给 miniapp。
* [ ] 说明当前代码是否仍有缺口，以及缺口表现。

## Out of Scope

* 不修改 plugin 源码。
* 不创建新的协议字段，除非审计证明现有字段无法表达。
* 不改 UI 展示，除非后续确认是前端取字段错误。

## Technical Notes

* 初始搜索关键词：`questionId`, `question.asked`, `question_reply`, `toolName=question`。
* plugin contract：`questionId` 是 question reply target；`partId` 只表示展示 part；`toolCallId` 是兼容字段，projector 缺省回填为 `questionId`。
* plugin reply：下行 `question_reply.payload.questionId` 先用于 pending interaction consume，再传给 provider `replyQuestion`，最终作为 `/question/{requestID}/reply` 的 requestID。
* gateway schema：`question_reply` 会接受旧 alias `toolCallId`，但 normalize 后字段仍是 `questionId`；同时存在时优先 `questionId`。
* SS personal scope：`SkillMessageController` 接收 `questionId`，`SkillMessageFlowService` 非空时写入 invoke payload；`GatewayRelayService` 原样 attach payload，所以个人插件路径能收到 `payload.questionId`。
* SS history：`CloudEventTranslator` 保留 `properties.questionId`；`MessagePersistenceService.buildQuestionInput` 写入 `toolInput.questionId`；`ProtocolMessageMapper` 从 input/question node 解析 `ProtocolMessagePart.questionId`。
* 当前缺口：business/default-assistant cloud request 构造仍只从 payload 读取 `toolCallId` 到 `replyToolCallId`，`replyContext` 只写 `toolCallId`，没有一等 `questionId`。如果该路径接新版 plugin/cloud endpoint 或 `toolCallId != questionId`，会把展示/兼容 ID 当 reply target。
* 当前测试缺口：已有测试覆盖 translator/history 保留 questionId、sendMessage payload 带 questionId，但 parity 测试和端到端测试没有强制 `partId != questionId != toolCallId` 的真实 plugin 语义。
* 本地日志证据（2026-06-02 15:02）：
  * ai-gateway 收到 `tool.update(toolName=question)`：`toolCallId=call_IfP3D0T47DE9KzLyOjZwRjWM`。
  * ai-gateway 随后收到 `question`：`questionId=que_e8724139f001KKJUv4qpdaJrNN` 且 `toolCallId=que_e8724139f001KKJUv4qpdaJrNN`。
  * skill-server outbound 给 miniapp 的 `question` 仍是同一组值，说明 SS 只是复制 plugin/cloud event，没有把二者改成相同。
  * plugin `QuestionAskedTranslator` 读取了 `properties.tool.messageID`，但没有把 `properties.tool.callID` 写入 `QuestionAskFact.toolCallId`；`DefaultFactToSkillEventProjector` 因此执行 `toolCallId: fact.toolCallId ?? fact.questionId` fallback。
