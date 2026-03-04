# AGENTS.md — opencode-CUI Project Navigation

**Last Updated:** 2026-03-04
**Project:** opencode-CUI — Skill 对话集成方案
**Status:** Active Development

---

## Project Overview

**opencode-CUI** is a Skill-based conversation integration solution that bridges IM platforms (DingTalk, Feishu, Slack) with local OpenCode instances. Users trigger Skill interactions via "/" commands in their IM client, which opens a mini-app for multi-turn AI coding conversations. Responses can be selectively sent back to the IM chat.

**Core Architecture:**
- **PC Agent Plugin** (TypeScript) — Bridges local OpenCode ↔ AI-Gateway via WebSocket
- **AI-Gateway** (Spring Boot) — Connection management, AK/SK authentication, bidirectional relay
- **Skill Server** (Spring Boot) — Session persistence, client streaming, IM message dispatch
- **Skill Mini-App** (React) — IM client UI, protocol adaptation, rich text rendering

**Key Constraint:** This MVP focuses on **local OpenCode mode only** (personal private sessions). Remote modes and team sharing are future iterations.

---

## Directory Structure

```
opencode-CUI/
├── ai-gateway/            # AI-Gateway service (Spring Boot 3.4.6, Java 21)
│   ├── src/main/java/com/yourapp/gateway/
│   │   ├── GatewayApplication.java
│   │   ├── config/       # WebSocket, Redis, Spring config
│   │   ├── controller/            # REST endpoints (agent registration, health)
│   │   ├── model/             # AgentConnection, GatewayMessage, MessageEnvelope
│   │   ├── repository/            # MyBatis mappers for agent persistence
│   │   ├── service/      # AkSkAuthService, EventRelayService, RedisMessageBroker
│   │   └── ws/       # AgentWebSocketHandler, SkillServerWSClient
│   ├── src/main/resources/
│   │   ├── application.yml        # Server config (port 8081)
│   │   ├── db/migration/          # Flyway SQL migrations
│   │   └── mapper/                # MyBatis XML mappers
│   └── pom.xml
│
├── skill-server/               # Skill Server service (Spring Boot 3.4.6, Java 21)
│   ├── src/main/java/com/yourapp/skill/
│   │   ├── SkillServerApplication.java
│   │   ├── config/         # WebSocket, Redis, Spring config
│   │   ├── controller/            # REST endpoints (sessions, messages, definitions)
│   │   ├── model/       # SkillSession, SkillMessage, SkillDefinition, PageResult
│   │   ├── repository/            # MyBatis mappers for skill data
│   │   ├── service/               # SkillSessionService, SkillMessageService, ImMessageService,
│   │   │                           # GatewayRelayService, RedisMessageBroker, SequenceTracker
│   │   └── ws/            # GatewayWSHandler, SkillStreamHandler
│   ├── src/main/resources/
│   │   ├── application.yml      # Server config (port 8082)
│   │   ├── db/migration/      # Flyway SQL migrations
│   │   └── mapper/                # MyBatis XML mappers
│   └── pom.xml
│
├── src/main/pc-agent/             # PC Agent plugin (TypeScript, Node.js)
│   ├── PcAgentPlugin.ts           # Main entry point, orchestrates all sub-modules
│   ├── GatewayConnection.ts       # WebSocket connection manager (reconnect, heartbeat)
│   ├── OpenCodeBridge.ts          # @opencode-ai/sdk wrapper (events, chat, sessions)
│   ├── AkSkAuth.ts                # AK/SK authentication parameter generation
│   ├── EventRelay.ts         # Bidirectional event relay (OpenCode ↔ Gateway)
│   ├── HealthChecker.ts        # Periodic health monitoring
│   ├── config/
│   │   └── AgentConfig.ts         # Configuration resolution (env vars, defaults)
│   ├── package.json            # Dependencies: @opencode-ai/sdk, ws, TypeScript
│   └── tsconfig.json
│
├── skill-miniapp/                 # Skill mini-app (React 18, TypeScript, Vite)
│   ├── src/
│   │   ├── App.tsx        # Root component
│   │   ├── components/         # UI components (chat, message, input, etc.)
│   │   ├── protocol/     # OpenCode protocol adaptation layer
│   │   ├── hooks/        # Custom React hooks
│   │   ├── types/              # TypeScript type definitions
│   │   ├── utils/                 # Utility functions
│   │   ├── styles/        # CSS/styling
│   │   └── main.tsx               # Entry point
│   ├── public/                    # Static assets
│   ├── package.json               # Dependencies: React, react-markdown, shiki, Vite
│   ├── tsconfig.json
│   ├── vite.config.ts
│   └── index.html
│
├── test-simulator/                # Test simulator (React, TypeScript, Vite)
│   ├── src/
│   │   ├── App.tsx
│   │   ├── components/
│   │   ├── pages/
│   │   └── main.tsx
│   ├── package.json
│   ├── vite.config.ts
│   └── index.html
│
├── plan/                        # Project planning documents
│   ├── 00-方案设计主文档.md       # Main design document
│   ├── 01-MVP需求文档.md       # MVP requirements
│   ├── 02-架构设计文档.md         # Architecture design
│   ├── 03-项目实施计划.md       # Implementation plan
│   └── 04-测试用例文档.md         # Test cases
│
├── docs/                  # Technical documentation
│   └── [additional docs]
│
├── .git/                          # Git repository
├── .omc/        # OMC state and coordination
├── .vscode/               # VS Code workspace settings
└── README.md (if exists)
```

