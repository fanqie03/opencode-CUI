import { useState } from 'react';
import { useAgentWebSocket } from '../hooks/useAgentWebSocket';
import type { GatewayMessage } from '../types';

interface AgentSimulatorProps {
  agentId: string;
  gatewayUrl?: string;
}

export function AgentSimulator({ agentId, gatewayUrl }: AgentSimulatorProps) {
  const { isConnected, sendMessage, messages, error, reconnectCount } = useAgentWebSocket({
    agentId,
    gatewayUrl,
  });

  const [messageType, setMessageType] = useState('tool_event');
  const [payloadData, setPayloadData] = useState('{"delta": "Hello from agent"}');

  const handleSendMessage = () => {
    try {
      // v1 flat GatewayMessage format
      const msg: GatewayMessage = {
        type: messageType,
        agentId,
        event: JSON.parse(payloadData),
        sequenceNumber: messages.length + 1,
      };
      sendMessage(msg);
    } catch (err) {
      alert(`Invalid JSON: ${err}`);
    }
  };

  return (
    <div className="panel">
      <h2>🤖 Agent Simulator</h2>

      <div className="field-row">
        <strong>Agent ID:</strong> <code>{agentId}</code>
      </div>

      <div className="field-row">
        <strong>Status:</strong>{' '}
        <span className={isConnected ? 'status-on' : 'status-off'}>
          {isConnected ? '● Connected' : '○ Disconnected'}
        </span>
      </div>

      <div className="field-row">
        <strong>Reconnects:</strong> {reconnectCount}
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="field-row">
        <label>
          Type:
          <input
            type="text"
            value={messageType}
            onChange={(e) => setMessageType(e.target.value)}
            className="input-field"
          />
        </label>
      </div>

      <div className="field-row">
        <label>
          Event (JSON):
          <br />
          <textarea
            value={payloadData}
            onChange={(e) => setPayloadData(e.target.value)}
            rows={3}
            className="textarea-field"
          />
        </label>
      </div>

      <button
        onClick={handleSendMessage}
        disabled={!isConnected}
        className={`btn ${isConnected ? 'btn-primary' : 'btn-disabled'}`}
      >
        Send Message
      </button>

      <div style={{ marginTop: '12px' }}>
        <h3>Message Log ({messages.length})</h3>
        <div className="log-container">
          {messages.map((msg, idx) => (
            <div key={idx} className="log-entry">
              <strong>{msg.type}</strong>
              {msg.seq && <span> [seq: {msg.seq}]</span>}
              {msg.content && <div className="log-content">{msg.content.substring(0, 100)}</div>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
