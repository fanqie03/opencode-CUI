import React, { useState, useEffect, useCallback, useRef } from 'react';
import { SessionSidebar } from './components/SessionSidebar';
import { ConversationView } from './components/ConversationView';
import { MessageInput } from './components/MessageInput';
import { AgentSelector } from './components/AgentSelector';
import { useSkillSession } from './hooks/useSkillSession';
import { useSkillStream } from './hooks/useSkillStream';
import { useAgentSelector } from './hooks/useAgentSelector';
import './index.css';

const DEFAULT_USER_ID = '1'; // Test user

const agentStatusConfig: Record<string, { className: string; label: string }> = {
  online: { className: 'online', label: 'Online' },
  offline: { className: 'offline', label: 'Offline' },
  unknown: { className: 'unknown', label: 'Connecting...' },
};

const App: React.FC = () => {
  const [sidebarVisible, setSidebarVisible] = useState(true);
  const conversationContainerRef = useRef<HTMLDivElement | null>(null);
  const pendingInitialMessageRef = useRef<string | null>(null);

  const {
    sessions,
    currentSession,
    loading: sessionsLoading,
    error: sessionError,
    createSession,
    switchSession,
  } = useSkillSession(DEFAULT_USER_ID);

  const activeSessionId = currentSession?.id ?? null;

  const {
    messages,
    isStreaming,
    agentStatus,
    socketReady,
    sendMessage,
    error: streamError,
  } = useSkillStream(activeSessionId);

  const {
    agents,
    selectedAgent,
    selectAgent,
    loading: agentsLoading,
  } = useAgentSelector(DEFAULT_USER_ID);

  const handleNewSession = useCallback(async () => {
    if (!selectedAgent) return;
    await createSession({
      ak: selectedAgent.akId,
      userId: 1,
      title: `Session ${new Date().toLocaleString()}`,
    });
  }, [createSession, selectedAgent]);

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

      if (!activeSessionId) {
        pendingInitialMessageRef.current = text;
        const session = await createSession({
          ak: selectedAgent.akId,
          userId: 1,
          title: text.slice(0, 50),
        });
        if (!session) {
          pendingInitialMessageRef.current = null;
        }
        return;
      }
      await sendMessage(text);
    },
    [activeSessionId, createSession, sendMessage, selectedAgent],
  );

  const displayError = sessionError ?? streamError;
  const statusCfg = agentStatusConfig[agentStatus] ?? agentStatusConfig.unknown;
  const inputDisabled = isStreaming || !selectedAgent;

  return (
    <div className="app-layout">
      {/* ---- Top Bar ---- */}
      <div className="app-topbar">
        <span className="app-title">💬 OpenCode Chat</span>
        <span className="app-badge">v1</span>
        <span className="spacer" />
        <span className={`status-indicator ${statusCfg.className}`} />
        <span className="status-label">{statusCfg.label}</span>
        <button
          type="button"
          className="btn btn-sidebar"
          onClick={() => setSidebarVisible((v) => !v)}
        >
          {sidebarVisible ? '隐藏侧栏' : '显示侧栏'}
        </button>
      </div>

      {/* ---- Error Banner ---- */}
      {displayError && <div className="error-banner">{displayError}</div>}

      {/* ---- Body ---- */}
      <div className="app-body">
        {sidebarVisible && (
          <SessionSidebar
            sessions={sessions}
            activeSessionId={activeSessionId}
            onSelect={(id) => switchSession(id)}
            onNewSession={handleNewSession}
          />
        )}
        <div className="main-content">
          <div ref={conversationContainerRef} style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <ConversationView messages={messages} loading={sessionsLoading} />
          </div>
          <AgentSelector
            agents={agents}
            selectedAgent={selectedAgent}
            onSelect={selectAgent}
            loading={agentsLoading}
          />
          <MessageInput
            onSend={handleSendMessage}
            disabled={inputDisabled}
            placeholder={
              !selectedAgent
                ? '请先选择 Agent...'
                : activeSessionId
                  ? '输入消息... (Enter 发送, Shift+Enter 换行)'
                  : '输入消息开始新会话...'
            }
          />
        </div>
      </div>
    </div>
  );
};

export default App;
