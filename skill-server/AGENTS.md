<!-- Parent: ../AGENTS.md -->

# Skill Server

## Purpose

Skill Server is the business logic layer responsible for:
- **Session Management** — Create, retrieve, update skill sessions with user context
- **Message Persistence** — Store all OpenCode responses in skill_message table (raw, unmodified)
- **WebSocket Streaming** — Push real-time messages to connected Skill miniapp clients
- **IM Integration** — Send selected responses back to IM platforms (DingTalk, Feishu, Slack)
- **Transparent Relay** — Pass through AI tool output without parsing or transforming event content

Skill Server does NOT parse OpenCode event payloads. All AI responses flow through unchanged; only metadata (timestamps, sequence numbers) is added at the envelope level.

## Key Files

| File | Description |
|------|-------------|
| `pom.xml` | Maven configuration: Spring Boot 3.4.6, Java 21, MyBatis 3.0.4, MariaDB, Redis |
| `src/main/resources/application.yml` | Service config: port 8082, database, Redis, WebSocket origins, session timeouts |
| `src/main/resources/db/migration/V1__skill.sql` | Database schema: skill_definition, skill_session, skill_message tables |

## Directory Structure

```
skill-server/
├── src/main/java/com/yourapp/skill/
│   ├── SkillServerApplication.java          # Spring Boot entry point
│   ├── config/
│   │   ├── SkillConfig.java              # WebSocket endpoint registration
│   │   └── RedisConfig.java                 # Redis connection pool
│   ├── controller/
│   │   ├── SkillDefinitionController.java   # GET /api/skill/definitions
│   │   ├── SkillSessionController.java      # CRUD /api/skill/sessions
│   │   └── SkillMessageController.java      # GET /api/skill/messages
│   ├── model/
│   │   ├── SkillDefinition.java           # Skill metadata (OpenCode, etc.)
│   │   ├── SkillSession.java                # User session state
│   │   ├── SkillMessage.java           # Persisted messages
│   │   ├── MessageEnvelope.java             # WebSocket message wrapper
│   │   └── PageResult.java            # Pagination response
│   ├── repository/
│   │   ├── SkillDefinitionRepository.java   # MyBatis mapper
│   │   ├── SkillSessionRepository.java      # MyBatis mapper
│   │   └── SkillMessageRepository.java      # MyBatis mapper
│   ├── service/
│   │   ├── SkillSessionService.java       # Session CRUD, idle timeout
│   │   ├── SkillMessageService.java         # Message persistence
│   │   ├── ImMessageService.java            # IM platform API integration
│   │   ├── GatewayRelayService.java         # Relay from AI-Gateway
│   │   ├── RedisMessageBroker.java          # Pub/Sub operations
│   │   └── SequenceTracker.java             # Message sequence validation
│   └── ws/
│       ├── GatewayWSHandler.java            # /ws/gateway (receives from AI-Gateway)
│       └── SkillStreamHandler.java     # /ws/skill/stream/{sessionId} (pushes to clients)
├── src/main/resources/
│   ├── application.yml               # Configuration
│   ├── db/migration/
│   │   └── V1__skill.sql                    # Flyway migration
│   └── mapper/
│     ├── SkillDefinitionMapper.xml     # MyBatis XML
│       ├── SkillSessionMapper.xml           # MyBatis XML
│       └── SkillMessageMapper.xml           # MyBatis XML
└── src/test/
    └── [unit and integration tests]
```

## AI Agent Work Guidelines

### When Working in This Directory
**Message Persistence**
- Store raw OpenCode output in `skill_message.content` (MEDIUMTEXT field)
- Do NOT parse or transform event payloads
- Include sequence numbers for ordering and gap detection
- Store metadata (timestamps, role, content_type) in separate columns

**WebSocket Streaming**
- Endpoint: `/ws/skill/stream/{sessionId}` (Skill miniapp clients connect here)
- Endpoint: `/ws/gateway` (AI-Gateway pushes messages here)
- Use `SkillStreamHandler` for client subscriptions and message broadcasting
- Implement sequence tracking via `SequenceTracker` to detect message gaps

**Session Management**
- Create sessions in `SkillSessionService.createSession()`
- Track session state: ACTIVE, IDLE, CLOSED
- Implement idle timeout (default 30 minutes, configurable)
- Update `last_active_at` on every message

**IM Integration**
- Send responses via `ImMessageService` to IM platform APIs
- Support DingTalk, Feishu, Slack (platform-specific adapters)
- Include message context (session ID, user ID, timestamp)
**Redis Pub/Sub**
- Subscribe to `session:{sessionId}` channel for multi-instance coordination
- Publish session events for horizontal scaling
- Use `RedisMessageBroker` for all Pub/Sub operations

### Testing Requirements

**Unit Tests**
- Session CRUD operations (create, read, update, delete)
- Message persistence and retrieval
- Sequence number validation and gap detection
- Idle timeout logic

