<!-- Parent: ../AGENTS.md -->

# AI-Gateway

## Purpose

AI-Gateway is the connection management layer that handles PCAgent WebSocket connections, AK/SK authentication, and bidirectional event relay between PCAgent clients and the Skill Server. It operates as a pure pass-through service—all AI tool outputs are relayed transparently without parsing or modification.

## Key Files

| File | Purpose |
|------|---------|
| `pom.xml` | Maven configuration: Spring Boot 3.4.6, Java 21, MyBatis, Redis |
| `src/main/resources/application.yml` | Service configuration: port 8081, database, Redis, Skill Server endpoint |
| `src/main/resources/db/migration/V1__gateway.sql` | Database schema: `agent_connection` table for tracking active agents |

## Directory Structure

```
src/main/java/com/yourapp/gateway/
├── GatewayApplication.java          # Spring Boot entry point
├── config/
│   ├── GatewayConfig.java           # WebSocket configuration
│   └── RedisConfig.java             # Redis connection setup
├── controller/
│   └── AgentController.java      # REST endpoints for agent queries
├── model/
│   ├── AgentConnection.java         # Agent connection entity
│   ├── GatewayMessage.java          # Message protocol (see below)
│   └── MessageEnvelope.java      # Envelope metadata for protocol evolution
├── repository/
│   └── AgentConnectionRepository.java # MyBatis mapper for agent_connection table
├── service/
│   ├── AgentRegistryService.java    # Agent lifecycle: register, heartbeat, offline
│   ├── AkSkAuthService.java         # HMAC-SHA256 signature verification
│   ├── EventRelayService.java       # Bidirectional relay between PCAgent and Skill Server
│   └── RedisMessageBroker.java      # Redis pub/sub for multi-instance coordination
└── ws/
    ├── AgentWebSocketHandler.java   # PCAgent WebSocket handler
    └── SkillServerWSClient.java     # Skill Server WebSocket client
```

## Message Protocol

### Message Types

**Upstream (PCAgent → Gateway):**
- `register` - Agent device registration with metadata
- `heartbeat` - Keep-alive signal
- `tool_event` - OpenCode tool event (transparent relay)
- `tool_done` - Tool execution completed with token usage
- `tool_error` - Tool execution error
- `session_created` - Session created on OpenCode side

**Downstream (Gateway → PCAgent):**
- `invoke` - Skill Server requesting action (chat, create_session, close_session)
- `status_query` - Query agent status

**Internal (Gateway ↔ Skill Server):**
- `agent_online` - Agent came online
- `agent_offline` - Agent went offline
- All upstream message types are relayed with `agentId` attached

### Message Structure

```json
{
  "type": "tool_event",
  "agentId": "123",
  "sessionId": "sess-456",
  "event": { /* OpenCode raw event - transparent */ },
  "sequenceNumber": 1,
  "envelope": {
    "version": "1.0.0",
    "messageId": "msg-789",
    "timestamp": "2025-03-04T10:30:00Z",
    "source": "PCAGENT",
    "agentId": "123",
    "sessionId": "sess-456",
    "sequenceNumber": 1,
    "sequenceScope": "agent"
  }
}
```

## Authentication (AK/SK)

### Signature Verification

Located in `AkSkAuthService.java`:

1. **Algorithm**: HMAC-SHA256
2. **Message format**: `{AK}\n{timestamp}\n{nonce}`
3. **Signature**: Base64-encoded HMAC result
4. **Timestamp window**: ±5 minutes (configurable)
5. **Nonce replay prevention**: Redis SET NX with 5-minute TTL

### WebSocket Handshake

Query parameters required:
- `ak` - Access Key
- `ts` - Unix timestamp (seconds)
- `nonce` - Random string for replay prevention
- `sign` - Base64-encoded signature

Example:
```
ws://localhost:8081/ws/agent?ak=test-ak-001&ts=1709529000&nonce=abc123&sign=base64_signature
```

### Development Testing

