import React from 'react';
import type { Session } from '../protocol/types';

interface SessionSidebarProps {
  sessions: Session[];
  activeSessionId: string | null;
  onSelect: (sessionId: string) => void;
  onNewSession: () => void;
}

const statusColors: Record<string, string> = {
  active: '#4caf50',
  idle: '#ff9800',
  closed: '#9e9e9e',
};

const styles: Record<string, React.CSSProperties> = {
  sidebar: {
    width: 240,
    borderRight: '1px solid #e0e0e0',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#fafafa',
    overflowY: 'auto',
    flexShrink: 0,
  },
  header: {
    padding: '12px 14px',
    borderBottom: '1px solid #e0e0e0',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  headerTitle: {
    fontSize: 13,
    fontWeight: 600,
    color: '#424242',
  },
  newBtn: {
    padding: '4px 10px',
    border: '1px solid #1976d2',
    borderRadius: 6,
    backgroundColor: '#ffffff',
    color: '#1976d2',
    fontSize: 12,
    fontWeight: 600,
    cursor: 'pointer',
  },
  list: {
    flex: 1,
    overflowY: 'auto',
  },
  item: {
    padding: '10px 14px',
    cursor: 'pointer',
    borderBottom: '1px solid #f0f0f0',
    transition: 'background-color 0.15s',
  },
  itemActive: {
    backgroundColor: '#e3f2fd',
  },
  itemTitle: {
    fontSize: 13,
    fontWeight: 500,
    color: '#212121',
    marginBottom: 4,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  itemMeta: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    fontSize: 11,
    color: '#9e9e9e',
  },
  statusDot: {
    width: 6,
    height: 6,
    borderRadius: '50%',
    display: 'inline-block',
    flexShrink: 0,
  },
  empty: {
    padding: 20,
    textAlign: 'center',
    fontSize: 13,
    color: '#bdbdbd',
  },
};

function formatTime(dateStr: string): string {
  try {
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60_000);

    if (diffMin < 1) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;

    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h ago`;

    const diffDay = Math.floor(diffHr / 24);
    if (diffDay < 7) return `${diffDay}d ago`;

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
    <div style={styles.sidebar}>
      <div style={styles.header}>
        <span style={styles.headerTitle}>Sessions</span>
        <button
          type="button"
          style={styles.newBtn}
          onClick={onNewSession}
        >
          + New
        </button>
      </div>
      <div style={styles.list}>
        {sessions.length === 0 ? (
          <div style={styles.empty}>No sessions yet</div>
        ) : (
          sessions.map((session) => {
            const isActive = session.id === activeSessionId;
            return (
              <div
                key={session.id}
                style={{
                  ...styles.item,
                  ...(isActive ? styles.itemActive : {}),
                }}
                onClick={() => onSelect(session.id)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') onSelect(session.id);
                }}
              >
                <div style={styles.itemTitle}>
                  {session.title || 'Untitled session'}
                </div>
                <div style={styles.itemMeta}>
                  <span
                    style={{
                      ...styles.statusDot,
                      backgroundColor:
                        statusColors[session.status] ?? '#9e9e9e',
                    }}
                  />
                  <span>{session.status}</span>
                  <span>{formatTime(session.lastActiveAt)}</span>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
