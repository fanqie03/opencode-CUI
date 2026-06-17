# brainstorm: 按旧协议模板重写新协议 spec

## Goal

把上一版过于抽象的 `.trellis/spec/business/protocol-contracts.md` 重写成旧协议文档那种可对照开发的多文件协议手册。旧 `documents/protocol/v3` 只作为模板结构参考，不作为事实来源；事实仍以当前代码、测试和 business spec 为准。

## What I already know

* 用户明确反馈上一版“太抽象”，希望“按照之前的协议文档当成模板来写新的协议文档”。
* 旧模板结构是 README + 分层 01-07 + CHANGELOG，内容风格包括概述、通道图、REST/WS/API、字段表、消息类型详解、映射表、端到端流程、生命周期。
* 新文档仍必须放在 `.trellis/spec`，旧 `documents/protocol` 继续退役。
* 当前已存在 `.trellis/spec/business/protocol-contracts.md`，需要拆分/替换为更具体的多文件入口。

## Requirements

* 在 `.trellis/spec/business/protocol/` 下创建新的协议文档套件，沿用旧文档的阅读体验和章节粒度。
* README 必须列出每个文档、层级、内容概要、阅读建议。
* 01 必须明确 miniapp 与 externalWs 最终消费同一套 `StreamMessage`，并写清 REST/WS 差异。
* 02 必须写清 miniapp REST 与 external REST 入站请求协议、字段、校验、错误语义。
* 03 必须写清 Skill Server ↔ AI Gateway 的 `/ws/skill`、`GatewayMessage`、invoke/action、上行事件、路由控制面。
* 04 必须写清 agent/cloud 返回侧三套协议：OpenCode、本地 cloud default、assistant_square。
* 05 必须提供从 agent/cloud 原始事件到 `StreamMessage` 的映射表。
* 06 必须给出典型端到端流程：miniapp chat、external direct/group chat、question reply、permission reply、cloud default、assistant_square、rebuild/restore。
* 07 必须按消息类型写生命周期和维护规则。
* 保留 `.trellis/spec/business/protocol-contracts.md` 作为新套件入口/摘要，不再承载全部细节。
* 更新 `.trellis/spec/business/index.md` 指向新 README。

## Acceptance Criteria

* [x] 新文档采用旧协议文档的多文件模板，而不是单篇抽象总纲。
* [x] 新文档位于 `.trellis/spec/business/protocol/`。
* [x] README 能作为协议入口。
* [x] externalWs / miniapp 的共享 `StreamMessage` 与传输差异被单独成章说明。
* [x] agent 返回三套协议分别有独立章节和映射表。
* [x] 入站请求、返回协议、最终下行协议层次清楚。
* [x] 旧 `documents/protocol` 不恢复、不作为 source of truth。
* [x] 链接、markdown 空白、旧路径引用、GitNexus detect_changes 检查通过。

## Definition of Done

* Docs updated from current implementation evidence.
* No runtime behavior changes.
* Link targets checked.
* Markdown whitespace checked.
* GitNexus detect_changes run before handoff.

## Out of Scope

* 不恢复旧 `documents/protocol` 目录。
* 不改运行时代码协议字段。
* 不新增测试代码；本任务是 spec 文档重写。

## Technical Notes

* Old template reference: `HEAD:documents/protocol/v3/README.md` and v3 layered docs.
* New canonical directory: `.trellis/spec/business/protocol/`.
* Existing abstract spec to replace/redirect: `.trellis/spec/business/protocol-contracts.md`.

## Completion Evidence

* New entry: `.trellis/spec/business/protocol/README.md`
* Layered docs: `.trellis/spec/business/protocol/01-consumer-stream-protocol.md` through `07-message-type-lifecycle.md`
* Changelog: `.trellis/spec/business/protocol/CHANGELOG.md`
* Compatibility entry: `.trellis/spec/business/protocol-contracts.md`
* Business index points to `protocol/README.md`
