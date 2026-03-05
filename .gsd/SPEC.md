# SPEC.md — Project Specification

> **Status**: `FINALIZED`

## Vision
Build a full-stack Skill-based conversation integration platform (opencode-CUI) that bridges IM platforms (DingTalk, Feishu, Slack) with local OpenCode AI coding instances. Users trigger "/" commands in their IM client, opening a mini-app for multi-turn AI coding conversations with real-time streaming. The system uses transparent pass-through architecture — backend services relay OpenCode events unchanged, with protocol adaptation happening only in the client.

## Goals
1. **Implement the full 6-layer protocol** as defined in `full_stack_protocol.md` — seamless event flow from OpenCode through PC Agent, AI-Gateway, Skill Server, to Client
2. **Real-time streaming** — WebSocket-based bidirectional communication with sequence tracking and gap detection
3. **Session lifecycle management** — Create, manage, and persist multi-turn AI coding sessions with message history
4. **Permission confirmation flow** — Support OpenCode permission requests (bash, file ops) with user approval via Client UI
5. **IM integration** — "Send to Chat" feature to dispatch AI responses back to IM conversations

## Non-Goals (Out of Scope)
- Remote AI tool support (Cursor, Windsurf) — local OpenCode mode only for MVP
- Team sharing and multi-user collaboration
- Production-grade security hardening (TLS, OAuth, API keys rotation)
- v1 protocol with platform-standardized event envelope (future iteration)
- Mobile-native IM integrations (SDK-level)

## Users
- **Developers** using IM platforms (DingTalk/Feishu/Slack) who want to interact with their local OpenCode AI coding instance through "/" commands without leaving the IM client
- **Single user per session** — personal private sessions only (MVP constraint)

## Constraints
- **Local mode only** — PC Agent connects to local OpenCode on port 54321
- **Brownfield project** — existing codebase with partial implementation across all 5 components
- **Technology locked**: Spring Boot 3.4.6 / Java 21 (backend), React 18/TypeScript (mini-app), Node.js/TypeScript (PC Agent)
- **Infrastructure**: MariaDB + Redis (Pub/Sub) — both self-hosted
- **Protocol version**: v0 — flat JSON, transparent OpenCode event pass-through

## Success Criteria
- [ ] All 6 protocol layers (L0-L5) implemented and communicating end-to-end
- [ ] Real-time streaming from OpenCode → Client with < 200ms relay latency
- [ ] Permission confirmation flow working (bash/file ops approval)
- [ ] Session CRUD + message history persistence via REST API
- [ ] "Send to Chat" dispatches formatted content to IM
- [ ] Sequence tracking with gap detection operational (small/medium/large gap handling)
- [ ] Test Simulator can exercise the full flow without real IM client
- [ ] AK/SK authentication successfully authenticates & rejects invalid agents

## Reference
- **Protocol Spec:** `D:\00_Inbox\full_stack_protocol.md`
- **Architecture:** `.gsd/ARCHITECTURE.md`
- **Stack:** `.gsd/STACK.md`
