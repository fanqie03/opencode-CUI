import React, { useState, useEffect, useCallback, useRef } from 'react';
import { SessionSidebar } from '../components/SessionSidebar';
import { ConversationView } from '../components/ConversationView';
import { MessageInput } from '../components/MessageInput';
import { SendToImButton } from '../components/SendToImButton';
import { AgentSelector } from '../components/AgentSelector';
import { useSkillSession } from '../hooks/useSkillSession';
import { useSkillStream } from '../hooks/useSkillStream';
import { useSendToIm } from '../hooks/useSendToIm';
import { useAgentSelector } from '../hooks/useAgentSelector';

interface SkillMainProps {
  onCollapse: () => void;
  /** Pre-selected session ID (e.g. from the initial SKILL trigger flow). */
  initialSessionId?: string | null;
  /** IM chat ID for the "send to IM" feature. */
  imChatId?: string;
}



const styles: Record<string, React.CSSProperties> = {
  overlay: {
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: '#ffffff',
    display: 'flex',
    flexDirection: 'column',
    zIndex: 100,
  },
  topBar: {
    display: 'flex',
    alignItems: 'center',
    height: 48,
    padding: '0 16px',
    borderBottom: '1px solid #e0e0e0',
    backgroundColor: '#fafafa',
    gap: 12,
    flexShrink: 0,
  },
  collapseBtn: {
    padding: '4px 12px',
    border: '1px solid #d0d0d0',
    borderRadius: 4,
    backgroundColor: '#ffffff',
    color: '#424242',
    fontSize: 12,
    cursor: 'pointer',
    fontWeight: 500,
  },
  skillName: {
    fontSize: 15,
    fontWeight: 600,
    color: '#212121',
  },
  statusIndicator: {
    width: 8,
    height: 8,
    borderRadius: '50%',
    flexShrink: 0,
  },
  statusLabel: {
    fontSize: 12,
    color: '#757575',
  },
  spacer: {
    flex: 1,
  },
  sidebarToggle: {
    padding: '4px 10px',
    border: '1px solid #d0d0d0',
    borderRadius: 4,
    backgroundColor: '#ffffff',
    color: '#616161',
    fontSize: 11,
    cursor: 'pointer',
  },
  body: {
    flex: 1,
    display: 'flex',
    overflow: 'hidden',
  },
  main: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    minWidth: 0,
  },
  errorBanner: {
    padding: '6px 14px',
    backgroundColor: '#ffebee',
    color: '#c62828',
    fontSize: 12,
    borderBottom: '1px solid #ef9a9a',
  },
};

const agentStatusConfig: Record<
  string,
  { color: string; label: string }
> = {
  online: { color: '#4caf50', label: 'Online' },
  offline: { color: '#9e9e9e', label: 'Offline' },
  unknown: { color: '#ff9800', label: 'Connecting...' },
};

