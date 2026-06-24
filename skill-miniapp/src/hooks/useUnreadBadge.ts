import { useCallback, useEffect, useRef, useState } from 'react';
import type { UnreadPushMessage } from '../protocol/types';
import * as api from '../utils/api';

export interface UseUnreadBadgeReturn {
  /** key = sessionId, value = maxSeq (未读消息的最大 seq) */
  unreadMap: Record<string, number>;
  isLoading: boolean;
  error: string | null;
}

function toNumber(v: unknown, fallback = 0): number {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string') {
    const n = Number(v);
    return Number.isFinite(n) ? n : fallback;
  }
  return fallback;
}

/**
 * 未读角标 Hook。
 *
 * 职责：
 * - 启动 / 前台 / WS 重连时，POST /unread 拉取全量未读信息
 * - 收到 session.unread 推送 → 单调校验后更新 unreadMap
 * - 收到 session.read 推送 → 清除对应会话角标
 *
 * 用法：
 * ```
 * const { unreadMap, isLoading } = useUnreadBadge(assistantAccount, sessionIds, unreadPush);
 * // unreadMap[sessionId] > 0 → 显示红点
 * ```
 */
export function useUnreadBadge(
  assistantAccount: string | undefined,
  sessionIds: string[],
  unreadPush: UnreadPushMessage | null,
  /** 触发全量拉取的外部信号（如 WS 重连后） */
  refreshSignal?: number,
  /** 当前活跃会话 ID，切换时立即清除该会话的角标 */
  activeSessionId?: string | null,
): UseUnreadBadgeReturn {
  const [unreadMap, setUnreadMap] = useState<Record<string, number>>({});
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 单调校验：记录每个 sessionId 已知的最大 maxSeq
  const knownMaxSeqRef = useRef<Record<string, number>>({});

  // 拉取全量未读
  const fetchUnread = useCallback(async (aa: string) => {
    setIsLoading(true);
    setError(null);
    try {
      const res = await api.fetchUnreadSessions(aa);
      const map: Record<string, number> = {};
      const seqMap: Record<string, number> = {};
      for (const item of res.unreadSessionList) {
        const seq = toNumber(item.maxSeq);
        map[item.sessionId] = seq;
        seqMap[item.sessionId] = seq;
      }
      setUnreadMap(map);
      knownMaxSeqRef.current = { ...seqMap };
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to fetch unread';
      setError(message);
    } finally {
      setIsLoading(false);
    }
  }, []);

  // 挂载 / assistantAccount 变化 / refreshSignal 变化 → 拉取
  useEffect(() => {
    if (!assistantAccount) {
      setUnreadMap({});
      knownMaxSeqRef.current = {};
      return;
    }
    void fetchUnread(assistantAccount);
  }, [assistantAccount, fetchUnread, refreshSignal]);

  // 点击进入会话时立即清除该会话的角标（不等 Redis 环回）
  useEffect(() => {
    if (!activeSessionId) return;
    setUnreadMap((prev) => {
      if (!(activeSessionId in prev)) return prev;
      const next = { ...prev };
      delete next[activeSessionId];
      return next;
    });
    const nextKnown = { ...knownMaxSeqRef.current };
    delete nextKnown[activeSessionId];
    knownMaxSeqRef.current = nextKnown;
  }, [activeSessionId]);

  // 处理 WS 推送
  useEffect(() => {
    if (!unreadPush) return;

    const { type, welinkSessionId, maxSeq, readSeq } = unreadPush;
    if (!welinkSessionId) return;

    const seq = toNumber(maxSeq);

    if (type === 'session.read') {
      // 已读推送：如果推送的 maxSeq >= 已知 maxSeq，清除角标
      const knownSeq = knownMaxSeqRef.current[welinkSessionId];
      const pushSeq = toNumber(readSeq ?? maxSeq);
      if (knownSeq === undefined || pushSeq >= knownSeq) {
        setUnreadMap((prev) => {
          const next = { ...prev };
          delete next[welinkSessionId];
          return next;
        });
        const nextKnown = { ...knownMaxSeqRef.current };
        delete nextKnown[welinkSessionId];
        knownMaxSeqRef.current = nextKnown;
      }
      return;
    }

    // session.unread：单调校验后更新
    const knownSeq = knownMaxSeqRef.current[welinkSessionId];
    if (knownSeq !== undefined && seq < knownSeq) {
      // 乱序到达的旧消息，丢弃
      return;
    }

    if (knownSeq === undefined || seq >= knownSeq) {
      knownMaxSeqRef.current = {
        ...knownMaxSeqRef.current,
        [welinkSessionId]: seq,
      };
      setUnreadMap((prev) => ({
        ...prev,
        [welinkSessionId]: seq,
      }));
    }
  }, [unreadPush]);

  // 当 sessionIds 变化时，清理已不存在会话的 unreadMap 残留
  useEffect(() => {
    const idSet = new Set(sessionIds);
    setUnreadMap((prev) => {
      let changed = false;
      const next: Record<string, number> = {};
      for (const [sid, seq] of Object.entries(prev)) {
        if (idSet.has(sid)) {
          next[sid] = seq;
        } else {
          changed = true;
        }
      }
      return changed ? next : prev;
    });
    const nextKnown: Record<string, number> = {};
    for (const sid of Object.keys(knownMaxSeqRef.current)) {
      if (idSet.has(sid)) {
        nextKnown[sid] = knownMaxSeqRef.current[sid];
      }
    }
    knownMaxSeqRef.current = nextKnown;
  }, [sessionIds]);

  return { unreadMap, isLoading, error };
}