Test credentials (hardcoded in `AkSkAuthService.lookupByAk()`):
- AK: `test-ak-001`
- SK: `test-sk-secret-001`
- User ID: `1`

**TODO**: Replace with database-backed AK/SK lookup.

## Agent Lifecycle

### Registration

1. PCAgent connects with valid AK/SK signature
2. Sends `register` message with device metadata
3. `AgentRegistryService.register()` creates `agent_connection` record
4. If same AK + toolType already online, old connection is kicked
5. `agent_online` message sent to Skill Server
6. Agent session registered in `EventRelayService` for relay routing

### Heartbeat

- PCAgent sends `heartbeat` message periodically
- `AgentRegistryService.heartbeat()` updates `last_seen_at` timestamp
- Scheduled task (`checkTimeouts()`) runs every 30 seconds
- Agents with no heartbeat for 90 seconds marked offline

### Offline Transition

Triggered by:
1. WebSocket close (normal or abnormal)
2. Heartbeat timeout (90 seconds)
3. New connection with same AK + toolType

Actions:
1. Mark agent offline in database
2. Close WebSocket session
3. Remove from `EventRelayService` relay map
4. Send `agent_offline` message to Skill Server

## Multi-Instance Coordination

### Redis Pub/Sub Channels

**Channel patterns:**
- `agent:{agentId}` - Messages to specific agent (routed by any instance with active connection)
- `session:{sessionId}` - Messages to specific session (with sequence tracking)

### Flow

1. **PCAgent → Skill Server**: Direct relay via `SkillServerWSClient`
2. **Skill Server → PCAgent**:
   - `SkillServerWSClient` receives `invoke` message
   - Publishes to Redis channel `agent:{agentId}`
   - Instance with active connection receives and forwards to PCAgent
   - Enables load balancing across multiple Gateway instances

### Sequence Tracking

- Each session maintains sequence number (incremented per message)
- Sequence numbers included in published messages for ordering
- Tracked in `RedisMessageBroker.sessionSequences` map

## Configuration

### application.yml

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mariadb://localhost:3306/ai_gateway
    username: root
    password: ${DB_PASSWORD:root}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

