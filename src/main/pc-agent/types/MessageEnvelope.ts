/**
 * MessageEnvelope — Unified protocol envelope for cross-IDE event streaming.
 *
 * Wraps all messages exchanged between PC Agent, AI-Gateway, and Skill Server
 * to provide consistent metadata, versioning, and sequence tracking.
 */

/**
 * Source identifier for the message origin.
 */
export type MessageSource = 'OPENCODE' | 'CURSOR' | 'WINDSURF';

/**
 * Envelope metadata attached to every protocol message.
 */
export interface EnvelopeMetadata {
  /** Protocol version (semver format, e.g., "1.0.0") */
  version: string;

  /** Unique message identifier (UUID v4) */
  messageId: string;

  /** ISO 8601 timestamp of message creation */
  timestamp: string;

  /** Source IDE/tool that generated this message */
  source: MessageSource;

  /** Agent connection ID (assigned by gateway on registration) */
  agentId: string;

  /** Session identifier (optional, for session-scoped messages) */
  sessionId?: string;

  /** Sequence number for ordering within a scope */
  sequenceNumber: number;

  /** Scope for sequence numbering: 'session' or 'agent' */
  sequenceScope: 'session' | 'agent';
}

/**
 * Complete message envelope with metadata and payload.
 */
export interface MessageEnvelope<T = unknown> {
  /** Envelope metadata */
  envelope: EnvelopeMetadata;

  /** Message type discriminator (e.g., 'tool_event', 'invoke', 'tool_done') */
  type: string;

  /** Message payload (type-specific content) */
  payload: T;
}

/**
 * Type guard to check if a message has an envelope.
 */
export function hasEnvelope(msg: unknown): msg is MessageEnvelope {
  return (
    typeof msg === 'object' &&
    msg !== null &&
    'envelope' in msg &&
    typeof (msg as Record<string, unknown>).envelope === 'object' &&
    'type' in msg &&
    'payload' in msg
  );
}

/**
 * Extract envelope metadata from a message, or return undefined.
 */
export function getEnvelope(msg: unknown): EnvelopeMetadata | undefined {
  return hasEnvelope(msg) ? msg.envelope : undefined;
}
