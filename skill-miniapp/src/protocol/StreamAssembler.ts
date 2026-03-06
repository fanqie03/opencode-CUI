import type { StreamMessage, MessagePart } from './types';

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
  private getOrCreatePart(partId: string, type: MessagePart['type']): MessagePart {
    let part = this.parts.get(partId);
    if (!part) {
      part = {
        partId,
        type,
        content: '',
        isStreaming: true,
      };
      this.parts.set(partId, part);
      this.partOrder.push(partId);
    }
    return part;
  }

  /** Handle an incoming StreamMessage and update the appropriate part */
  handleMessage(msg: StreamMessage): void {
    if (this.completed) return;

    switch (msg.type) {
      case 'text.delta': {
        const id = msg.partId || this.findActivePartId('text') || this.genPartId('text');
        const part = this.getOrCreatePart(id, 'text');
        part.content += msg.content ?? '';
        part.isStreaming = true;
        break;
      }

      case 'text.done': {
        const id = msg.partId || this.findActivePartId('text');
        if (id) {
          const part = this.parts.get(id);
          if (part) {
            if (msg.content) part.content = msg.content;
            part.isStreaming = false;
          }
        } else {
          // Create a completed text part directly
          const newId = this.genPartId('text');
          const part = this.getOrCreatePart(newId, 'text');
          part.content = msg.content ?? '';
          part.isStreaming = false;
        }
        break;
      }

      case 'thinking.delta': {
        const id = msg.partId || this.findActivePartId('thinking') || this.genPartId('thinking');
        const part = this.getOrCreatePart(id, 'thinking');
        part.content += msg.content ?? '';
        part.isStreaming = true;
        break;
      }

      case 'thinking.done': {
        const id = msg.partId || this.findActivePartId('thinking');
        if (id) {
          const part = this.parts.get(id);
          if (part) {
            if (msg.content) part.content = msg.content;
            part.isStreaming = false;
          }
        } else {
          const newId = this.genPartId('thinking');
          const part = this.getOrCreatePart(newId, 'thinking');
          part.content = msg.content ?? '';
          part.isStreaming = false;
        }
        break;
      }

      case 'tool.update': {
        const id = msg.partId || this.genPartId('tool');
        const part = this.getOrCreatePart(id, 'tool');
        part.toolName = msg.toolName;
        part.toolCallId = msg.toolCallId;
        part.toolStatus = msg.status;
        part.toolTitle = msg.title;
        if (msg.input) part.toolInput = msg.input;
        if (msg.output) part.toolOutput = msg.output;
        if (msg.error) part.content = msg.error;
        part.isStreaming = msg.status === 'pending' || msg.status === 'running';
        break;
      }

      case 'question': {
        const id = msg.partId || this.genPartId('question');
        const part = this.getOrCreatePart(id, 'question');
        part.toolName = msg.toolName;
        part.header = msg.header;
        part.question = msg.question;
        part.options = msg.options;
        part.isStreaming = false;
        break;
      }

      case 'permission.ask': {
        const id = msg.partId || msg.permissionId || this.genPartId('perm');
        const part = this.getOrCreatePart(id, 'permission');
        part.permissionId = msg.permissionId;
        part.permType = msg.permType;
        part.toolName = msg.toolName;
        part.content = msg.content ?? '';
        part.isStreaming = false;
        break;
      }

      case 'file': {
        const id = msg.partId || this.genPartId('file');
        const part = this.getOrCreatePart(id, 'file');
        part.fileName = msg.title;
        part.fileUrl = msg.content;
        part.isStreaming = false;
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
