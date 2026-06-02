# 云端和 OpenCode 协议事件 parity 审计与测试

## Goal

确保 skill-server 对云端协议事件和 OpenCode 协议事件在进入 SS 后产生一致的用户可见效果：实时 `StreamMessage`、活跃消息上下文、持久化 part、历史记录 `ProtocolMessagePart` 的语义不因协议来源不同而漂移。

## What I already know

* 用户要求不是口头确认，而是“全部审计和补测试”，最终要能证明云端和 OpenCode 协议所有事件处理后的效果一致。
* 上一轮已修复 `question` 的历史重复 part：cloud/OpenCode projection 同时产生 dedicated `question` 和 `tool.update(toolName=question)` 时，SS 持久化层合并到原 question part。
* 事件链路关键边界：
  * `PersonalScopeStrategy` 按 `protocol=cloud/opencode/缺省` 分派到 `CloudEventTranslator` / `OpenCodeEventTranslator`。
  * `CloudEventTranslator` 负责 cloud event -> `StreamMessage`，并补 `messageId/sourceMessageId/role/partId/partSeq`。
  * `MessagePersistenceService` 决定哪些 `StreamMessage` 落 MySQL history，哪些只参与活跃上下文。
  * `ProtocolMessageMapper` 负责 DB/live streaming part -> history/snapshot 协议视图。
* 当前可对齐的 OpenCode 语义类型包括：text、thinking(reasoning)、tool.update、step.start/step.done、question、permission、session.status/title/error、file。
* cloud-only 扩展类型包括：planning.delta/done、searching、search_result、reference、ask_more；这些没有 OpenCode 等价事件，不能硬说“与 OpenCode 一样”，但必须明确测试其 live/context/history 行为不会污染通用 history part。
* 初步审计发现一个真实 drift 候选：`CloudEventTranslator.handleToolUpdate` 目前用 `event.path("input").asText(null)` 读取 `tool.update.input`，如果云端传 JSON object，会丢成空文本；OpenCode 路径会保留 tool input 对象。

## Requirements

* 建立云端事件类型与 OpenCode 事件类型的 parity 矩阵，并将 cloud-only 类型明确标注为无 OpenCode 对应。
* 对有 OpenCode 对应语义的事件，补测试证明转换后的 `StreamMessage` 关键字段一致：`type/messageId/sourceMessageId/role/partId/partSeq/content/status/tool/permission/question/file/usage`。
* 对 history 可见类型，补测试证明 cloud 和 OpenCode 经 `ProtocolMessageMapper.toProtocolStreamingPart` 或持久化后得到一致的 `ProtocolMessagePart` 形态。
* 修复审计发现的协议 drift，但不修改 plugin 代码。
* cloud-only 扩展类型要有测试证明：
  * 能获得 message context，保持 message/part 标识稳定。
  * 不被 history mapper 误归类成 `text/tool/question/permission/file` 等通用历史 part。
  * 不影响 OpenCode 类型处理。
* 更新 `.trellis/spec/skill-server/backend/type-safety.md`，记录协议 parity 的长期约束和测试要求。

## Acceptance Criteria

* [ ] `CloudEventTranslatorTest` 或新的 parity 测试覆盖 OpenCode 等价类型矩阵。
* [ ] 测试覆盖 cloud `tool.update.input` 为 JSON object 时不会被压成空字符串，history mapper 可恢复 object input。
* [ ] 测试覆盖 text/thinking/tool/step/session/file/question/permission 的 cloud vs OpenCode 输出等价。
* [ ] 测试覆盖 planning/search/reference/ask_more 为 cloud-only，并且 history mapper 不暴露成通用 history part。
* [ ] `MessagePersistenceServiceTest` 保持 question/permission reply、防重复、普通 tool persistence 通过。
* [ ] 相关 `skill-server` 测试通过，必要时跑全量 `mvn test`。
* [ ] `git diff --check` 通过。
* [ ] GitNexus impact / detect_changes 确认影响范围。

## Definition of Done

* 完成事件矩阵审计，并以可执行测试固定关键结论。
* 生产代码只修协议差异，不做无关重构。
* 不修改 plugin 代码。
* Trellis 任务归档，提交记录清晰说明 parity audit 和修复点。

## Out of Scope

* 不重构 OpenCode 协议本身。
* 不新增云端协议事件类型。
* 不改 miniapp UI 渲染，除非测试证明后端输出已正确但前端协议镜像丢字段。
* 不调整 ai-gateway/plugin 代码。

## Technical Notes

* 必读规范：
  * `.trellis/spec/skill-server/backend/directory-structure.md`
  * `.trellis/spec/skill-server/backend/conventions.md`
  * `.trellis/spec/skill-server/backend/type-safety.md`
  * `.trellis/spec/skill-server/backend/database-guidelines.md`
  * `.trellis/spec/skill-server/backend/error-handling.md`
  * `.trellis/spec/guides/cross-layer-thinking-guide.md`
  * `.trellis/spec/guides/code-reuse-thinking-guide.md`
* 重点文件：
  * `skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/ProtocolMessageMapper.java`
  * `skill-server/src/test/java/com/opencode/cui/skill/service/CloudEventTranslatorTest.java`
  * `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java`
  * `skill-server/src/test/java/com/opencode/cui/skill/service/MessagePersistenceServiceTest.java`
* Event matrix draft:

| Effect family | Cloud event | OpenCode event source | Expected SS effect |
| --- | --- | --- | --- |
| Text delta/done | `text.delta`, `text.done` | `message.part.delta/updated` part `type=text` | `StreamMessage` text delta/done; history type `text` for done |
| Thinking | `thinking.delta`, `thinking.done` | `message.part.delta/updated` part `type=reasoning` | `StreamMessage` thinking; history type `thinking` for done |
| Tool | `tool.update` | `message.part.updated` part `type=tool` | preserve tool name/call/input/output/status/error/title; completed/error persists as history type `tool` |
| Step | `step.start`, `step.done` | `step-start`, `step-finish` parts or `message.updated.finish` | step.start is live/context only; step.done updates usage stats |
| Question | `question` + possible `tool.update(toolName=question)` | `question.asked` + question tool completion | one visible question part; reply updates original part |
| Permission | `permission.ask`, `permission.reply` | `permission.*` events | ask pending, reply completed, reply updates original pending part |
| Session | `session.status/title/error` | `session.status/idle/updated/error` | same session status/title/error semantics |
| File | `file` | `message.part.updated` part `type=file` | file part fields preserved |
| Cloud-only | `planning.*`, `searching`, `search_result`, `reference`, `ask_more` | none | live/context only unless future history support is explicitly added |
