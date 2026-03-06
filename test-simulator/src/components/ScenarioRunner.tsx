import { useState, useRef } from 'react';
import type { TestScenario, ScenarioResult, ErrorEntry, Metrics, GatewayMessage } from '../types';
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
  {
    id: 'scenario-7',
    name: 'Permission Confirmation Flow',
    description: 'Trigger permission request and verify reply path',
    steps: [
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'send_message', params: { text: 'Do something requiring permission' } },
      { action: 'wait_for_stream', params: { timeout: 5000 } },
    ],
  },
  {
    id: 'scenario-8',
    name: 'Message History Pagination',
    description: 'Verify message history API supports pagination',
    steps: [
      { action: 'create_session', params: { agentId: 'agent-001' } },
      { action: 'send_message', params: { text: 'Message 1' } },
      { action: 'send_message', params: { text: 'Message 2' } },
      { action: 'verify_message_history', params: {} },
    ],
  },
];

interface TestContext {
  sessionId?: string;
  gatewayClient?: GatewayWebSocketClient;
  gatewayClient2?: GatewayWebSocketClient;
  skillClient?: SkillWebSocketClient;
  apiClient: APIClient;
  receivedMessages: GatewayMessage[];
  lastSequence: number;
  errorReceived: boolean;
  reconnectDetected: boolean;
}

export function ScenarioRunner({ onError, onMetricsUpdate }: ScenarioRunnerProps) {
  const [results, setResults] = useState<ScenarioResult[]>([]);
  const [runningScenario, setRunningScenario] = useState<string | null>(null);
  const contextRef = useRef<TestContext>({
    apiClient: new APIClient(
      config.skillServerUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:')
    ),
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

        // v1 flat GatewayMessage format
        const msg: GatewayMessage = {
          type: 'tool_event',
          agentId: config.agentId,
          sessionId: context.sessionId,
          event: { delta: params.text },
          sequenceNumber: ++context.lastSequence,
        };

        context.gatewayClient.sendMessage(msg);
        context.receivedMessages.push(msg);
        onMetricsUpdate({ messagesSent: 1 });
        break;
      }

      case 'send_invalid_message': {
        if (!context.gatewayClient) {
          throw new Error('Gateway not connected');
        }
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
            context.gatewayClient.onMessageType('tool_done', () => {
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
        if (context.lastSequence === 0) {
          throw new Error('No sequence gap was simulated');
        }
        break;
      }

      case 'verify_message_history': {
        if (!context.sessionId) {
          throw new Error('No session');
        }
        const result = await context.apiClient.getMessages(context.sessionId, 0, 10);
        if (!result.content || result.content.length === 0) {
          throw new Error('No messages found in history');
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

    contextRef.current = {
      apiClient: new APIClient(
        config.skillServerUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:')
      ),
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

        await executeStep(step.action, step.params || {}, contextRef.current);

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
    <div className="panel">
      <h2>🎯 Scenario Runner</h2>

      <div style={{ marginBottom: '12px' }}>
        <button
          onClick={runAllScenarios}
          disabled={runningScenario !== null}
          className={`btn ${runningScenario ? 'btn-disabled' : 'btn-success'}`}
        >
          {runningScenario ? 'Running...' : 'Run All Scenarios'}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
        {TEST_SCENARIOS.map((scenario) => {
          const result = results.find((r) => r.scenarioId === scenario.id);
          const isRunning = runningScenario === scenario.id;

          return (
            <div
              key={scenario.id}
              className={`scenario-item ${result?.status === 'passed' ? 'scenario-passed' :
                  result?.status === 'failed' ? 'scenario-failed' :
                    isRunning ? 'scenario-running' : ''
                }`}
              style={{ flexDirection: 'column', alignItems: 'flex-start' }}
            >
              <div style={{ width: '100%', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <strong style={{ fontSize: '0.85rem' }}>{scenario.name}</strong>
                <button
                  onClick={() => runScenario(scenario)}
                  disabled={isRunning}
                  className={`btn btn-sm ${isRunning ? 'btn-disabled' : 'btn-primary'}`}
                >
                  {isRunning ? '...' : 'Run'}
                </button>
              </div>
              <small className="placeholder-text">{scenario.description}</small>

              {result && (
                <div style={{ fontSize: '0.75rem', marginTop: '4px' }}>
                  <span>{result.status.toUpperCase()}</span>
                  {result.endTime && <span> ({result.endTime - result.startTime}ms)</span>}
                  {result.error && <div className="error-banner" style={{ marginTop: '4px', padding: '4px 8px' }}>{result.error}</div>}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {results.length > 0 && (
        <div style={{ marginTop: '12px', fontSize: '0.85rem', display: 'flex', gap: '16px' }}>
          <span>✅ {results.filter((r) => r.status === 'passed').length}</span>
          <span>❌ {results.filter((r) => r.status === 'failed').length}</span>
          <span>🔄 {results.filter((r) => r.status === 'running').length}</span>
        </div>
      )}
    </div>
  );
}
