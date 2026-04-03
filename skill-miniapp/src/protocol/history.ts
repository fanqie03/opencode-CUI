import type { Message, MessagePart, QuestionOption } from './types';

interface BackendMessagePart {
  partId?: string | null;
  partSeq?: number | null;
  seq?: number | null;
  type?: string | null;
  partType?: string | null;
  content?: string | null;
  toolName?: string | null;
  toolCallId?: string | null;
  status?: string | null;
  toolStatus?: string | null;
  input?: Record<string, unknown> | null;
  toolInput?: string | Record<string, unknown> | null;
  output?: string | null;
  toolOutput?: string | null;
  error?: string | null;
  toolError?: string | null;
  title?: string | null;
  toolTitle?: string | null;
  header?: string | null;
  question?: string | null;
  options?: unknown[] | null;
  answered?: boolean | null;
  permissionId?: string | null;
  permType?: string | null;
  response?: string | null;
  metadata?: Record<string, unknown> | null;
  fileName?: string | null;
  fileUrl?: string | null;
  fileMime?: string | null;
  subagentSessionId?: string | null;
  subagentName?: string | null;
}

interface BackendMessage {
  id?: string | number | null;
  messageId?: string | null;
  seq?: number | null;
  messageSeq?: number | null;
  role?: string | null;
  content?: string | null;
  contentType?: string | null;
  createdAt?: string | number | null;
  meta?: string | Record<string, unknown> | null;
  parts?: BackendMessagePart[] | null;
}

function normalizeRole(role: string | null | undefined): Message['role'] {
  const value = role?.toLowerCase();
  switch (value) {
    case 'user':
    case 'assistant':
    case 'system':
    case 'tool':
      return value;
    default:
      return 'system';
  }
}

function normalizeContentType(contentType: string | null | undefined): Message['contentType'] {
  const value = contentType?.toLowerCase();
  switch (value) {
    case 'markdown':
    case 'code':
    case 'plain':
      return value;
    default:
      return 'plain';
  }
}

function normalizeTimestamp(createdAt: string | number | null | undefined): number {
  if (typeof createdAt === 'number' && Number.isFinite(createdAt)) {
    return createdAt;
  }

  if (typeof createdAt === 'string') {
    const parsed = Date.parse(createdAt);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }

  return Date.now();
}

