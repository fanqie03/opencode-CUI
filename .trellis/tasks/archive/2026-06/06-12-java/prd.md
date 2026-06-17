# 整合 Java 软件设计重构规范与业务一致性八荣八耻

## Goal

把本次讨论形成的 Java 软件设计、重构纪律、业务一致性八荣八耻沉淀到 Trellis code-spec，让后续 AI 修改 `ai-gateway` / `skill-server` 后端代码时能自动读到这些约束。

## Requirements

- 新增 Java 后端设计与重构专题 spec。
- 将业务一致性八荣八耻写入 spec，并置于高优先级位置。
- 将分层放置、用例编排、模型/值对象、抽象策略、重构阈值、测试要求写成可执行规范。
- 更新 `ai-gateway/backend/index.md` 与 `skill-server/backend/index.md` 的开发前必读清单和文件列表。

## Acceptance Criteria

- [x] 两个 Java 后端 spec layer 都能从 index 找到新规范。
- [x] 新规范包含业务一致性八荣八耻和修改前自检。
- [x] 新规范包含 Wrong vs Correct / Good Base Bad / Tests Required 等可执行内容。
- [x] 不修改业务代码。

## Definition of Done

- Spec 文件已写入。
- 当前 task 的 implement/check context 指向新增规范和索引。
- Git diff 只包含 Trellis task/spec 文档变更。

## Out of Scope

- 不重构现有 Java 代码。
- 不修改 Trellis 工作流机制。
- 不调整 package 配置。

## Technical Notes

- 当前 Trellis spec layer：`ai-gateway/backend`、`skill-server/backend`、`skill-miniapp/frontend`。
- 本任务只影响 Java 后端，frontend spec 不更新。
