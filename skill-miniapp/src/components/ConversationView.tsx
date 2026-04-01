import React, { useEffect, useRef } from 'react';
import { MessageBubble } from './MessageBubble';
import type { Message } from '../protocol/types';

interface ConversationViewProps {
  messages: Message[];
  loading?: boolean;
  onQuestionAnswer?: (answer: string, toolCallId?: string) => void;
  onPermissionDecision?: (permissionId: string, response: 'once' | 'always' | 'reject', subagentSessionId?: string) => void;
}

export const ConversationView: React.FC<ConversationViewProps> = ({
  messages,
  loading = false,
  onQuestionAnswer,
  onPermissionDecision,
}) => {
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages]);

  if (loading) {
    return (
      <div className="conversation-loading">
        <span className="loading-dot" />
        <span className="loading-dot" />
        <span className="loading-dot" />
        &nbsp;加载消息中...
      </div>
    );
  }

  if (messages.length === 0) {
    return (
      <div className="conversation-empty">
        <span className="emoji">💬</span>
        <span>发送一条消息开始对话</span>
      </div>
    );
  }

  return (
    <div ref={containerRef} className="conversation-container">
      {messages.map((msg) => (
        <MessageBubble
          key={msg.id}
          message={msg}
          onQuestionAnswer={onQuestionAnswer}
          onPermissionDecision={onPermissionDecision}
        />
      ))}
      <div ref={bottomRef} />
    </div>
  );
};
