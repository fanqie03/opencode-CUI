# ROADMAP.md

> **Current Phase**: 全部完成
> **Milestone**: v0 MVP ✅

## Must-Haves (from SPEC)
- [x] All 6 protocol layers implemented end-to-end *(Phase 1: Layer 0+1, Phase 2: Layer 1-3, Phase 3: Layer 2-5)*
- [x] Real-time streaming with sequence tracking *(Phase 3)*
- [x] Permission confirmation flow *(Phase 3)*
- [x] Session CRUD + message persistence *(Phase 3)*
- [x] "Send to Chat" IM dispatch *(Phase 4 — APIClient + UI)*
- [x] Test Simulator exercising full flow *(Phase 4 — 8 scenarios)*

## Phases

### Phase 1: Protocol Foundation — PC Agent Plugin (Layer 0 + Layer 1)
**Status**: ✅ 已完成
**Objective**: Implement the PC Agent Plugin as an OpenCode plugin that receives all event types and relays them upstream to AI-Gateway via WebSocket with AK/SK authentication.
**Requirements**: REQ-01, REQ-02, REQ-03, REQ-04, REQ-05, REQ-06, REQ-07
**Deliverable**: PC Agent connects to Gateway, authenticates, receives OpenCode events, and relays invoke commands back to OpenCode.

### Phase 2: Backend Relay — AI-Gateway (Layer 1 + Layer 2 + Layer 3)
**Status**: ✅ 已完成并验证 (7/7 REQ PASS)
**Objective**: Implement Gateway WebSocket handlers, AK/SK verification, agent connection management, Redis Pub/Sub routing, and bidirectional relay to Skill Server.
**Requirements**: REQ-03, REQ-08, REQ-09, REQ-10, REQ-11, REQ-12, REQ-26
**Deliverable**: Gateway accepts agents, validates credentials, relays events to Skill Server, and routes invoke commands back to agents via Redis.

### Phase 3: Backend Relay — Skill Server (Layer 2 + Layer 3 + Layer 4 + Layer 5)
**Status**: ✅ 已完成并验证 (15/15 REQ PASS, 101 tests)
**Objective**: Implement Skill Server session management, message persistence, WebSocket streaming to clients, REST API, sequence tracking, and permission confirmation flow. V1 protocol migration (方案5).
**Requirements**: REQ-08, REQ-11, REQ-13, REQ-14, REQ-15, REQ-16, REQ-17, REQ-18, REQ-19, REQ-20, REQ-21, REQ-22, REQ-23, REQ-24, REQ-25
**Deliverable**: Skill Server persists sessions/messages, streams events to clients, handles REST API calls, supports permission flow. Gateway + Skill 两侧方案5迁移完成。

### Phase 4: Client — Test Simulator v1 迁移 (Layer 4 + Layer 5)
**Status**: ✅ 已完成并验证 (10/10 REQ PASS, BUILD SUCCESS)
**Objective**: test-simulator 工程 v1 协议迁移，实现 PermissionPanel、MessageHistory、Markdown 渲染、深色主题。
**Requirements**: REQ-14, REQ-15, REQ-16, REQ-17, REQ-18, REQ-19, REQ-20, REQ-21, REQ-22, REQ-23
**Deliverable**: test-simulator 适配 v1 GatewayMessage，8 个测试场景，分页消息历史，权限确认 UI，深色主题。

### Phase 5: Integration & Verification
**Status**: ✅ 已完成
**Objective**: 包名重命名 `com.yourapp` → `com.opencode.cui`，安全加固（token/CORS），配置规范化，AK/SK 数据库存储确认。
**Requirements**: REQ-26 + 技术债清理
**Deliverable**: 两工程 101 tests BUILD SUCCESS，TODO 技术债全部清除。
