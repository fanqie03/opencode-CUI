import { useState, useCallback, useRef } from 'react';
import * as api from '../utils/api';

export interface UseSendToImReturn {
  sendToIm: (content: string, chatId: string) => Promise<void>;
  sending: boolean;
  success: boolean;
  error: string | null;
}

export function useSendToIm(sessionId: number | null): UseSendToImReturn {
  const [sending, setSending] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const clearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const sendToIm = useCallback(
    async (content: string, chatId: string) => {
      if (!sessionId) return;

      // Clear any previous success timer
      if (clearTimerRef.current) {
        clearTimeout(clearTimerRef.current);
        clearTimerRef.current = null;
      }

      setSending(true);
      setSuccess(false);
      setError(null);

      try {
        await api.sendToIm(sessionId, content, chatId);
        setSuccess(true);

        // Auto-clear success after 3 seconds
        clearTimerRef.current = setTimeout(() => {
          setSuccess(false);
          clearTimerRef.current = null;
        }, 3000);
      } catch (err) {
        const message =
          err instanceof Error ? err.message : 'Failed to send to IM';
        setError(message);
      } finally {
        setSending(false);
      }
    },
    [sessionId],
  );

  return { sendToIm, sending, success, error };
}