export const SkillMain: React.FC<SkillMainProps> = ({
  onCollapse,
  initialSessionId,
  imChatId = '',
}) => {
  const [sidebarVisible, setSidebarVisible] = useState(true);
  const conversationContainerRef = useRef<HTMLDivElement | null>(null);
  const pendingInitialMessageRef = useRef<string | null>(null);

  // Session management
  const {
    sessions,
    currentSession,
    loading: sessionsLoading,
    error: sessionError,
    createSession,
    switchSession,
    // closeSession is available for future use (FR-5.3)
  } = useSkillSession();

  // Determine active session ID (prefer current session, fall back to initial)
  const activeSessionId = currentSession?.id ?? initialSessionId ?? null;

  // Switch to initial session when sessions load
  React.useEffect(() => {
    if (initialSessionId && !currentSession && sessions.length > 0) {
      switchSession(initialSessionId);
    }
  }, [initialSessionId, currentSession, sessions, switchSession]);

  // Streaming
  const {
    messages,
    isStreaming,
    agentStatus,
    socketReady,
    sendMessage,
    replyPermission,
    error: streamError,
  } = useSkillStream(activeSessionId);

  // Send to IM
  const {
    sendToIm,
    sending: imSending,
    success: imSuccess,
    error: imError,
  } = useSendToIm(activeSessionId);

  // Agent selector
  const {
    agents,
    selectedAgent,
    selectAgent,
    loading: agentsLoading,
  } = useAgentSelector();

  const handleNewSession = useCallback(async () => {
    if (!selectedAgent) return;
    await createSession({
      ak: selectedAgent.ak,
      title: `Session ${new Date().toLocaleString()}`,
      imGroupId: imChatId,
    });
  }, [createSession, imChatId, selectedAgent]);

  useEffect(() => {
    if (!activeSessionId || !socketReady || !pendingInitialMessageRef.current) {
      return;
    }

    const text = pendingInitialMessageRef.current;
    pendingInitialMessageRef.current = null;
    void sendMessage(text);
  }, [activeSessionId, socketReady, sendMessage]);

  const handleSendMessage = useCallback(
    async (text: string) => {
      if (!selectedAgent) return;
      // Auto-create a session if none exists
      if (!activeSessionId) {
        pendingInitialMessageRef.current = text;
        const session = await createSession({
          ak: selectedAgent.ak,
          title: text.slice(0, 50),
          imGroupId: imChatId,
        });
        if (!session) {
          pendingInitialMessageRef.current = null;
        }
        return;
      }
      await sendMessage(text);
    },
    [activeSessionId, createSession, imChatId, sendMessage, selectedAgent],
  );

  const handleSendToIm = useCallback(
    (selectedText: string) => {
      void sendToIm(selectedText, imChatId);
    },
    [sendToIm, imChatId],
  );

  const handleQuestionAnswer = useCallback(
    (answer: string, toolCallId?: string) => {
      if (!activeSessionId) {
        void handleSendMessage(answer);
        return;
      }
      void sendMessage(answer, toolCallId ? { toolCallId } : undefined);
    },
    [activeSessionId, handleSendMessage, sendMessage],
  );

  const handlePermissionDecision = useCallback(
    (permissionId: string, response: 'once' | 'always' | 'reject') => {
      void replyPermission(permissionId, response);
    },
    [replyPermission],
  );

  const displayError = sessionError ?? streamError ?? imError;
  const resolvedAgentStatus =
    !socketReady
      ? 'unknown'
      : agentStatus !== 'unknown'
        ? agentStatus
        : selectedAgent
          ? 'online'
          : agentsLoading
            ? 'unknown'
            : 'offline';
  const statusCfg = agentStatusConfig[resolvedAgentStatus] ?? agentStatusConfig.unknown;

  return (
    <div style={styles.overlay}>
      {/* ---- Top Bar ---- */}
      <div style={styles.topBar}>
        <button
          type="button"
          style={styles.collapseBtn}
          onClick={onCollapse}
        >
          收起
        </button>
        <span style={styles.skillName}>OpenCode</span>
        <span
          style={{
            ...styles.statusIndicator,
            backgroundColor: statusCfg.color,
          }}
        />
        <span style={styles.statusLabel}>{statusCfg.label}</span>
        <span style={styles.spacer} />
        <button
          type="button"
          style={styles.sidebarToggle}
          onClick={() => setSidebarVisible((v) => !v)}
        >
          {sidebarVisible ? 'Hide sidebar' : 'Show sidebar'}
        </button>
      </div>

      {/* ---- Error Banner ---- */}
      {displayError && (
        <div style={styles.errorBanner}>{displayError}</div>
      )}

      {/* ---- Body ---- */}
      <div style={styles.body}>
        {sidebarVisible && (
          <SessionSidebar
            sessions={sessions}
            activeSessionId={activeSessionId}
            onSelect={(id) => switchSession(id)}
            onNewSession={handleNewSession}
          />
        )}
        <div style={styles.main}>
          <div ref={conversationContainerRef} style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <ConversationView
              messages={messages}
              loading={sessionsLoading}
              onQuestionAnswer={handleQuestionAnswer}
              onPermissionDecision={handlePermissionDecision}
            />
          </div>
          <AgentSelector
            agents={agents}
            selectedAgent={selectedAgent}
            onSelect={selectAgent}
            loading={agentsLoading}
          />
          <MessageInput
            onSend={handleSendMessage}
            disabled={isStreaming || !selectedAgent}
            placeholder={
              !selectedAgent
                ? '请先选择 Agent...'
                : activeSessionId
                  ? '输入消息... (Shift+Enter 换行)'
                  : '输入消息开始新会话...'
            }
          />
        </div>
      </div>

      {/* ---- Send to IM floating button ---- */}
      <SendToImButton
        onSend={handleSendToIm}
        sending={imSending}
        success={imSuccess}
        error={imError}
        containerRef={conversationContainerRef}
      />
    </div>
  );
};
