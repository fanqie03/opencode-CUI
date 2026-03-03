import { useState, useEffect, useRef } from 'react';
import { StreamAssembler } from '../protocol/StreamAssembler';
import type { StreamMessage } from '../types';

interface StreamViewerProps {
  sessionId: string | null;
  skillServerUrl?: string;
}

const DEFAULT_SKILL_SERVER_URL = 'ws://localhost:8080';

export function StreamViewer({ sessionId, skillServerUrl = DEFAULT_SKILL_SERVER_URL }: StreamViewerProps) {
  const [streamText, setStreamText] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [sequenceGaps, setSequenceGaps] = useState<number[]>([]);
  const [lastSeq, setLastSeq] = useState<number | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const assemblerRef = useRef(new StreamAssembler());

  useEffect(() => {
    if (!sessionId) {
      setStreamText('');
      setIsStreaming(false);
      return;
    }

    const url = `${skillServerUrl}/ws/skill/stream/${sessionId}`;
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      console.log('StreamViewer connected');
    };

    ws.onmessage = (evt) => {
      try {
        const msg: StreamMessage = JSON.parse(evt.data);
        handleStreamMessage(msg);
      } catch (err) {
        console.error('Failed to parse stream message:', err);
      }
    };

    ws.onerror = () => {
      console.error('StreamViewer WebSocket error');
    };

    ws.onclose = () => {
      console.log('StreamViewer disconnected');
      wsRef.current = null;
    };

    return () => {
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [sessionId, skillServerUrl]);

  const handleStreamMessage = (msg: StreamMessage) => {
    // Check for sequence gaps
    if (msg.seq !== undefined) {
      if (lastSeq !== null && msg.seq !== lastSeq + 1) {
        setSequenceGaps((prev) => [...prev, msg.seq!]);
      }
      setLastSeq(msg.seq);
    }

    switch (msg.type) {
      case 'delta': {
        const assembler = assemblerRef.current;
        assembler.push(msg.content || '');
        setStreamText(assembler.getText());
        setIsStreaming(true);
        break;
      }

      case 'done': {
        assemblerRef.current.complete();
        setIsStreaming(false);
        break;
      }

      case 'error': {
        setStreamText((prev) => prev + `\n\n[ERROR: ${msg.message}]`);
        setIsStreaming(false);
        break;
      }

      case 'agent_offline':
        setStreamText((prev) => prev + '\n\n[Agent went offline]');
        break;

      case 'agent_online':
        setStreamText((prev) => prev + '\n\n[Agent came online]');
        break;
    }
  };

  const handleClear = () => {
    setStreamText('');
    assemblerRef.current.reset();
    setSequenceGaps([]);
    setLastSeq(null);
  };

  return (
    <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '8px' }}>
      <h2>Stream Viewer</h2>

      <div style={{ marginBottom: '12px' }}>
        <strong>Session ID:</strong> {sessionId || 'None'}
      </div>

      <div style={{ marginBottom: '12px' }}>
        <strong>Status:</strong>{' '}
        <span style={{ color: isStreaming ? 'blue' : 'gray' }}>
          {isStreaming ? '▶ Streaming' : '■ Idle'}
        </span>
      </div>

      <div style={{ marginBottom: '12px' }}>
        <strong>Last Sequence:</strong> {lastSeq ?? 'N/A'}
      </div>

      {sequenceGaps.length > 0 && (
        <div style={{ marginBottom: '12px', color: 'orange', padding: '8px', background: '#fff3cd' }}>
          <strong>⚠ Sequence Gaps Detected:</strong> {sequenceGaps.join(', ')}
        </div>
      )}

      <button
        onClick={handleClear}
        style={{
          marginBottom: '12px',
          padding: '6px 12px',
          background: '#6c757d',
          color: 'white',
          border: 'none',
          borderRadius: '4px',
          cursor: 'pointer',
        }}
      >
        Clear
      </button>

      <div
        style={{
          minHeight: '400px',
          maxHeight: '600px',
          overflow: 'auto',
          border: '1px solid #ddd',
          padding: '12px',
          background: '#f9f9f9',
          fontFamily: 'monospace',
          fontSize: '14px',
          whiteSpace: 'pre-wrap',
        }}
      >
        {streamText || 'Waiting for stream data...'}
      </div>
    </div>
  );
}
