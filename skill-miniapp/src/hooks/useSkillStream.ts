import { useCallback, useEffect, useRef, useState } from 'react';
import type { Message, MessagePart, MessageRole, StreamMessage, StreamMessageType } from '../protocol/types';
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
  replyPermission: (permissionId: string, response: 'once' | 'always' | 'reject', subagentSessionId?: string) => Promise<void>;
  error: string | null;
}

const WS_BASE_URL =
  typeof import.meta !== 'undefined' && (import.meta as unknown as Record<string, Record<string, string>>).env?.VITE_SKILL_SERVER_WS
    ? (import.meta as unknown as Record<string, Record<string, string>>).env.VITE_SKILL_SERVER_WS
    : (typeof window !== 'undefined'
      ? `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
      : 'ws://localhost:8082');
const STREAM_HEARTBEAT_INTERVAL_MS = 25_000;

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

function streamMessageToSubPart(msg: StreamMessage): MessagePart | null {
  switch (msg.type) {
    case 'text.delta':
    case 'text.done':
      return {
        partId: msg.partId ?? `text-${Date.now()}`,
        type: 'text',
        content: msg.content ?? '',
        isStreaming: msg.type === 'text.delta',
      };
    case 'thinking.delta':
    case 'thinking.done':
      return {
        partId: msg.partId ?? `thinking-${Date.now()}`,
        type: 'thinking',
        content: msg.content ?? '',
        isStreaming: msg.type === 'thinking.delta',
      };
    case 'tool.update':
      return {
        partId: msg.partId ?? msg.toolCallId ?? `tool-${Date.now()}`,
        type: 'tool',
        content: '',
        isStreaming: false,
        toolName: msg.toolName,
        toolCallId: msg.toolCallId,
        toolStatus: msg.status as MessagePart['toolStatus'],
        toolInput: msg.input,
        toolOutput: msg.output,
        toolTitle: msg.title,
      };
    case 'permission.ask':
      return {
        partId: msg.partId ?? msg.permissionId ?? `perm-${Date.now()}`,
        type: 'permission',
        content: msg.title ?? '',
        isStreaming: false,
        permissionId: msg.permissionId,
        permType: msg.permType,
        subagentSessionId: msg.subagentSessionId,
        subagentName: msg.subagentName,
      };
    case 'question':
      return {
        partId: msg.partId ?? msg.toolCallId ?? `q-${Date.now()}`,
        type: 'question',
        content: '',
        isStreaming: false,
        toolCallId: msg.toolCallId,
        header: msg.header,
        question: msg.question,
        options: msg.options,
        subagentSessionId: msg.subagentSessionId,
        subagentName: msg.subagentName,
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

export interface UseSkillStreamOptions {
  onSessionTitleUpdate?: (sessionId: string, title: string) => void;
}

export function useSkillStream(sessionId: string | null, options?: UseSkillStreamOptions): UseSkillStreamReturn {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [agentStatus, setAgentStatus] = useState<AgentStatus>('unknown');
  const [socketReady, setSocketReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectAttemptRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const heartbeatTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const historyRequestRef = useRef(0);
  const assemblersRef = useRef(new Map<string, StreamAssembler>());
  const activeMessageIdsRef = useRef(new Set<string>());
  const knownUserMessageIdsRef = useRef(new Set<string>());
  const sessionIdRef = useRef<string | null>(sessionId);
  const onSessionTitleUpdateRef = useRef(options?.onSessionTitleUpdate);

  const clearHeartbeatTimer = useCallback(() => {
    if (heartbeatTimerRef.current) {
      clearInterval(heartbeatTimerRef.current);
      heartbeatTimerRef.current = null;
    }
  }, []);

  const sendHeartbeat = useCallback((ws?: WebSocket | null) => {
    const socket = ws ?? wsRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return;
    }

    try {
      socket.send(JSON.stringify({ action: 'ping', timestamp: new Date().toISOString() }));
    } catch {
      // Best-effort keepalive only.
    }
  }, []);

  const startHeartbeat = useCallback((ws: WebSocket) => {
    clearHeartbeatTimer();
    heartbeatTimerRef.current = setInterval(() => {
      sendHeartbeat(ws);
    }, STREAM_HEARTBEAT_INTERVAL_MS);
  }, [clearHeartbeatTimer, sendHeartbeat]);

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
    onSessionTitleUpdateRef.current = options?.onSessionTitleUpdate;
  }, [options?.onSessionTitleUpdate]);

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
    // 兜底：即使 activeMessageIdsRef 已为空（例如 text.done 已清理完毕），
    // 也必须确保 isStreaming 被复位。
    // 场景：session.status:busy 直接 setIsStreaming(true) 但不往 activeMessageIdsRef 加 ID，
    // 后续 session.status:idle 到达时 activeIds 为空，forEach 不执行，
    // finalizeMessage 中的 setIsStreaming(false) 永远不会被调用。
    setIsStreaming(false);
  }, [finalizeMessage]);

  const applyStreamedMessage = useCallback((msg: StreamMessage) => {
    const messageId = msg.messageId ?? msg.sourceMessageId ?? genId('stream');
    const role = normalizeRole(msg.role);
    // 只跳过已知的用户消息（miniapp 发送的），允许 OpenCode CLI 发出的 user 消息通过
    if (knownUserMessageIdsRef.current.has(messageId)) {
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

    if (partMessages.length === 0) {
      if (msg.sessionStatus === 'idle') {
        finalizeAllStreamingMessages();
      }
      return;
    }

    // 分离 subagent parts 和主会话 parts
    const subagentParts = partMessages.filter((p) => p.subagentSessionId);
    const mainParts = partMessages.filter((p) => !p.subagentSessionId);

    // subagent parts: 按 subagentSessionId 分组，构建虚拟 SubtaskBlock 消息
    if (subagentParts.length > 0) {
      const grouped = new Map<string, { name: string; parts: StreamMessage[] }>();
      for (const sp of subagentParts) {
        const sid = sp.subagentSessionId!;
        let entry = grouped.get(sid);
        if (!entry) {
          entry = { name: sp.subagentName ?? 'Subagent', parts: [] };
          grouped.set(sid, entry);
        }
        entry.parts.push(sp);
      }
      setMessages((prev) => {
        let next = [...prev];
        for (const [sid, entry] of grouped) {
          const vmId = `subtask-${sid}`;
          if (next.some((m) => m.id === vmId)) continue;
          const subParts = entry.parts
            .map((sp) => streamMessageToSubPart(sp))
            .filter((sp): sp is MessagePart => sp !== null);
          next = [
            ...next,
            {
              id: vmId,
              role: 'assistant' as const,
              content: '',
              contentType: 'plain' as const,
              timestamp: Date.now(),
              isStreaming: msg.sessionStatus !== 'idle',
              parts: [{
                partId: vmId,
                type: 'subtask' as const,
                content: '',
                isStreaming: msg.sessionStatus !== 'idle',
                subagentSessionId: sid,
                subagentName: entry.name,
                subagentPrompt: '',
                subagentStatus: msg.sessionStatus === 'idle' ? 'completed' as const : 'running' as const,
                subParts,
              }],
            },
          ];
        }
        return next;
      });
    }

    // 主会话 parts 走原有 assembler 路径
    if (mainParts.length > 0) {
      const messageId = msg.messageId
        ?? mainParts[0]?.messageId
        ?? mainParts[0]?.sourceMessageId
        ?? genId('stream');

      const role = normalizeRole(msg.role ?? mainParts[0]?.role);
      if (role === 'user' || knownUserMessageIdsRef.current.has(messageId)) {
        return;
      }

      const assembler = new StreamAssembler();
      mainParts.forEach((part) => assembler.handleMessage({
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
      const timestamp = normalizeTimestamp(msg.emittedAt ?? mainParts[0]?.emittedAt);

      setMessages((prev) => upsertMessage(prev, {
        id: messageId,
        role,
        content,
        contentType: contentTypeForRole(role),
        timestamp,
        messageSeq: msg.messageSeq ?? mainParts[0]?.messageSeq,
        isStreaming: msg.sessionStatus !== 'idle',
        parts: parts.length > 0 ? [...parts] : undefined,
      }));
    }

    // 如果只有 subagent parts 且 session 仍在流式中
    if (mainParts.length === 0 && msg.sessionStatus !== 'idle') {
      setIsStreaming(true);
    }
  }, [finalizeAllStreamingMessages]);

  const handleSubagentMessage = useCallback(
    (msg: StreamMessage) => {
      const { subagentSessionId, subagentName, type } = msg;
      if (!subagentSessionId) return;

      // 方案 B：用 subagentSessionId 作为虚拟 message 的 ID
      // 子 session 的事件 messageId 是子 session 内部的，miniapp 中不存在
      // 所以为每个 subagent 创建一条独立的虚拟 message
      const virtualMessageId = `subtask-${subagentSessionId}`;

      // 确保虚拟 message 存在（含单个 subtask part）
      setMessages((prev) => {
        const exists = prev.some((m) => m.id === virtualMessageId);
        if (!exists) {
          return [
            ...prev,
            {
              id: virtualMessageId,
              role: 'assistant' as const,
              content: '',
              contentType: 'plain' as const,
              timestamp: Date.now(),
              isStreaming: true,
              parts: [
                {
                  partId: virtualMessageId,
                  type: 'subtask' as const,
                  content: '',
                  isStreaming: true,
                  subagentSessionId,
                  subagentName: subagentName ?? 'Subagent',
                  subagentPrompt: msg.content ?? '',
                  subagentStatus: 'running' as const,
                  subParts: [],
                },
              ],
            },
          ];
        }
        return prev;
      });

      // 将消息内容追加到 subtask block 的 subParts 中
      const subPart = streamMessageToSubPart(msg);
      if (subPart) {
        setMessages((prev) =>
          prev.map((message) => {
            if (message.id !== virtualMessageId) return message;
            return {
              ...message,
              parts: (message.parts ?? []).map((p) => {
                if (p.type !== 'subtask' || p.subagentSessionId !== subagentSessionId) return p;
                const existing = p.subParts ?? [];

                // text/thinking delta: 按 partId 合并到同一个 part
                if ((msg.type === 'text.delta' || msg.type === 'thinking.delta') && msg.partId) {
                  const idx = existing.findIndex((sp) => sp.partId === subPart.partId);
                  if (idx >= 0) {
                    const updated = [...existing];
                    updated[idx] = { ...updated[idx], content: updated[idx].content + (msg.content ?? '') };
                    return { ...p, subParts: updated };
                  }
                }

                // text.done / thinking.done: 按 partId 替换 delta，标记为非 streaming
                if ((msg.type === 'text.done' || msg.type === 'thinking.done') && msg.partId) {
                  const idx = existing.findIndex((sp) => sp.partId === subPart.partId);
                  if (idx >= 0) {
                    const updated = [...existing];
                    updated[idx] = { ...subPart, content: msg.content ?? updated[idx].content };
                    return { ...p, subParts: updated };
                  }
                }

                // tool.update: 按 toolCallId upsert（更新已有或追加新的）
                if (msg.type === 'tool.update' && subPart.toolCallId) {
                  const idx = existing.findIndex((sp) => sp.type === 'tool' && sp.toolCallId === subPart.toolCallId);
                  if (idx >= 0) {
                    const updated = [...existing];
                    updated[idx] = subPart;
                    return { ...p, subParts: updated };
                  }
                }

                // 其他情况：追加
                return { ...p, subParts: [...existing, subPart] };
              }),
            };
          }),
        );
      }

      // 更新 subtask 状态
      if (msg.type === 'session.status' && msg.sessionStatus) {
        const newStatus = msg.sessionStatus === 'idle' || msg.sessionStatus === 'completed'
          ? 'completed' : msg.sessionStatus === 'error' ? 'error' : undefined;
        if (newStatus) {
          setMessages((prev) =>
            prev.map((message) => {
              if (message.id !== virtualMessageId) return message;
              return {
                ...message,
                isStreaming: false,
                parts: (message.parts ?? []).map((p) =>
                  p.type === 'subtask' && p.subagentSessionId === subagentSessionId
                    ? { ...p, subagentStatus: newStatus, isStreaming: false }
                    : p,
                ),
              };
            }),
          );
        }
      }

      // permission.reply: 更新 SubtaskBlock 内 permission subPart 状态为已处理
      if (type === 'permission.reply') {
        const replyPermId = msg.permissionId;
        if (replyPermId) {
          setMessages((prev) =>
            prev.map((message) => {
              if (message.id !== virtualMessageId) return message;
              return {
                ...message,
                parts: (message.parts ?? []).map((p) => {
                  if (p.type !== 'subtask' || p.subagentSessionId !== subagentSessionId) return p;
                  return {
                    ...p,
                    subParts: (p.subParts ?? []).map((sp) =>
                      sp.type === 'permission' && sp.permissionId === replyPermId
                        ? { ...sp, permResolved: true, permissionResponse: msg.response }
                        : sp,
                    ),
                  };
                }),
              };
            }),
          );
        }
      }
    },
    [setMessages],
  );

  const handleStreamMessage = useCallback((msg: StreamMessage) => {
    const currentSessionId = sessionIdRef.current;
    if (msg.welinkSessionId && (!currentSessionId || String(msg.welinkSessionId) !== String(currentSessionId))) {
      return;
    }

    // Subagent 消息分发
    if (msg.subagentSessionId) {
      handleSubagentMessage(msg);
      return;
    }
    switch (msg.type) {
      case 'text.delta':
      case 'text.done':
      case 'thinking.delta':
      case 'thinking.done':
      case 'tool.update':
      case 'permission.ask':
      case 'file':
        applyStreamedMessage(msg);
        break;

      case 'permission.reply':
        // 不调 applyStreamedMessage（避免创建额外的"已处理"消息）
        // 主会话: PermissionCard 通过 local state 显示已处理
        // subagent: handleSubagentMessage 中更新 SubtaskBlock 内 permission 状态
        break;

      case 'question': {
        const messageId = msg.messageId ?? msg.sourceMessageId;
        const isCompleted = msg.status === 'completed' || msg.status === 'error';
        const hasActiveAssembler = messageId ? assemblersRef.current.has(messageId) : false;

        // If the question is completed and the assembler was already finalized
        // (session.status:idle arrived first), we must NOT recreate the assembler
        // or it would replace all original parts with just the question part.
        // Instead, directly patch the existing message's question part.
        if (isCompleted && messageId && !hasActiveAssembler) {
          setMessages((prev) =>
            prev.map((message) => {
              if (message.id !== messageId || !message.parts) return message;
              const updatedParts = message.parts.map((part) => {
                if (part.type !== 'question') return part;
                // Match by partId or toolCallId
                if (msg.partId && part.partId !== msg.partId &&
                  msg.toolCallId && part.toolCallId !== msg.toolCallId) {
                  return part;
                }
                return {
                  ...part,
                  answered: true,
                  toolStatus: msg.status,
                  toolOutput: msg.output ?? part.toolOutput,
                };
              });
              return { ...message, parts: updatedParts };
            }),
          );
          break;
        }

        applyStreamedMessage(msg);
        break;
      }

      case 'message.user': {
        const messageId = msg.messageId;
        if (!messageId) break;

        // 发送方的乐观更新消息已在 knownUserMessageIdsRef 中，自动跳过
        if (knownUserMessageIdsRef.current.has(messageId)) break;

        const userMsg: Message = {
          id: messageId,
          role: 'user',
          content: msg.content ?? '',
          contentType: 'plain',
          timestamp: msg.emittedAt ? new Date(msg.emittedAt).getTime() : Date.now(),
          messageSeq: msg.messageSeq,
        };

        setMessages((prev) => upsertMessage(prev, userMsg));
        break;
      }

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
        if (msg.error) {
          setError(msg.error);
        }
        break;

      case 'session.title':
        if (msg.title && onSessionTitleUpdateRef.current && sessionIdRef.current) {
          onSessionTitleUpdateRef.current(sessionIdRef.current, msg.title);
        }
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

      case 'streaming': {
        // Snapshot（先到达）已包含完整的历史状态。
        // Streaming 只用于标记会话是否仍在流式中，不创建新消息（避免与 snapshot 重复）。
        if (msg.sessionStatus === 'idle' || msg.sessionStatus === 'completed') {
          finalizeAllStreamingMessages();
        } else if (Array.isArray(msg.parts) && msg.parts.length > 0) {
          // 会话仍在流式中：标记最后一条 assistant 消息为 streaming
          setIsStreaming(true);
          setMessages((prev) => {
            const lastAssistant = [...prev].reverse().find((m) => m.role === 'assistant');
            if (!lastAssistant) return prev;
            return prev.map((m) =>
              m.id === lastAssistant.id ? { ...m, isStreaming: true } : m,
            );
          });
        }
        break;
      }

      default:
        break;
    }
  }, [applyStreamedMessage, handleSubagentMessage, finalizeAllStreamingMessages, resetStreamingState, restoreStreamingMessage]);

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
        const response = await api.getMessageHistory(sessionId, 50);
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
      startHeartbeat(ws);
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
      clearHeartbeatTimer();
      wsRef.current = null;
      setSocketReady(false);
      scheduleReconnect();
    };
  }, [clearHeartbeatTimer, handleStreamMessage, requestResume, startHeartbeat]);

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
      clearHeartbeatTimer();
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
  }, [clearHeartbeatTimer, connect]);

  const sendMessageFn = useCallback(
    async (text: string, options?: { toolCallId?: string }) => {
      if (!sessionId) {
        return;
      }

      setError(null);

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
    [sessionId],
  );

  const replyPermissionFn = useCallback(
    async (permissionId: string, response: 'once' | 'always' | 'reject', subagentSessionId?: string) => {
      if (!sessionId) {
        return;
      }

      // 同步更新 StreamAssembler，防止后续 applyStreamedMessage 重建 parts 时覆盖 permResolved
      for (const assembler of assemblersRef.current.values()) {
        if (assembler.resolvePermission(permissionId, response)) {
          break;
        }
      }

      // 立即更新 messages state 中 permission part 的 permResolved
      // 防止后续 re-render 时 PermissionCard 的 useEffect 重置 resolved 状态
      setMessages((prev) =>
        prev.map((message) => ({
          ...message,
          parts: (message.parts ?? []).map((p) => {
            // 直接在 message parts 中匹配
            if (p.type === 'permission' && p.permissionId === permissionId) {
              return { ...p, permResolved: true, permissionResponse: response };
            }
            // SubtaskBlock 内的 subParts 中匹配
            if (p.type === 'subtask' && p.subParts?.length) {
              return {
                ...p,
                subParts: p.subParts.map((sp) =>
                  sp.type === 'permission' && sp.permissionId === permissionId
                    ? { ...sp, permResolved: true, permissionResponse: response }
                    : sp,
                ),
              };
            }
            return p;
          }),
        })),
      );

      setError(null);
      try {
        await api.replyPermission(sessionId, permissionId, response, subagentSessionId);
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
