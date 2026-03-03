# Test Simulator - Real WebSocket Integration

This test simulator now includes real WebSocket integration for E2E testing of the gateway and skill-server.

## Architecture

### Components

1. **WebSocketClient.ts** - WebSocket client implementations
   - `BaseWebSocketClient`: Base class with reconnection logic
   - `GatewayWebSocketClient`: Gateway-specific client for agent connections
   - `SkillWebSocketClient`: Skill-server client for streaming responses

2. **APIClient.ts** - HTTP REST API client
   - Session management (create, get, delete, list)
   - Converts WebSocket URLs to HTTP for REST endpoints

3. **config.ts** - Configuration management
   - Environment variable support via Vite
   - Default values for local development

4. **ScenarioRunner.tsx** - Test execution engine
   - Executes 6 E2E test scenarios
   - Real WebSocket connections and API calls
   - Metrics tracking and error reporting

5. **useAgentWebSocket.ts** - React hook for WebSocket
   - Simplified WebSocket integration for React components
   - Uses GatewayWebSocketClient internally

## Test Scenarios

### Scenario 1: Session Creation + Streaming
- Creates session via REST API
- Connects to gateway WebSocket
- Sends message and waits for streaming response

### Scenario 2: Multi-turn Conversation
- Creates session
- Sends multiple messages in sequence
- Verifies streaming responses for each

### Scenario 3: Error Handling
- Creates session
- Sends invalid/malformed message
- Verifies error is received and handled

### Scenario 4: Reconnection After Disconnect
- Creates session
- Disconnects WebSocket
- Verifies automatic reconnection

### Scenario 5: Multi-instance Routing
- Connects to 2 gateway instances (configurable URLs)
- Creates session
- Verifies routing works across instances

### Scenario 6: Sequence Gap Detection
- Creates session
- Simulates message loss by skipping sequence numbers
- Verifies gap detection logic

## Configuration

Copy `.env.example` to `.env.local` and configure:

```bash
# Gateway URLs
VITE_GATEWAY_URL=ws://localhost:8080
VITE_GATEWAY_URL_2=ws://localhost:8081

# Skill Server URL
VITE_SKILL_SERVER_URL=ws://localhost:9090

# Agent ID
VITE_AGENT_ID=test-agent-001

# Connection settings
VITE_RECONNECT_DELAY=1000
VITE_MAX_RECONNECT_ATTEMPTS=5
VITE_MESSAGE_TIMEOUT=30000
```

## Usage

1. Start the gateway and skill-server:
```bash
# Terminal 1: Start gateway
cd ai-gateway
./mvnw spring-boot:run

# Terminal 2: Start skill-server
cd skill-server
./mvnw spring-boot:run
```

2. Start the test simulator:
```bash
cd test-simulator
npm run dev
```

3. Open browser to `http://localhost:5173`

4. Click "Run All Scenarios" or run individual scenarios

## Implementation Details

### WebSocket Connection Flow

1. **Connection**: `GatewayWebSocketClient.connect()` establishes WebSocket to `/ws/agent/{agentId}`
2. **Message Sending**: `sendMessage(envelope)` sends MessageEnvelope as JSON
3. **Message Receiving**: `onMessage` handler parses incoming MessageEnvelope
4. **Reconnection**: Automatic exponential backoff on disconnect (unless manual)

### Session Management

1. **Create Session**: POST `/api/sessions` with `{ agentId, metadata }`
2. **Get Session**: GET `/api/sessions/{sessionId}`
3. **Delete Session**: DELETE `/api/sessions/{sessionId}`
4. **List Sessions**: GET `/api/sessions?agentId={agentId}`

### Error Handling

- Connection failures trigger `onError` callback
- Parse errors are logged and reported via metrics
- Timeout errors fail the test step
- All errors include detailed messages for debugging

## Metrics Tracked

- `messagesSent`: Total messages sent
- `messagesReceived`: Total messages received
- `sequenceGaps`: Detected sequence number gaps
- `reconnectCount`: Number of reconnections
- `latencies`: Array of message latencies (future)

## Next Steps

- Add latency tracking for performance testing
- Implement load testing with concurrent connections
- Add message validation and schema checking
- Integrate with CI/CD for automated E2E testing
