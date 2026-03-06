import type { Session, Message } from '../protocol/types';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

// Use empty base URL in dev (Vite proxy handles /api/* -> localhost:8082)
// Set VITE_SKILL_SERVER_URL to override in production
const DEFAULT_BASE_URL =
  typeof import.meta !== 'undefined' && (import.meta as unknown as Record<string, Record<string, string>>).env?.VITE_SKILL_SERVER_URL
    ? (import.meta as unknown as Record<string, Record<string, string>>).env.VITE_SKILL_SERVER_URL
    : '';

let baseURL = DEFAULT_BASE_URL;

/** Override the Skill Server base URL at runtime. */
export function setBaseURL(url: string): void {
  baseURL = url.replace(/\/+$/, '');
}

// ---------------------------------------------------------------------------
// Error handling
// ---------------------------------------------------------------------------

export class ApiError extends Error {
  constructor(
    public status: number,
    public statusText: string,
    public body?: unknown,
  ) {
    super(`API Error ${status}: ${statusText}`);
    this.name = 'ApiError';
  }
}

async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const url = `${baseURL}${path}`;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  };

  const res = await fetch(url, { ...options, headers });

  if (!res.ok) {
    let body: unknown;
    try {
      body = await res.json();
    } catch {
      /* ignore parse errors */
    }
    throw new ApiError(res.status, res.statusText, body);
  }

  // 204 No Content
  if (res.status === 204) {
    return undefined as T;
  }

  return res.json() as Promise<T>;
}

// ---------------------------------------------------------------------------
// Skill Definition
// ---------------------------------------------------------------------------

export interface SkillDefinition {
  id: number;
  skillCode: string;
  skillName: string;
  toolType: string;
  description?: string;
  iconUrl?: string;
  status: string;
}

/** GET /api/skill/definitions */
export function getDefinitions(): Promise<SkillDefinition[]> {
  return request<SkillDefinition[]>('/api/skill/definitions');
}

// ---------------------------------------------------------------------------
// Agent query
// ---------------------------------------------------------------------------

export interface AgentInfo {
  id: number;
  userId: number;
  deviceName: string;
  os: string;
  toolType: string;
  toolVersion: string;
  status: string;
}

/** GET /api/skill/agents?userId={userId} */
export function getOnlineAgents(userId: string): Promise<AgentInfo[]> {
  return request<AgentInfo[]>(`/api/skill/agents?userId=${userId}`);
}

// ---------------------------------------------------------------------------
// Session CRUD
// ---------------------------------------------------------------------------

export interface CreateSessionParams {
  userId?: number;
  skillDefinitionId: number;
  agentId: number;
  title?: string;
  imChatId?: string;
}

interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** POST /api/skill/sessions */
export function createSession(params: CreateSessionParams): Promise<Session> {
  return request<Session>('/api/skill/sessions', {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

/** GET /api/skill/sessions */
export function getSessions(
  userId: string,
  page = 0,
  size = 20,
): Promise<PaginatedResponse<Session>> {
  return request<PaginatedResponse<Session>>(
    `/api/skill/sessions?userId=${userId}&page=${page}&size=${size}`,
  );
}

/** GET /api/skill/sessions/{id} */
export function getSession(id: string): Promise<Session> {
  return request<Session>(`/api/skill/sessions/${id}`);
}

/** DELETE /api/skill/sessions/{id} */
export function closeSession(id: string): Promise<void> {
  return request<void>(`/api/skill/sessions/${id}`, { method: 'DELETE' });
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

/** POST /api/skill/sessions/{id}/messages */
export function sendMessage(
  sessionId: string,
  content: string,
): Promise<Message> {
  return request<Message>(`/api/skill/sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  });
}

/** GET /api/skill/sessions/{id}/messages */
export function getMessages(
  sessionId: string,
  page = 0,
  size = 50,
): Promise<PaginatedResponse<Message>> {
  return request<PaginatedResponse<Message>>(
    `/api/skill/sessions/${sessionId}/messages?page=${page}&size=${size}`,
  );
}

// ---------------------------------------------------------------------------
// Send to IM
// ---------------------------------------------------------------------------

/** POST /api/skill/sessions/{id}/send-to-im */
export function sendToIm(
  sessionId: string,
  content: string,
  chatId: string,
): Promise<void> {
  return request<void>(`/api/skill/sessions/${sessionId}/send-to-im`, {
    method: 'POST',
    body: JSON.stringify({ content, chatId }),
  });
}