---
## Technology Stack

| Layer | Technology | Version | Purpose |
|-------|--------|---------|---------|
| **Backend** | Java | 21 | Spring Boot 3.4.6 runtime |
| **Backend Framework** | Spring Boot | 3.4.6 | Web, WebSocket, scheduling |
| **Database** | MariaDB | Latest | Persistent storage (sessions, messages) |
| **ORM** | MyBatis | 3.0.4 | SQL mapping, query builder |
| **Message Broker** | Redis | Latest | Pub/Sub for inter-service messaging |
| **PC Agent** | TypeScript | 5.x | Node.js plugin for OpenCode bridge |
| **PC Agent SDK** | @opencode-ai/sdk | 1.2+ | OpenCode protocol client |
| **Frontend** | React | 18.2.0 | UI framework for mini-app |
| **Frontend Build** | Vite | 5.0.0 | Fast dev server and bundler |
| **Markdown Rendering** | react-markdown | 9.0.0 | Render AI responses as Markdown |
| **Syntax Highlighting** | shiki | 1.9.0 | Code block syntax highlighting |

---

## Quick Start

### Prerequisites
- Java 21 (for Spring Boot services)
- Node.js 18+ (for PC Agent and mini-app)
- MariaDB (running, accessible)
- Redis (running, accessible)

### Startup Sequence

1. **Start MariaDB and Redis**
   ```bash
   # Ensure MariaDB is running on default port (3306)
   # Ensure Redis is running on default port (6379)
   ```

2. **Start AI-Gateway** (port 8081)
   ```bash
   cd ai-gateway
   mvn spring-boot:run
   # Or: mvn clean package && java -jar target/ai-gateway-1.0.0-SNAPSHOT.jar
   ```

3. **Start Skill Server** (port 8082)
   ```bash
   cd skill-server
   mvn spring-boot:run
   # Or: mvn clean package && java -jar target/skill-server-0.1.0-SNAPSHOT.jar
   ```

4. **Configure PC Agent** (AK/SK credentials)
   ```bash
   cd src/main/pc-agent
   # Set environment variables or config file:
   # - OPENCODE_AK=<your-access-key>
   # - OPENCODE_SK=<your-secret-key>
   # - GATEWAY_URL=ws://localhost:8081/ws/agent
   ```

5. **Start OpenCode** (local instance, port 54321)
   ```bash
   # Ensure OpenCode is running on http://localhost:54321
   ```

6. **Start PC Agent Plugin**
   ```bash
   cd src/main/pc-agent
   npm install
   npm run build
   # Load plugin into IM client (Electron main process)
   ```

