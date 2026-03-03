import { useState } from 'react';
import { AgentSimulator } from './components/AgentSimulator';
import { SessionManager } from './components/SessionManager';
import { StreamViewer } from './components/StreamViewer';
import { ErrorPanel } from './components/ErrorPanel';
import { MetricsPanel } from './components/MetricsPanel';
import { ScenarioRunner } from './components/ScenarioRunner';
import type { Session, ErrorEntry, Metrics } from './types';

function App() {
  const [currentSession, setCurrentSession] = useState<Session | null>(null);
  const [errors, setErrors] = useState<ErrorEntry[]>([]);
  const [metrics, setMetrics] = useState<Metrics>({
    messagesSent: 0,
    messagesReceived: 0,
    sequenceGaps: 0,
    reconnectCount: 0,
    latencies: [],
  });

  const handleSessionCreated = (session: Session) => {
    setCurrentSession(session);
    setErrors((prev) => [
      ...prev,
      {
        timestamp: Date.now(),
        severity: 'info',
        message: `Session created: ${session.id}`,
      },
    ]);
  };

  const handleSessionDestroyed = (sessionId: string) => {
    if (currentSession?.id === sessionId) {
      setCurrentSession(null);
    }
    setErrors((prev) => [
      ...prev,
      {
        timestamp: Date.now(),
        severity: 'info',
        message: `Session destroyed: ${sessionId}`,
      },
    ]);
  };

  const handleError = (error: ErrorEntry) => {
    setErrors((prev) => [...prev, error]);
  };

  const handleMetricsUpdate = (update: Partial<Metrics>) => {
    setMetrics((prev) => ({
      messagesSent: update.messagesSent !== undefined ? prev.messagesSent + update.messagesSent : prev.messagesSent,
      messagesReceived: update.messagesReceived !== undefined ? prev.messagesReceived + update.messagesReceived : prev.messagesReceived,
      sequenceGaps: update.sequenceGaps !== undefined ? prev.sequenceGaps + update.sequenceGaps : prev.sequenceGaps,
      reconnectCount: update.reconnectCount !== undefined ? prev.reconnectCount + update.reconnectCount : prev.reconnectCount,
      latencies: update.latencies ? [...prev.latencies, ...update.latencies] : prev.latencies,
    }));
  };

  const handleClearErrors = () => {
    setErrors([]);
  };

  return (
    <div style={{ padding: '20px', fontFamily: 'Arial, sans-serif' }}>
      <h1 style={{ marginBottom: '20px' }}>E2E Integration Test Simulator</h1>

      {/* Top Bar: Session Manager + Scenario Runner */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '20px' }}>
        <SessionManager
          onSessionCreated={handleSessionCreated}
          onSessionDestroyed={handleSessionDestroyed}
        />
        <ScenarioRunner
          onError={handleError}
          onMetricsUpdate={handleMetricsUpdate}
        />
      </div>

      {/* Main Content: 3 Columns */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '20px', marginBottom: '20px' }}>
        <AgentSimulator agentId="agent-001" />
        <StreamViewer sessionId={currentSession?.id || null} />
        <MetricsPanel metrics={metrics} />
      </div>

      {/* Bottom Panel: Error Panel */}
      <ErrorPanel errors={errors} onClear={handleClearErrors} />
    </div>
  );
}

export default App;
