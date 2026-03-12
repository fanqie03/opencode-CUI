# opencode-CUI

## What This Is

`opencode-CUI` 是一个 brownfield 的多组件 AI 会话系统，包含 `ai-gateway`、`skill-server`、`skill-miniapp` 和相关 agent 侧能力。它负责把不同上游服务发起的会话请求路由到 OpenCode，并把流式返回稳定、准确地回传给对应来源服务。

## Core Value

无论 `gateway` 对接多少上游服务，每一条请求和每一段流式返回都必须只属于它真实的来源服务与会话，不允许串流、错投或 session 标识冲突。

## Current State

已完成并归档 v1.2 `Gateway Multi-Service Isolation`。

当前系统已具备：
- 多来源服务的 `source` 识别、握手绑定与回流隔离
- `skill-server` 与 `ai-gateway` 统一 Snowflake ID 基础设施
- `welinkSessionId` 在对外协议上的数字型基线
- 可支撑里程碑审计的 verification / summary / traceability 证据链

## Requirements

### Validated

- [x] 多来源服务接入下，`gateway` 能识别并透传来源服务身份
- [x] OpenCode 返回只会回到发起请求的来源服务
- [x] `gateway` 可为不同来源服务生成全局唯一的 session / entity ID
- [x] 并发运行时不存在跨服务消息串流或 session id 冲突
- [x] 路由链路具备结构化观测字段，可排查来源错投与 owner 选择
- [x] `welinkSessionId` 在 REST / WebSocket / relay / frontend 上统一为数字型

### Active

- [ ] 在真实测试数据库上执行清库重建与跨服务并发 UAT
- [ ] 清理协议文档历史编码噪声并压缩无意义 diff
- [ ] 统一旧 phase summary 的 `requirements-completed` frontmatter

### Out of Scope

- 重写 `skill-server` 现有实时广播模型
- 引入独立的全局发号服务
- 对外开放通用第三方接入平台

## Context

- v1.1 解决了连接归属与实时回流错投问题
- v1.2 把问题从“单链路修复”推进到了“多服务隔离 + 全局 ID 治理 + 协议基线收口”
- 当前剩余工作以验证深度、文档治理和 planning 工件一致性为主，不再是核心业务能力缺口

## Constraints

- **Compatibility**: 不能破坏现有 `skill-server` 链路
- **Protocol**: 优先在现有 `GatewayMessage` / gateway routing 模型上演进
- **Reliability**: 多服务并发时必须避免消息错投
- **Traceability**: session id 与路由决策需要可观测、可排障

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 将“来源服务”提升为显式路由维度 | 支撑多服务隔离 | Completed |
| 采用统一 Snowflake 基础设施而非 DB 自增 | 从标识层规避跨服务冲突 | Completed |
| 对外协议统一数字型 `welinkSessionId` | 消除后端、网关、前端的类型漂移 | Completed |
| 用单独 gap closure phase 回填审计工件 | 保持审计标准稳定，避免把工件缺失伪装成实现完成 | Completed |

## Next Milestone Goals

- 基于真实环境联调验证 v1.2 的隔离与 Snowflake 治理基线
- 清理协议文档和 planning 工件的历史噪声
- 根据新的产品目标重新定义下一个 milestone 的 requirements

---
*Last updated: 2026-03-12 after v1.2 milestone completion*
