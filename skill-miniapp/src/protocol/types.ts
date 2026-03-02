// ============================================================
// OpenCode Event Type Definitions
// Protocol adaptation types for the Skill Miniapp client
// ============================================================

/** Event types from the OpenCode SSE stream */
export type OpenCodeEventType =
  | 'message.updated'
  | 'session.completed'
  | 'session.created'
  | 'tool.start'
  | 'tool.result'
  | 'tool.error'
  | 'unknown';

/** Raw event object received from the OpenCode SSE stream (via transparent relay) */
export interface OpenCodeEvent {
  type: string;
  sessionId?: string;
  content?: { delta?: string; text?: string };
  usage?: { input_tokens: number; output_tokens: number };
  tool?: string;
  args?: Record<string, unknown>;
  result?: string;
  error?: string;
  [key: string]: unknown;
}

/** Parsed and normalized event ready for UI consumption */
export interface ParsedEvent {
  eventType: OpenCodeEventType;
  sessionId?: string;
  delta?: string;
  text?: string;
  usage?: { input_tokens: number; output_tokens: number };
  toolName?: string;
  toolArgs?: Record<string, unknown>;
  toolResult?: string;
  error?: string;
  raw: OpenCodeEvent;
}

/**
 * Stream message delivered from Skill Server WebSocket.
 * The Skill Server transparently relays OpenCode events;
 * the `event` field carries the raw OpenCode event JSON.
 */
export interface StreamMessage {
  type: 'delta' | 'done' | 'error' | 'agent_offline' | 'agent_online';
  seq?: number;
  content?: string;
  usage?: { input_tokens: number; output_tokens: number };
  message?: string;
  event?: OpenCodeEvent;
}

// ============================================================
// UI State Types
// ============================================================

/** A single message in the conversation */
export interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  contentType: 'markdown' | 'code' | 'plain';
  timestamp: number;
  meta?: Record<string, unknown>;
  isStreaming?: boolean;
}

/** A Skill session */
export interface Session {
  id: string;
  title: string;
  status: 'active' | 'idle' | 'closed';
  createdAt: string;
  lastActiveAt: string;
}

/** Mini Bar status indicator */
export type MiniBarStatus = 'processing' | 'completed' | 'error' | 'offline';

/** Tool use information for rendering */
export interface ToolUseInfo {
  toolName: string;
  args?: Record<string, unknown>;
  result?: string;
  error?: string;
  status: 'running' | 'completed' | 'error';
}
