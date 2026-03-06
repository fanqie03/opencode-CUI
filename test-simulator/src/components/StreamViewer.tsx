import { useState, useEffect, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { StreamAssembler } from '../protocol/StreamAssembler';
import type { StreamMessage, PermissionRequest } from '../types';

interface StreamViewerProps {
  sessionId: string | null;
  skillServerUrl?: string;
  onPermissionRequest?: (request: PermissionRequest) => void;
}

const DEFAULT_SKILL_SERVER_URL = 'ws://localhost:8080';

export function StreamViewer({
  sessionId,
  skillServerUrl = DEFAULT_SKILL_SERVER_URL,
  onPermissionRequest,
}: StreamViewerProps) {
  const [streamText, setStreamText] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [sequenceGaps, setSequenceGaps] = useState<number[]>([]);
  const [lastSeq, setLastSeq] = useState<number | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const assemblerRef = useRef(new StreamAssembler());
  const contentRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom
  useEffect(() => {
    if (contentRef.current) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight;
    }
  }, [streamText]);

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
        setStreamText((prev) => prev + `\n\n> ❌ **ERROR:** ${msg.message}`);
        setIsStreaming(false);
        break;
      }

      case 'agent_offline':
        setStreamText((prev) => prev + '\n\n> ⚠️ Agent went offline');
        break;

      case 'agent_online':
        setStreamText((prev) => prev + '\n\n> ✅ Agent came online');
        break;

      case 'permission_updated':
        if (onPermissionRequest && msg.permissionId) {
          onPermissionRequest({
            permissionId: msg.permissionId,
            sessionId: sessionId || '',
            type: msg.permissionType || 'unknown',
            description: msg.description || 'Permission requested',
            timestamp: Date.now(),
          });
        }
        setStreamText((prev) => prev + `\n\n> 🔐 **Permission requested:** ${msg.description || msg.permissionId}`);
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
    <div className="panel">
      <h2>📡 Stream Viewer</h2>

      <div className="field-row">
        <strong>Session:</strong> <code>{sessionId || 'None'}</code>
      </div>

      <div className="field-row">
        <strong>Status:</strong>{' '}
        <span className={isStreaming ? 'status-streaming' : 'status-idle'}>
          {isStreaming ? '▶ Streaming' : '■ Idle'}
        </span>
        <span style={{ marginLeft: '12px' }}>
          <strong>Seq:</strong> {lastSeq ?? 'N/A'}
        </span>
      </div>

      {sequenceGaps.length > 0 && (
        <div className="warning-banner">
          ⚠ Sequence Gaps: {sequenceGaps.join(', ')}
        </div>
      )}

      <button onClick={handleClear} className="btn btn-secondary" style={{ marginBottom: '8px' }}>
        Clear
      </button>

      <div className="stream-content" ref={contentRef}>
        {streamText ? (
          <ReactMarkdown
            components={{
              code({ className, children, ...props }) {
                const match = /language-(\w+)/.exec(className || '');
                const codeString = String(children).replace(/\n$/, '');
                return match ? (
                  <SyntaxHighlighter
                    style={oneDark}
                    language={match[1]}
                    PreTag="div"
                  >
                    {codeString}
                  </SyntaxHighlighter>
                ) : (
                  <code className={className} {...props}>
                    {children}
                  </code>
                );
              },
            }}
          >
            {streamText}
          </ReactMarkdown>
        ) : (
          <span className="placeholder-text">Waiting for stream data...</span>
        )}
      </div>
    </div>
  );
}
