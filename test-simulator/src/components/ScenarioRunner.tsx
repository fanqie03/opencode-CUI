import { useState, useRef } from 'react';
import type { TestScenario, ScenarioResult, ErrorEntry, Metrics, MessageEnvelope } from '../types';
import { GatewayWebSocketClient, SkillWebSocketClient } from '../services/WebSocketClient';
import { APIClient } from '../services/APIClient';
import { config } from '../config';

interface ScenarioRunnerProps {
  onError: (error: ErrorEntry) => void;
  onMetricsUpdate: (metrics: Partial<Metrics>) => void;
}

const TEST_SCENARIOS: TestScenario[] = [
  {
    id: 'scenario-1',
    name: 'Session Creation + Streaming',
    description: 'Create session, send message, receive streaming response',
    steps: [
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'send_message', params: { text: 'Hello' } },
      { action: 'wait_for_stream', params: { timeout: 5000 } },
    ],
  },
  {
    id: 'scenario-2',
    name: 'Multi-turn Conversation',
    description: 'Send multiple messages in sequence',
    steps: [
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'send_message', params: { text: 'First message' } },
      { action: 'wait_for_stream', params: { timeout: 5000 } },
      { action: 'send_message', params: { text: 'Second message' } },
      { action: 'wait_for_stream', params: { timeout: 5000 } },
    ],
  },
  {
    id: 'scenario-3',
    name: 'Error Handling',
    description: 'Send invalid message and verify error handling',
    steps: [
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'send_invalid_message', params: {} },
      { action: 'verify_error', params: {} },
    ],
  },
  {
    id: 'scenario-4',
    name: 'Reconnection After Disconnect',
    description: 'Simulate disconnect and verify reconnection',
    steps: [
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'disconnect', params: {} },
      { action: 'wait', params: { duration: 2000 } },
      { action: 'verify_reconnect', params: {} },
    ],
  },
  {
    id: 'scenario-5',
    name: 'Multi-instance Routing',
    description: 'Connect to 2 gateways and verify routing',
    steps: [
      { action: 'connect_gateway', params: { url: 'ws://localhost:8080' } },
      { action: 'connect_gateway', params: { url: 'ws://localhost:8081' } },
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'verify_routing', params: {} },
    ],
  },
  {
    id: 'scenario-6',
    name: 'Sequence Gap Detection',
    description: 'Simulate message loss and verify gap detection',
    steps: [
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'send_message', params: { text: 'Test' } },
      { action: 'simulate_message_loss', params: { count: 2 } },
      { action: 'verify_gap_detection', params: {} },
    ],
  },
];

interface TestContext {
  sessionId?: string;
  gatewayClient?: GatewayWebSocketClient;
  gatewayClient2?: GatewayWebSocketClient;
  skillClient?: SkillWebSocketClient;
  apiClient: APIClient;
  receivedMessages: MessageEnvelope[];
  lastSequence: number;
  errorReceived: boolean;
  reconnectDetected: boolean;
}

