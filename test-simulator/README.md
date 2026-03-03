# E2E Integration Test Simulator

Web UI for testing the OpenCode integration system (AI Gateway + Skill Server).

## Setup

```bash
npm install
npm run dev
```

The app will start at `http://localhost:5173`.

## Architecture

The test simulator provides a comprehensive UI for testing the e2e integration flow:

- **AgentSimulator**: Simulates an AI agent connecting to the gateway via WebSocket
- **SessionManager**: Creates and destroys sessions via skill-server API
- **StreamViewer**: Displays streaming responses in real-time
- **MetricsPanel**: Tracks messages sent/received, sequence gaps, reconnects, and latency
- **ErrorPanel**: Displays connection errors, sequence gaps, and timeout errors
- **ScenarioRunner**: Executes 6 automated test scenarios

## Test Scenarios

1. **Session Creation + Streaming**: Create session, send message, receive streaming response
2. **Multi-turn Conversation**: Send multiple messages in sequence
3. **Error Handling**: Send invalid message and verify error handling
4. **Reconnection After Disconnect**: Simulate disconnect and verify reconnection
5. **Multi-instance Routing**: Connect to 2 gateways and verify routing
6. **Sequence Gap Detection**: Simulate message loss and verify gap detection

## Configuration

Default endpoints:
- Gateway WebSocket: `ws://localhost:8080/ws/agent/{agentId}`
- Skill Server API: `http://localhost:8080/api/sessions`
- Stream WebSocket: `ws://localhost:8080/ws/skill/stream/{sessionId}`

To change endpoints, modify the component props in `App.tsx`.

## Usage

### Manual Testing

1. Start the gateway and skill-server
2. Open the test simulator in your browser
3. Create a session using the Session Manager
4. Use AgentSimulator to send messages
5. Watch the StreamViewer for responses
6. Monitor metrics and errors in real-time

### Automated Testing

1. Click "Run All Scenarios" in the ScenarioRunner
2. Watch each scenario execute automatically
3. Review results summary at the bottom

## Troubleshooting

### WebSocket Connection Failed

- Verify gateway is running on `localhost:8080`
- Check browser console for CORS errors
- Ensure WebSocket endpoint is correct

### Session Creation Failed

- Verify skill-server is running
- Check skill-server logs for errors
- Ensure Redis is running and accessible

### No Streaming Data

- Verify agent is connected to gateway
- Check session is active in skill-server
- Ensure Redis pub/sub is working correctly

## Development

### Project Structure

```
test-simulator/
├── src/
│   ├── components/
│   │   ├── AgentSimulator.tsx
│   │   ├── SessionManager.tsx
│   │   ├── StreamViewer.tsx
│   │   ├── ErrorPanel.tsx
│   │   ├── MetricsPanel.tsx
│   │   └── ScenarioRunner.tsx
│   ├── hooks/
│   │   └── useAgentWebSocket.ts
│   ├── protocol/
│   │   └── StreamAssembler.ts
│   ├── types/
│   │   └── index.ts
│   ├── App.tsx
│   └── main.tsx
├── package.json
└── README.md
```

### Adding New Scenarios

Edit `ScenarioRunner.tsx` and add to the `TEST_SCENARIOS` array:

```typescript
{
  id: 'scenario-7',
  name: 'Your Scenario Name',
  description: 'Description',
  steps: [
    { action: 'your_action', params: {} },
  ],
}
```

Then implement the action in the `executeStep` function.

## Building for Production

```bash
npm run build
npm run preview
```

The built files will be in the `dist/` directory.
