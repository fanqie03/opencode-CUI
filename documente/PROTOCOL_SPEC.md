# Unified Message Envelope Protocol Specification

**Version:** 1.0.0
**Last Updated:** 2026-03-03

## Overview

The Unified Message Envelope Protocol provides a consistent message format for cross-IDE event streaming between PC Agent, AI-Gateway, and Skill Server. It enables:

- **Protocol versioning** for backward compatibility
- **Message ordering** via sequence numbers
- **Gap detection** for reliability
- **Source identification** for multi-IDE support
- **Transparent relay** across system boundaries

## Message Structure

### Envelope Format

All protocol messages follow this structure:

```json
{
  "envelope": {
    "version": "1.0.0",
    "messageId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-03-03T10:15:30.123Z",
    "source": "OPENCODE",
    "agentId": "12345",
    "sessionId": "67890",
    "sequenceNumber": 42,
    "sequenceScope": "session"
  },
  "type": "tool_event",
  "payload": {
    // Type-specific content
  }
}
```

### Envelope Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | string | Yes | Protocol version (semver format, e.g., "1.0.0") |
| `messageId` | string | Yes | Unique message identifier (UUID v4) |
| `timestamp` | string | Yes | ISO 8601 timestamp of message creation |
| `source` | string | Yes | Source IDE: "OPENCODE", "CURSOR", or "WINDSURF" |
| `agentId` | string | Yes | Agent connection ID (assigned by gateway) |
| `sessionId` | string | No | Session identifier (for session-scoped messages) |
| `sequenceNumber` | number | Yes | Sequence number for ordering within scope |
| `sequenceScope` | string | Yes | Scope for sequencing: "session" or "agent" |

### Message Types

| Type | Direction | Description |
|------|-----------|-------------|
| `tool_event` | PC Agent → Gateway → Skill Server | OpenCode event stream delta |
| `tool_done` | PC Agent → Gateway → Skill Server | Tool execution completed |
| `tool_error` | PC Agent → Gateway → Skill Server | Tool execution error |
| `session_created` | PC Agent → Gateway → Skill Server | New session created |
| `invoke` | Skill Server → Gateway → PC Agent | Command to execute action |
| `agent_online` | Gateway → Skill Server | Agent connected |
| `agent_offline` | Gateway → Skill Server | Agent disconnected |

## Message Examples

### 1. Tool Event (OpenCode Delta)

```json
{
  "envelope": {
    "version": "1.0.0",
    "messageId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "timestamp": "2026-03-03T10:15:30.123Z",
    "source": "OPENCODE",
    "agentId": "12345",
    "sessionId": "67890",
    "sequenceNumber": 1,
    "sequenceScope": "session"
  },
  "type": "tool_event",
  "payload": {
    "event": {
      "type": "content_block_delta",
      "index": 0,
      "delta": {
        "type": "text_delta",
        "text": "Hello, "
      }
    }
  }
}
```

### 2. Tool Done

```json
{
  "envelope": {
    "version": "1.0.0",
    "messageId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "timestamp": "2026-03-03T10:15:35.456Z",
    "source": "OPENCODE",
    "agentId": "12345",
    "sessionId": "67890",
    "sequenceNumber": 15,
    "sequenceScope": "session"
  },
  "type": "tool_done",
  "payload": {
    "usage": {
      "input_tokens": 1024,
      "output_tokens": 512
    }
  }
}
```

### 3. Tool Error

```json
{
  "envelope": {
    "version": "1.0.0",
    "messageId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "timestamp": "2026-03-03T10:15:40.789Z",
    "source": "OPENCODE",
    "agentId": "12345",
    "sessionId": "67890",
    "sequenceNumber": 16,
    "sequenceScope": "session"
  },
  "type": "tool_error",
  "payload": {
    "error": "Connection timeout after 30s"
  }
}
```

### 4. Session Created

