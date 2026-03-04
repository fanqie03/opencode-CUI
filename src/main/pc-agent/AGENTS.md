<!-- Parent: ../../../AGENTS.md -->

# PC Agent Plugin

## Purpose

PC Agent Plugin is a bridge module running in the IM client's Electron Main Process, connecting the local OpenCode instance with the remote AI-Gateway via WebSocket. It orchestrates bidirectional event relay, authentication, connection management, and health monitoring.

## Architecture Overview

```
┌───────────────┐
│                    Electron Main Process                │
├───────────────────┤
│                  │
│  ┌───────────────────────────────┐   │
│  │           PcAgentPlugin (Orchestrator)               │   │
│  └─────────────────┘   │
│           │              │              │              │
│           ▼              ▼              ▼                    │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────┐       │
│  │ AkSkAuth     │ │ Gateway      │ │ OpenCode     │         │
│  │ (Signing)    │ │ Connection   │ │ Bridge       │         │
│  └─────────┘ └──────────────┘ └────────┘         │
│                 │           │              │
│        ┌──────┴──────────────┴──────┐            │
│               ▼                ▼            │
│            ┌───────────┐      ┌──────────────────┐   │
│            │  EventRelay      │      │ HealthChecker    │   │
│            │ (Bidirectional)  │      │ (Periodic Check) │   │
│       └──────────────────┘      └────────────────┘   │
│               │                    │
│            ┌───────┴────────┐                  │
│            ▼                ▼                       │
│      Upstream          Downstream                  │
│   (OpenCode SSE)    (Gateway Commands)            │
│                    │
└───────────────────────┘
         │                      │
         ▼             ▼
    OpenCode              AI-Gateway
  (localhost:54321)                  (ws://host:8081)
```

## Key Files

| File | Responsibility |
|------|---------|
| `PcAgentPlugin.ts` | Main entry point; orchestrates lifecycle (start/stop) and module initialization |
| `GatewayConnection.ts` | WebSocket connection manager with AK/SK auth, heartbeat, and exponential backoff reconnect |
| `OpenCodeBridge.ts` | SDK wrapper for OpenCode HTTP REST + SSE; event subscription with degradation fallback |
| `EventRelay.ts` | Bidirectional event relay: OpenCode SSE → Gateway (upstream), Gateway commands → OpenCode SDK (downstream) |
| `AkSkAuth.ts` | HMAC-SHA256 signature generation for WebSocket authentication |
| `HealthChecker.ts` | Periodic OpenCode reachability monitor; reports status changes to gateway |
| `ProtocolAdapter.ts` | Converts OpenCode events to/from unified MessageEnvelope format with sequence tracking |
| `config/AgentConfig.ts` | Configuration interface and defaults resolver |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `config/` | Configuration management (AK/SK, Gateway URL, timeouts) |
| `types/` | TypeScript type definitions (MessageEnvelope, etc.) |

## Data Flow

### Upstream: OpenCode → Gateway

1. **OpenCodeBridge** subscribes to OpenCode SSE stream via `api.event.subscribe()`
2. **EventRelay** consumes events from the subscription
3. Events are wrapped in **MessageEnvelope** (if agentId is set) by **ProtocolAdapter**
4. **GatewayConnection** sends wrapped events to the gateway as `tool_event` messages
5. If main stream is degraded (30s of heartbeat-only traffic), fallback to `api.global.event()`

### Downstream: Gateway → OpenCode

1. **GatewayConnection** receives messages from the gateway
2. **EventRelay** unwraps **MessageEnvelope** (if present) and routes by message type:
   - `invoke` → dispatch to OpenCode SDK (chat, create_session, close_session)
   - `status_query` → respond with OpenCode health status
3. **OpenCodeBridge** executes the SDK call
4. Errors are wrapped and sent back as `tool_error` messages

### Health Monitoring

1. **HealthChecker** polls `api.global.health()` at configurable interval (default 30s)
2. On status change (online ↔ offline), sends `agent_online` or `agent_offline` to gateway
3. Status is exposed via `PcAgentPlugin.getStatus()`

## AI Agent Work Guidelines

### When Working in This Directory

