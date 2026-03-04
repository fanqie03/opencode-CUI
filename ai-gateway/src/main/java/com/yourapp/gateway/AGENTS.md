<!-- Parent: ../../../../../../AGENTS.md -->

# AI-Gateway Java Source Code

## Purpose

AI-Gateway is a Spring Boot application that acts as a bidirectional relay between PCAgent (client-side tool) and Skill Server (backend service). The gateway handles WebSocket connections, authentication, agent lifecycle management, and transparent message routing. Core responsibility: pass through all messages without parsing AI tool outputs.

## Architecture

Layered design with clear separation of concerns:

- **WebSocket Layer** (`ws/`): Handles PCAgent connections and Skill Server communication
- **Service Layer** (`service/`): Business logic for authentication, agent registry, event relay, and pub/sub
- **Model Layer** (`model/`): Data structures for messages and agent connections
- **Repository Layer** (`repository/`): MyBatis-based database access
- **Config Layer** (`config/`): Spring configuration for WebSocket and Redis
- **Controller Layer** (`controller/`): REST API for agent status queries

## Directory Structure

| Directory | Purpose |
|---------|---------|
| `config/` | Spring configuration: WebSocket handler registration, Redis connection setup |
| `ws/` | WebSocket handlers: `AgentWebSocketHandler` (PCAgent connections), `SkillServerWSClient` (Skill Server client) |
| `service/` | Business logic: `AkSkAuthService` (HMAC-SHA256 auth), `AgentRegistryService` (lifecycle), `EventRelayService` (message routing), `RedisMessageBroker` (pub/sub) |
| `model/` | Data models: `AgentConnection`, `GatewayMessage`, `MessageEnvelope` |
| `repository/` | MyBatis mappers: `AgentConnectionRepository` (agent_connection table) |
| `controller/` | REST endpoints: `AgentController` (status queries) |

## Key Components

### WebSocket Handlers

**AgentWebSocketHandler**
- Implements both `TextWebSocketHandler` and `HandshakeInterceptor`
- Handshake: validates AK/SK signature from query parameters (`ak`, `ts`, `nonce`, `sign`)
- Message types handled:
  - `register`: registers agent, notifies Skill Server `agent_online`
  - `heartbeat`: updates `last_seen_at` timestamp
  - `tool_event`, `tool_done`, `tool_error`, `session_created`: relayed to Skill Server
- On disconnect: marks agent offline, notifies Skill Server `agent_offline`
- Maintains `sessionId -> agentId` mapping for cleanup

**SkillServerWSClient**
- Outbound WebSocket client connecting to Skill Server
- URL: `ws://{skill-host}/ws/internal/gateway?token={internal_token}`
- Reconnection: exponential backoff (1s → 2s → 4s → ... → 30s max)
- Handles `invoke` messages from Skill Server, routes to appropriate PCAgent via `EventRelayService`
- Implements `EventRelayService.SkillServerRelayTarget` interface to break circular dependency

### Services

**AkSkAuthService**
- Signature verification: HMAC-SHA256(`SK`, `"{AK}\n{timestamp}\n{nonce}"`)
- Timestamp validation: ±5 minutes window (configurable)
- Nonce replay prevention: Redis SET NX with 5-minute TTL
- Returns `userId` on success, `null` on failure
- Configuration:
  - `gateway.auth.timestamp-tolerance-seconds` (default: 300)
  - `gateway.auth.nonce-ttl-seconds` (default: 300)

**AgentRegistryService**
- Agent lifecycle: register, heartbeat, mark offline
- Single active connection per AK + toolType: kicks old connection on new register
- Scheduled task: checks for stale agents (no heartbeat for 90 seconds) and marks offline
- Configuration:
  - `gateway.agent.heartbeat-timeout-seconds` (default: 90)
  - `gateway.agent.heartbeat-check-interval-seconds` (default: 30)

**EventRelayService**
- Maintains local map: `agentId -> WebSocketSession` (connected agents only)
- Subscribes to Redis channel `agent:{agentId}` on agent connect
- Routes messages:
  - PCAgent → Skill Server: `relayToSkillServer(agentId, message)`
  - Skill Server → PCAgent: `relayToAgent(agentId, message)` (publishes to Redis)
- Multi-instance coordination: any gateway instance with active agent connection receives and forwards
- Implements callback interface `SkillServerRelayTarget` for Skill Server relay

**RedisMessageBroker**
- Pub/Sub for multi-instance message routing
- Channels: `agent:{agentId}` for agent-specific messages
- Subscription handler: forwards received messages to local agent via `EventRelayService.sendToLocalAgent()`

### Models

**GatewayMessage**
- Unified message protocol with optional envelope
- Message types:
  - Upstream (PCAgent → Gateway): `register`, `heartbeat`, `tool_event`, `tool_done`, `tool_error`, `session_created`
  - Downstream (Gateway → PCAgent): `invoke`, `status_query`
  - Internal (Gateway ↔ Skill Server): `agent_online`, `agent_offline`
- Factory methods for each message type
- Supports `withAgentId()` and `withSequenceNumber()` for message enrichment