function parseObject(value: string | Record<string, unknown> | null | undefined): Record<string, unknown> | undefined {
  if (!value) {
    return undefined;
  }

  if (typeof value === 'object') {
    return value;
  }

  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

function normalizeQuestionOptions(options: unknown): QuestionOption[] | undefined {
  if (!Array.isArray(options)) {
    return undefined;
  }

  const result: QuestionOption[] = [];
  for (const opt of options) {
    if (typeof opt === 'string') {
      result.push({ label: opt });
    } else if (opt && typeof opt === 'object') {
      const obj = opt as Record<string, unknown>;
      const label = typeof obj.label === 'string' ? obj.label : '';
      if (label) {
        result.push({
          label,
          description: typeof obj.description === 'string' ? obj.description : undefined,
        });
      }
    }
  }

  return result.length > 0 ? result : undefined;
}

function extractQuestionFields(input?: Record<string, unknown>): Pick<MessagePart, 'header' | 'question' | 'options'> {
  if (!input) {
    return {};
  }

  if (Array.isArray(input.questions) && input.questions.length > 0) {
    const firstQuestion = input.questions[0];
    if (firstQuestion && typeof firstQuestion === 'object') {
      const question = firstQuestion as Record<string, unknown>;
      return {
        header: typeof question.header === 'string' ? question.header : undefined,
        question: typeof question.question === 'string' ? question.question : undefined,
        options: normalizeQuestionOptions(question.options),
      };
    }
  }

  return {
    header: typeof input.header === 'string' ? input.header : undefined,
    question: typeof input.question === 'string' ? input.question : undefined,
    options: normalizeQuestionOptions(input.options),
  };
}

function normalizePart(raw: BackendMessagePart, index: number): MessagePart | null {
  const partId = raw.partId ?? `part_${index + 1}`;
  const toolInput = raw.input ?? parseObject(raw.toolInput);
  const partSeq = raw.partSeq ?? raw.seq ?? undefined;
  const partType = raw.type ?? raw.partType;

  switch (partType) {
    case 'text':
      return {
        partId,
        partSeq,
        type: 'text',
        content: raw.content ?? '',
        isStreaming: false,
        subagentSessionId: raw.subagentSessionId ?? undefined,
        subagentName: raw.subagentName ?? undefined,
      };

    case 'thinking':
    case 'reasoning':
      return {
        partId,
        partSeq,
        type: 'thinking',
        content: raw.content ?? '',
        isStreaming: false,
        subagentSessionId: raw.subagentSessionId ?? undefined,
        subagentName: raw.subagentName ?? undefined,
      };

    case 'question':
      return {
        partId,
        partSeq,
        type: 'question',
        content: raw.content ?? '',
        isStreaming: false,
        toolName: raw.toolName ?? 'question',
        toolCallId: raw.toolCallId ?? undefined,
        toolStatus: (raw.status ?? raw.toolStatus) as MessagePart['toolStatus'] ?? undefined,
        toolOutput: raw.output ?? raw.toolOutput ?? undefined,
        header: raw.header ?? undefined,
        question: raw.question ?? undefined,
        options: normalizeQuestionOptions(raw.options),
        answered: raw.answered === true
          || raw.status === 'completed'
          || raw.toolStatus === 'completed',
        subagentSessionId: raw.subagentSessionId ?? undefined,
        subagentName: raw.subagentName ?? undefined,
      };

    case 'permission': {
      const resolved = Boolean(
        raw.response
          || raw.status === 'completed'
          || raw.status === 'resolved'
          || raw.status === 'approved'
          || raw.status === 'rejected'
          || raw.toolStatus === 'completed'
          || raw.toolStatus === 'resolved'
          || raw.toolStatus === 'approved'
          || raw.toolStatus === 'rejected',
      );
      return {
        partId,
        partSeq,
        type: 'permission',
        content: raw.content ?? raw.title ?? '',
        isStreaming: false,
        permissionId: raw.permissionId ?? raw.toolCallId ?? partId,
        permType: raw.permType ?? raw.toolName ?? undefined,
        permResolved: resolved,
        permissionResponse: raw.response ?? raw.output ?? raw.toolOutput ?? undefined,
        subagentSessionId: raw.subagentSessionId ?? undefined,
        subagentName: raw.subagentName ?? undefined,
      };
    }

    case 'tool': {
      const toolStatus = raw.status ?? raw.toolStatus;
      if (raw.toolName === 'question' && toolStatus === 'running') {
        const questionFields = extractQuestionFields(toolInput);
        return {
          partId,
          partSeq,
          type: 'question',
          content: raw.content ?? '',
          isStreaming: false,
          toolName: raw.toolName ?? undefined,
          toolCallId: raw.toolCallId ?? undefined,
          header: questionFields.header,
          question: questionFields.question,
          options: questionFields.options,
          subagentSessionId: raw.subagentSessionId ?? undefined,
          subagentName: raw.subagentName ?? undefined,
        };
      }

      return {
        partId,
        partSeq,
        type: 'tool',
        content: raw.error ?? raw.toolError ?? raw.content ?? '',
        isStreaming: false,
        toolName: raw.toolName ?? undefined,
        toolCallId: raw.toolCallId ?? undefined,
        toolStatus: (toolStatus as MessagePart['toolStatus']) ?? undefined,
        toolInput,
        toolOutput: raw.output ?? raw.toolOutput ?? undefined,
        toolTitle: raw.title ?? raw.toolTitle ?? undefined,
        subagentSessionId: raw.subagentSessionId ?? undefined,
        subagentName: raw.subagentName ?? undefined,
      };
    }

    case 'file':
      return {
        partId,
        partSeq,
        type: 'file',
        content: raw.content ?? '',
        isStreaming: false,
        fileName: raw.fileName ?? undefined,
        fileUrl: raw.fileUrl ?? undefined,
        fileMime: raw.fileMime ?? undefined,
        subagentSessionId: raw.subagentSessionId ?? undefined,
        subagentName: raw.subagentName ?? undefined,
      };

    default:
      return null;
  }
}

function groupPartsIntoSubtasks(parts: MessagePart[]): MessagePart[] {
  const result: MessagePart[] = [];
  const subtaskMap = new Map<string, MessagePart>();

  for (const part of parts) {
    if (part.subagentSessionId) {
      let subtask = subtaskMap.get(part.subagentSessionId);
      if (!subtask) {
        subtask = {
          partId: `subtask-${part.subagentSessionId}`,
          type: 'subtask',
          content: '',
          isStreaming: false,
          subagentSessionId: part.subagentSessionId,
          subagentName: part.subagentName ?? 'Subagent',
          subagentPrompt: '',
          subagentStatus: 'completed',
          subParts: [],
        };
        subtaskMap.set(part.subagentSessionId, subtask);
        result.push(subtask);
      }
      subtask.subParts!.push(part);
    } else {
      result.push(part);
    }
  }
  return result;
}

function normalizeMeta(meta: string | Record<string, unknown> | null | undefined): Record<string, unknown> | undefined {
  return parseObject(meta);
}

export function normalizeHistoryMessage(raw: BackendMessage): Message {
  const parts = Array.isArray(raw.parts)
    ? raw.parts
      .map((part, index) => normalizePart(part, index))
      .filter((part): part is MessagePart => part !== null)
    : [];

  const derivedContent = parts
    .filter((part) => part.type === 'text')
    .map((part) => part.content)
    .join('');

  return {
    id: String(raw.messageId ?? raw.id ?? `history_${Math.random().toString(36).slice(2)}`),
    role: normalizeRole(raw.role),
    content: raw.content ?? derivedContent,
    contentType: normalizeContentType(raw.contentType),
    timestamp: normalizeTimestamp(raw.createdAt),
    messageSeq: typeof raw.messageSeq === 'number'
      ? raw.messageSeq
      : (typeof raw.seq === 'number' ? raw.seq : undefined),
    meta: normalizeMeta(raw.meta),
    isStreaming: false,
    parts: parts.length > 0 ? groupPartsIntoSubtasks(parts) : undefined,
  };
}

export function normalizeHistoryMessages(rawMessages: BackendMessage[]): Message[] {
  const messages = rawMessages
    .map(normalizeHistoryMessage)
    // 过滤掉空白的用户消息（服务端 bug 产生的残留记录）
    .filter((msg) => !(msg.role === 'user' && !msg.content && (!msg.parts || msg.parts.length === 0)));

  // 跨 message 合并 subagent parts 为虚拟 SubtaskBlock message（方案 B）
  return mergeSubagentPartsAcrossMessages(messages);
}

/**
 * 将分散在多条 message 中的 subagent parts 合并为独立的虚拟 SubtaskBlock message。
 * 同一个 subagentSessionId 的所有 parts 合并到同一个 SubtaskBlock 中。
 */
function mergeSubagentPartsAcrossMessages(messages: Message[]): Message[] {
  // 收集所有 subagent parts，按 subagentSessionId 分组
  const subtaskMap = new Map<string, { parts: MessagePart[]; name: string }>();

  for (const msg of messages) {
    for (const part of msg.parts ?? []) {
      const sid = part.type === 'subtask' ? part.subagentSessionId : part.subagentSessionId;
      if (!sid) continue;
      let entry = subtaskMap.get(sid);
      if (!entry) {
        entry = { parts: [], name: part.subagentName ?? 'Subagent' };
        subtaskMap.set(sid, entry);
      }
      if (part.type === 'subtask' && part.subParts) {
        entry.parts.push(...part.subParts);
      } else {
        entry.parts.push(part);
      }
    }
  }

  if (subtaskMap.size === 0) return messages;

  // 重建消息列表：移除 subagent parts，在首次出现位置插入虚拟消息
  const inserted = new Set<string>();
  const result: Message[] = [];

  for (const msg of messages) {
    const parts = msg.parts ?? [];
    const normalParts = parts.filter(
      (p) => !p.subagentSessionId && p.type !== 'subtask',
    );

    // 找到本条消息涉及的 subagentSessionId（按出现顺序）
    const sidsInMsg: string[] = [];
    for (const p of parts) {
      const sid = p.type === 'subtask' ? p.subagentSessionId : p.subagentSessionId;
      if (sid && !sidsInMsg.includes(sid)) sidsInMsg.push(sid);
    }

    // 在首次出现位置前插入虚拟 SubtaskBlock
    for (const sid of sidsInMsg) {
      if (inserted.has(sid)) continue;
      inserted.add(sid);
      const entry = subtaskMap.get(sid)!;
      result.push({
        id: `subtask-${sid}`,
        role: 'assistant',
        content: '',
        contentType: 'plain',
        timestamp: msg.timestamp,
        isStreaming: false,
        parts: [{
          partId: `subtask-${sid}`,
          type: 'subtask',
          content: '',
          isStreaming: false,
          subagentSessionId: sid,
          subagentName: entry.name,
          subagentPrompt: '',
          subagentStatus: 'completed',
          subParts: entry.parts,
        }],
      });
    }

    // 保留有内容的非 subagent 消息
    if (normalParts.length > 0 || (msg.content && !sidsInMsg.length)) {
      result.push(normalParts.length < parts.length
        ? { ...msg, parts: normalParts.length > 0 ? normalParts : undefined }
        : msg,
      );
    } else if (sidsInMsg.length === 0) {
      // 无 subagent 也无 parts 的消息（如用户消息）
      result.push(msg);
    }
  }

  return result;
}
