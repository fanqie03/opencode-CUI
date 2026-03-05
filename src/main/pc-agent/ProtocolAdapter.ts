/**
 * ProtocolAdapter — Converts OpenCode events to/from MessageEnvelope format.
 *
 * Responsibilities:
 * - Wrap outgoing OpenCode events in unified envelope
 * - Unwrap incoming enveloped commands to OpenCode SDK format
 * - Maintain per-session sequence counters for gap detection
 * - Generate unique message IDs and timestamps
 */

import { randomUUID } from 'crypto';
import type { MessageEnvelope, EnvelopeMetadata, MessageSource } from './types/MessageEnvelope';
import type { OpenCodeEvent } from './types/PluginTypes';

/**
 * Protocol version for the unified message envelope.
 * Increment on breaking changes to envelope structure.
 */
const PROTOCOL_VERSION = '1.0.0';

/**
 * Adapter for converting between OpenCode events and MessageEnvelope protocol.
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
  // Outgoing: OpenCode Event -> MessageEnvelope
  // -----------------------------------------------------------------------

  /**
   * Wrap an OpenCode event in a MessageEnvelope.
   *
   * @param type       Message type (e.g., 'tool_event', 'tool_done', 'tool_error')
   * @param payload    The event payload (raw OpenCode event or custom data)
   * @param sessionId  Optional session identifier for session-scoped messages
   * @returns          Complete MessageEnvelope ready for transmission
   */
  wrapEvent<T = unknown>(type: string, payload: T, sessionId?: string): MessageEnvelope<T> {
    const envelope: EnvelopeMetadata = {
      version: PROTOCOL_VERSION,
      messageId: randomUUID(),
      timestamp: new Date().toISOString(),
      source: this.source,
      agentId: this.agentId,
      sessionId,
      sequenceNumber: this.getNextSequence(sessionId),
      sequenceScope: sessionId ? 'session' : 'agent',
    };

    return {
      envelope,
      type,
      payload,
    };
  }

  /**
   * Convenience method: wrap an OpenCode tool_event.
   */
  wrapToolEvent(event: OpenCodeEvent, sessionId?: string): MessageEnvelope<OpenCodeEvent> {
    return this.wrapEvent('tool_event', event, sessionId);
  }

  /**
   * Convenience method: wrap a tool_done message.
   */
  wrapToolDone(usage: unknown, sessionId?: string): MessageEnvelope<{ usage: unknown }> {
    return this.wrapEvent('tool_done', { usage }, sessionId);
  }

  /**
   * Convenience method: wrap a tool_error message.
   */
  wrapToolError(error: string, sessionId?: string): MessageEnvelope<{ error: string }> {
    return this.wrapEvent('tool_error', { error }, sessionId);
  }

  /**
   * Convenience method: wrap a session_created message.
   */
  wrapSessionCreated(
    toolSessionId: string,
    session: unknown,
  ): MessageEnvelope<{ toolSessionId: string; session: unknown }> {
    return this.wrapEvent('session_created', { toolSessionId, session });
  }

  // -----------------------------------------------------------------------
  // Incoming: MessageEnvelope -> OpenCode Command
  // -----------------------------------------------------------------------

  /**
   * Unwrap an incoming MessageEnvelope to extract the payload.
   *
   * @param envelope  The enveloped message from the gateway
   * @returns         The unwrapped payload
   */
  unwrapEvent<T = unknown>(envelope: MessageEnvelope<T>): T {
    // Future: validate envelope version, log warnings for mismatches
    if (envelope.envelope.version !== PROTOCOL_VERSION) {
      console.warn(
        `[ProtocolAdapter] Protocol version mismatch: expected ${PROTOCOL_VERSION}, got ${envelope.envelope.version}`,
      );
    }

    return envelope.payload;
  }

  // -----------------------------------------------------------------------
  // Sequence Management
  // -----------------------------------------------------------------------

  /**
   * Get the next sequence number for a given session (or agent-level if no session).
   *
   * @param sessionId  Optional session identifier
   * @returns          The next sequence number
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
   *
   * @param sessionId  The session identifier to reset
   */
  resetSessionSequence(sessionId: string): void {
    this.sessionSequences.delete(sessionId);
  }

  /**
   * Get current sequence number for a session without incrementing.
   *
   * @param sessionId  The session identifier
   * @returns          Current sequence number (0 if not yet initialized)
   */
  getCurrentSequence(sessionId?: string): number {
    if (sessionId) {
      return this.sessionSequences.get(sessionId) ?? 0;
    } else {
      return this.agentSequence;
    }
  }
}
