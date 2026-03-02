import { useState, useEffect, useRef, useCallback } from 'react';
import type { Message, StreamMessage, ToolUseInfo } from '../protocol/types';
import { parse } from '../protocol/OpenCodeEventParser';
import { StreamAssembler } from '../protocol/StreamAssembler';
import * as ToolUseRenderer from '../protocol/ToolUseRenderer';
import * as api from '../utils/api';

type AgentStatus = 'online' | 'offline' | 'unknown';

export interface UseSkillStreamReturn {
  messages: Message[];
  isStreaming: boolean;
  agentStatus: AgentStatus;
  sendMessage: (text: string) => Promise<void>;
  error: string | null;
}

const WS_BASE_URL =
  typeof import.meta !== 'undefined' && (import.meta as Record<string, Record<string, string>>).env?.VITE_SKILL_SERVER_WS
    ? (import.meta as Record<string, Record<string, string>>).env.VITE_SKILL_SERVER_WS
    : 'ws://localhost:8080';

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
  const activeToolsRef = useRef<Map<string, ToolUseInfo>>(new Map());
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
          setMessages(res.content);
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
  // Handle incoming stream messages
  // ------------------------------------------------------------------
  const handleStreamMessage = useCallback((msg: StreamMessage) => {
    switch (msg.type) {
      case 'delta': {
        const assembler = assemblerRef.current;
        const deltaText = msg.content ?? '';
        assembler.push(deltaText);
        setIsStreaming(true);

        // If we also get a raw event, parse it for richer info
        if (msg.event) {
          const parsed = parse(msg.event);
          if (parsed.eventType === 'tool.start' && parsed.toolName) {
            const toolInfo = ToolUseRenderer.startTool(parsed);
            activeToolsRef.current.set(parsed.toolName, toolInfo);
          }
          if (
            (parsed.eventType === 'tool.result' ||
              parsed.eventType === 'tool.error') &&
            parsed.toolName
          ) {
            const existing = activeToolsRef.current.get(parsed.toolName);
            if (existing) {
              const updated = ToolUseRenderer.completeTool(parsed, existing);
              activeToolsRef.current.set(parsed.toolName, updated);
            }
          }
        }

        // Upsert the streaming assistant message
        const currentText = assembler.getText();
        setMessages((prev) => {
          if (streamingMsgIdRef.current) {
            return prev.map((m) =>
              m.id === streamingMsgIdRef.current
                ? { ...m, content: currentText }
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
            },
          ];
        });
        break;
      }

      case 'done': {
        assemblerRef.current.complete();
        setIsStreaming(false);

        // Finalize the streaming message
        if (streamingMsgIdRef.current) {
          const finalId = streamingMsgIdRef.current;
          setMessages((prev) =>
            prev.map((m) =>
              m.id === finalId
                ? {
                    ...m,
                    isStreaming: false,
                    meta: msg.usage ? { usage: msg.usage } : m.meta,
                  }
                : m,
            ),
          );
        }

        // Append completed tool messages
        activeToolsRef.current.forEach((info) => {
          if (info.status !== 'running') {
            const rendered = ToolUseRenderer.renderToolUse(info);
            setMessages((prev) => [
              ...prev,
              {
                id: genId(),
                role: 'tool',
                content: `**${rendered.title}**\n\n${rendered.language ? '```' + rendered.language + '\n' + rendered.content + '\n```' : rendered.content}`,
                contentType: 'markdown',
                timestamp: Date.now(),
              },
            ]);
          }
        });
        activeToolsRef.current.clear();

        // Reset assembler for the next message
        assemblerRef.current.reset();
        streamingMsgIdRef.current = null;
        break;
      }

      case 'error': {
        setIsStreaming(false);
        setError(msg.message ?? 'Unknown stream error');
        assemblerRef.current.reset();
        streamingMsgIdRef.current = null;
        break;
      }

      case 'agent_offline':
        setAgentStatus('offline');
        break;

      case 'agent_online':
        setAgentStatus('online');
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
