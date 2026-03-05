# ROADMAP.md

> **Current Phase**: Not started
> **Milestone**: v0 MVP

## Must-Haves (from SPEC)
- [ ] All 6 protocol layers implemented end-to-end
- [ ] Real-time streaming with sequence tracking
- [ ] Permission confirmation flow
- [ ] Session CRUD + message persistence
- [ ] "Send to Chat" IM dispatch
- [ ] Test Simulator exercising full flow

## Phases

### Phase 1: Protocol Foundation — PC Agent Plugin (Layer 0 + Layer 1)
**Status**: ⬜ Not Started
**Objective**: Implement the PC Agent Plugin as an OpenCode plugin that receives all event types and relays them upstream to AI-Gateway via WebSocket with AK/SK authentication.
**Requirements**: REQ-01, REQ-02, REQ-03, REQ-04, REQ-05, REQ-06, REQ-07
**Deliverable**: PC Agent connects to Gateway, authenticates, receives OpenCode events, and relays invoke commands back to OpenCode.

### Phase 2: Backend Relay — AI-Gateway (Layer 1 + Layer 2 + Layer 3)
**Status**: ⬜ Not Started
**Objective**: Implement Gateway WebSocket handlers, AK/SK verification, agent connection management, Redis Pub/Sub routing, and bidirectional relay to Skill Server.
**Requirements**: REQ-03, REQ-08, REQ-09, REQ-10, REQ-11, REQ-12, REQ-26
**Deliverable**: Gateway accepts agents, validates credentials, relays events to Skill Server, and routes invoke commands back to agents via Redis.

### Phase 3: Backend Relay — Skill Server (Layer 2 + Layer 3 + Layer 4 + Layer 5)
**Status**: ⬜ Not Started
**Objective**: Implement Skill Server session management, message persistence, WebSocket streaming to clients, REST API, sequence tracking, and permission confirmation flow.
**Requirements**: REQ-08, REQ-11, REQ-13, REQ-14, REQ-15, REQ-16, REQ-17, REQ-18, REQ-19, REQ-20, REQ-21, REQ-22, REQ-23, REQ-24, REQ-25
**Deliverable**: Skill Server persists sessions/messages, streams events to clients, handles REST API calls, and supports permission flow.

### Phase 4: Client — Skill Mini-App + Test Simulator (Layer 4 + Layer 5)
**Status**: ⬜ Not Started
**Objective**: Implement the React mini-app with protocol adaptation, real-time streaming UI, permission confirmation dialogs, message rendering, and "Send to Chat". Update Test Simulator for end-to-end testing.
**Requirements**: REQ-14, REQ-15, REQ-16, REQ-17, REQ-18, REQ-19, REQ-20, REQ-21, REQ-22, REQ-23
**Deliverable**: Client connects to Skill Server WebSocket, renders streaming events with Markdown/code highlighting, handles permissions, and can send to IM.

### Phase 5: Integration & Verification
**Status**: ⬜ Not Started
**Objective**: End-to-end integration testing, gap filling, technical debt cleanup, and final verification against all success criteria.
**Requirements**: All
**Deliverable**: Full system working end-to-end via Test Simulator, all success criteria met.