7. **Start Skill Mini-App** (development)
   ```bash
   cd skill-miniapp
   npm install
   npm run dev
   # Or build for production: npm run build
   ```

---

## AI Agent Work Guidelines

### Backend Development (Java)

**When modifying AI-Gateway or Skill Server:**

1. **Preserve Pass-Through Mode**
   - AI-Gateway and Skill Server must NOT parse or transform OpenCode event payloads
   - All OpenCode responses flow through unchanged (transparent relay)
   - Only add metadata (timestamps, sequence numbers) at the envelope level

2. **Database Migrations**
   - Place new migrations in `src/main/resources/db/migration/`
   - Follow Flyway naming: `V{version}__{description}.sql`
   - Test migrations locally before committing

3. **WebSocket Endpoints**
   - AI-Gateway: `/ws/agent` (PCAgent connects here with AK/SK)
   - Skill Server: `/ws/gateway` (receives stream from AI-Gateway)
   - Skill Server: `/ws/client` (IM mini-app connects here)
   - Config locations: `src/main/java/com/yourapp/{service}/config/`

4. **Redis Pub/Sub**
   - AI-Gateway publishes events to `gateway:events:{agentId}`
   - Skill Server subscribes and relays to connected clients
   - Use `RedisMessageBroker` for publish/subscribe operations

5. **Authentication**
   - AK/SK validation happens in `AkSkAuthService` (AI-Gateway)
   - Verify credentials before accepting WebSocket connections
   - Store validated agent metadata in `AgentConnection` model

### Frontend Development (TypeScript/React)

**When modifying PC Agent or Skill Mini-App:**

1. **Protocol Adaptation Layer**
   - All OpenCode protocol parsing lives in `skill-miniapp/src/protocol/`
   - PC Agent passes raw OpenCode events to Skill Server
   - Mini-app interprets events and renders UI (Markdown, code blocks, Tool Use, etc.)

2. **PC Agent Responsibilities**
   - Bridge local OpenCode ↔ AI-Gateway (transparent relay)
   - Handle AK/SK authentication and WebSocket lifecycle
   - Implement exponential backoff reconnection (1s → 2s → 4s → ... → 30s cap)
   - Send periodic heartbeats to keep connection alive
   - Do NOT parse OpenCode event content

3. **Mini-App Responsibilities**
   - Render "/" command UI in IM client
   - Display Mini Bar (1-line status indicator)
   - Expand to full Skill conversation UI on demand
   - Parse OpenCode events and render rich text (Markdown, code, syntax highlighting)
   - Implement "Send to Chat" feature to post responses back to IM

4. **Build and Type Checking**
   - PC Agent: `npm run build` (TypeScript compilation)
   - PC Agent: `npm run typecheck` (type validation without emit)
   - Mini-App: `npm run build` (Vite production build)
   - Mini-App: `npm run typecheck` (type validation)

### Database and Configuration

**Migration Files:**
- AI-Gateway: `ai-gateway/src/main/resources/db/migration/`
- Skill Server: `skill-server/src/main/resources/db/migration/`
- Use Flyway versioning: `V1__init.sql`, `V2__add_column.sql`, etc.

**Configuration Files:**
- AI-Gateway: `ai-gateway/src/main/resources/application.yml`
- Skill Server: `skill-server/src/main/resources/application.yml`
- PC Agent: `src/main/pc-agent/config/AgentConfig.ts`

**WebSocket Configuration:**
- AI-Gateway: `ai-gateway/src/main/java/com/yourapp/gateway/config/GatewayConfig.java`
- Skill Server: `skill-server/src/main/java/com/yourapp/skill/config/SkillConfig.java`

---

## Key Architectural Decisions

1. **Two Independent Services**
   - AI-Gateway handles PCAgent connections and AK/SK auth
   - Skill Server handles session persistence and IM integration
   - Decoupled via Redis Pub/Sub and WebSocket relay

2. **Transparent Pass-Through**
   - No parsing of OpenCode event content in backend
   - Protocol adaptation happens only in Skill Mini-App
   - Enables future support for other AI tools (Claude Code, OpenClaw)

