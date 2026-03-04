<!-- Parent: ../../../../../../AGENTS.md -->

# Skill Server Java Source Code

## Purpose

Skill Server's Java implementation uses a layered architecture to handle REST APIs, business logic, data persistence, and real-time WebSocket streaming. This directory contains the core application logic organized by responsibility.

## Directory Structure

| Directory | Purpose |
|-----------|---------|
| `config/` | Spring configuration: WebSocket endpoint registration, Redis connection pool |
| `controller/` | REST API handlers: SkillDefinitionController, SkillSessionController, SkillMessageController |
| `model/` | Data models: SkillDefinition, SkillSession, SkillMessage, MessageEnvelope, PageResult |
| `repository/` | MyBatis mappers: SkillDefinitionRepository, SkillSessionRepository, SkillMessageRepository |
| `service/` | Business logic: session management, message persistence, IM integration, Gateway relay, Redis Pub/Sub |
| `ws/` | WebSocket handlers: GatewayWSHandler (receives from AI-Gateway), SkillStreamHandler (pushes to clients) |

## AI Agent Work Guidelines

### Architecture Principles

**Layered Design**
- Controllers handle HTTP routing and parameter validation only
- Services contain all business logic and transaction boundaries
- Repositories abstract database access via MyBatis
- WebSocket handlers manage connection lifecycle and message broadcasting

**Message Flow**
- AI-Gateway sends messages to `/ws/gateway` (GatewayWSHandler)
- Messages are persisted via SkillMessageService
- Redis Pub/Sub broadcasts to all connected clients
- Clients receive via `/ws/skill/stream/{sessionId}` (SkillStreamHandler)

**Transparent Pass-Through**
- Service layer does NOT parse OpenCode event content
- Raw event payloads stored in `skill_message.content` (MEDIUMTEXT)
- Only metadata (timestamps, sequence numbers) added at envelope level
- Protocol adaptation happens in Skill Mini-App, not backend

### Code Standards

**Annotations & Patterns**
- Use Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) to reduce boilerplate
- All Service methods must be annotated with `@Transactional` (or `@Transactional(readOnly = true)` for queries)
- Use `@Slf4j` for logging via SLF4J
- Controllers use `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping`, etc.

**Exception Handling**
- Throw `IllegalArgumentException` for validation failures (e.g., session not found)
- Service methods should not catch exceptions; let Spring handle transaction rollback
- Log errors at appropriate levels (info for expected cases, error for unexpected)

**Database Access**
- All database operations go through Repository interfaces
- MyBatis XML mappers in `src/main/resources/mapper/`
- Use parameterized queries to prevent SQL injection
- Repositories return domain models (SkillSession, SkillMessage, etc.)

### Session Management

**Creating Sessions**
```java
SkillSession session = skillSessionService.createSession(
    userId, skillDefinitionId, agentId, title, imChatId
);
```
- Sessions start in ACTIVE status
- Each session is independent (user + skill + agent)
- `last_active_at` updated on every message

**Session Lifecycle**
- ACTIVE: Session is open and receiving messages
- IDLE: Session inactive beyond timeout threshold (default 30 minutes)
- CLOSED: Session explicitly closed by user or cleanup

**Idle Timeout**
- Scheduled cleanup runs every 10 minutes (configurable)
- Marks ACTIVE sessions as IDLE if `last_active_at` exceeds timeout
- Call `touchSession(sessionId)` to update `last_active_at` on message receipt

### Message Persistence

**Storing Messages**
- Use `SkillMessageService.persistMessage()` to save raw OpenCode output
- Store in `skill_message.content` (MEDIUMTEXT field)
- Include sequence number for ordering and gap detection
- Metadata columns: `role`, `content_type`, `timestamp`

**Sequence Tracking**
- Each message has a sequence number for ordering
- `SequenceTracker` detects gaps and triggers recovery
- Gap thresholds:
  - 1-3 missing: log warning, continue (acceptable for streaming)
  - 4-10 missing: request recovery (significant gap)
  - >10 missing: trigger reconnect (major desync)

### WebSocket Streaming

**Gateway Endpoint** (`/ws/gateway`)
- Receives messages from AI-Gateway
- Handled by `GatewayWSHandler`
- Validates internal token from `application.yml`
- Broadcasts to all connected clients via Redis Pub/Sub

**Client Endpoint** (`/ws/skill/stream/{sessionId}`)
- Skill miniapp clients connect here
- Handled by `SkillStreamHandler`
- Subscribes to Redis channel `session:{sessionId}`
- Sends messages as MessageEnvelope JSON

**Message Envelope Structure**
```json
{
  "type": "delta|done|error|agent_offline|agent_online",
  "sessionId": "...",
  "seq": 123,
  "timestamp": "2026-03-04T10:30:00Z",
  "content": "...",
  "meta": { "role": "ASSISTANT", "contentType": "MARKDOWN" }
}
```

### Redis Pub/Sub

**Channel Naming**
- `session:{sessionId}` — Broadcast channel for session events
- `gateway:events:{agentId}` — Events from AI-Gateway

**Operations**
- Use `RedisMessageBroker` for all Pub/Sub operations
- Subscribe to channels in `SkillStreamHandler`
- Publish session events for horizontal scaling

### IM Integration

**Sending to IM Platforms**
- Use `ImMessageService` to send responses to DingTalk, Feishu, Slack
- Include message context: session ID, user ID, timestamp
- Platform-specific adapters handle API differences
- Called after message is persisted and confirmed

