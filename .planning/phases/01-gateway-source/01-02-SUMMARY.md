---
phase: 01-gateway-source
plan: 02
subsystem: infra
tags: [routing, websocket, redis, protocol-v3, relay]
requires:
  - phase: 01
    provides: 当前双向链路现实基线与冲突清单
provides:
  - Target Routing Model v3 正式设计文档
  - GW 共享路由真源与索引模型
  - Source Protocol v3 与连接池吞吐语义
affects: [01-03, implementation-phase, load-test-phase]
tech-stack:
  added: []
  patterns:
    - 目标模型按真源、索引、协议、连接池四层定稿
    - 业务路由与连接级 owner 分层建模
key-files:
  created:
    - documents/routing-redesign/02-target-routing-model-v3.md
  modified: []
key-decisions:
  - GW 共享 Redis 是唯一的 GW 内部共享路由真源
  - instanceId 在 v3 中按 sourceInstanceId 语义解释
  - 连接池默认 4 条长连接并通过一致性哈希保证单会话有序
patterns-established:
  - "Source Protocol v3: 显式 route_bind/route_unbind 取代被动学习"
  - "Connection-level ownership: 业务归属与传输归属分层"
requirements-completed: [ROUT-01, ROUT-02, ROUT-03, PROT-01, PROT-02]
duration: 5 min
completed: 2026-03-30
---

# Phase 1 Plan 02: 目标路由模型定稿 Summary

**ALB-only 接入、GW 共享 Redis 真源、Source Protocol v3 与连接级 owner 的正式目标模型**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-30T01:35:31Z
- **Completed:** 2026-03-30T01:40:25Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments

- 定稿了新版 Source 只连统一 ALB 入口、GW 内部共享 Redis 作为唯一真源的目标模型
- 固化了 `toolSessionId -> sourceInstance`、`welinkSessionId -> sourceInstance`、`sourceInstance -> activeConnectionSet`、`connectionId -> owningGw` 四层索引
- 把 `Source Protocol v3`、固定路由优先级和连接池吞吐策略写成可直接指导实现 phase 的正式语义

## Task Commits

Each task was committed atomically:

1. **Task 1: 编写目标模型与路由真源章节** - `860432d` (`docs`)
2. **Task 2: 定稿 v3 协议与连接池吞吐语义** - `06c852b` (`docs`)

## Files Created/Modified

- `documents/routing-redesign/02-target-routing-model-v3.md` - 统一记录目标模型、共享索引、协议字段、路由优先级和连接池设计

## Decisions Made

- 目标态必须和现状态分离描述，避免继续把被动学习和广播兜底当成主设计
- owner 语义调整为连接级，`sourceInstance` 负责业务归属，`connectionId` 负责传输归属

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `02-target-routing-model-v3.md` 已经为 legacy 边界和验收文档提供稳定目标基线
- Ready for `01-03`

---
*Phase: 01-gateway-source*
*Completed: 2026-03-30*
