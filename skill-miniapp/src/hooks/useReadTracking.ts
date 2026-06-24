import { useCallback, useEffect, useRef } from 'react';
import * as api from '../utils/api';

/**
 * 已读上报追踪 Hook。
 *
 * 职责：
 * - 维护当前会话的 readMessageSeq（已渲染完成的最大 message_seq）
 * - readMessageSeq 变化后 debounce 500ms → REST reportRead
 * - 流式进行中（isStreaming=true）不推进 readMessageSeq
 * - 会话切换时清除旧 timer + 重置游标
 *
 * 用法：
 * ```
 * const { trackMessageRendered } = useReadTracking(activeSessionId, isStreaming);
 * // 当消息渲染完成时调用 trackMessageRendered(seq)
 * ```
 */
export function useReadTracking(
  sessionId: string | null,
  isStreaming: boolean,
): {
  trackMessageRendered: (messageSeq: number) => void;
  /** 直接上报已读，跳过 isStreaming 检查（session.status: idle 时使用） */
  reportReadNow: (messageSeq: number) => void;
} {
  const readSeqRef = useRef(0);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isStreamingRef = useRef(isStreaming);

  // Keep streaming ref in sync
  useEffect(() => {
    isStreamingRef.current = isStreaming;
  }, [isStreaming]);

  // 会话切换时清除旧 timer + 重置游标
  useEffect(() => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
    readSeqRef.current = 0;
  }, [sessionId]);

  // Cleanup debounce timer on unmount
  useEffect(() => {
    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
        debounceRef.current = null;
      }
    };
  }, []);

  const trackMessageRendered = useCallback((messageSeq: number) => {
    // 流式进行中不推进已读游标
    if (isStreamingRef.current) return;
    if (!messageSeq || messageSeq <= 0) return;

    const current = readSeqRef.current;
    if (messageSeq <= current) return;

    readSeqRef.current = messageSeq;

    // debounce 500ms；capture 捕获当前的 sessionId + seq 防止闭包滞后
    const capturedSid = sessionId;
    const capturedSeq = messageSeq;

    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }
    debounceRef.current = setTimeout(() => {
      if (!capturedSid || isStreamingRef.current) return;
      // 仅在 sessionId 未变化（即捕获的 sid 仍等于当前）时上报
      if (capturedSid !== sessionId) return;
      void api.reportRead(capturedSid, capturedSeq);
    }, 500);
  }, [sessionId]);

  const reportReadNow = useCallback((messageSeq: number) => {
    if (!messageSeq || messageSeq <= 0) return;

    // Always advance readSeqRef so trackMessageRendered won't double-fire
    if (messageSeq > readSeqRef.current) {
      readSeqRef.current = messageSeq;
    }

    const capturedSid = sessionId;
    const capturedSeq = messageSeq;

    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }
    debounceRef.current = setTimeout(() => {
      if (!capturedSid) return;
      if (capturedSid !== sessionId) return;
      void api.reportRead(capturedSid, capturedSeq);
    }, 500);
  }, [sessionId]);

  return { trackMessageRendered, reportReadNow };
}
