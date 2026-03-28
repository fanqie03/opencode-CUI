# Roadmap: opencode-CUI

## Overview

`opencode-CUI` 已经具备运行中的多模块系统形态，本次 GSD 初始化不重做历史 roadmap，而是围绕当前最重要的 Gateway/Source 路由重构建立一个可持续推进的里程碑。当前 milestone 的目标是先把现网拓扑、现有代码、目标路由模型、协议变化、legacy 兼容和验收口径定稿，再据此拆分后续实现、验证和 rollout phase。

## Current Milestone: v1.0 Gateway/Source Routing Redesign

**Milestone Goal:** 为 Gateway/Source 路由体系建立唯一的正式设计基线，明确现状、目标模型、协议边界、兼容策略、验收场景与后续实现拆分。

## Phases

- [ ] **Phase 1: Gateway/Source 路由方案定稿** - 定稿当前路由现状、目标模型、协议与兼容边界，为后续实现和压测 phase 提供唯一基线

## Phase Details

### Phase 1: Gateway/Source 路由方案定稿

**Goal:** 沉淀一套简单、合理、兼容旧服务的 Gateway/Source 路由正式方案，并明确后续实现 phase 的边界
**Depends on**: Nothing (first phase)
**Requirements**: [CURR-01, CURR-02, ROUT-01, ROUT-02, ROUT-03, PROT-01, PROT-02, COMP-01, COMP-02, ACPT-01, ACPT-02]
**Success Criteria** (what must be TRUE):
  1. 团队可以用一套统一叙述解释当前 `Source -> GW -> Agent` 与 `Agent -> GW -> Source` 的真实链路
  2. 新版 Source 仅连统一 ALB WS 入口、GW 内部负责路由查询与跨 GW relay 的目标模型被定稿
  3. `Source Protocol v3` 的握手字段、控制消息和内部状态抽象被定稿
  4. legacy SS 与三方 Source 的兼容边界被明确，不再与新版 Source 路径混淆
  5. 验收场景、实现边界和后续压测拆分被明确写入 phase 产出
**Plans**: 3 plans

Plans:
- [ ] 01-01: 梳理现网拓扑与当前代码中的双向路由路径
- [ ] 01-02: 定稿目标路由模型、协议变化和状态抽象
- [ ] 01-03: 收口 legacy 兼容、验收口径与后续实现边界

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Gateway/Source 路由方案定稿 | 0/3 | Not started | - |

---