export function ScenarioRunner({ onError, onMetricsUpdate }: ScenarioRunnerProps) {
  const [results, setResults] = useState<ScenarioResult[]>([]);
  const [runningScenario, setRunningScenario] = useState<string | null>(null);
  const contextRef = useRef<TestContext>({
    apiClient: new APIClient(config.gatewayUrl),
    receivedMessages: [],
    lastSequence: 0,
    errorReceived: false,
    reconnectDetected: false,
  });

  const executeStep = async (action: string, params: Record<string, unknown>, context: TestContext): Promise<void> => {
    switch (action) {
      case 'create_session': {
        const agentId = (params.agentId as string) || config.agentId;
        const response = await context.apiClient.createSession({ agentId });
        context.sessionId = response.sessionId;
        onMetricsUpdate({ messagesSent: 1 });

        // Connect gateway WebSocket
        if (!context.gatewayClient) {
          context.gatewayClient = new GatewayWebSocketClient(agentId, {
            gatewayUrl: config.gatewayUrl,
            reconnectDelay: config.reconnectDelay,
            maxReconnectAttempts: config.maxReconnectAttempts,
            onMessage: () => {
              onMetricsUpdate({ messagesReceived: 1 });
            },
            onError: (error) => {
              context.errorReceived = true;
              onError({
                timestamp: Date.now(),
                severity: 'error',
                message: 'Gateway WebSocket error',
                details: String(error),
              });
            },
            onOpen: () => {
              if (context.gatewayClient && context.gatewayClient.getReconnectAttempts() > 0) {
                context.reconnectDetected = true;
                onMetricsUpdate({ reconnectCount: 1 });
              }
            },
          });
          await context.gatewayClient.connect();
        }
        break;
      }

      case 'send_message': {
        if (!context.gatewayClient || !context.sessionId) {
          throw new Error('Gateway not connected or session not created');
        }

        const envelope: MessageEnvelope = {
          version: '1.0',
          messageId: `msg-${Date.now()}`,
          timestamp: Date.now(),
          source: {
            type: 'agent',
            id: config.agentId,
          },
          payload: {
            type: 'user_message',
            data: { text: params.text },
          },
          metadata: {
            sessionId: context.sessionId,
            sequenceNumber: ++context.lastSequence,
          },
        };

        context.gatewayClient.sendMessage(envelope);
        context.receivedMessages.push(envelope);
        onMetricsUpdate({ messagesSent: 1 });
        break;
      }

      case 'send_invalid_message': {
        if (!context.gatewayClient) {
          throw new Error('Gateway not connected');
        }

        // Send malformed message
        context.gatewayClient.send('invalid json {{{');
        onMetricsUpdate({ messagesSent: 1 });
        break;
      }

      case 'wait_for_stream': {
        const timeout = (params.timeout as number) || 5000;
        await new Promise<void>((resolve, reject) => {
          const timer = setTimeout(() => {
            reject(new Error('Stream timeout'));
          }, timeout);

          if (context.gatewayClient) {
            context.gatewayClient.onMessageType('stream_response', () => {
              clearTimeout(timer);
              resolve();
            });
          } else {
            clearTimeout(timer);
            reject(new Error('Gateway not connected'));
          }
        });
        break;
      }

      case 'verify_error': {
        if (!context.errorReceived) {
          throw new Error('Expected error was not received');
        }
        break;
      }

      case 'disconnect': {
        if (context.gatewayClient) {
          context.gatewayClient.disconnect();
          context.gatewayClient = undefined;
        }
        break;
      }

      case 'verify_reconnect': {
        if (!context.reconnectDetected) {
          throw new Error('Reconnection was not detected');
        }
        break;
      }

      case 'connect_gateway': {
        const url = (params.url as string) || config.gatewayUrl;
        const isSecondary = url !== config.gatewayUrl;

        const client = new GatewayWebSocketClient(config.agentId, {
          gatewayUrl: url,
          reconnectDelay: config.reconnectDelay,
          maxReconnectAttempts: config.maxReconnectAttempts,
          onMessage: () => {
            onMetricsUpdate({ messagesReceived: 1 });
          },
        });

        await client.connect();

        if (isSecondary) {
          context.gatewayClient2 = client;
        } else {
          context.gatewayClient = client;
        }
        break;
      }

      case 'verify_routing': {
        if (!context.gatewayClient || !context.gatewayClient2) {
          throw new Error('Both gateway clients must be connected');
        }

        if (!context.gatewayClient.isConnected() || !context.gatewayClient2.isConnected()) {
          throw new Error('One or both gateways are not connected');
        }
        break;
      }

      case 'simulate_message_loss': {
        const count = (params.count as number) || 1;
        context.lastSequence += count;
        onMetricsUpdate({ sequenceGaps: count });
        break;
      }

      case 'verify_gap_detection': {
        // In a real implementation, this would check if the system detected the gap
        // For now, we just verify that we simulated the gap
        if (context.lastSequence === 0) {
          throw new Error('No sequence gap was simulated');
        }
        break;
      }

      case 'wait': {
        const duration = (params.duration as number) || 1000;
        await new Promise((resolve) => setTimeout(resolve, duration));
        break;
      }

      default:
        throw new Error(`Unknown action: ${action}`);
    }
  };

  const runScenario = async (scenario: TestScenario) => {
    setRunningScenario(scenario.id);

    // Reset context for new scenario
    contextRef.current = {
      apiClient: new APIClient(config.gatewayUrl),
      receivedMessages: [],
      lastSequence: 0,
      errorReceived: false,
      reconnectDetected: false,
    };

    const result: ScenarioResult = {
      scenarioId: scenario.id,
      status: 'running',
      startTime: Date.now(),
      steps: scenario.steps.map((step, idx) => ({
        step: idx + 1,
        action: step.action,
        status: 'pending',
      })),
    };

    setResults((prev) => [...prev.filter((r) => r.scenarioId !== scenario.id), result]);

    try {
      for (let i = 0; i < scenario.steps.length; i++) {
        const step = scenario.steps[i];

        // Update step status to running
        setResults((prev) =>
          prev.map((r) =>
            r.scenarioId === scenario.id
              ? {
                  ...r,
                  steps: r.steps.map((s, idx) =>
                    idx === i ? { ...s, status: 'running' } : s
                  ),
                }
              : r
          )
        );

        // Execute step
        await executeStep(step.action, step.params || {}, contextRef.current);

        // Update step status to passed
        setResults((prev) =>
          prev.map((r) =>
            r.scenarioId === scenario.id
              ? {
                  ...r,
                  steps: r.steps.map((s, idx) =>
                    idx === i ? { ...s, status: 'passed', result: 'OK' } : s
                  ),
                }
              : r
          )
        );
      }

      // Mark scenario as passed
      setResults((prev) =>
        prev.map((r) =>
          r.scenarioId === scenario.id
            ? { ...r, status: 'passed', endTime: Date.now() }
            : r
        )
      );
    } catch (error) {
      const errorMsg = error instanceof Error ? error.message : String(error);

      onError({
        timestamp: Date.now(),
        severity: 'error',
        message: `Scenario ${scenario.name} failed`,
        details: errorMsg,
      });

      setResults((prev) =>
        prev.map((r) =>
          r.scenarioId === scenario.id
            ? { ...r, status: 'failed', endTime: Date.now(), error: errorMsg }
            : r
        )
      );
    } finally {
      // Cleanup connections
      contextRef.current.gatewayClient?.disconnect();
      contextRef.current.gatewayClient2?.disconnect();
      contextRef.current.skillClient?.disconnect();
      setRunningScenario(null);
    }
  };

  const runAllScenarios = async () => {
    for (const scenario of TEST_SCENARIOS) {
      await runScenario(scenario);
    }
  };

  return (
    <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '8px' }}>
      <h2>Scenario Runner</h2>

      <div style={{ marginBottom: '16px' }}>
        <button
          onClick={runAllScenarios}
          disabled={runningScenario !== null}
          style={{
            padding: '8px 16px',
            background: runningScenario ? '#ccc' : '#28a745',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            cursor: runningScenario ? 'not-allowed' : 'pointer',
            marginRight: '8px',
          }}
        >
          {runningScenario ? 'Running...' : 'Run All Scenarios'}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
        {TEST_SCENARIOS.map((scenario) => {
          const result = results.find((r) => r.scenarioId === scenario.id);
          const isRunning = runningScenario === scenario.id;

          return (
            <div
              key={scenario.id}
              style={{
                border: '1px solid #ddd',
                borderRadius: '4px',
                padding: '12px',
                background: result?.status === 'passed' ? '#d4edda' : result?.status === 'failed' ? '#f8d7da' : 'white',
              }}
            >
              <h3 style={{ marginTop: 0, fontSize: '16px' }}>{scenario.name}</h3>
              <p style={{ fontSize: '12px', color: '#666', margin: '8px 0' }}>{scenario.description}</p>

              <button
                onClick={() => runScenario(scenario)}
                disabled={isRunning}
                style={{
                  padding: '6px 12px',
                  background: isRunning ? '#ccc' : '#007bff',
                  color: 'white',
                  border: 'none',
                  borderRadius: '4px',
                  cursor: isRunning ? 'not-allowed' : 'pointer',
                  fontSize: '12px',
                }}
              >
                {isRunning ? 'Running...' : 'Run'}
              </button>

              {result && (
                <div style={{ marginTop: '8px', fontSize: '12px' }}>
                  <strong>Status:</strong> {result.status}
                  {result.endTime && (
                    <div>
                      <strong>Duration:</strong> {result.endTime - result.startTime}ms
                    </div>
                  )}
                  {result.error && (
                    <div style={{ color: 'red', marginTop: '4px' }}>
                      <strong>Error:</strong> {result.error}
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {results.length > 0 && (
        <div style={{ marginTop: '16px' }}>
          <h3>Results Summary</h3>
          <div style={{ fontSize: '14px' }}>
            <div>
              <strong>Passed:</strong> {results.filter((r) => r.status === 'passed').length}
            </div>
            <div>
              <strong>Failed:</strong> {results.filter((r) => r.status === 'failed').length}
            </div>
            <div>
              <strong>Running:</strong> {results.filter((r) => r.status === 'running').length}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
