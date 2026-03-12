import React from 'react';
import type { Session } from '../protocol/types';

interface SessionSidebarProps {
  sessions: Session[];
  activeSessionId: string | null;
  onSelect: (sessionId: string) => void;
  onNewSession: () => void;
}

const statusColors: Record<string, string> = {
  active: 'var(--success)',
  idle: 'var(--warning)',
  closed: 'var(--text-muted)',
};

function formatTime(dateStr: string): string {
  try {
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60_000);
    if (diffMin < 1) return '刚刚';
    if (diffMin < 60) return `${diffMin}分前`;
    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr}小时前`;
    const diffDay = Math.floor(diffHr / 24);
    if (diffDay < 7) return `${diffDay}天前`;
    return date.toLocaleDateString();
  } catch {
    return dateStr;
  }
}

export const SessionSidebar: React.FC<SessionSidebarProps> = ({
  sessions,
  activeSessionId,
  onSelect,
  onNewSession,
}) => {
  return (
    <div className="sidebar">
      <div className="sidebar-header">
        <span className="title">会话列表</span>
        <button type="button" className="btn btn-new" onClick={onNewSession}>
          + 新会话
        </button>
      </div>
      <div className="sidebar-list">
        {sessions.length === 0 ? (
          <div className="sidebar-empty">暂无会话</div>
        ) : (
          sessions.map((session) => {
            const isActive = session.id === activeSessionId;
            return (
              <div
                key={session.id}
                className={`session-item${isActive ? ' active' : ''}`}
                onClick={() => onSelect(session.id)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') onSelect(session.id);
                }}
              >
                <div className="session-title">
                  {session.title || '未命名会话'}
                </div>
                <div className="session-meta">
                  <span
                    className="session-status-dot"
                    style={{ backgroundColor: statusColors[session.status] ?? 'var(--text-muted)' }}
                  />
                  <span>{session.status}</span>
                  <span>{formatTime(session.updatedAt)}</span>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