**Integration Tests**
- WebSocket connection lifecycle (connect, subscribe, disconnect)
- Message streaming from AI-Gateway to clients
- Sequence number ordering and gap recovery
- Redis Pub/Sub message delivery

**End-to-End Tests**
- Complete message flow: AI-Gateway → Skill Server → Skill miniapp
- Session creation → message persistence → client streaming
- IM integration (send to chat)
- Reconnection and recovery scenarios

### Common Patterns

**Message Sequence Validation**
```
Gap detection thresholds (in SequenceTracker):
- 1-3 missing: log warning, continue (acceptable for streaming)
- 4-10 missing: request recovery (significant gap)
- >10 missing: trigger reconnect (major desync)
```

**Redis Channel Naming**
```
session:{sessionId}          # Broadcast channel for session events
gateway:events:{agentId}     # Events from AI-Gateway (subscribed by Skill Server)
```

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

## Dependencies

### Internal Dependencies
- **AI-Gateway** — Receives WebSocket stream via `/ws/gateway`
- **Skill Miniapp** — Pushes messages via `/ws/skill/stream/{sessionId}`

### External Dependencies
- **MariaDB** — Persistent storage (skill_definition, skill_session, skill_message)
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

## Key Architectural Decisions

1. **Transparent Pass-Through**
   - No parsing of OpenCode event content in backend
   - Protocol adaptation happens only in Skill Mini-App
   - Enables future support for other AI tools

2. **WebSocket Streaming**
   - All responses flow as WebSocket messages (not HTTP polling)
   - Real-time streaming of AI responses
   - Reduced latency and improved UX

3. **Message Sequencing**
   - Sequence numbers prevent message reordering
   - Gap detection triggers recovery or reconnection
   - Three-tier strategy: continue → recover → reconnect

4. **Redis for Inter-Service Communication**
   - Pub/Sub for event distribution
   - Decouples AI-Gateway from Skill Server
   - Enables horizontal scaling

5. **Session Isolation**
   - Each session is independent (user + skill + agent)
   - Idle sessions marked for cleanup
   - Supports multi-turn conversations

## Common Tasks

### Adding a New REST Endpoint

1. Create controller method in `controller/SkillSessionController.java` (or appropriate controller)
2. Add service logic in `service/SkillSessionService.java`
3. Add repository method in `repository/SkillSessionRepository.java`
4. Add MyBatis XML mapper in `src/main/resources/mapper/SkillSessionMapper.xml`
5. Test with curl or Postman

### Adding a New Database Table

1. Create migration: `src/main/resources/db/migration/V{N}__{description}.sql`
2. Create model: `model/{Entity}.java`
3. Create repository: `repository/{Entity}Repository.java`
4. Create MyBatis mapper: `src/main/resources/mapper/{Entity}Mapper.xml`
5. Run migration: `mvn flyway:migrate`

### Debugging WebSocket Issues

**Gateway WebSocket** (`/ws/gateway`)
- Check `GatewayWSHandler` for connection lifecycle
- Verify internal token in `application.yml`
- Monitor Redis: `redis-cli SUBSCRIBE session:*`

**Client WebSocket** (`/ws/skill/stream/{sessionId}`)
- Check `SkillStreamHandler` for subscription logic
- Verify sequence numbers in `SequenceTracker`
- Check browser console for client-side errors

**Message Flow**
- Trace message from AI-Gateway → Redis → Skill Server → clients
- Verify sequence numbers are incrementing
- Check for gap detection and recovery triggers

### Running Tests

```bash
# Backend tests
cd skill-server
mvn test

# Run specific test class
mvn test -Dtest=SkillSessionServiceTest

# Run with coverage
mvn test jacoco:report
```

## Troubleshooting
| Issue | Diagnosis | Solution |
|-------|----------|
| WebSocket connection fails | Check port 8082 accessibility | Verify firewall rules, check `server.port` in application.yml |
| Messages not persisting | Check database connection | Verify MariaDB is running, check `spring.datasource.url` |
| Redis connection error | Check Redis service | Ensure Redis is running on port 6379, verify credentials |
| Sequence gaps detected | Network latency or message loss | Check `SequenceTracker` thresholds, monitor network |
| Session timeout not working | Check scheduler | Verify `@Scheduled` annotation in `SkillSessionService` |
| IM integration fails | Check API credentials | Verify `IM_API_URL` and platform-specific tokens |

## References

- **Parent Documentation:** `../AGENTS.md`
- **Spring Boot WebSocket:** https://spring.io/guides/gs/messaging-stomp-websocket/
- **MyBatis Documentation:** https://mybatis.org/mybatis-3/
- **Redis Pub/Sub:** https://redis.io/docs/interact/pubsub/
- **MariaDB JDBC:** https://mariadb.com/kb/en/about-mariadb-connector-j/
