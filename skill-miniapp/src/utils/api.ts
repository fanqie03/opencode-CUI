import type { Session, Message } from '../protocol/types';
import { ensureDevUserIdCookie } from './devAuth';

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
  ensureDevUserIdCookie();
  const url = `${baseURL}${path}`;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  };

  const res = await fetch(url, {
    credentials: options.credentials ?? 'include',
    ...options,
    headers,
  });

  if (!res.ok) {
    let body: unknown;
    try {
      body = await res.json();
    } catch {
      /* ignore parse errors */
    }

    const errorText =
      body !== null &&
        typeof body === 'object' &&
        'errormsg' in body &&
        typeof body.errormsg === 'string'
        ? body.errormsg
        : res.statusText;

    throw new ApiError(res.status, errorText, body);
  }

  // 204 No Content
  if (res.status === 204) {
    return undefined as T;
  }

  const json = await res.json();

  // Auto-unwrap ApiResponse wrapper: { code, errormsg, data }
  if (
    json !== null &&
    typeof json === 'object' &&
    'code' in json &&
    'data' in json
  ) {
    if (json.code !== 0) {
      throw new ApiError(res.status, json.errormsg ?? 'Unknown error', json);
    }
    return json.data as T;
  }

  return json as T;
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
  id: string;
  userId?: string;
  ak: string;
  deviceName: string;
  os: string;
  toolType: string;
  toolVersion: string;
  status: string;
}

interface RawAgentInfo {
  id?: string | number | null;
  userId?: string | number | null;
  akId?: string | null;
  ak?: string | null;
  deviceName?: string | null;
  os?: string | null;
  toolType?: string | null;
  toolVersion?: string | null;
  status?: string | null;
}

function normalizeAgent(raw: RawAgentInfo): AgentInfo | null {
  const ak = raw.ak?.trim() || raw.akId?.trim() || '';
  if (!ak) {
    return null;
  }

  const normalizedUserId =
    raw.userId == null || raw.userId === ''
      ? undefined
      : String(raw.userId);

  return {
    id: raw.id != null ? String(raw.id) : ak,
    userId: normalizedUserId,
    ak,
    deviceName: raw.deviceName?.trim() || ak,
    os: raw.os?.trim() || 'UNKNOWN',
    toolType: raw.toolType?.trim() || 'UNKNOWN',
    toolVersion: raw.toolVersion?.trim() || '',
    status: raw.status?.trim() || 'UNKNOWN',
  };
}

/** GET /api/skill/agents */
export function getOnlineAgents(): Promise<AgentInfo[]> {
  return request<RawAgentInfo[]>('/api/skill/agents').then((agents) =>
    agents
      .map((agent) => normalizeAgent(agent))
      .filter((agent): agent is AgentInfo => agent !== null),
  );
}

// ---------------------------------------------------------------------------
// Session CRUD
// ---------------------------------------------------------------------------

export interface CreateSessionParams {
  ak: string;
  title?: string;
  imGroupId?: string;
}

interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

interface BackendSession {
  welinkSessionId?: string | number | null;
  userId?: string | null;
  ak?: string | null;
  title?: string | null;
  imGroupId?: string | null;
  status?: string | null;
  toolSessionId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

function normalizeSessionStatus(status: string | null | undefined): Session['status'] {
  switch (status?.toUpperCase()) {
    case 'ACTIVE':
      return 'active';
    case 'CLOSED':
      return 'closed';
    default:
      return 'idle';
  }
}

function normalizeSession(raw: BackendSession): Session {
  const createdAt = raw.createdAt ?? new Date().toISOString();
  return {
    id: raw.welinkSessionId != null ? String(raw.welinkSessionId) : '0',
    userId: raw.userId ?? undefined,
    ak: raw.ak ?? undefined,
    title: raw.title ?? '',
    imGroupId: raw.imGroupId ?? undefined,
    status: normalizeSessionStatus(raw.status),
    toolSessionId: raw.toolSessionId ?? undefined,
    createdAt,
    updatedAt: raw.updatedAt ?? createdAt,
  };
}

/** POST /api/skill/sessions */
export function createSession(params: CreateSessionParams): Promise<Session> {
  return request<BackendSession>('/api/skill/sessions', {
    method: 'POST',
    body: JSON.stringify(params),
  }).then(normalizeSession);
}

/** GET /api/skill/sessions */
export function getSessions(
  page = 0,
  size = 20,
): Promise<PaginatedResponse<Session>> {
  return request<PaginatedResponse<BackendSession>>(
    `/api/skill/sessions?page=${page}&size=${size}`,
  ).then((pageResult) => ({
    ...pageResult,
    content: pageResult.content.map(normalizeSession),
  }));
}

/** GET /api/skill/sessions/{id} */
export function getSession(id: string | number): Promise<Session> {
  return request<BackendSession>(`/api/skill/sessions/${id}`).then(normalizeSession);
}

/** DELETE /api/skill/sessions/{id} */
export function closeSession(id: string | number): Promise<void> {
  return request<void>(`/api/skill/sessions/${id}`, { method: 'DELETE' });
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

/** POST /api/skill/sessions/{id}/messages */
export function sendMessage(
  sessionId: string | number,
  content: string,
  toolCallId?: string,
): Promise<Message> {
  return request<Message>(`/api/skill/sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify(toolCallId ? { content, toolCallId } : { content }),
  });
}

/** GET /api/skill/sessions/{id}/messages */
export function getMessages(
  sessionId: string | number,
  page = 0,
  size = 50,
): Promise<PaginatedResponse<Message>> {
  return request<PaginatedResponse<Message>>(
    `/api/skill/sessions/${sessionId}/messages?page=${page}&size=${size}`,
  );
}

/** POST /api/skill/sessions/{id}/permissions/{permId} */
export function replyPermission(
  sessionId: string | number,
  permissionId: string,
  response: 'once' | 'always' | 'reject',
): Promise<{ welinkSessionId: string; permissionId: string; response: string }> {
  return request<{ welinkSessionId: string; permissionId: string; response: string }>(
    `/api/skill/sessions/${sessionId}/permissions/${permissionId}`,
    {
      method: 'POST',
      body: JSON.stringify({ response }),
    },
  );
}

// ---------------------------------------------------------------------------
// Send to IM
// ---------------------------------------------------------------------------

/** POST /api/skill/sessions/{id}/send-to-im */
export function sendToIm(
  sessionId: string | number,
  content: string,
  chatId: string,
): Promise<void> {
  return request<void>(`/api/skill/sessions/${sessionId}/send-to-im`, {
    method: 'POST',
    body: JSON.stringify({ content, chatId }),
  });
}
