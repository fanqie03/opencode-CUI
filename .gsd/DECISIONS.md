# DECISIONS.md — Architecture Decision Records

> **Last Updated:** 2026-03-05

## ADR-001: Transparent Pass-Through Architecture
**Date:** Pre-existing
**Status:** Accepted
**Context:** Need to relay AI coding tool events through multiple backend layers.
**Decision:** Backend services (Gateway, Skill Server) do NOT parse or transform OpenCode event payloads. Protocol adaptation happens only in the Client.
**Consequence:** Future tool support (Cursor, Windsurf) requires only new Client protocol adapters, not backend changes.

## ADR-002: Redis Pub/Sub for Multi-Instance Routing
**Date:** Pre-existing
**Status:** Accepted
**Context:** Gateway and Skill Server may run as multiple instances for scalability.
**Decision:** Use Redis Pub/Sub with `agent:{agentId}` (downlink) and `session:{sessionId}` (uplink) channels for cross-instance routing.
**Consequence:** Requires Redis dependency but enables horizontal scaling without sticky sessions.

## ADR-003: Sequence Number Tracking
**Date:** Pre-existing
**Status:** Accepted
**Context:** WebSocket messages may arrive out-of-order or be lost in transit.
**Decision:** Implement per-session AtomicLong sequence numbers with 3-tier gap detection (warn/recover/reconnect).
**Consequence:** Added complexity but ensures message reliability and ordering.

---

## Phase 1 Decisions

**Date:** 2026-03-05

### ADR-004: Refactor to OpenCode Plugin Format
**Status:** Accepted
**Context:** Current PC Agent is a standalone class (`new PcAgentPlugin()`), but the protocol spec (Layer 0) requires an OpenCode Plugin Hook format that registers via `opencode.json` and receives events through the plugin event hook.
**Decision:** Refactor PC Agent to use the OpenCode Plugin format: `export const PlatformAgent: Plugin = async (ctx) => { ... }`. Use `ctx.client` for all SDK operations instead of creating a standalone client.
**Consequence:** Major refactor of current code; entry point, event reception, and SDK calls all change. But aligns exactly with how OpenCode expects plugins to work.

### ADR-005: Envelope as Platform Protocol, Event as Raw Pass-Through
**Status:** Accepted
**Context:** Need to clarify the relationship between `envelope` and `event` fields in upstream messages.
**Decision:** `envelope` is the platform's own protocol metadata (version, messageId, agentId, sequenceNumber, etc.) used for server-side validation and routing. `event` contains the raw, unmodified OpenCode event — complete transparent pass-through.
**Consequence:** Backend services can use envelope for routing/validation without parsing the event payload.

### ADR-006: MVP SDK Operations — abort + permissions
**Status:** Accepted
**Context:** Protocol spec lists 8 SDK operations but not all are needed for MVP.
**Decision:** Add `session.abort()` and `session.permissions()` (permission reply) to OpenCodeBridge. Skip fork and revert for MVP.
**Consequence:** Enables permission confirmation flow and session abort — critical for UX.

### ADR-007: Bun Runtime Compatibility
**Status:** Accepted
**Context:** Protocol spec states "Plugin runs in OpenCode process via Bun". Current code uses `node:crypto`, `node:os`.
**Decision:** Ensure all code is Bun-compatible. Use `node:` prefix for built-in modules (Bun supports most `node:` APIs). Test under Bun runtime.
**Consequence:** Must verify `node:crypto` (HMAC-SHA256) and `node:os` work under Bun.

### ADR-008: Dual Testing Strategy
**Status:** Accepted
**Context:** No tests exist currently. Need verification strategy for Phase 1.
**Decision:** Both unit tests (mock WebSocket, mock SDK) for PC Agent AND end-to-end verification via Test Simulator.
**Consequence:** More thorough coverage but Test Simulator e2e depends on Phase 2-3 being at least partially ready. Unit tests can be done in Phase 1; e2e in Phase 5.

---

## Phase 2 Decisions

**Date:** 2026-03-06

### ADR-009: 基于现有 Java 代码进行增量改进（非零起步）
**Status:** Accepted
**Context:** 初始分析误判 Gateway 为"零实现"，实际分析发现 `ai-gateway/src/` 下有完整的 Java 源码：10 个 .java 文件、application.yml、DB migration、MyBatis mapper。代码已实现全部核心功能（WS handler、AK/SK、Redis、Agent 生命周期、Skill Server 重连）。
**Decision:** Phase 2 不是从零实现，而是对现有代码进行增量改进：编译验证、REQ-26 (DB AK/SK)、协议格式对齐检查、单元测试。
**Consequence:** 工作量远小于预期。重点移至验证、测试、和差距修补。

### ADR-010: 实现 REQ-26 — 数据库 AK/SK 凭证存储
**Status:** Accepted
**Context:** 当前 `AkSkAuthService.lookupByAk()` 使用硬编码凭证（test-ak-001/test-sk-secret-001），标记 TODO 需改数据库。
**Decision:** 创建 `ak_sk_credential` 表 + MyBatis mapper，替换硬编码 lookup。保留测试凭证通过 migration 插入。
**Consequence:** 生产可用的凭证管理，可由运维维护。

### ADR-011: 不需要 Mock Skill Server
**Status:** Accepted
**Context:** Skill Server 代码存在但尚未按计划实施。
**Decision:** 按 ROADMAP 顺序实施，Gateway 先完成后直接在后续 Phase 3 中对接真实 Skill Server。
**Consequence:** Phase 2 验证限于 Gateway 自身编译+单元测试，端到端在 Phase 5 完成。
