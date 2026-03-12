import { useCallback, useEffect, useRef, useState } from 'react';
import type { Message, MessageRole, StreamMessage, StreamMessageType } from '../protocol/types';
import { StreamAssembler } from '../protocol/StreamAssembler';
import { normalizeHistoryMessage, normalizeHistoryMessages } from '../protocol/history';
import * as api from '../utils/api';
import { ensureDevUserIdCookie } from '../utils/devAuth';

type AgentStatus = 'online' | 'offline' | 'unknown';

export interface UseSkillStreamReturn {
  messages: Message[];
  isStreaming: boolean;
  agentStatus: AgentStatus;
  socketReady: boolean;
  sendMessage: (text: string, options?: { toolCallId?: string }) => Promise<void>;
  replyPermission: (permissionId: string, response: 'once' | 'always' | 'reject') => Promise<void>;
  error: string | null;
}

const WS_BASE_URL =
  typeof import.meta !== 'undefined' && (import.meta as unknown as Record<string, Record<string, string>>).env?.VITE_SKILL_SERVER_WS
    ? (import.meta as unknown as Record<string, Record<string, string>>).env.VITE_SKILL_SERVER_WS
    : (typeof window !== 'undefined'
      ? `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
      : 'ws://localhost:8082');

function getReconnectDelay(attempt: number): number {
  return Math.min(1000 * Math.pow(2, attempt), 30_000);
}

let nextMsgId = 1;
function genId(prefix = 'msg'): string {
  return `${prefix}_${Date.now()}_${nextMsgId++}`;
}

function normalizeRole(role: string | null | undefined): MessageRole {
  switch (role?.toLowerCase()) {
    case 'user':
    case 'assistant':
    case 'system':
    case 'tool':
      return role.toLowerCase() as MessageRole;
    default:
      return 'assistant';
  }
}

function normalizeTimestamp(value: string | number | null | undefined): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }

  if (typeof value === 'string') {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }

  return Date.now();
}

function normalizeWelinkSessionId(value: unknown): string | undefined {
  if (typeof value === 'string' && value.length > 0) {
    return value;
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value);
  }
  return undefined;
}

function contentTypeForRole(role: MessageRole): Message['contentType'] {
  switch (role) {
    case 'assistant':
      return 'markdown';
    case 'tool':
      return 'code';
    default:
      return 'plain';
  }
}

function sortMessages(messages: Message[]): Message[] {
  const deduped = new Map<string, Message>();
  messages.forEach((message) => {
    deduped.set(message.id, message);
  });

  return [...deduped.values()].sort((left, right) => {
    if (left.messageSeq != null && right.messageSeq != null && left.messageSeq !== right.messageSeq) {
      return left.messageSeq - right.messageSeq;
    }
    return left.timestamp - right.timestamp;
  });
}

function upsertMessage(messages: Message[], next: Message): Message[] {
  const index = messages.findIndex((message) => message.id === next.id);
  if (index === -1) {
    return sortMessages([...messages, next]);
  }

  const updated = [...messages];
  updated[index] = next;
  return sortMessages(updated);
}

function mergeHistoryMessage(existing: Message, incoming: Message): Message {
  const preserveStreaming = Boolean(existing.isStreaming);
  const content = preserveStreaming && existing.content
    ? existing.content
    : (incoming.content || existing.content);
  const parts = preserveStreaming && existing.parts && existing.parts.length > 0
    ? existing.parts
    : (incoming.parts ?? existing.parts);

  return {
    ...incoming,
    role: preserveStreaming ? existing.role : incoming.role,
    content,
    contentType: preserveStreaming ? existing.contentType : incoming.contentType,
    timestamp: incoming.timestamp || existing.timestamp,
    messageSeq: incoming.messageSeq ?? existing.messageSeq,
    meta: incoming.meta ?? existing.meta,
    isStreaming: preserveStreaming,
    parts,
  };
}

function mergeHistoryMessages(messages: Message[], incomingMessages: Message[]): Message[] {
  let merged = [...messages];

  incomingMessages.forEach((incoming) => {
    const index = merged.findIndex((message) => message.id === incoming.id);
    if (index === -1) {
      merged = [...merged, incoming];
      return;
    }

    const updated = [...merged];
    updated[index] = mergeHistoryMessage(updated[index], incoming);
    merged = updated;
  });

  return sortMessages(merged);
}

function isKnownStreamType(type: unknown): type is StreamMessageType {
  return typeof type === 'string' && [
    'text.delta',
    'text.done',
    'thinking.delta',
    'thinking.done',
    'tool.update',
    'question',
    'file',
    'step.start',
    'step.done',
    'session.status',
    'session.title',
    'session.error',
    'permission.ask',
    'permission.reply',
    'agent.online',
    'agent.offline',
    'error',
    'snapshot',
    'streaming',
  ].includes(type);
}

function normalizeStreamingPart(raw: Record<string, unknown>): StreamMessage | null {
  if (isKnownStreamType(raw.type)) {
    return raw as unknown as StreamMessage;
  }

  const base: Partial<StreamMessage> = {
    welinkSessionId: normalizeWelinkSessionId(raw.welinkSessionId),
    emittedAt: typeof raw.emittedAt === 'string' ? raw.emittedAt : undefined,
    messageId: typeof raw.messageId === 'string' ? raw.messageId : undefined,
    messageSeq: typeof raw.messageSeq === 'number' ? raw.messageSeq : undefined,
    role: typeof raw.role === 'string' ? normalizeRole(raw.role) : undefined,
    sourceMessageId: typeof raw.sourceMessageId === 'string' ? raw.sourceMessageId : undefined,
    partId: typeof raw.partId === 'string' ? raw.partId : undefined,
    partSeq: typeof raw.partSeq === 'number' ? raw.partSeq : undefined,
  };

  switch (raw.type) {
    case 'text':
      return { ...base, type: 'text.delta', content: typeof raw.content === 'string' ? raw.content : '' };
    case 'thinking':
      return { ...base, type: 'thinking.delta', content: typeof raw.content === 'string' ? raw.content : '' };
    case 'tool':
      return {
        ...base,
        type: 'tool.update',
        toolName: typeof raw.toolName === 'string' ? raw.toolName : undefined,
        toolCallId: typeof raw.toolCallId === 'string' ? raw.toolCallId : undefined,
        status: typeof raw.status === 'string' ? raw.status as StreamMessage['status'] : undefined,
        title: typeof raw.title === 'string' ? raw.title : undefined,
        output: typeof raw.output === 'string' ? raw.output : undefined,
      };
    case 'question':
      return {
        ...base,
        type: 'question',
        toolName: 'question',
        toolCallId: typeof raw.toolCallId === 'string' ? raw.toolCallId : undefined,
        status: 'running',
        header: typeof raw.header === 'string' ? raw.header : undefined,
        question: typeof raw.question === 'string' ? raw.question : undefined,
        options: Array.isArray(raw.options)
          ? raw.options
            .map((v: unknown) => {
              if (typeof v === 'string') return { label: v };
              if (v && typeof v === 'object') {
                const obj = v as Record<string, unknown>;
                const label = typeof obj.label === 'string' ? obj.label : '';
                if (label) return { label, description: typeof obj.description === 'string' ? obj.description : undefined };
              }
              return null;
            })
            .filter((v): v is { label: string; description?: string } => v !== null)
          : undefined,
      };
    case 'permission':
      return {
        ...base,
        type: 'permission.ask',
        permissionId: typeof raw.permissionId === 'string' ? raw.permissionId : undefined,
        permType: typeof raw.permType === 'string' ? raw.permType : undefined,
        content: typeof raw.content === 'string' ? raw.content : undefined,
      };
    case 'file':
      return {
        ...base,
        type: 'file',
        fileName: typeof raw.fileName === 'string' ? raw.fileName : undefined,
        fileUrl: typeof raw.fileUrl === 'string' ? raw.fileUrl : undefined,
        fileMime: typeof raw.fileMime === 'string' ? raw.fileMime : undefined,
      };
    default:
      return null;
  }
}

function normalizeIncomingStreamMessage(raw: Record<string, unknown>): StreamMessage {
  const welinkSessionId = normalizeWelinkSessionId(raw.welinkSessionId);

  return {
    ...(raw as unknown as StreamMessage),
    welinkSessionId,
  };
}

export function useSkillStream(sessionId: string | null): UseSkillStreamReturn {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [agentStatus, setAgentStatus] = useState<AgentStatus>('unknown');
  const [socketReady, setSocketReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectAttemptRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const historyRequestRef = useRef(0);
  const assemblersRef = useRef(new Map<string, StreamAssembler>());
  const activeMessageIdsRef = useRef(new Set<string>());
  const knownUserMessageIdsRef = useRef(new Set<string>());
  const sessionIdRef = useRef<string | null>(sessionId);

  const requestResume = useCallback(() => {
    const ws = wsRef.current;
    const currentSessionId = sessionIdRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN || !currentSessionId) {
      return;
    }

    try {
      ws.send(JSON.stringify({ action: 'resume' }));
    } catch {
      // Best-effort recovery only.
    }
  }, []);

  useEffect(() => {
    sessionIdRef.current = sessionId;
  }, [sessionId]);

  useEffect(() => {
    knownUserMessageIdsRef.current = new Set(
      messages
        .filter((message) => message.role === 'user')
        .map((message) => message.id),
    );
  }, [messages]);

  const resetStreamingState = useCallback(() => {
    assemblersRef.current.clear();
    activeMessageIdsRef.current.clear();
    setIsStreaming(false);
  }, []);

  const finalizeMessage = useCallback((messageId: string) => {
    const assembler = assemblersRef.current.get(messageId);
    if (assembler) {
      assembler.complete();
      const content = assembler.getText();
      const parts = assembler.getParts();

      setMessages((prev) =>
        prev.map((message) =>
          message.id === messageId
            ? {
              ...message,
              content,
              isStreaming: false,
              parts: parts.length > 0 ? [...parts] : message.parts,
            }
            : message,
        ),
      );

      assemblersRef.current.delete(messageId);
    } else {
      setMessages((prev) =>
        prev.map((message) =>
          message.id === messageId ? { ...message, isStreaming: false } : message,
        ),
      );
    }

    activeMessageIdsRef.current.delete(messageId);
    if (activeMessageIdsRef.current.size === 0) {
      setIsStreaming(false);
    }
  }, []);

  const finalizeAllStreamingMessages = useCallback(() => {
    const activeIds = Array.from(activeMessageIdsRef.current);
    activeIds.forEach((messageId) => finalizeMessage(messageId));
  }, [finalizeMessage]);

  const applyStreamedMessage = useCallback((msg: StreamMessage) => {
    const messageId = msg.messageId ?? msg.sourceMessageId ?? genId('stream');
    const role = normalizeRole(msg.role);
    if (role === 'user' || knownUserMessageIdsRef.current.has(messageId)) {
      return;
    }

    let assembler = assemblersRef.current.get(messageId);
    if (!assembler) {
      assembler = new StreamAssembler();
      assemblersRef.current.set(messageId, assembler);
    }

    assembler.handleMessage({ ...msg, messageId, role });
    const hasActiveStreaming = assembler.hasActiveStreaming();
    if (hasActiveStreaming) {
      activeMessageIdsRef.current.add(messageId);
    } else {
      activeMessageIdsRef.current.delete(messageId);
    }
    setIsStreaming(activeMessageIdsRef.current.size > 0);

    const content = assembler.getText();
    const parts = assembler.getParts();
    const timestamp = normalizeTimestamp(msg.emittedAt);

    setMessages((prev) => {
      const existing = prev.find((message) => message.id === messageId);
      const nextMessage: Message = {
        id: messageId,
        role,
        content,
        contentType: existing?.contentType ?? contentTypeForRole(role),
        timestamp: existing?.timestamp ?? timestamp,
        messageSeq: msg.messageSeq ?? existing?.messageSeq,
        meta: existing?.meta,
        isStreaming: hasActiveStreaming,
        parts: parts.length > 0 ? [...parts] : existing?.parts,
      };
      return upsertMessage(prev, nextMessage);
    });
  }, []);

  const restoreStreamingMessage = useCallback((msg: StreamMessage) => {
    const rawParts = Array.isArray(msg.parts) ? msg.parts : [];
    const partMessages = rawParts
      .map((part) => normalizeStreamingPart(part))
      .filter((part): part is StreamMessage => part !== null);

    const messageId = msg.messageId
      ?? partMessages[0]?.messageId
      ?? partMessages[0]?.sourceMessageId
      ?? genId('stream');

    if (partMessages.length === 0) {
      if (msg.sessionStatus === 'idle') {
        finalizeAllStreamingMessages();
      }
      return;
    }

    const role = normalizeRole(msg.role ?? partMessages[0]?.role);
    if (role === 'user' || knownUserMessageIdsRef.current.has(messageId)) {
      return;
    }

    const assembler = new StreamAssembler();
    partMessages.forEach((part) => assembler.handleMessage({
      ...part,
      messageId,
      role: part.role ?? msg.role ?? 'assistant',
      messageSeq: part.messageSeq ?? msg.messageSeq,
    }));
    assemblersRef.current.set(messageId, assembler);

    if (msg.sessionStatus !== 'idle') {
      activeMessageIdsRef.current.add(messageId);
      setIsStreaming(true);
    }

    const content = assembler.getText();
    const parts = assembler.getParts();
    const timestamp = normalizeTimestamp(msg.emittedAt ?? partMessages[0]?.emittedAt);

    setMessages((prev) => upsertMessage(prev, {
      id: messageId,
      role,
      content,
      contentType: contentTypeForRole(role),
      timestamp,
      messageSeq: msg.messageSeq ?? partMessages[0]?.messageSeq,
      isStreaming: msg.sessionStatus !== 'idle',
      parts: parts.length > 0 ? [...parts] : undefined,
    }));
  }, [finalizeAllStreamingMessages]);

  const handleStreamMessage = useCallback((msg: StreamMessage) => {
    const currentSessionId = sessionIdRef.current;
    if (msg.welinkSessionId && (!currentSessionId || String(msg.welinkSessionId) !== String(currentSessionId))) {
      return;
    }

    switch (msg.type) {
      case 'text.delta':
      case 'text.done':
      case 'thinking.delta':
      case 'thinking.done':
      case 'tool.update':
      case 'question':
      case 'permission.ask':
      case 'permission.reply':
      case 'file':
        applyStreamedMessage(msg);
        break;

      case 'step.start':
        if (msg.messageId) {
          activeMessageIdsRef.current.add(msg.messageId);
          setIsStreaming(true);
        }
        break;

      case 'step.done':
        if (msg.messageId) {
          setMessages((prev) =>
            prev.map((message) =>
              message.id === msg.messageId
                ? {
                  ...message,
                  meta: {
                    ...message.meta,
                    tokens: msg.tokens,
                    cost: msg.cost,
                    reason: msg.reason,
                  },
                }
                : message,
            ),
          );
        }
        break;

      case 'session.status':
        if (msg.sessionStatus === 'idle' || msg.sessionStatus === 'completed') {
          finalizeAllStreamingMessages();
        } else if (msg.sessionStatus === 'busy' || msg.sessionStatus === 'retry') {
          setIsStreaming(true);
        }
        break;

      case 'session.error':
        finalizeAllStreamingMessages();
        setError(msg.error ?? 'Session error');
        break;

      case 'session.title':
        break;

      case 'agent.online':
        setAgentStatus('online');
        break;

      case 'agent.offline':
        setAgentStatus('offline');
        break;

      case 'error':
        finalizeAllStreamingMessages();
        setError(msg.error ?? 'Unknown stream error');
        break;

      case 'snapshot':
        if (Array.isArray(msg.messages)) {
          resetStreamingState();
          setMessages(sortMessages(normalizeHistoryMessages(msg.messages)));
        }
        break;

      case 'streaming':
        restoreStreamingMessage(msg);
        break;

      default:
        break;
    }
  }, [applyStreamedMessage, finalizeAllStreamingMessages, resetStreamingState, restoreStreamingMessage]);

  useEffect(() => {
    if (!sessionId) {
      historyRequestRef.current += 1;
      setMessages([]);
      knownUserMessageIdsRef.current.clear();
      resetStreamingState();
      return;
    }

    resetStreamingState();
    setMessages([]);
    knownUserMessageIdsRef.current.clear();
    const requestId = historyRequestRef.current + 1;
    historyRequestRef.current = requestId;
    let cancelled = false;
    (async () => {
      try {
        const response = await api.getMessages(sessionId, 0, 50);
        if (!cancelled && historyRequestRef.current === requestId) {
          const normalized = normalizeHistoryMessages(response.content as unknown as Array<Record<string, unknown>>);
          setMessages((prev) => mergeHistoryMessages(prev, normalized));
          requestResume();
        }
      } catch {
        // History loading failure is non-fatal.
        if (!cancelled && historyRequestRef.current === requestId) {
          requestResume();
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [requestResume, resetStreamingState, sessionId]);

  const connect = useCallback(() => {
    ensureDevUserIdCookie();
    setSocketReady(false);
    const url = `${WS_BASE_URL}/ws/skill/stream`;
    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      reconnectAttemptRef.current = 0;
      setError(null);
      setSocketReady(true);
      requestResume();
    };

    ws.onmessage = (event) => {
      try {
        const raw = JSON.parse(event.data as string) as Record<string, unknown>;
        const msg = normalizeIncomingStreamMessage(raw);
        handleStreamMessage(msg);
      } catch {
        // Ignore malformed messages.
      }
    };

    ws.onerror = () => {
      setError('WebSocket connection error');
      setSocketReady(false);
    };

    ws.onclose = () => {
      wsRef.current = null;
      setSocketReady(false);
      scheduleReconnect();
    };
  }, [handleStreamMessage, requestResume]);

  const scheduleReconnect = useCallback(() => {
    if (reconnectTimerRef.current) {
      return;
    }

    const delay = getReconnectDelay(reconnectAttemptRef.current);
    reconnectAttemptRef.current += 1;
    reconnectTimerRef.current = setTimeout(() => {
      reconnectTimerRef.current = null;
      connect();
    }, delay);
  }, [connect]);

  useEffect(() => {
    connect();

    return () => {
      setSocketReady(false);
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      reconnectAttemptRef.current = 0;
      if (wsRef.current) {
        wsRef.current.onclose = null;
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [connect]);

  const sendMessageFn = useCallback(
    async (text: string, options?: { toolCallId?: string }) => {
      if (!sessionId) {
        return;
      }

      setError(null);
      finalizeAllStreamingMessages();

      const tempId = genId('user');
      const optimisticMessage: Message = {
        id: tempId,
        role: 'user',
        content: text,
        contentType: 'plain',
        timestamp: Date.now(),
      };
      setMessages((prev) => upsertMessage(prev, optimisticMessage));

      try {
        const saved = await api.sendMessage(sessionId, text, options?.toolCallId);
        const normalized = normalizeHistoryMessage(saved as unknown as Record<string, unknown>);
        setMessages((prev) => {
          const nextMessages = prev.filter((message) => message.id !== tempId && message.id !== normalized.id);
          return upsertMessage(nextMessages, normalized);
        });
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to send message';
        setError(message);
      }
    },
    [finalizeAllStreamingMessages, sessionId],
  );

  const replyPermissionFn = useCallback(
    async (permissionId: string, response: 'once' | 'always' | 'reject') => {
      if (!sessionId) {
        return;
      }

      setError(null);
      try {
        await api.replyPermission(sessionId, permissionId, response);
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to reply permission';
        setError(message);
      }
    },
    [sessionId],
  );

  return {
    messages,
    isStreaming,
    agentStatus,
    socketReady,
    sendMessage: sendMessageFn,
    replyPermission: replyPermissionFn,
    error,
  };
}
