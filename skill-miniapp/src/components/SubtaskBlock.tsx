import React, { useState, useMemo, useEffect } from 'react';
import type { MessagePart } from '../protocol/types';
import { ToolCard } from './ToolCard';
import { ThinkingBlock } from './ThinkingBlock';
import { PermissionCard } from './PermissionCard';
import { QuestionCard } from './QuestionCard';

interface SubtaskBlockProps {
  part: MessagePart;
  onPermissionDecision?: (permissionId: string, response: 'once' | 'always' | 'reject', subagentSessionId?: string) => void;
  onQuestionAnswer?: (answer: string, toolCallId?: string, subagentSessionId?: string) => void;
}

export const SubtaskBlock: React.FC<SubtaskBlockProps> = ({
  part,
  onPermissionDecision,
  onQuestionAnswer,
}) => {
  const [collapsed, setCollapsed] = useState(true);
  const status = part.subagentStatus ?? 'running';
  const subParts = part.subParts ?? [];
  const toolCount = useMemo(() => subParts.filter((p) => p.type === 'tool').length, [subParts]);

  // 有 pending permission/question 时自动展开
  const hasPendingInteraction = useMemo(
    () => subParts.some(
      (p) => (p.type === 'permission' && !p.permResolved) || (p.type === 'question' && !p.answered),
    ),
    [subParts],
  );

  useEffect(() => {
    if (hasPendingInteraction) {
      setCollapsed(false);
    }
  }, [hasPendingInteraction]);

  const statusLabel = status === 'running' ? '运行中' : status === 'completed' ? '已完成' : '错误';
  const promptPreview = part.subagentPrompt && part.subagentPrompt.length > 50
    ? part.subagentPrompt.slice(0, 50) + '...' : part.subagentPrompt ?? '';
  const pendingCount = subParts.filter(
    (p) => (p.type === 'permission' && !p.permResolved) || (p.type === 'question' && !p.answered),
  ).length;

  return (
    <div className={`subtask-block subtask-block--${status}`}>
      <div className="subtask-block__header" onClick={() => setCollapsed(!collapsed)}>
        <div className="subtask-block__title">
          <span className="subtask-block__icon">&#x1F916;</span>
          <span className="subtask-block__agent-name">{part.subagentName ?? 'Subagent'}</span>
          <span className={`subtask-block__status-dot subtask-block__status-dot--${status}`} />
          <span className="subtask-block__status-label">{statusLabel}</span>
          {pendingCount > 0 && (
            <span className="subtask-block__pending-badge">
              {pendingCount} 待处理
            </span>
          )}
        </div>
        <div className="subtask-block__meta">
          <span className="subtask-block__prompt-preview">&quot;{promptPreview}&quot;</span>
          {toolCount > 0 && <span className="subtask-block__tool-count">{toolCount} tools</span>}
        </div>
        <span className="subtask-block__toggle">{collapsed ? '\u25B6 展开' : '\u25BC 收起'}</span>
      </div>
      {!collapsed && (
        <div className="subtask-block__content">
          {subParts.map((subPart, index) => {
            const key = subPart.partId || `sub-${index}`;
            switch (subPart.type) {
              case 'text':
                return <div key={key} className="subtask-block__text">{subPart.content}</div>;
              case 'thinking':
                return <ThinkingBlock key={key} part={subPart} />;
              case 'tool':
                return <ToolCard key={key} part={subPart} />;
              case 'permission':
                return <PermissionCard key={key} part={subPart} onDecision={onPermissionDecision} />;
              case 'question':
                return <QuestionCard key={key} part={subPart} onAnswer={onQuestionAnswer} />;
              default:
                return null;
            }
          })}
        </div>
      )}
    </div>
  );
};