### Testing Requirements

**Unit Tests** (`@SpringBootTest` or `@WebMvcTest`)
- Session CRUD operations (create, read, update, close)
- Message persistence and retrieval
- Sequence number validation and gap detection
- Idle timeout logic and cleanup scheduling

**Integration Tests** (`@SpringBootTest`)
- WebSocket connection lifecycle (connect, subscribe, disconnect)
- Message streaming from AI-Gateway to clients
- Sequence number ordering and gap recovery
- Redis Pub/Sub message delivery

**End-to-End Tests**
- Complete message flow: AI-Gateway → Skill Server → Skill miniapp
- Session creation → message persistence → client streaming
- IM integration (send to chat)
- Reconnection and recovery scenarios

### Common Tasks

**Adding a New REST Endpoint**
1. Create controller method in `controller/SkillSessionController.java` (or appropriate controller)
2. Add service logic in `service/SkillSessionService.java`
3. Add repository method in `repository/SkillSessionRepository.java`
4. Add MyBatis XML mapper in `src/main/resources/mapper/SkillSessionMapper.xml`
5. Test with curl or Postman

**Adding a New Database Table**
1. Create migration: `src/main/resources/db/migration/V{N}__{description}.sql`
2. Create model: `model/{Entity}.java`
3. Create repository: `repository/{Entity}Repository.java`
4. Create MyBatis mapper: `src/main/resources/mapper/{Entity}Mapper.xml`
5. Run migration: `mvn flyway:migrate`

**Debugging WebSocket Issues**

Gateway WebSocket (`/ws/gateway`):
- Check `GatewayWSHandler` for connection lifecycle
- Verify internal token in `application.yml`
- Monitor Redis: `redis-cli SUBSCRIBE session:*`

Client WebSocket (`/ws/skill/stream/{sessionId}`):
- Check `SkillStreamHandler` for subscription logic
- Verify sequence numbers in `SequenceTracker`
- Check browser console for client-side errors

Message Flow:
- Trace message from AI-Gateway → Redis → Skill Server → clients
- Verify sequence numbers are incrementing
- Check for gap detection and recovery triggers

## Dependencies

### Internal
- **AI-Gateway** — Sends WebSocket stream to `/ws/gateway`
- **Skill Miniapp** — Receives messages via `/ws/skill/stream/{sessionId}`

### External
- **MariaDB** — Persistent storage (skill_definition, skill_session, skill_message tables)
- **Redis** — Pub/Sub channels and session subscriptions
- **IM Platform APIs** — DingTalk, Feishu, Slack for message dispatch

## Configuration

**Port:** 8082 (configurable via `server.port`)

**Database Connection**
```yaml
spring.datasource.url: jdbc:mariadb://localhost:3306/skill_db
spring.datasource.username: root
spring.datasource.password: ${MYSQL_PASSWORD:root}
```

**Redis Connection**
```yaml
spring.data.redis.host: ${REDIS_HOST:localhost}
spring.data.redis.port: ${REDIS_PORT:6379}
```

**Session Timeout**
```yaml
skill.session.idle-timeout-minutes: 30
skill.session.cleanup-interval-minutes: 10
```

**WebSocket Configuration**
```yaml
skill.websocket.allowed-origins: "*"
skill.gateway.internal-token: ${SKILL_SERVER_INTERNAL_TOKEN:changeme}
```

## Key Files

| File | Purpose |
|---------|
| `SkillServerApplication.java` | Spring Boot entry point |
| `config/SkillConfig.java` | WebSocket endpoint registration |
| `config/RedisConfig.java` | Redis connection pool |
| `controller/SkillDefinitionController.java` | GET /api/skill/definitions |
| `controller/SkillSessionController.java` | CRUD /api/skill/sessions |
| `controller/SkillMessageController.java` | GET /api/skill/messages |
| `model/SkillDefinition.java` | Skill metadata (OpenCode, etc.) |
| `model/SkillSession.java` | User session state |
| `model/SkillMessage.java` | Persisted messages |
| `model/MessageEnvelope.java` | WebSocket message wrapper |
| `model/PageResult.java` | Pagination response |
| `repository/SkillDefinitionRepository.java` | MyBatis mapper |
| `repository/SkillSessionRepository.java` | MyBatis mapper |
| `repository/SkillMessageRepository.java` | MyBatis mapper |
| `service/SkillSessionService.java` | Session CRUD, idle timeout |
| `service/SkillMessageService.java` | Message persistence |
| `service/ImMessageService.java` | IM platform API integration |
| `service/GatewayRelayService.java` | Relay from AI-Gateway |
| `service/RedisMessageBroker.java` | Pub/Sub operations |
| `service/SequenceTracker.java` | Message sequence validation |
| `ws/GatewayWSHandler.java` | /ws/gateway (receives from AI-Gateway) |
| `ws/SkillStreamHandler.java` | /ws/skill/stream/{sessionId} (pushes to clients) |

## References

- **Parent Documentation:** `../../../../AGENTS.md`
- **Spring Boot WebSocket:** https://spring.io/guides/gs/messaging-stomp-websocket/
- **MyBatis Documentation:** https://mybatis.org/mybatis-3/
- **Redis Pub/Sub:** https://redis.io/docs/interact/pubsub/
- **MariaDB JDBC:** https://mariadb.com/kb/en/about-mariadb-connector-j/
- **Lombok:** https://projectlombok.org/
