import { useState, useEffect, useCallback, useRef } from 'react';
import type { Session } from '../protocol/types';
import * as api from '../utils/api';
import { ApiError } from '../utils/api';

/**
 * 从 ApiError.body 中提取业务 code（body 是 ApiResponse 信封：{ code, errormsg, data }）。
 * 后端助理删除校验返回 HTTP 200 + body.code=410；前端必须判 body.code，而非 HTTP status。
 */
function extractBusinessCode(err: unknown): number | undefined {
  if (err instanceof ApiError && err.body && typeof err.body === 'object' && 'code' in err.body) {
    const code = (err.body as { code?: unknown }).code;
    return typeof code === 'number' ? code : undefined;
  }
  return undefined;
}

export interface UseSkillSessionReturn {
  sessions: Session[];
  currentSession: Session | null;
  loading: boolean;
  error: string | null;
  createSession: (params: api.CreateSessionParams) => Promise<Session | null>;
  loadSessions: () => Promise<void>;
  switchSession: (sessionId: string) => void;
  closeSession: (sessionId: string) => Promise<void>;
  updateSessionStatus: (sessionId: string, status: Session['status']) => void;
  updateSessionTitle: (sessionId: string, title: string) => void;
}

export function useSkillSession(): UseSkillSessionReturn {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSession, setCurrentSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const creatingRef = useRef(false);

  const loadSessions = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.getSessions(0, 100);
      setSessions(res.content);
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to load sessions';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  const createSession = useCallback(
    async (params: api.CreateSessionParams): Promise<Session | null> => {
      if (creatingRef.current) return null;
      creatingRef.current = true;
      setError(null);
      try {
        const session = await api.createSession(params);
        setSessions((prev) => [session, ...prev]);
        setCurrentSession(session);
        return session;
      } catch (err) {
        if (extractBusinessCode(err) === 410) {
          setError('该助理已被删除');
        } else {
          const message =
            err instanceof Error ? err.message : 'Failed to create session';
          setError(message);
        }
        return null;
      } finally {
        creatingRef.current = false;
      }
    },
    [],
  );

  const switchSession = useCallback(
    (sessionId: string) => {
      const target = sessions.find((s) => s.id === sessionId) ?? null;
      setCurrentSession(target);
    },
    [sessions],
  );

  const closeSessionFn = useCallback(
    async (sessionId: string) => {
      setError(null);
      try {
        await api.closeSession(sessionId);
        setSessions((prev) => prev.filter((s) => s.id !== sessionId));
        if (currentSession?.id === sessionId) {
          setCurrentSession(null);
        }
      } catch (err) {
        const message =
          err instanceof Error ? err.message : 'Failed to close session';
        setError(message);
      }
    },
    [currentSession],
  );

  const updateSessionStatus = useCallback(
    (sessionId: string, status: Session['status']) => {
      setSessions((prev) =>
        prev.map((s) => (s.id === sessionId ? { ...s, status } : s)),
      );
      setCurrentSession((prev) =>
        prev && prev.id === sessionId ? { ...prev, status } : prev,
      );
    },
    [],
  );

  const updateSessionTitle = useCallback(
    (sessionId: string, title: string) => {
      if (!title) return;
      setSessions((prev) =>
        prev.map((s) => (s.id === sessionId && s.title !== title ? { ...s, title } : s)),
      );
      setCurrentSession((prev) =>
        prev && prev.id === sessionId && prev.title !== title ? { ...prev, title } : prev,
      );
    },
    [],
  );

  // Auto-load sessions on mount
  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  return {
    sessions,
    currentSession,
    loading,
    error,
    createSession,
    loadSessions,
    switchSession,
    closeSession: closeSessionFn,
    updateSessionStatus,
    updateSessionTitle,
  };
}
