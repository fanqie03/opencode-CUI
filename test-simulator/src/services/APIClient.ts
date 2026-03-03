// HTTP API client for session management
import type { Session } from '../types';

export interface CreateSessionRequest {
  agentId: string;
  metadata?: Record<string, unknown>;
}

export interface CreateSessionResponse {
  sessionId: string;
  agentId: string;
  status: string;
  createdAt: number;
}

export class APIClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    // Convert ws:// to http:// for REST API
    this.baseUrl = baseUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:');
  }

  async createSession(request: CreateSessionRequest): Promise<CreateSessionResponse> {
    const response = await fetch(`${this.baseUrl}/api/sessions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to create session: ${response.status} ${errorText}`);
    }

    return response.json();
  }

  async getSession(sessionId: string): Promise<Session> {
    const response = await fetch(`${this.baseUrl}/api/sessions/${sessionId}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to get session: ${response.status} ${errorText}`);
    }

    return response.json();
  }

  async deleteSession(sessionId: string): Promise<void> {
    const response = await fetch(`${this.baseUrl}/api/sessions/${sessionId}`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to delete session: ${response.status} ${errorText}`);
    }
  }

  async listSessions(agentId?: string): Promise<Session[]> {
    const url = agentId
      ? `${this.baseUrl}/api/sessions?agentId=${encodeURIComponent(agentId)}`
      : `${this.baseUrl}/api/sessions`;

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Failed to list sessions: ${response.status} ${errorText}`);
    }

    return response.json();
  }
}
