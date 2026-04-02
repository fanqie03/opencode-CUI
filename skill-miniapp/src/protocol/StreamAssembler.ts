import type { StreamMessage, MessagePart, QuestionOption } from './types';

function normalizeQuestionOptions(options: unknown): QuestionOption[] | undefined {
  if (!Array.isArray(options)) {
    return undefined;
  }

  const result: QuestionOption[] = [];
  for (const option of options) {
    if (typeof option === 'string') {
      result.push({ label: option });
    } else if (option && typeof option === 'object') {
      const obj = option as Record<string, unknown>;
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

/**
 * StreamAssembler v2: manages message parts by partId.
 *
 * Instead of a single text accumulator, it manages multiple concurrent
 * parts (text blocks, thinking blocks, tool calls, etc.) organized by
 * their partId. This supports OpenCode's multi-part message structure.
 */
export class StreamAssembler {
  private parts = new Map<string, MessagePart>();
  private partOrder: string[] = [];
  private completed = false;
  private partIdCounter = 0;

  /** Generate a fallback partId when one is not provided */
  private genPartId(prefix: string): string {
    return `${prefix}_${++this.partIdCounter}`;
  }

  /** Get or create a part by ID */
  private getOrCreatePart(partId: string, type: MessagePart['type'], partSeq?: number): MessagePart {
    let part = this.parts.get(partId);
    if (!part) {
      part = {
        partId,
        partSeq,
        type,
        content: '',
        isStreaming: true,
      };
      this.parts.set(partId, part);
      this.insertPartOrder(partId, partSeq);
    } else if (partSeq !== undefined) {
      part.partSeq = partSeq;
    }
    return part;
  }

  private insertPartOrder(partId: string, partSeq?: number): void {
    if (partSeq === undefined) {
      this.partOrder.push(partId);
      return;
    }

    const index = this.partOrder.findIndex((existingId) => {
      const existingSeq = this.parts.get(existingId)?.partSeq;
      return typeof existingSeq === 'number' && existingSeq > partSeq;
    });

    if (index === -1) {
      this.partOrder.push(partId);
      return;
    }

    this.partOrder.splice(index, 0, partId);
  }

  private findPermissionPartId(permissionId?: string): string | null {
    if (!permissionId) {
      return null;
    }

    for (const id of this.partOrder) {
      const part = this.parts.get(id);
      if (part?.type === 'permission' && part.permissionId === permissionId) {
        return id;
      }
    }

    return null;
  }

  /** Handle an incoming StreamMessage and update the appropriate part */
  handleMessage(msg: StreamMessage): void {
    if (this.completed) return;

    switch (msg.type) {
      case 'text.delta': {
        const id = msg.partId || this.findActivePartId('text') || this.genPartId('text');
        const part = this.getOrCreatePart(id, 'text', msg.partSeq);
        part.content += msg.content ?? '';
        part.isStreaming = true;
        part.subagentSessionId = msg.subagentSessionId ?? part.subagentSessionId;
        part.subagentName = msg.subagentName ?? part.subagentName;
        break;
      }

      case 'text.done': {
        const id = msg.partId || this.findActivePartId('text') || this.genPartId('text');
        const part = this.getOrCreatePart(id, 'text', msg.partSeq);
        if (msg.content !== undefined) {
          part.content = msg.content;
        }
        part.isStreaming = false;
        part.subagentSessionId = msg.subagentSessionId ?? part.subagentSessionId;
        part.subagentName = msg.subagentName ?? part.subagentName;
        break;
      }

      case 'thinking.delta': {
        const id = msg.partId || this.findActivePartId('thinking') || this.genPartId('thinking');
        const part = this.getOrCreatePart(id, 'thinking', msg.partSeq);
        part.content += msg.content ?? '';
        part.isStreaming = true;
        part.subagentSessionId = msg.subagentSessionId ?? part.subagentSessionId;
        part.subagentName = msg.subagentName ?? part.subagentName;
        break;
      }

      case 'thinking.done': {
        const id = msg.partId || this.findActivePartId('thinking') || this.genPartId('thinking');
        const part = this.getOrCreatePart(id, 'thinking', msg.partSeq);
        if (msg.content !== undefined) {
          part.content = msg.content;
        }
        part.isStreaming = false;
        part.subagentSessionId = msg.subagentSessionId ?? part.subagentSessionId;
        part.subagentName = msg.subagentName ?? part.subagentName;
        break;
      }

      case 'tool.update': {
        const id = msg.partId || this.genPartId('tool');
        const part = this.getOrCreatePart(id, 'tool', msg.partSeq);
        part.toolName = msg.toolName;
        part.toolCallId = msg.toolCallId;
        part.toolStatus = msg.status;
        part.toolTitle = msg.title;
        if (msg.input) part.toolInput = msg.input;
        if (msg.output) part.toolOutput = msg.output;
        if (msg.error) part.content = msg.error;
        part.isStreaming = msg.status === 'pending' || msg.status === 'running';
        part.subagentSessionId = msg.subagentSessionId ?? part.subagentSessionId;
        part.subagentName = msg.subagentName ?? part.subagentName;
        break;
      }

      case 'question': {
        const id = msg.partId || this.genPartId('question');
        const part = this.getOrCreatePart(id, 'question', msg.partSeq);
        const questionFields = extractQuestionFields(msg.input);
        part.type = 'question';
        part.toolName = msg.toolName ?? part.toolName;
        part.toolCallId = msg.toolCallId ?? part.toolCallId;
        part.toolStatus = msg.status ?? part.toolStatus;
        part.toolOutput = msg.output ?? part.toolOutput;
        if (msg.input) {
          part.toolInput = msg.input;
        }
        part.header = msg.header ?? questionFields.header ?? part.header;
        part.question = msg.question ?? questionFields.question ?? part.question;
        part.options = questionFields.options ?? normalizeQuestionOptions(msg.options) ?? part.options;
        part.subagentSessionId = msg.subagentSessionId ?? part.subagentSessionId;
        part.subagentName = msg.subagentName ?? part.subagentName;
        if (msg.status === 'completed' || msg.status === 'error') {
          part.answered = true;
        }
        if (msg.content !== undefined && msg.content !== '') {
          part.content = msg.content;
        } else if (part.question && !part.content) {
          part.content = part.question;
        }
        part.isStreaming = false;
        break;
      }

      case 'permission.ask': {
        const id = msg.partId || msg.permissionId || this.genPartId('perm');
        const part = this.getOrCreatePart(id, 'permission', msg.partSeq);
        part.permissionId = msg.permissionId;
        part.permType = msg.permType;
        part.toolName = msg.toolName;
        part.content = msg.title ?? msg.content ?? '';
        part.permResolved = false;
        part.isStreaming = false;
        part.subagentSessionId = msg.subagentSessionId;
        part.subagentName = msg.subagentName;
        break;
      }

      case 'permission.reply': {
        const id = this.findPermissionPartId(msg.permissionId) || msg.partId || msg.permissionId || this.genPartId('perm');
        const part = this.getOrCreatePart(id, 'permission', msg.partSeq);
        part.permissionId = msg.permissionId;
        part.permResolved = true;
        part.permissionResponse = msg.response;
        part.isStreaming = false;
        break;
      }

      case 'file': {
        const id = msg.partId || this.genPartId('file');
        const part = this.getOrCreatePart(id, 'file', msg.partSeq);
        part.fileName = msg.fileName;
        part.fileUrl = msg.fileUrl;
        part.fileMime = msg.fileMime;
        part.isStreaming = false;
        part.subagentSessionId = msg.subagentSessionId ?? part.subagentSessionId;
        part.subagentName = msg.subagentName ?? part.subagentName;
        break;
      }

      // step.start / step.done / session.status don't create parts
      default:
        break;
    }
  }

  /** Find the last active (streaming) part of a given type */
  private findActivePartId(type: MessagePart['type']): string | null {
    for (let i = this.partOrder.length - 1; i >= 0; i--) {
      const id = this.partOrder[i];
      const part = this.parts.get(id);
      if (part && part.type === type && part.isStreaming) {
        return id;
      }
    }
    return null;
  }

  /** Get assembled text from all text parts (for backward compat) */
  getText(): string {
    return this.partOrder
      .map(id => this.parts.get(id))
      .filter((p): p is MessagePart => p !== undefined && p.type === 'text')
      .map(p => p.content)
      .join('');
  }

  /** Get all parts in order */
  getParts(): MessagePart[] {
    return this.partOrder
      .map(id => this.parts.get(id))
      .filter((p): p is MessagePart => p !== undefined);
  }

  /** Whether any part is still streaming */
  hasActiveStreaming(): boolean {
    for (const part of this.parts.values()) {
      if (part.isStreaming) return true;
    }
    return false;
  }

  /** Mark a permission part as resolved (used by replyPermission to keep assembler in sync) */
  resolvePermission(permissionId: string, response: string): boolean {
    const partId = this.findPermissionPartId(permissionId);
    if (!partId) return false;
    const part = this.parts.get(partId);
    if (!part) return false;
    part.permResolved = true;
    part.permissionResponse = response;
    return true;
  }

  /** Mark the entire message as done */
  complete(): void {
    this.completed = true;
    for (const part of this.parts.values()) {
      part.isStreaming = false;
    }
  }

  /** Whether complete() has been called */
  isCompleted(): boolean {
    return this.completed;
  }

  /** Reset internal state for a new message */
  reset(): void {
    this.parts.clear();
    this.partOrder = [];
    this.completed = false;
    this.partIdCounter = 0;
  }
}
