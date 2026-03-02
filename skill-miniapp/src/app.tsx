import React, { useState, useCallback, useEffect } from 'react';
import { SkillMiniBar } from './pages/SkillMiniBar';
import { SkillMain } from './pages/SkillMain';
import type { MiniBarStatus } from './protocol/types';

/**
 * App — Skill Miniapp entry point.
 *
 * Renders the SkillMiniBar (always visible) and the expanded SkillMain
 * overlay when the user taps "展开".
 *
 * The host IM client triggers this miniapp via the "/" command framework.
 * On mount we simulate the initial SKILL trigger flow:
 *   1. Check for a `sessionId` query parameter (passed by the host).
 *   2. If present, associate with that session; otherwise start fresh.
 */
const App: React.FC = () => {
  const [expanded, setExpanded] = useState(false);
  const [miniBarStatus, setMiniBarStatus] = useState<MiniBarStatus>('processing');
  const [miniBarSummary, setMiniBarSummary] = useState('');
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [imChatId, setImChatId] = useState('');
  const [userId, setUserId] = useState('');

  // ---------------------------------------------------------------
  // Initial SKILL trigger flow
  // ---------------------------------------------------------------
  useEffect(() => {
    // Read parameters from the URL that the host IM client provides.
    const params = new URLSearchParams(window.location.search);
    const sessionId = params.get('sessionId');
    const chatId = params.get('chatId') ?? '';
    const uid = params.get('userId') ?? '';

    if (sessionId) {
      setCurrentSessionId(sessionId);
    }
    setImChatId(chatId);
    setUserId(uid);

    // Register a global callback that the "/" command framework may invoke.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).__SKILL_CALLBACK__ = (payload: {
      action: string;
      sessionId?: string;
      chatId?: string;
    }) => {
      if (payload.action === 'trigger') {
        if (payload.sessionId) setCurrentSessionId(payload.sessionId);
        if (payload.chatId) setImChatId(payload.chatId);
        setExpanded(true);
      }
    };

    return () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (window as any).__SKILL_CALLBACK__;
    };
  }, []);

  // ---------------------------------------------------------------
  // Mini bar status sync
  // ---------------------------------------------------------------
  // In a production integration the host would push status updates;
  // here we expose a global setter so the streaming hook (or host)
  // can drive the mini bar.
  useEffect(() => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).__SKILL_SET_STATUS__ = (
      status: MiniBarStatus,
      summary?: string,
    ) => {
      setMiniBarStatus(status);
      if (summary !== undefined) setMiniBarSummary(summary);
    };

    return () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (window as any).__SKILL_SET_STATUS__;
    };
  }, []);

  const handleExpand = useCallback(() => {
    setExpanded(true);
  }, []);

  const handleCollapse = useCallback(() => {
    setExpanded(false);
  }, []);

  return (
    <>
      <SkillMiniBar
        status={miniBarStatus}
        summary={miniBarSummary}
        onExpand={handleExpand}
      />
      {expanded && (
        <SkillMain
          onCollapse={handleCollapse}
          userId={userId}
          initialSessionId={currentSessionId}
          imChatId={imChatId}
        />
      )}
    </>
  );
};

export default App;
