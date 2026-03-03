import { useState } from 'react';
import { useAgentWebSocket } from '../hooks/useAgentWebSocket';
import type { MessageEnvelope } from '../types';

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
  const [payloadData, setPayloadData] = useState('{"event": "test"}');

  const handleSendMessage = () => {
    try {
      const envelope: MessageEnvelope = {
        version: '1.0',
        messageId: `msg_${Date.now()}`,
        timestamp: Date.now(),
        source: {
          type: 'agent',
          id: agentId,
        },
        payload: {
          type: messageType,
          data: JSON.parse(payloadData),
        },
        metadata: {
          sequenceNumber: messages.length + 1,
        },
      };
      sendMessage(envelope);
    } catch (err) {
      alert(`Invalid JSON: ${err}`);
    }
  };

  return (
    <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '8px' }}>
      <h2>Agent Simulator</h2>

      <div style={{ marginBottom: '12px' }}>
        <strong>Agent ID:</strong> {agentId}
      </div>

      <div style={{ marginBottom: '12px' }}>
        <strong>Status:</strong>{' '}
        <span style={{ color: isConnected ? 'green' : 'red' }}>
          {isConnected ? '● Connected' : '○ Disconnected'}
        </span>
      </div>

      <div style={{ marginBottom: '12px' }}>
        <strong>Reconnect Count:</strong> {reconnectCount}
      </div>

      {error && (
        <div style={{ color: 'red', marginBottom: '12px', padding: '8px', background: '#fee' }}>
          {error}
        </div>
      )}

      <div style={{ marginBottom: '12px' }}>
        <label>
          Message Type:
          <input
            type="text"
            value={messageType}
            onChange={(e) => setMessageType(e.target.value)}
            style={{ marginLeft: '8px', width: '200px' }}
          />
        </label>
      </div>

      <div style={{ marginBottom: '12px' }}>
        <label>
          Payload Data (JSON):
          <br />
          <textarea
            value={payloadData}
            onChange={(e) => setPayloadData(e.target.value)}
            rows={4}
            style={{ width: '100%', fontFamily: 'monospace' }}
          />
        </label>
      </div>

      <button
        onClick={handleSendMessage}
        disabled={!isConnected}
        style={{
          padding: '8px 16px',
          background: isConnected ? '#007bff' : '#ccc',
          color: 'white',
          border: 'none',
          borderRadius: '4px',
          cursor: isConnected ? 'pointer' : 'not-allowed',
        }}
      >
        Send Message
      </button>

      <div style={{ marginTop: '16px' }}>
        <h3>Message Log ({messages.length})</h3>
        <div
          style={{
            maxHeight: '300px',
            overflow: 'auto',
            border: '1px solid #ddd',
            padding: '8px',
            background: '#f9f9f9',
            fontFamily: 'monospace',
            fontSize: '12px',
          }}
        >
          {messages.map((msg, idx) => (
            <div key={idx} style={{ marginBottom: '8px', borderBottom: '1px solid #eee' }}>
              <strong>{msg.type}</strong>
              {msg.seq && <span> [seq: {msg.seq}]</span>}
              {msg.content && <div>{msg.content.substring(0, 100)}...</div>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
