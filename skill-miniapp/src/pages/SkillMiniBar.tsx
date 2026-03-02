import React from 'react';
import type { MiniBarStatus } from '../protocol/types';

interface SkillMiniBarProps {
  status: MiniBarStatus;
  summary: string;
  onExpand: () => void;
}

const statusConfig: Record<
  MiniBarStatus,
  { icon: string; color: string; label: string }
> = {
  processing: { icon: '⟳', color: '#1976d2', label: 'OpenCode 处理中...' },
  completed: { icon: '✓', color: '#4caf50', label: 'OpenCode 已完成' },
  error: { icon: '✕', color: '#ef5350', label: 'OpenCode 错误' },
  offline: { icon: '○', color: '#9e9e9e', label: '工具离线' },
};

const styles: Record<string, React.CSSProperties> = {
  bar: {
    display: 'flex',
    alignItems: 'center',
    height: 40,
    padding: '0 12px',
    backgroundColor: '#f5f7fa',
    borderBottom: '1px solid #e0e0e0',
    borderTop: '1px solid #e0e0e0',
    fontSize: 13,
    gap: 8,
    overflow: 'hidden',
  },
  statusIcon: {
    fontSize: 14,
    flexShrink: 0,
    width: 20,
    textAlign: 'center',
  },
  statusLabel: {
    fontWeight: 500,
    flexShrink: 0,
  },
  summary: {
    flex: 1,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    color: '#616161',
    fontSize: 12,
    marginLeft: 4,
  },
  expandBtn: {
    flexShrink: 0,
    padding: '4px 12px',
    border: '1px solid #d0d0d0',
    borderRadius: 4,
    backgroundColor: '#ffffff',
    color: '#424242',
    fontSize: 12,
    cursor: 'pointer',
    fontWeight: 500,
  },
};

/** Spinning animation for the processing status icon */
const SpinStyle: React.FC = () => (
  <style>{`
    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
    .spin-icon {
      animation: spin 1s linear infinite;
      display: inline-block;
    }
  `}</style>
);

export const SkillMiniBar: React.FC<SkillMiniBarProps> = ({
  status,
  summary,
  onExpand,
}) => {
  const config = statusConfig[status];

  return (
    <div style={styles.bar}>
      {status === 'processing' && <SpinStyle />}
      <span
        style={{ ...styles.statusIcon, color: config.color }}
        className={status === 'processing' ? 'spin-icon' : undefined}
      >
        {config.icon}
      </span>
      <span style={{ ...styles.statusLabel, color: config.color }}>
        {config.label}
      </span>
      {summary && <span style={styles.summary}>{summary}</span>}
      <button type="button" style={styles.expandBtn} onClick={onExpand}>
        展开
      </button>
    </div>
  );
};
