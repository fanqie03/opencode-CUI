import { useState } from 'react';
import type { Session } from '../types';

interface SessionManagerProps {
  onSessionCreated: (session: Session) => void;
  onSessionDestroyed: (sessionId: string) => void;
}

export function SessionManager({ onSessionCreated, onSessionDestroyed }: SessionManagerProps) {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [agentId, setAgentId] = useState('agent-001');
  const [isCreating, setIsCreating] = useState(false);

  const handleCreateSession = async () => {
    setIsCreating(true);
    try {
      const response = await fetch('http://localhost:8080/api/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agentId }),
      });

      if (!response.ok) {
        throw new Error(`Failed to create session: ${response.statusText}`);
      }

      const session: Session = await response.json();
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
      const response = await fetch(`http://localhost:8080/api/sessions/${sessionId}`, {
        method: 'DELETE',
      });

      if (!response.ok) {
        throw new Error(`Failed to destroy session: ${response.statusText}`);
      }

      setSessions((prev) => prev.filter((s) => s.id !== sessionId));
      onSessionDestroyed(sessionId);
    } catch (err) {
      alert(`Error destroying session: ${err}`);
    }
  };

  return (
    <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '8px' }}>
      <h2>Session Manager</h2>

      <div style={{ marginBottom: '12px' }}>
        <label>
          Agent ID:
          <input
            type="text"
            value={agentId}
            onChange={(e) => setAgentId(e.target.value)}
            style={{ marginLeft: '8px', width: '150px' }}
          />
        </label>
        <button
          onClick={handleCreateSession}
          disabled={isCreating}
          style={{
            marginLeft: '8px',
            padding: '6px 12px',
            background: '#28a745',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            cursor: isCreating ? 'not-allowed' : 'pointer',
          }}
        >
          {isCreating ? 'Creating...' : 'Create Session'}
        </button>
      </div>

      <div>
        <h3>Active Sessions ({sessions.length})</h3>
        {sessions.length === 0 ? (
          <p style={{ color: '#666' }}>No active sessions</p>
        ) : (
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {sessions.map((session) => (
              <li
                key={session.id}
                style={{
                  padding: '8px',
                  marginBottom: '8px',
                  border: '1px solid #ddd',
                  borderRadius: '4px',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <div>
                  <strong>{session.id}</strong>
                  <br />
                  <small>
                    Agent: {session.agentId} | Status: {session.status}
                  </small>
                </div>
                <button
                  onClick={() => handleDestroySession(session.id)}
                  style={{
                    padding: '4px 8px',
                    background: '#dc3545',
                    color: 'white',
                    border: 'none',
                    borderRadius: '4px',
                    cursor: 'pointer',
                  }}
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