```json
{
  "envelope": {
    "version": "1.0.0",
    "messageId": "d4e5f6a7-b8c9-0123-def1-234567890123",
    "timestamp": "2026-03-03T10:15:25.000Z",
    "source": "OPENCODE",
    "agentId": "12345",
    "sessionId": null,
    "sequenceNumber": 5,
    "sequenceScope": "agent"
  },
  "type": "session_created",
  "payload": {
    "toolSessionId": "opencode-session-abc123",
    "session": {
      "id": "opencode-session-abc123",
      "created_at": "2026-03-03T10:15:25.000Z"
    }
  }
}
```

### 5. Invoke Command

```json
{
  "envelope": {
    "version": "1.0.0",
    "messageId": "e5f6a7b8-c9d0-1234-ef12-345678901234",
    "timestamp": "2026-03-03T10:15:28.000Z",
    "source": "OPENCODE",
    "agentId": "12345",
    "sessionId": "67890",
    "sequenceNumber": 2,
    "sequenceScope": "session"
  },
  "type": "invoke",
  "payload": {
    "action": "chat",
    "toolSessionId": "opencode-session-abc123",
    "text": "Write a hello world function"
  }
}
```

## Sequence Number Semantics

### Scope

- **Session-scoped**: Sequence numbers increment per session (most messages)
- **Agent-scoped**: Sequence numbers increment per agent (session-independent messages)

### Ordering Guarantees

- Sequence numbers start at 1 for each scope
- Numbers increment by 1 for each message
- Gaps indicate message loss or out-of-order delivery

### Gap Detection

| Gap Size | Action | Description |
|----------|--------|-------------|
| 0 | Continue | No gap, process normally |
| 1-5 | Log warning | Small gap, likely transient |
| 6-20 | Request recovery | Medium gap, request missing messages |
| >20 | Reconnect | Large gap, trigger full reconnection |

## Backward Compatibility

### Legacy Format Support

Messages without an `envelope` field are treated as legacy format:

```json
{
  "type": "tool_event",
  "sessionId": "67890",
  "event": {
    "type": "content_block_delta",
    "index": 0,
    "delta": {
      "type": "text_delta",
      "text": "Hello, "
    }
  }
}
```

### Migration Strategy

1. **Phase 1**: PC Agent sends enveloped messages, Gateway/Skill Server accept both formats
2. **Phase 2**: All components log warnings for legacy messages
3. **Phase 3**: Legacy format deprecated (version 2.0.0)

### Version Mismatch Handling

- **Minor version mismatch** (1.0.0 vs 1.1.0): Log warning, continue processing
- **Major version mismatch** (1.0.0 vs 2.0.0): Reject message, return error

## Implementation Notes

### TypeScript (PC Agent)

```typescript
import { ProtocolAdapter } from './ProtocolAdapter';

const adapter = new ProtocolAdapter('OPENCODE', agentId);
const envelope = adapter.wrapToolEvent(event, sessionId);
gateway.send(envelope);
```

### Java (Gateway/Skill Server)

```java
if (message.hasEnvelope()) {
    MessageEnvelope.EnvelopeMetadata env = message.getEnvelope();
    log.debug("Processing enveloped message: seq={}, source={}",
        env.getSequenceNumber(), env.getSource());
}
```

## Future Extensions

### Planned Features (v1.1.0)

- **Compression flag**: Indicate payload compression (gzip, brotli)
- **Priority field**: Message priority for QoS routing
- **Correlation ID**: Link request/response pairs

### Planned Features (v2.0.0)

- **Binary payload support**: Protobuf/MessagePack encoding
- **Multi-hop routing**: Track message path across services
- **End-to-end encryption**: Payload encryption metadata

## References

- [Phase 2 Implementation Plan](../.omc/plans/e2e-integration-testing-system.md#phase-2-protocol-specification--adaptation-2-3-days)
- [Sequence Tracker Design](../.omc/plans/e2e-integration-testing-system.md#phase-1-redis-pubsub-multi-instance-coordination-3-4-days)
- [OpenCode SDK Documentation](https://docs.opencode.dev)

---

**Changelog:**

- **1.0.0** (2026-03-03): Initial protocol specification
