import { useState } from 'react';
import type { Session } from '../types';
import { APIClient } from '../services/APIClient';
import { config } from '../config';

interface SessionManagerProps {
  onSessionCreated: (session: Session) => void;
  onSessionDestroyed: (sessionId: string) => void;
}

const apiClient = new APIClient(
  config.skillServerUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:')
);

export function SessionManager({ onSessionCreated, onSessionDestroyed }: SessionManagerProps) {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [agentId, setAgentId] = useState('agent-001');
  const [isCreating, setIsCreating] = useState(false);

  const handleCreateSession = async () => {
    setIsCreating(true);
    try {
      const response = await apiClient.createSession({ agentId });
      const session: Session = {
        id: response.sessionId,
        agentId: response.agentId,
        status: 'ACTIVE',
        createdAt: response.createdAt,
      };
      setSessions((prev) => [...prev, session]);
      onSessionCreated(session);
    } catch (err) {
      alert(`Error creating session: ${err}`);
    } finally {
      setIsCreating(false);
    }
  };

  const handleDestroySession = async (sessionId: string) => {
    try {
      await apiClient.deleteSession(sessionId);
      setSessions((prev) => prev.filter((s) => s.id !== sessionId));
      onSessionDestroyed(sessionId);
    } catch (err) {
      alert(`Error destroying session: ${err}`);
    }
  };

  const handleRefreshSessions = async () => {
    try {
      const list = await apiClient.listSessions();
      setSessions(list);
    } catch (err) {
      alert(`Error refreshing sessions: ${err}`);
    }
  };

  return (
    <div className="panel">
      <h2>📋 Session Manager</h2>

      <div className="field-row">
        <label>
          Agent ID:
          <input
            type="text"
            value={agentId}
            onChange={(e) => setAgentId(e.target.value)}
            className="input-field"
          />
        </label>
        <button
          onClick={handleCreateSession}
          disabled={isCreating}
          className={`btn ${isCreating ? 'btn-disabled' : 'btn-success'}`}
        >
          {isCreating ? 'Creating...' : 'Create Session'}
        </button>
        <button onClick={handleRefreshSessions} className="btn btn-secondary">
          Refresh
        </button>
      </div>

      <div>
        <h3>Active Sessions ({sessions.length})</h3>
        {sessions.length === 0 ? (
          <p className="placeholder-text">No active sessions</p>
        ) : (
          <ul className="session-list">
            {sessions.map((session) => (
              <li key={session.id} className="session-item">
                <div>
                  <strong>{session.id}</strong>
                  <br />
                  <small>
                    Agent: {session.agentId} | Status: {session.status}
                  </small>
                </div>
                <button
                  onClick={() => handleDestroySession(session.id)}
                  className="btn btn-danger btn-sm"
                >
                  Destroy
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
