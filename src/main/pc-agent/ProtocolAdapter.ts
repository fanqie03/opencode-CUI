/**
 * ProtocolAdapter — Converts OpenCode events to/from protocol message format.
 *
 * Responsibilities:
 * - Wrap outgoing OpenCode events in protocol format matching full_stack_protocol.md
 * - Maintain per-session sequence counters for gap detection
 * - Generate unique message IDs and timestamps
 *
 * Output format: { type, sessionId, event, envelope }
 */

import { randomUUID } from 'crypto';
import type { EnvelopeMetadata, MessageSource } from './types/MessageEnvelope';
import type { OpenCodeEvent } from './types/PluginTypes';

/**
 * Protocol version for the message envelope.
 * Matches full_stack_protocol.md specification.
 */
const PROTOCOL_VERSION = '1.0.0';

/**
 * Adapter for converting OpenCode events to protocol message format.
 */
export class ProtocolAdapter {
  /** Per-session sequence counters: sessionId -> next sequence number */
  private readonly sessionSequences = new Map<string, number>();

  /** Per-agent sequence counter (for messages without sessionId) */
  private agentSequence = 0;

  /**
   * @param source    The source IDE identifier (e.g., 'OPENCODE')
   * @param agentId   The agent connection ID assigned by the gateway
   */
  constructor(
    private readonly source: MessageSource,
    private readonly agentId: string,
  ) { }

  // -----------------------------------------------------------------------
  // Outgoing: OpenCode Event -> Protocol Message
  // -----------------------------------------------------------------------

  /**
   * Create envelope metadata for a message.
   */
  private createEnvelope(sessionId?: string): EnvelopeMetadata {
    return {
      version: PROTOCOL_VERSION,
      messageId: randomUUID(),
      timestamp: new Date().toISOString(),
      source: this.source,
      agentId: this.agentId,
      sessionId,
      sequenceNumber: this.getNextSequence(sessionId),
      sequenceScope: sessionId ? 'session' : 'agent',
    };
  }

  /**
   * Wrap an OpenCode event as a tool_event message.
   * Output: { type, sessionId, event, envelope }
   */
  wrapToolEvent(event: OpenCodeEvent, sessionId?: string): Record<string, unknown> {
    return {
      type: 'tool_event',
      sessionId,
      event,
      envelope: this.createEnvelope(sessionId),
    };
  }

  /**
   * Wrap a `tool_done` message (sent when session goes idle).
   */
  wrapToolDone(usage: unknown, sessionId?: string): Record<string, unknown> {
    return {
      type: 'tool_done',
      sessionId,
      usage,
      envelope: this.createEnvelope(sessionId),
    };
  }

  /**
   * Wrap a `tool_error` message (sent when an SDK operation fails).
   */
  wrapToolError(error: string, sessionId?: string): Record<string, unknown> {
    return {
      type: 'tool_error',
      sessionId,
      error,
      envelope: this.createEnvelope(sessionId),
    };
  }

  /**
   * Wrap a `session_created` message.
   */
  wrapSessionCreated(
    toolSessionId: string,
    session: unknown,
    sessionId?: string,
  ): Record<string, unknown> {
    return {
      type: 'session_created',
      sessionId,
      toolSessionId,
      session,
      envelope: this.createEnvelope(),
    };
  }

  // -----------------------------------------------------------------------
  // Sequence Management
  // -----------------------------------------------------------------------

  /**
   * Get the next sequence number for a given session (or agent-level if no session).
   */
  getNextSequence(sessionId?: string): number {
    if (sessionId) {
      const current = this.sessionSequences.get(sessionId) ?? 0;
      const next = current + 1;
      this.sessionSequences.set(sessionId, next);
      return next;
    } else {
      return ++this.agentSequence;
    }
  }

  /**
   * Reset sequence counter for a session (e.g., when session closes).
   */
  resetSessionSequence(sessionId: string): void {
    this.sessionSequences.delete(sessionId);
  }

  /**
   * Get current sequence number for a session without incrementing.
   */
  getCurrentSequence(sessionId?: string): number {
    if (sessionId) {
      return this.sessionSequences.get(sessionId) ?? 0;
    } else {
      return this.agentSequence;
    }
  }
}
