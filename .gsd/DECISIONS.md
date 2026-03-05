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