**AgentConnection**
- Database model for agent_connection table
- Fields: `id`, `userId`, `akId`, `deviceName`, `os`, `toolType`, `toolVersion`, `status`, `lastSeenAt`, `createdAt`
- Status enum: `ONLINE`, `OFFLINE`

**MessageEnvelope**
- Optional metadata wrapper for protocol evolution
- Fields: `version`, `messageId`, `timestamp`, `source`, `agentId`, `sessionId`, `sequenceNumber`, `sequenceScope`

## Message Flow

### Agent Registration
```
PCAgent --register--> Gateway --agent_online--> Skill Server
           |
                        v
                   Database (insert)
                   Redis subscribe
```

### Tool Event Relay
```
PCAgent --tool_event--> Gateway --tool_event--> Skill Server
           |
                           v
             Redis publish (if multi-instance)
```

### Skill Server Invoke
```
Skill Server --invoke--> Gateway --invoke--> PCAgent
                          |
                         v
                  Redis publish (if multi-instance)
```

### Agent Disconnect
```
PCAgent (disconnect) --> Gateway --agent_offline--> Skill Server
             |
                           v
                      Database (mark offline)
              Redis unsubscribe
```

## AI Agent Work Guidelines

### When Working in This Directory

1. **Message Transparency**: All messages are pass-through. Do not add parsing logic for AI tool outputs. The gateway is a relay, not an interpreter.

2. **Authentication**: AK/SK uses HMAC-SHA256 signature. Verify in `AkSkAuthService.verify()`:
   - Extract `ak`, `ts`, `nonce`, `sign` from WebSocket handshake query parameters
   - Validate timestamp window (±5 minutes)
   - Check nonce replay via Redis
   - Compute expected signature and compare (constant-time)

3. **Multi-Instance Coordination**: Redis channels `agent:{agentId}` route messages across instances:
   - PCAgent connects to any gateway instance
   - Skill Server publishes to Redis channel
   - Instance with active connection receives and forwards to PCAgent

4. **Heartbeat & Timeout**:
   - PCAgent sends `heartbeat` message periodically
   - Gateway updates `last_seen_at` in database
   - Scheduled task marks agents offline after 90 seconds without heartbeat

5. **Connection Lifecycle**:
   - Register: creates database record, subscribes to Redis channel, notifies Skill Server
   - Heartbeat: updates timestamp
   - Disconnect: marks offline, unsubscribes from Redis, notifies Skill Server

### Code Standards

- Use Lombok annotations (`@Data`, `@Slf4j`, `@Service`, etc.)
- WebSocket exceptions: catch and log, do not propagate
- Logging: use SLF4J with appropriate levels (info for lifecycle, debug for relay, warn for errors)
- All Service methods: add try-catch with logging
- Transactional operations: use `@Transactional` on methods that modify database
- Thread safety: use `ConcurrentHashMap` for shared mutable state

### Testing Requirements

- **WebSocket Integration**: `@SpringBootTest` + `WebSocketStompClient` for end-to-end handshake and message flow
- **Service Unit Tests**: `@SpringBootTest` with mocked dependencies
- **Repository Tests**: `@MybatisTest` for database operations
- **Auth Tests**: verify signature validation, timestamp window, nonce replay prevention
- **Relay Tests**: verify message routing between PCAgent and Skill Server

## Dependencies

- **WebSocket Layer** depends on: Service (auth, registry, relay)
- **Service Layer** depends on: Repository, Redis, ObjectMapper
- **EventRelayService** coordinates: PCAgent WebSocket sessions, Skill Server relay, Redis pub/sub
- **SkillServerWSClient** implements: `EventRelayService.SkillServerRelayTarget` (callback interface)

## Configuration Properties

```properties
# WebSocket
gateway.ws.path=/ws/agent

# Authentication
gateway.auth.timestamp-tolerance-seconds=300
gateway.auth.nonce-ttl-seconds=300

# Agent Lifecycle
gateway.agent.heartbeat-timeout-seconds=90
gateway.agent.heartbeat-check-interval-seconds=30

# Skill Server Connection
gateway.skill-server.ws-url=ws://localhost:8082/ws/internal/gateway
gateway.skill-server.internal-token=changeme
gateway.skill-server.reconnect-initial-delay-ms=1000
gateway.skill-server.reconnect-max-delay-ms=30000

# Redis
spring.redis.host=localhost
spring.redis.port=6379
```

## Common Tasks

### Adding a New Message Type
1. Add type constant to `GatewayMessage`
2. Add factory method in `GatewayMessage`
3. Add case in `AgentWebSocketHandler.handleTextMessage()` switch statement
4. Add relay logic in `EventRelayService` if needed

### Debugging Message Flow
1. Enable debug logging: `logging.level.com.yourapp.gateway=DEBUG`
2. Check Redis channels: `redis-cli SUBSCRIBE agent:*`
3. Verify database: `SELECT * FROM agent_connection WHERE id = ?`
4. Monitor WebSocket: browser DevTools Network tab

### Handling Multi-Instance Scenarios
1. Ensure Redis is accessible from all gateway instances
2. Verify `agent:{agentId}` channel subscription in `EventRelayService.registerAgentSession()`
3. Check `RedisMessageBroker` subscription handler for message delivery
4. Test with multiple gateway instances connecting to same Redis