gateway:
  skill-server:
    ws-url: ${SKILL_SERVER_WS_URL:ws://localhost:8082/ws/internal/gateway}
    internal-token: ${SKILL_SERVER_INTERNAL_TOKEN:changeme}
    reconnect-initial-delay-ms: 1000
    reconnect-max-delay-ms: 30000
  agent:
    heartbeat-timeout-seconds: 90
    heartbeat-check-interval-seconds: 30
  auth:
    timestamp-tolerance-seconds: 300
    nonce-ttl-seconds: 300
```

### Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_PASSWORD` | `root` | MariaDB password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | (empty) | Redis password |
| `SKILL_SERVER_WS_URL` | `ws://localhost:8082/ws/internal/gateway` | Skill Server WebSocket endpoint |
| `SKILL_SERVER_INTERNAL_TOKEN` | `changeme` | Internal token for Skill Server authentication |

## AI Agent Work Guidelines

### When Working in This Directory

**Message Relay Principles:**
- All WebSocket messages are pass-through—do not add parsing or transformation logic
- Preserve message structure and content exactly as received
- Only attach `agentId` when relaying to Skill Server
- Only attach `sequenceNumber` when publishing to Redis

**Key Service Responsibilities:**
- `AkSkAuthService`: HMAC-SHA256 signature verification with replay prevention
- `AgentRegistryService`: Agent lifecycle management and heartbeat tracking
- `EventRelayService`: Bidirectional relay routing and session management
- `RedisMessageBroker`: Multi-instance pub/sub coordination
- `SkillServerWSClient`: Skill Server connection with exponential backoff reconnection

**Connection Management:**
- One active connection per AK + toolType (old connections are kicked)
- Heartbeat timeout: 90 seconds
- Skill Server reconnection: exponential backoff (1s → 2s → 4s → ... → 30s max)
- Stop reconnecting on authentication failure (invalid internal token)

### Testing Requirements

**Unit Tests:**
- AK/SK signature verification (valid/invalid signatures, timestamp window, nonce replay)
- Message serialization/deserialization
- Agent registration and status transitions

**Integration Tests:**
- WebSocket connection with valid/invalid credentials
- Message relay from PCAgent to Skill Server
- Message relay from Skill Server to PCAgent
- Heartbeat timeout and offline marking
- Multi-instance Redis pub/sub coordination

**Load/Stress Tests:**
- Multiple concurrent PCAgent connections
- High-frequency message relay
- Skill Server reconnection under network failures

### Common Patterns

**Message Routing:**
```java
// Attach agentId for Skill Server routing
GatewayMessage forwarded = message.withAgentId(agentId);
eventRelayService.relayToSkillServer(agentId, forwarded);

// Publish to Redis for multi-instance routing
redisMessageBroker.publishToAgent(agentId, message);
```

**Session Management:**
```java
// Register on connect
eventRelayService.registerAgentSession(agentId, session);
// Remove on disconnect
eventRelayService.removeAgentSession(agentId);
```

**Heartbeat Tracking:**
```java
// Update on heartbeat
agentRegistryService.heartbeat(agentId);

// Check for timeouts (scheduled)
agentRegistryService.checkTimeouts();
```

## Dependencies

### Internal Dependencies
- Connects to Skill Server WebSocket endpoint (port 8082)
- Uses Redis Pub/Sub for multi-instance coordination
- Queries MariaDB for agent connection state

### External Dependencies
- **Spring Boot 3.4.6**: Web framework and WebSocket support
- **MyBatis 3.0.4**: Database access layer
- **MariaDB JDBC 3.x**: Database driver
- **Spring Data Redis**: Redis client (Lettuce)
- **Java-WebSocket 1.5.6**: Skill Server client connection
- **Jackson**: JSON serialization/deserialization
- **Lombok**: Code generation (getters, setters, builders)

## Database Schema

### agent_connection Table

```sql
CREATE TABLE agent_connection (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  ak_id           VARCHAR(64) NOT NULL,
  device_name     VARCHAR(100),
  os      VARCHAR(50),
  tool_type     VARCHAR(50) NOT NULL DEFAULT 'OPENCODE',
  tool_version    VARCHAR(50),
  status          ENUM('ONLINE','OFFLINE') DEFAULT 'OFFLINE',
  last_seen_at    DATETIME,
  created_at      DATETIME NOT NULL,
  INDEX idx_user (user_id),
  INDEX idx_ak (ak_id),
  INDEX idx_status (status)
);
```

**Indexes:**
- `idx_user`: Query agents by user
- `idx_ak`: Query agents by access key
- `idx_status`: Query online/offline agents

## Troubleshooting

### Agent Not Connecting

1. Verify AK/SK signature is correct (check `AkSkAuthService` logs)
2. Verify timestamp is within ±5 minutes of server time
3. Verify nonce is unique (not replayed)
4. Check WebSocket endpoint URL and port 8081 is accessible

### Messages Not Relaying

1. Verify Skill Server connection is open (check `SkillServerWSClient` logs)
2. Verify agent is registered (check `agent_connection` table status = ONLINE)
3. Verify Redis is accessible and pub/sub channels are working
4. Check message type is recognized (register, heartbeat, tool_event, etc.)

### Heartbeat Timeout

1. Verify PCAgent is sending heartbeat messages periodically
2. Check `heartbeat-timeout-seconds` configuration (default 90s)
3. Check `heartbeat-check-interval-seconds` configuration (default 30s)
4. Verify database connectivity for updating `last_seen_at`

### Skill Server Reconnection Loop

1. Verify `SKILL_SERVER_INTERNAL_TOKEN` matches Skill Server configuration
2. Check Skill Server is running and WebSocket endpoint is accessible
3. Verify network connectivity between Gateway and Skill Server
4. Check logs for "invalid internal token" reason (stops reconnection)