- **Use native SDK APIs**: Always use `@opencode-ai/sdk` for OpenCode operations; do not implement custom HTTP clients
- **Preserve event semantics**: Pass OpenCode events through unchanged; do not parse or transform them
- **Implement exponential backoff**: Reconnect delays follow 1s → 2s → 4s → 8s → 16s → 30s (capped)
- **Envelope protocol**: Message wrapping/unwrapping happens in **ProtocolAdapter** and **EventRelay**; keep concerns separated
- **Error handling**: Use the `RelayErrorHandler` callback pattern; do not crash the relay loops
- **Async patterns**: Use `async/await` and async iterators; avoid callback hell

### Testing Requirements

- **Unit tests**: AK/SK signature generation, sequence tracking, envelope wrapping/unwrapping
- **Integration tests**: OpenCode connection, event subscription, SDK method calls
- **End-to-end tests**: Complete message flow from OpenCode event to gateway and back

### Common Patterns

**Upstream flow:**
```
OpenCode SSE → EventRelay.runUpstreamLoop() → ProtocolAdapter.wrapToolEvent() → GatewayConnection.send()
```

**Downstream flow:**
```
GatewayConnection.onMessage() → EventRelay.handleDownstreamMessage() → ProtocolAdapter.unwrapEvent() → OpenCodeBridge.chat()
```

**Health check:**
```
HealthChecker.performCheck() → OpenCodeBridge.healthCheck() → api.global.health()
```

## Dependencies

### Internal Dependencies

- Connects to AI-Gateway WebSocket endpoint (port 8081, path `/ws/agent`)
- Uses AK/SK authentication (HMAC-SHA256 signatures)
- Communicates with local OpenCode instance (default `http://localhost:54321`)

### External Dependencies

| Package | Version | Purpose |
|---------|-----|---------|
| `@opencode-ai/sdk` | ^1.2 | OpenCode HTTP REST + SSE client |
| `ws` | ^8 | WebSocket client library |
| `@types/ws` | ^8 | TypeScript types for ws |
| `@types/node` | ^20 | Node.js built-in types |
| `typescript` | ^5 | TypeScript compiler |

## Configuration

All configuration is resolved via `AgentConfig` interface with sensible defaults:

```typescript
interface AgentConfig {
  ak: string;             // Access Key (required)
  sk: string;                 // Secret Key (required)
  gatewayUrl: string;         // WebSocket URL (required)
  opencodeBaseUrl: string;         // Default: http://localhost:54321
  heartbeatIntervalMs: number;     // Default: 30000
  healthCheckIntervalMs: number;   // Default: 30000
  reconnectBaseMs: number;      // Default: 1000
  reconnectMaxMs: number;          // Default: 30000
}
```

## Lifecycle

### Startup Sequence

1. Generate AK/SK auth parameters via `AkSkAuth.sign()`
2. Create `GatewayConnection` and connect with auth params
3. Initialize `OpenCodeBridge` with local OpenCode URL
4. Create `EventRelay` and start upstream/downstream loops
5. Start `HealthChecker` for periodic health monitoring
6. Send `register` message to gateway with device info

### Shutdown Sequence

1. Stop `HealthChecker`
2. Stop `EventRelay` (upstream and downstream)
3. Close `GatewayConnection`
4. Discard references to all modules

## Message Types

### Upstream (OpenCode → Gateway)

- `tool_event`: OpenCode event wrapped in envelope
- `tool_error`: Error from OpenCode SDK call
- `session_created`: Response to `create_session` invoke
- `status_response`: Response to `status_query`
- `agent_online` / `agent_offline`: Health status change

### Downstream (Gateway → OpenCode)

- `invoke`: Execute OpenCode SDK action (chat, create_session, close_session)
- `status_query`: Request current OpenCode health status

## Error Handling

- **Connection errors**: Logged and trigger exponential backoff reconnect
- **Event stream errors**: Reported via `RelayErrorHandler`; relay loops continue
- **SDK call errors**: Wrapped as `tool_error` and sent to gateway
- **Health check errors**: Treated as offline status

## Performance Considerations

- **Heartbeat interval**: 30s default; tune based on gateway requirements
- **Health check interval**: 30s default; balance between responsiveness and load
- **Degradation threshold**: 30s default; time before fallback to global event stream
- **Reconnect backoff**: Exponential with 30s cap; prevents thundering herd on gateway restart
