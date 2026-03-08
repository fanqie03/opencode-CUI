# STATE.md — Project Memory

> Last updated: 2026-03-08

## Current Position

```
Phase: 2 — Gateway 架构简化与 AK 路由改造
Status: Not Started
Milestone: v1.0
```

## Session Log

### 2026-03-08: Gap Analysis → Phase 2-5 插入
- 完成协议 Gap Analysis：17 个差异项（P0×4, P1×4, P2×4）
- Redis Pub/Sub 逐 key 讨论：9 个 key 中 7 个复用、1 个改名(agent→ak)、1 个删除(session-owner)
- 架构决策：Gateway 不再做 session 级路由，回归纯链路转发
- 插入 Phase 2-5（协议对齐），原 Phase 2-5 顺延为 Phase 6-9
- Phase 1 标记完成
- Next: /plan 2 — 制定 Gateway 改造执行计划

### 2026-03-08: Insert Phase 1 — Protocol Definition
- Inserted new Phase 1: 端到端协议报文定义
- Renumbered existing Phase 1-4 → Phase 2-5
- Created e2e protocol overview document for reference
- Next: 逐个确认每个环节的协议报文

### 2026-03-07: Project Initialization
- Ran /install → GSD v1.4.0 installed
- Ran /map → ARCHITECTURE.md + STACK.md generated
- Ran /new-project → SPEC.md + ROADMAP.md created
- Deep questioning completed: 5 questions, all answered
- Project type: Brownfield (existing code, partially working)

## Active Decisions

- Scope limited to: Gateway + Skill Server + PC Agent + Web UI
- IM client and miniapp NOT in scope
- First deliverable: Web UI with full miniapp-equivalent capabilities
- Only app creator (AK/SK owner) can use OpenCode skills
- Gateway 不做 session 级路由，上行消息发给任意有 Skill 连接的实例
- agent Redis channel 从 agent:{agentId} 改为 agent:{ak}
- Skill Server 通过 DB 查 toolSessionId → welinkSessionId 做路由

## Blockers

None currently identified.

## Notes

- stream-message-design: Phase 1-4 完成, Phase 5 (集成测试) 待执行
- gateway-skill-routing: 文档完成, 代码实现未开始
- Gap Analysis 文档: gap_analysis.md
