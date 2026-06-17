# SS+GW 业务场景梳理

## Goal

沉淀 SS+GW 当前承载的业务场景全貌，先以业务视角记录聊天入口、助手类型、会话唯一性、回复形态、历史/会话查询和可恢复会话等流程，避免后续讨论被拆成多个零散任务。

## What I already know

- 当前聊天场景只有 external 和 miniapp。
- external 已确认包含 IM 单聊和 IM 群聊，且现在 IM 聊天不走 imInbound。
- 当前助手类型有本地 agent、云端 agent、虚拟 agent；虚拟 agent 暂时只在 miniapp 对话。
- 云端 agent 还要再区分标准协议助理和助手广场协议助理。
- 已记录 external 单聊、external 群聊、miniapp 对话、助手类型矩阵和 toolSessionId 生命周期相关业务文档。
- 本任务只负责业务梳理文档，不修改运行时代码。

## Requirements

- 维护 `.trellis/spec/business/index.md` 作为业务 spec 入口。
- 维护 external IM 单聊业务流程文档，覆盖入口、会话唯一性、回复、question/permission reply、rebuild 和 toolSessionId 自动恢复。
- 维护 external IM 群聊业务流程文档，覆盖入口、群聊上下文、回复抑制/发送、rebuild 和 toolSessionId 自动恢复。
- 维护 miniapp 对话业务流程文档，覆盖本地/云端/虚拟 agent、会话列表、历史查询和废弃接口边界。
- 维护助手类型矩阵，明确本地 agent、云端 agent、虚拟 agent，以及云端标准协议/助手广场协议子类型。
- 维护 toolSessionId 生命周期文档，记录业务上的创建、复用、恢复和结束语义。

## Acceptance Criteria

- [x] 只保留一个业务梳理任务承载上述文档工作。
- [x] 业务文档集中保留在 `.trellis/spec/business/`。
- [x] external 单聊、external 群聊和 miniapp 文档都能从索引发现。
- [x] 助手类型矩阵体现云端标准协议助理和助手广场协议助理。
- [x] 本次不修改运行时代码。

## Out of Scope

- 不修改代码行为。
- 不新增技术实现任务。
- 不继续梳理用户已暂停的下一个场景。

## Business Notes

- 后续继续梳理时，优先沿用 external 单聊/群聊的业务模板：入口、参与方、会话唯一性、助手类型、回复形态、历史/恢复、边界。
- 避免把业务文档拆成多个 Trellis task；新增内容直接归入本任务。
