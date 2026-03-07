import type { Message, MessagePart } from './types';

interface BackendMessagePart {
  partId?: string | null;
  seq?: number | null;
  partType?: string | null;
  content?: string | null;
  toolName?: string | null;
  toolCallId?: string | null;
  toolStatus?: string | null;
  toolInput?: string | Record<string, unknown> | null;
  toolOutput?: string | null;
  toolError?: string | null;
  toolTitle?: string | null;
  fileName?: string | null;
  fileUrl?: string | null;
  fileMime?: string | null;
}

interface BackendMessage {
  id?: string | number | null;
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

function normalizeQuestionOptions(options: unknown): string[] | undefined {
  if (!Array.isArray(options)) {
    return undefined;
  }

  const labels = options
    .map((opt) => {
      if (typeof opt === 'string') {
        return opt;
      }
      if (opt && typeof opt === 'object') {
        const label = (opt as { label?: unknown }).label;
        return typeof label === 'string' ? label : '';
      }
      return '';
    })
    .filter((opt): opt is string => Boolean(opt));

  return labels.length > 0 ? labels : undefined;
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
  const toolInput = parseObject(raw.toolInput);

  switch (raw.partType) {
    case 'text':
      return {
        partId,
        type: 'text',
        content: raw.content ?? '',
        isStreaming: false,
      };

    case 'reasoning':
      return {
        partId,
        type: 'thinking',
        content: raw.content ?? '',
        isStreaming: false,
      };

    case 'tool': {
      if (raw.toolName === 'question' && raw.toolStatus === 'running') {
        const questionFields = extractQuestionFields(toolInput);
        return {
          partId,
          type: 'question',
          content: raw.content ?? '',
          isStreaming: false,
          toolName: raw.toolName ?? undefined,
          toolCallId: raw.toolCallId ?? undefined,
          header: questionFields.header,
          question: questionFields.question,
          options: questionFields.options,
        };
      }

      return {
        partId,
        type: 'tool',
        content: raw.toolError ?? raw.content ?? '',
        isStreaming: false,
        toolName: raw.toolName ?? undefined,
        toolCallId: raw.toolCallId ?? undefined,
        toolStatus: (raw.toolStatus as MessagePart['toolStatus']) ?? undefined,
        toolInput,
        toolOutput: raw.toolOutput ?? undefined,
        toolTitle: raw.toolTitle ?? undefined,
      };
    }

    case 'file':
      return {
        partId,
        type: 'file',
        content: raw.content ?? '',
        isStreaming: false,
        fileName: raw.fileName ?? undefined,
        fileUrl: raw.fileUrl ?? undefined,
        fileMime: raw.fileMime ?? undefined,
      };

    default:
      return null;
  }
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
    id: String(raw.id ?? `history_${Math.random().toString(36).slice(2)}`),
    role: normalizeRole(raw.role),
    content: raw.content ?? derivedContent,
    contentType: normalizeContentType(raw.contentType),
    timestamp: normalizeTimestamp(raw.createdAt),
    meta: normalizeMeta(raw.meta),
    isStreaming: false,
    parts: parts.length > 0 ? parts : undefined,
  };
}

export function normalizeHistoryMessages(rawMessages: BackendMessage[]): Message[] {
  return rawMessages.map(normalizeHistoryMessage);
}
