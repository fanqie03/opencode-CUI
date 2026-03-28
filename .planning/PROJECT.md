# opencode-CUI

## What This Is

`opencode-CUI` 是一个围绕 Agent 协作场景构建的 brownfield 分布式系统，包含 `ai-gateway`、`skill-server`、`skill-miniapp` 和本地插件桥接能力。当前项目重点不是重写整套系统，而是用 GSD 把 Gateway/Source 路由重构这条主线沉淀成可持续推进的规划、文档和后续 phase。

## Core Value

在双集群、统一 ALB 入口、旧服务并存的生产约束下，Gateway 与 Source 之间的路由模型必须简单、可解释、可扩展，并且能稳定把消息送到正确的 Agent 与 Source 实例。

## Requirements

### Validated

- [x] Gateway 已承载 Agent 与 Source 的 WebSocket 接入、鉴权和基础消息中继能力 - existing
- [x] Skill Server 已承载会话、消息持久化、前端流式推送和对 Gateway 的下行调用能力 - existing
- [x] 仓库内已经存在路由重构资料和部分实现，可作为这次 GSD 初始化的直接背景 - existing

### Active

- [ ] 现网拓扑、现有代码和真实消息路径需要被统一梳理成一套可执行的路由基线
- [ ] 新版 Source 仅连接统一 ALB WS 入口、GW 内部负责路由与 relay 的目标模型需要定稿
- [ ] 新版 `Source Protocol v3`、legacy 兼容边界、验收场景与后续实现拆分需要明确

### Out of Scope

- 立刻改造生产代码或切换生产路由 - 本次初始化和首个 phase 只做方案定稿，不混入实现
- 改为 MQ-only 或纯 HTTP 回调模型 - 当前默认继续以 WebSocket 作为核心通道
- 强制旧版 SS 和三方 Source 同步升级协议 - 第一阶段只要求兼容旧行为

## Context

- 仓库是典型 brownfield，多模块结构包括 `ai-gateway/`、`skill-server/`、`skill-miniapp/`、`plugins/message-bridge/`
- 现网背景是 SS 和 GW 双集群部署；每个集群各有 8 个实例，集群内部可以用服务 IP 和端口互连，跨集群不能依赖这种实例级长连
- 集群外部统一通过 `server -> ingress -> ALB -> domain` 暴露访问入口，新版路由方案必须服从这个现实拓扑
- `SS` 与 `GW` 不共享一套 Redis，但 `GW` 集群内部可以共享 Redis 做内部注册、查询与 relay 协调
- 已有设计与调研资料可直接引用：
  - `documents/routing-redesign/routing-redesign-proposal.md`
  - `documents/routing-redesign/industry-routing-research.md`
- 当前代码已同时存在 mesh/legacy 思路与若干历史兼容逻辑，说明路由抽象边界尚不清晰，需要通过 phase 重新定稿

## Constraints

- **Topology**: Source 与 Gateway 的目标路由必须适配双集群、统一 ALB 入口、跨集群不可实例级直连的生产环境 - 这是部署事实
- **State Sharing**: `SS` 与 `GW` 不共享 Redis，但 `GW` 集群内部允许共享 Redis - 直接决定路由状态的落点
- **Compatibility**: 旧版 SS 和三方 Source 第一阶段必须继续可用 - 不能用一次协议升级换取全量停机改造
- **Transport**: 继续以 WebSocket 为核心链路，不改为 MQ-only 或纯 HTTP 回调 - 避免方案偏离当前系统能力
- **Scale Target**: 后续实现和压测拆分需要能承接 `60 万用户 / 120 万 agent` 的目标 - 方案不能只在单机视角成立

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 以整仓方式初始化 GSD，而不是把项目缩成单次局部讨论 | 后续 phase 还会继续演进，GSD 应覆盖完整仓库上下文 | Pending |
| 当前 milestone 聚焦 Gateway/Source 路由重构 | 这是眼下最重要、风险最高、跨模块最强的工作主线 | Pending |
| 首个 phase 只做路由方案定稿，不混入实现 | 先把目标模型、兼容策略和验收口径定清楚，后续实现才不会反复返工 | Pending |
| 新版方案继续以 WebSocket 为核心通道 | 更贴合现有系统形态，避免为设计期引入不必要的架构转向 | Pending |
| 新版 Source 可升级协议，legacy 服务第一阶段保留旧行为 | 兼顾前进路线和现网兼容性 | Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition**:
1. Requirements invalidated? -> Move to Out of Scope with reason
2. Requirements validated? -> Move to Validated with phase reference
3. New requirements emerged? -> Add to Active
4. Decisions to log? -> Add to Key Decisions
5. "What This Is" still accurate? -> Update if drifted

**After each milestone**:
1. Full review of all sections
2. Core Value check -> still the right priority?
3. Audit Out of Scope -> reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-03-29 after initialization*
