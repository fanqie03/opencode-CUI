import { useState } from 'react';
import { AgentSimulator } from './components/AgentSimulator';
import { SessionManager } from './components/SessionManager';
import { StreamViewer } from './components/StreamViewer';
import { ErrorPanel } from './components/ErrorPanel';
import { MetricsPanel } from './components/MetricsPanel';
import { ScenarioRunner } from './components/ScenarioRunner';
import { PermissionPanel } from './components/PermissionPanel';
import { MessageHistory } from './components/MessageHistory';
import type { Session, ErrorEntry, Metrics, PermissionRequest } from './types';
import './App.css';

function App() {
  const [currentSession, setCurrentSession] = useState<Session | null>(null);
  const [errors, setErrors] = useState<ErrorEntry[]>([]);
  const [permissions, setPermissions] = useState<PermissionRequest[]>([]);
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

  const handlePermissionRequest = (request: PermissionRequest) => {
    setPermissions((prev) => [...prev, request]);
  };

  const handlePermissionHandled = (permId: string) => {
    setPermissions((prev) => prev.filter((p) => p.permissionId !== permId));
  };

  return (
    <div className="app-container">
      <header className="app-header">
        <h1>🧪 E2E Integration Test Simulator</h1>
        <span className="header-badge">v1 Protocol</span>
      </header>

      {/* Row 1: Session Manager + Scenario Runner */}
      <div className="grid-2">
        <SessionManager
          onSessionCreated={handleSessionCreated}
          onSessionDestroyed={handleSessionDestroyed}
        />
        <ScenarioRunner
          onError={handleError}
          onMetricsUpdate={handleMetricsUpdate}
        />
      </div>

      {/* Row 2: Agent + Stream + Permission */}
      <div className="grid-3">
        <AgentSimulator agentId="agent-001" />
        <StreamViewer
          sessionId={currentSession?.id || null}
          onPermissionRequest={handlePermissionRequest}
        />
        <PermissionPanel
          permissions={permissions}
          onPermissionHandled={handlePermissionHandled}
        />
      </div>

      {/* Row 3: Message History + Metrics */}
      <div className="grid-2">
        <MessageHistory sessionId={currentSession?.id || null} />
        <MetricsPanel metrics={metrics} />
      </div>

      {/* Row 4: Error Panel */}
      <ErrorPanel errors={errors} onClear={handleClearErrors} />
    </div>
  );
}

export default App;
