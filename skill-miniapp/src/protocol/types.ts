export type MessageRole = 'user' | 'assistant' | 'system' | 'tool';

export interface QuestionOption {
  label: string;
  description?: string;
}

export interface SearchResultItem {
  index: string;
  title: string;
  source: string;
}

export interface ReferenceItem {
  index: string;
  title: string;
  source: string;
  url: string;
  content: string;
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
  | 'session.deleted'
  | 'permission.ask'
  | 'permission.reply'
  | 'agent.online'
  | 'agent.offline'
  | 'message.user'
  | 'planning.delta'
  | 'planning.done'
  | 'searching'
  | 'search_result'
  | 'reference'
  | 'ask_more'
  | 'error'
  | 'snapshot'
  | 'streaming'
  | 'session.unread'
  | 'session.read';

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
  /** opencode question request id（personal scope 快路径）。来源：SS QuestionInfo.questionId @JsonUnwrapped 顶层 */
  questionId?: string;

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

  subagentSessionId?: string;
  subagentName?: string;

  // 未读推送专用字段 (session.unread / session.read)
  maxSeq?: number;
  readSeq?: number;
  assistantAccount?: string;

  // 云端扩展
  keywords?: string[];
  searchResults?: SearchResultItem[];
  references?: ReferenceItem[];
  askMoreQuestions?: string[];

  messages?: Array<Record<string, unknown>>;
  parts?: Array<Record<string, unknown>>;
}

export interface MessagePart {
  partId: string;
  partSeq?: number;
  type: 'text' | 'thinking' | 'tool' | 'question' | 'permission' | 'file' | 'subtask' | 'planning' | 'searching' | 'search_result' | 'reference' | 'ask_more';
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
  /** opencode question request id（personal scope 快路径）；只在实时 question event 出现，历史恢复时为 undefined（D9 fallback） */
  questionId?: string;

  permissionId?: string;
  permType?: string;
  permResolved?: boolean;
  permissionResponse?: string;

  fileName?: string;
  fileUrl?: string;
  fileMime?: string;

  // 云端扩展字段
  keywords?: string[];
  searchResults?: SearchResultItem[];
  references?: ReferenceItem[];
  askMoreQuestions?: string[];

  // Subtask (subagent) 专用字段
  subagentSessionId?: string;
  subagentName?: string;
  subagentPrompt?: string;
  subagentStatus?: 'running' | 'completed' | 'error';
  subParts?: MessagePart[];
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

export interface MessageHistoryPage<T> {
  content: T[];
  size: number;
  hasMore: boolean;
  nextBeforeSeq?: number;
}

export interface Session {
  id: string;
  userId?: string;
  ak?: string;
  title: string;
  businessSessionDomain?: string;
  businessSessionType?: string;
  businessSessionId?: string;
  assistantAccount?: string;
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

// 未读信息查询响应
export interface UnreadSessionItem {
  sessionId: string;
  maxSeq: number;
}

export interface UnreadResponse {
  unreadSessionCount: number;
  unreadSessionList: UnreadSessionItem[];
}

// 已读上报响应
export interface ReadReportResponse {
  welinkSessionId: string;
  unreadCount: number;
}

// WS 未读推送消息 (session.unread / session.read)
export interface UnreadPushMessage {
  type: 'session.unread' | 'session.read';
  welinkSessionId: string;
  maxSeq: number;
  readSeq?: number;
  assistantAccount?: string;
}