3. **Local Mode Only (MVP)**
   - PCAgent connects to local OpenCode instance (port 54321)
   - No remote AI tool support in this iteration
   - Simplifies authentication and reduces latency

4. **WebSocket Streaming**
   - All responses flow as WebSocket messages (not HTTP polling)
   - Enables real-time streaming of AI responses
   - Reduces latency and improves UX

5. **Redis for Inter-Service Communication**
   - Pub/Sub for event distribution
   - Decouples AI-Gateway from Skill Server
   - Enables horizontal scaling in future

---

## Common Tasks

### Adding a New Endpoint (Skill Server)

1. Create controller method in `skill-server/src/main/java/com/yourapp/skill/controller/`
2. Add service logic in `skill-server/src/main/java/com/yourapp/skill/service/`
3. Add repository method in `skill-server/src/main/java/com/yourapp/skill/repository/`
4. Add MyBatis XML mapper in `skill-server/src/main/resources/mapper/`
5. Test with curl or Postman

### Adding a New Database Table

1. Create migration file: `skill-server/src/main/resources/db/migration/V{N}__{description}.sql`
2. Create model class: `skill-server/src/main/java/com/yourapp/skill/model/{Entity}.java`
3. Create repository interface: `skill-server/src/main/java/com/yourapp/skill/repository/{Entity}Repository.java`
4. Create MyBatis mapper XML: `skill-server/src/main/resources/mapper/{Entity}Mapper.xml`
5. Run migration: `mvn flyway:migrate`

### Debugging WebSocket Issues

1. **AI-Gateway WebSocket** (`/ws/agent`)
   - Check `AgentWebSocketHandler` for connection lifecycle
   - Verify AK/SK validation in `AkSkAuthService`
   - Monitor Redis Pub/Sub: `redis-cli SUBSCRIBE gateway:events:*`

2. **Skill Server WebSocket** (`/ws/gateway` and `/ws/client`)
   - Check `GatewayWSHandler` for gateway relay
   - Check `SkillStreamHandler` for client relay
   - Verify message sequencing in `SequenceTracker`

3. **PC Agent Connection**
   - Check `GatewayConnection` for reconnect logic
   - Verify heartbeat in `HealthChecker`
   - Check `EventRelay` for bidirectional message flow

### Running Tests

```bash
# Backend tests (if configured)
cd ai-gateway && mvn test
cd skill-server && mvn test

# Frontend type checking
cd src/main/pc-agent && npm run typecheck
cd skill-miniapp && npm run typecheck
```

---

## Troubleshooting

| Issue | Diagnosis | Solution |
|-------|-----------|----------|
| PCAgent fails to connect | Check AK/SK credentials | Verify `OPENCODE_AK` and `OPENCODE_SK` env vars |
| WebSocket connection drops | Check network/firewall | Verify gateway URL and port 8081 accessibility |
| OpenCode offline | Check local OpenCode instance | Ensure OpenCode is running on port 54321 |
| Database migration fails | Check SQL syntax | Review migration file in `db/migration/` |
| Redis connection error | Check Redis service | Ensure Redis is running on port 6379 |
| Mini-app not rendering | Check protocol adaptation | Review `skill-miniapp/src/protocol/` |

---

## References

- **Design Document:** `plan/00-方案设计主文档.md`
- **MVP Requirements:** `plan/01-MVP需求文档.md`
- **Architecture Design:** `plan/02-架构设计文档.md`
- **Implementation Plan:** `plan/03-项目实施计划.md`
- **Test Cases:** `plan/04-测试用例文档.md`
- **OpenCode SDK:** https://github.com/opencode-ai/sdk (TypeScript)
- **Spring Boot Docs:** https://spring.io/projects/spring-boot
- **MyBatis Docs:** https://mybatis.org/mybatis-3/
- **React Docs:** https://react.dev

---

## Contact & Escalation

For architecture questions, design decisions, or cross-service issues, refer to the design documents in `plan/` or escalate to the project lead.
