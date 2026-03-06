import { useState, useEffect, useCallback, useRef } from 'react';
import type { Session } from '../protocol/types';
import * as api from '../utils/api';

export interface UseSkillSessionReturn {
  sessions: Session[];
  currentSession: Session | null;
  loading: boolean;
  error: string | null;
  createSession: (params: api.CreateSessionParams) => Promise<Session | null>;
  loadSessions: () => Promise<void>;
  switchSession: (sessionId: string) => void;
  closeSession: (sessionId: string) => Promise<void>;
}

export function useSkillSession(userId: string): UseSkillSessionReturn {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSession, setCurrentSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const creatingRef = useRef(false);

  const loadSessions = useCallback(async () => {
    if (!userId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.getSessions(userId, 0, 100);
      setSessions(res.content);
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to load sessions';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [userId]);

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
        const message =
          err instanceof Error ? err.message : 'Failed to create session';
        setError(message);
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
  };
}
