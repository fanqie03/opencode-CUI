import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { Components } from 'react-markdown';
import { CodeBlock } from './CodeBlock';
import { ToolCard } from './ToolCard';
import { ThinkingBlock } from './ThinkingBlock';
import { QuestionCard } from './QuestionCard';
import { PermissionCard } from './PermissionCard';
import type { Message, MessagePart } from '../protocol/types';

interface MessageBubbleProps {
  message: Message;
  onQuestionAnswer?: (answer: string) => void;
  onPermissionDecision?: (permissionId: string, allow: boolean) => void;
}

const roleLabels: Record<string, string> = {
  user: '你',
  assistant: 'OpenCode',
  system: '系统',
  tool: '工具',
};

export const MessageBubble: React.FC<MessageBubbleProps> = ({
  message,
  onQuestionAnswer,
  onPermissionDecision,
}) => {
  const isUser = message.role === 'user';
  const roleClass = message.role;

  const markdownComponents: Components = useMemo(
    () => ({
      code({ className, children, ...rest }) {
        const match = /language-(\w+)/.exec(className ?? '');
        const codeString = String(children).replace(/\n$/, '');
        if (match) {
          return <CodeBlock code={codeString} language={match[1]} />;
        }
        return (
          <code className={className} {...rest}>
            {children}
          </code>
        );
      },
    }),
    [],
  );

  /** Render a single MessagePart */
  const renderPart = (part: MessagePart) => {
    switch (part.type) {
      case 'thinking':
        return <ThinkingBlock key={part.partId} part={part} />;

      case 'tool':
        return <ToolCard key={part.partId} part={part} />;

      case 'question':
        return (
          <QuestionCard
            key={part.partId}
            part={part}
            onAnswer={onQuestionAnswer}
          />
        );

      case 'permission':
        return (
          <PermissionCard
            key={part.partId}
            part={part}
            onDecision={onPermissionDecision}
          />
        );

      case 'file':
        return (
          <div key={part.partId} className="file-part">
            <span className="file-part__icon">📄</span>
            {part.fileUrl ? (
              <a href={part.fileUrl} target="_blank" rel="noopener noreferrer">
                {part.fileName ?? '文件'}
              </a>
            ) : (
              <span>{part.fileName ?? '文件'}</span>
            )}
          </div>
        );

      case 'text':
      default:
        return (
          <div key={part.partId} className="text-part">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
              {part.content}
            </ReactMarkdown>
            {part.isStreaming && <span className="streaming-cursor" />}
          </div>
        );
    }
  };

  const renderContent = () => {
    // If message has structured parts (v2 protocol), render them
    if (message.parts && message.parts.length > 0) {
      return (
        <div className="message-parts">
          {message.parts.map(renderPart)}
        </div>
      );
    }

    // Fallback: plain content rendering (legacy / user messages)
    if (message.role === 'assistant' || message.role === 'tool') {
      return (
        <>
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {message.content}
          </ReactMarkdown>
          {message.isStreaming && <span className="streaming-cursor" />}
        </>
      );
    }
    return <span style={{ whiteSpace: 'pre-wrap' }}>{message.content}</span>;
  };

  return (
    <div className={`message-wrapper ${isUser ? 'user' : 'other'}`}>
      <div className={`message-bubble ${roleClass}`}>
        {!isUser && (
          <div className="message-role-label">
            {roleLabels[message.role] ?? message.role}
          </div>
        )}
        {renderContent()}
      </div>
    </div>
  );
};
