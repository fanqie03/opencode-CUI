# Requirements: opencode-CUI

**Defined:** 2026-03-29
**Core Value:** 在双集群、统一 ALB 入口、旧服务并存的生产约束下，Gateway 与 Source 之间的路由模型必须简单、可解释、可扩展，并且能稳定把消息送到正确的 Agent 与 Source 实例。

## v1 Requirements

### Current State

- [ ] **CURR-01**: 团队可以从代码和现网拓扑角度清楚描述当前 `Source -> GW -> Agent` 的真实消息路径
- [ ] **CURR-02**: 团队可以从代码和现网拓扑角度清楚描述当前 `Agent -> GW -> Source` 的真实回流路径

### Routing Model

- [ ] **ROUT-01**: 新版 Source 路由模型明确规定只连接统一 ALB WS 入口，不依赖跨集群实例级直连
- [ ] **ROUT-02**: GW 内部路由状态明确抽象为 `sourceInstance -> owningGw` 与 `session/toolSession -> sourceInstance`
- [ ] **ROUT-03**: 目标模型明确说明何时本地直达、何时跨 GW relay，以及这些路径不依赖 `SS` 与 `GW` 共享 Redis

### Protocol

- [ ] **PROT-01**: 新版 `Source Protocol v3` 明确握手字段 `source`、`instanceId/sourceInstanceId`、`protocolVersion`
- [ ] **PROT-02**: 新版控制消息 `route_bind`、`route_unbind`、`protocol_error` 的职责和触发时机被明确说明

### Compatibility

- [ ] **COMP-01**: 旧版 SS 与三方 Source 的 legacy 路径、保留能力和不承诺能力被清晰界定
- [ ] **COMP-02**: phase 文档能够区分“新版 Source 必选能力”和“legacy 兼容路径”

### Acceptance

- [ ] **ACPT-01**: 方案包含新版 Source 仅连 ALB、Agent 回包跨 GW relay、Source 重连、GW 重启、owner 漂移等验收场景
- [ ] **ACPT-02**: 方案明确后续实现 phase、压测 phase 与本次设计 phase 的边界

## v2 Requirements

### Implementation

- **IMPL-01**: 在 `ai-gateway` 与 `skill-server` 中实现 `Source Protocol v3` 和新的路由状态管理
- **IMPL-02**: 完成 legacy/new mixed mode 下的自动化测试与灰度改造

### Operations

- **OPER-01**: 补齐连接 draining、断线补偿和观测指标，支撑生产 rollout
- **OPER-02**: 完成以 `60 万用户 / 120 万 agent` 为目标的压测方案和容量验收

## Out of Scope

| Feature | Reason |
|---------|--------|
| 本次直接修改生产路由实现 | 首 phase 只做方案定稿，不做实现落地 |
| 用 MQ-only 或纯 HTTP 模型替代当前 WS 主链路 | 偏离现有系统和本次既定方向 |
| 要求 legacy 服务立即升级到 v3 协议 | 与第一阶段兼容目标冲突 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| CURR-01 | Phase 1 | Pending |
| CURR-02 | Phase 1 | Pending |
| ROUT-01 | Phase 1 | Pending |
| ROUT-02 | Phase 1 | Pending |
| ROUT-03 | Phase 1 | Pending |
| PROT-01 | Phase 1 | Pending |
| PROT-02 | Phase 1 | Pending |
| COMP-01 | Phase 1 | Pending |
| COMP-02 | Phase 1 | Pending |
| ACPT-01 | Phase 1 | Pending |
| ACPT-02 | Phase 1 | Pending |

**Coverage:**
- v1 requirements: 11 total
- Mapped to phases: 11
- Unmapped: 0

---
*Requirements defined: 2026-03-29*
*Last updated: 2026-03-29 after initial definition*
