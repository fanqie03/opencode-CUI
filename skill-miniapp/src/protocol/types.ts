export type MessageRole = 'user' | 'assistant' | 'system' | 'tool';

export interface QuestionOption {
  label: string;
  description?: string;
}

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
  | 'session.title'
  | 'session.error'
  | 'permission.ask'
  | 'permission.reply'
  | 'agent.online'
  | 'agent.offline'
  | 'error'
  | 'snapshot'
  | 'streaming';

export interface StreamTokenUsage {
  input?: number;
  output?: number;
  reasoning?: number;
  cache?: { read?: number; write?: number };
  [key: string]: unknown;
}

export interface StreamMessage {
  type: StreamMessageType;
  seq?: number;
  welinkSessionId?: string | number;
  emittedAt?: string;
  raw?: unknown;

  messageId?: string;
  messageSeq?: number;
  role?: MessageRole;
  sourceMessageId?: string;

  partId?: string;
  partSeq?: number;
  content?: string;

  toolName?: string;
  toolCallId?: string;
  status?: 'pending' | 'running' | 'completed' | 'error';
  input?: Record<string, unknown>;
  output?: string;
  title?: string;

  header?: string;
  question?: string;
  options?: QuestionOption[];

  permissionId?: string;
  permType?: string;
  metadata?: Record<string, unknown>;
  response?: string;

  tokens?: StreamTokenUsage;
  cost?: number;
  reason?: string;

  sessionStatus?: 'busy' | 'idle' | 'retry' | 'completed' | string;
  error?: string;

  fileName?: string;
  fileUrl?: string;
  fileMime?: string;

  messages?: Array<Record<string, unknown>>;
  parts?: Array<Record<string, unknown>>;
}

export interface MessagePart {
  partId: string;
  partSeq?: number;
  type: 'text' | 'thinking' | 'tool' | 'question' | 'permission' | 'file';
  content: string;
  isStreaming: boolean;

  toolName?: string;
  toolCallId?: string;
  toolStatus?: 'pending' | 'running' | 'completed' | 'error';
  toolInput?: Record<string, unknown>;
  toolOutput?: string;
  toolTitle?: string;

  header?: string;
  question?: string;
  options?: QuestionOption[];
  answered?: boolean;

  permissionId?: string;
  permType?: string;
  permResolved?: boolean;
  permissionResponse?: string;

  fileName?: string;
  fileUrl?: string;
  fileMime?: string;
}

export interface Message {
  id: string;
  role: MessageRole;
  content: string;
  contentType: 'markdown' | 'code' | 'plain';
  timestamp: number;
  messageSeq?: number;
  meta?: Record<string, unknown>;
  isStreaming?: boolean;
  parts?: MessagePart[];
}

export interface Session {
  id: string;
  userId?: string;
  ak?: string;
  title: string;
  imGroupId?: string;
  status: 'active' | 'idle' | 'closed';
  toolSessionId?: string;
  createdAt: string;
  updatedAt: string;
}

export type MiniBarStatus = 'processing' | 'completed' | 'error' | 'offline';

export interface ToolUseInfo {
  toolName: string;
  args?: Record<string, unknown>;
  result?: string;
  error?: string;
  status: 'running' | 'completed' | 'error';
}

export type OpenCodeEventType = string;
export type OpenCodeEvent = Record<string, unknown>;
export type ParsedEvent = Record<string, unknown>;
