import type { OpenCodeEvent, OpenCodeEventType, ParsedEvent } from './types';

/**
 * Known event type strings emitted by the OpenCode SSE stream.
 * Any event whose `type` does not match is classified as 'unknown'.
 */
const KNOWN_TYPES: Record<string, OpenCodeEventType> = {
  'message.updated': 'message.updated',
  'session.completed': 'session.completed',
  'session.created': 'session.created',
  'tool.start': 'tool.start',
  'tool.result': 'tool.result',
  'tool.error': 'tool.error',
};

/**
 * Parse a raw OpenCode event into a typed `ParsedEvent` structure.
 *
 * This function never throws. Unrecognised event types are returned
 * with `eventType: 'unknown'` so that callers can decide whether to
 * ignore or log them.
 */
export function parse(event: OpenCodeEvent): ParsedEvent {
  const eventType: OpenCodeEventType = KNOWN_TYPES[event.type] ?? 'unknown';

  const base: ParsedEvent = {
    eventType,
    sessionId: event.sessionId,
    raw: event,
  };

  switch (eventType) {
    case 'message.updated':
      return {
        ...base,
        delta: event.content?.delta,
        text: event.content?.text,
      };

    case 'session.completed':
      return {
        ...base,
        usage: event.usage,
      };

    case 'session.created':
      return {
        ...base,
        sessionId: event.sessionId,
      };

    case 'tool.start':
      return {
        ...base,
        toolName: event.tool,
        toolArgs: event.args,
      };

    case 'tool.result':
      return {
        ...base,
        toolName: event.tool,
        toolResult: event.result,
      };

    case 'tool.error':
      return {
        ...base,
        toolName: event.tool,
        error: event.error,
      };

    case 'unknown':
    default:
      return base;
  }
}
