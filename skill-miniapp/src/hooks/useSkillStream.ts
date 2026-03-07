import { useState, useEffect, useRef, useCallback } from 'react';
import type { Message, StreamMessage } from '../protocol/types';
import { StreamAssembler } from '../protocol/StreamAssembler';
import { normalizeHistoryMessages } from '../protocol/history';
import * as api from '../utils/api';

type AgentStatus = 'online' | 'offline' | 'unknown';

export interface UseSkillStreamReturn {
  messages: Message[];
  isStreaming: boolean;
  agentStatus: AgentStatus;
  sendMessage: (text: string) => Promise<void>;
  error: string | null;
}

// WebSocket base URL: use env var, or derive from page origin
const WS_BASE_URL =
  typeof import.meta !== 'undefined' && (import.meta as unknown as Record<string, Record<string, string>>).env?.VITE_SKILL_SERVER_WS
    ? (import.meta as unknown as Record<string, Record<string, string>>).env.VITE_SKILL_SERVER_WS
    : (typeof window !== 'undefined'
      ? `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
      : 'ws://localhost:8082');

/** Reconnect delay with exponential backoff (max 30s). */
function getReconnectDelay(attempt: number): number {
  return Math.min(1000 * Math.pow(2, attempt), 30_000);
}

let nextMsgId = 1;
function genId(): string {
  return `msg_${Date.now()}_${nextMsgId++}`;
}

export function useSkillStream(sessionId: string | null): UseSkillStreamReturn {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [agentStatus, setAgentStatus] = useState<AgentStatus>('unknown');
  const [error, setError] = useState<string | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const assemblerRef = useRef(new StreamAssembler());
  const reconnectAttemptRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const streamingMsgIdRef = useRef<string | null>(null);

  // ------------------------------------------------------------------
  // Load message history when session changes
  // ------------------------------------------------------------------
  useEffect(() => {
    if (!sessionId) {
      setMessages([]);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const res = await api.getMessages(sessionId, 0, 50);
        if (!cancelled) {
          setMessages(normalizeHistoryMessages(res.content as unknown as Array<Record<string, unknown>>));
        }
      } catch {
        /* history load failure is non-fatal */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  // ------------------------------------------------------------------
  // WebSocket connection lifecycle
  // ------------------------------------------------------------------
  const connect = useCallback(() => {
    if (!sessionId) return;

    const url = `${WS_BASE_URL}/ws/skill/stream/${sessionId}`;
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      reconnectAttemptRef.current = 0;
      setError(null);
      setAgentStatus('online');
    };

    ws.onmessage = (evt) => {
      try {
        const msg: StreamMessage = JSON.parse(evt.data as string);
        handleStreamMessage(msg);
      } catch {
        /* ignore malformed messages */
      }
    };

    ws.onerror = () => {
      setError('WebSocket connection error');
    };

    ws.onclose = () => {
      wsRef.current = null;
      scheduleReconnect();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  const scheduleReconnect = useCallback(() => {
    if (reconnectTimerRef.current) return;
    const delay = getReconnectDelay(reconnectAttemptRef.current);
    reconnectAttemptRef.current += 1;
    reconnectTimerRef.current = setTimeout(() => {
      reconnectTimerRef.current = null;
      connect();
    }, delay);
  }, [connect]);

  // ------------------------------------------------------------------
  // Handle incoming stream messages (v2 protocol)
  // ------------------------------------------------------------------
  const handleStreamMessage = useCallback((msg: StreamMessage) => {
    switch (msg.type) {
      // ---- Text streaming ----
      case 'text.delta':
      case 'text.done':
      case 'thinking.delta':
      case 'thinking.done':
      case 'tool.update':
      case 'question':
      case 'permission.ask':
      case 'file': {
        setIsStreaming(true);

        // Feed the message to the assembler
        const assembler = assemblerRef.current;
        assembler.handleMessage(msg);

        // Upsert the streaming assistant message with parts
        const currentText = assembler.getText();
        const currentParts = assembler.getParts();

        setMessages((prev) => {
          if (streamingMsgIdRef.current) {
            return prev.map((m) =>
              m.id === streamingMsgIdRef.current
                ? { ...m, content: currentText, parts: [...currentParts], isStreaming: true }
                : m,
            );
          }
          const id = genId();
          streamingMsgIdRef.current = id;
          return [
            ...prev,
            {
              id,
              role: 'assistant',
              content: currentText,
              contentType: 'markdown',
              timestamp: Date.now(),
              isStreaming: true,
              parts: [...currentParts],
            },
          ];
        });
        break;
      }

      // ---- Step events (metadata only, no UI part) ----
      case 'step.start':
        setIsStreaming(true);
        break;

      case 'step.done':
        // Update message meta with token info
        if (streamingMsgIdRef.current && msg.tokens) {
          const finalId = streamingMsgIdRef.current;
          setMessages((prev) =>
            prev.map((m) =>
              m.id === finalId
                ? { ...m, meta: { ...m.meta, tokens: msg.tokens, cost: msg.cost } }
                : m,
            ),
          );
        }
        break;

      // ---- Session status ----
      case 'session.status': {
        if (msg.sessionStatus === 'idle' || msg.sessionStatus === 'completed') {
          // Finalize the current streaming message
          assemblerRef.current.complete();
          setIsStreaming(false);

          if (streamingMsgIdRef.current) {
            const finalId = streamingMsgIdRef.current;
            const finalParts = assemblerRef.current.getParts();
            setMessages((prev) =>
              prev.map((m) =>
                m.id === finalId
                  ? { ...m, isStreaming: false, parts: [...finalParts] }
                  : m,
              ),
            );
          }

          // Reset for next message
          assemblerRef.current.reset();
          streamingMsgIdRef.current = null;
        }
        break;
      }

      // ---- Agent status ----
      case 'agent.online':
        setAgentStatus('online');
        break;

      case 'agent.offline':
        setAgentStatus('offline');
        break;

      // ---- Error ----
      case 'error':
        setIsStreaming(false);
        setError(msg.error ?? 'Unknown stream error');
        assemblerRef.current.reset();
        streamingMsgIdRef.current = null;
        break;

      // ---- Snapshot (reconnect recovery) ----
      case 'snapshot': {
        // snapshot contains full message history, replace current
        // This will be implemented when Phase 3 (Redis buffer) is done
        break;
      }

      // ---- Streaming parts (reconnect recovery) ----
      case 'streaming': {
        // Contains in-progress parts to resume display
        // This will be implemented when Phase 3 (Redis buffer) is done
        break;
      }

      default:
        // Unknown type - silently ignore
        break;
    }
  }, []);

  // ------------------------------------------------------------------
  // Connect / disconnect
  // ------------------------------------------------------------------
  useEffect(() => {
    if (!sessionId) return;
    connect();

    return () => {
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      reconnectAttemptRef.current = 0;
      if (wsRef.current) {
        wsRef.current.onclose = null; // prevent reconnect on intentional close
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [sessionId, connect]);

  // ------------------------------------------------------------------
  // Send a user message
  // ------------------------------------------------------------------
  const sendMessageFn = useCallback(
    async (text: string) => {
      if (!sessionId) return;
      setError(null);

      // Optimistic local message
      const userMsg: Message = {
        id: genId(),
        role: 'user',
        content: text,
        contentType: 'plain',
        timestamp: Date.now(),
      };
      setMessages((prev) => [...prev, userMsg]);

      try {
        await api.sendMessage(sessionId, text);
      } catch (err) {
        const message =
          err instanceof Error ? err.message : 'Failed to send message';
        setError(message);
      }
    },
    [sessionId],
  );

  return {
    messages,
    isStreaming,
    agentStatus,
    sendMessage: sendMessageFn,
    error,
  };
}
