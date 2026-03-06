// HTTP API client for Skill Server REST API (v1 protocol)
import type { Session, MessageHistoryItem, PagedResponse } from '../types';

export interface CreateSessionRequest {
  agentId: string;
  metadata?: Record<string, unknown>;
}

export interface CreateSessionResponse {
  sessionId: string;
  agentId: string;
  status: string;
  createdAt: string;
}

export interface SendMessageRequest {
  content: string;
  contentType?: string;
}

export interface PermissionReplyRequest {
  approved: boolean;
  reason?: string;
}

export interface SendToIMRequest {
  targetChat: string;
  messageIds?: number[];
}

export class APIClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  // ==================== Session CRUD ====================

  async createSession(request: CreateSessionRequest): Promise<CreateSessionResponse> {
    const response = await fetch(`${this.baseUrl}/api/skill/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to create session: ${response.status} ${errorText}`);
    }

    return response.json();
  }

  async getSession(sessionId: string): Promise<Session> {
    const response = await fetch(`${this.baseUrl}/api/skill/sessions/${sessionId}`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to get session: ${response.status} ${errorText}`);
    }

    return response.json();
  }

  async listSessions(agentId?: string): Promise<Session[]> {
    const url = agentId
      ? `${this.baseUrl}/api/skill/sessions?agentId=${encodeURIComponent(agentId)}`
      : `${this.baseUrl}/api/skill/sessions`;

    const response = await fetch(url, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to list sessions: ${response.status} ${errorText}`);
    }

    return response.json();
  }

  async deleteSession(sessionId: string): Promise<void> {
    const response = await fetch(`${this.baseUrl}/api/skill/sessions/${sessionId}`, {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to delete session: ${response.status} ${errorText}`);
    }
  }

  // ==================== Messages ====================

  async sendMessage(sessionId: string, request: SendMessageRequest): Promise<void> {
    const response = await fetch(`${this.baseUrl}/api/skill/sessions/${sessionId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to send message: ${response.status} ${errorText}`);
    }
  }

  async getMessages(
    sessionId: string,
    page: number = 0,
    size: number = 20
  ): Promise<PagedResponse<MessageHistoryItem>> {
    const response = await fetch(
      `${this.baseUrl}/api/skill/sessions/${sessionId}/messages?page=${page}&size=${size}`,
      {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
      }
    );

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to get messages: ${response.status} ${errorText}`);
    }

    return response.json();
  }

  // ==================== Permission ====================

  async replyPermission(
    sessionId: string,
    permId: string,
    request: PermissionReplyRequest
  ): Promise<void> {
    const response = await fetch(
      `${this.baseUrl}/api/skill/sessions/${sessionId}/permissions/${permId}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      }
    );

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to reply permission: ${response.status} ${errorText}`);
    }
  }

  // ==================== Send to IM ====================

  async sendToIM(sessionId: string, request: SendToIMRequest): Promise<void> {
    const response = await fetch(
      `${this.baseUrl}/api/skill/sessions/${sessionId}/send-to-im`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      }
    );

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to send to IM: ${response.status} ${errorText}`);
    }
  }
}
