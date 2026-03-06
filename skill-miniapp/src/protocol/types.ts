// ============================================================
// StreamMessage Protocol Type Definitions (v2)
// Adapted from backend StreamMessage.java — 17 message types
// ============================================================

/** All supported StreamMessage type strings */
export type StreamMessageType =
  | 'text.delta'
  | 'text.done'
  | 'thinking.delta'
  | 'thinking.done'
  | 'tool.update'
  | 'question'
  | 'file'
  | 'step.start'
  | 'step.done'
  | 'session.status'
  | 'permission.ask'
  | 'permission.result'
  | 'agent.online'
  | 'agent.offline'
  | 'error'
  | 'snapshot'
  | 'streaming';

/**
 * StreamMessage delivered from Skill Server WebSocket.
 * The backend translates OpenCode events into this semantic format.
 */
export interface StreamMessage {
  type: StreamMessageType;
  seq?: number;
  partId?: string;

  // Text content (text.delta / text.done / thinking.delta / thinking.done)
  content?: string;

  // Tool fields (tool.update)
  toolName?: string;
  toolCallId?: string;
  status?: 'pending' | 'running' | 'completed' | 'error';
  input?: Record<string, unknown>;
  output?: string;
  title?: string;

  // Question fields
  header?: string;
  question?: string;
  options?: string[];

  // Permission fields
  permissionId?: string;
  permType?: string;

  // Step fields
  tokens?: { input?: number; output?: number };
  cost?: number;
  reason?: string;

  // Session status
  sessionStatus?: string;

  // Error
  error?: string;

  // Metadata
  metadata?: Record<string, unknown>;
  raw?: unknown;
}

// ============================================================
// UI State Types
// ============================================================

/** A structured part within an assistant message */
export interface MessagePart {
  partId: string;
  type: 'text' | 'thinking' | 'tool' | 'question' | 'permission' | 'file';
  content: string;
  isStreaming: boolean;

  // Tool-specific
  toolName?: string;
  toolCallId?: string;
  toolStatus?: 'pending' | 'running' | 'completed' | 'error';
  toolInput?: Record<string, unknown>;
  toolOutput?: string;
  toolTitle?: string;

  // Question-specific
  header?: string;
  question?: string;
  options?: string[];
  answered?: boolean;

  // Permission-specific
  permissionId?: string;
  permType?: string;
  permResolved?: boolean;

  // File-specific
  fileName?: string;
  fileUrl?: string;
  fileMime?: string;
}

/** A single message in the conversation */
export interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  contentType: 'markdown' | 'code' | 'plain';
  timestamp: number;
  meta?: Record<string, unknown>;
  isStreaming?: boolean;
  parts?: MessagePart[];
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

/** Tool use information for rendering (legacy, kept for compat) */
export interface ToolUseInfo {
  toolName: string;
  args?: Record<string, unknown>;
  result?: string;
  error?: string;
  status: 'running' | 'completed' | 'error';
}

// Legacy type re-exports for backward compatibility
export type OpenCodeEventType = string;
export type OpenCodeEvent = Record<string, unknown>;
export type ParsedEvent = Record<string, unknown>;
