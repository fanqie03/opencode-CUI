/**
 * OpenCodeEventParser (v2 — simplified)
 *
 * With the backend now translating OpenCode events into semantic
 * StreamMessage format, this parser is largely vestigial. It provides
 * a thin compatibility layer for any code that still references it.
 *
 * The primary message handling is done in useSkillStream via
 * StreamMessage types directly.
 */

import type { StreamMessage } from './types';

/**
 * Classify a StreamMessage type into a broad category.
 * Useful for logging and debugging.
 */
export function classifyType(type: string): 'content' | 'tool' | 'session' | 'error' | 'unknown' {
  if (type.startsWith('text.') || type.startsWith('thinking.')) return 'content';
  if (type.startsWith('tool.') || type === 'question' || type === 'file') return 'tool';
  if (type.startsWith('session.') || type.startsWith('agent.') || type.startsWith('permission.') || type.startsWith('step.')) return 'session';
  if (type === 'error') return 'error';
  return 'unknown';
}

/**
 * Check if a StreamMessage represents a final/completed state.
 */
export function isFinalState(msg: StreamMessage): boolean {
  return (
    msg.type === 'text.done' ||
    msg.type === 'thinking.done' ||
    msg.type === 'session.status' ||
    msg.type === 'step.done' ||
    (msg.type === 'tool.update' && (msg.status === 'completed' || msg.status === 'error'))
  );
}

/**
 * Check if a StreamMessage represents streaming/in-progress state.
 */
export function isStreamingState(msg: StreamMessage): boolean {
  return (
    msg.type === 'text.delta' ||
    msg.type === 'thinking.delta' ||
    (msg.type === 'tool.update' && (msg.status === 'pending' || msg.status === 'running'))
  );
}
