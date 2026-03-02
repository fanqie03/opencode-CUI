import React, { useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { Components } from 'react-markdown';
import { CodeBlock } from './CodeBlock';
import type { Message } from '../protocol/types';

interface MessageBubbleProps {
  message: Message;
}

const roleColors: Record<string, string> = {
  user: '#e3f2fd',
  assistant: '#ffffff',
  system: '#fff3e0',
  tool: '#f3e5f5',
};

const roleBorderColors: Record<string, string> = {
  user: '#90caf9',
  assistant: '#e0e0e0',
  system: '#ffcc80',
  tool: '#ce93d8',
};

const roleLabels: Record<string, string> = {
  user: 'You',
  assistant: 'OpenCode',
  system: 'System',
  tool: 'Tool',
};

const styles: Record<string, React.CSSProperties> = {
  wrapper: {
    display: 'flex',
    marginBottom: 12,
  },
  wrapperUser: {
    justifyContent: 'flex-end',
  },
  wrapperOther: {
    justifyContent: 'flex-start',
  },
  bubble: {
    maxWidth: '80%',
    padding: '10px 14px',
    borderRadius: 12,
    fontSize: 14,
    lineHeight: 1.6,
    position: 'relative',
    wordBreak: 'break-word',
  },
  roleLabel: {
    fontSize: 11,
    fontWeight: 600,
    marginBottom: 4,
    opacity: 0.7,
  },
  streamingCursor: {
    display: 'inline-block',
    width: 2,
    height: 16,
    backgroundColor: '#1976d2',
    marginLeft: 2,
    verticalAlign: 'text-bottom',
    animation: 'blink 1s step-end infinite',
  },
};

/** Inject a minimal keyframe animation for the blinking cursor. */
const BlinkStyle: React.FC = () => (
  <style>{`
    @keyframes blink {
      50% { opacity: 0; }
    }
  `}</style>
);

export const MessageBubble: React.FC<MessageBubbleProps> = ({ message }) => {
  const isUser = message.role === 'user';

  const bubbleStyle: React.CSSProperties = useMemo(
    () => ({
      ...styles.bubble,
      backgroundColor: roleColors[message.role] ?? '#ffffff',
      border: `1px solid ${roleBorderColors[message.role] ?? '#e0e0e0'}`,
    }),
    [message.role],
  );

  const markdownComponents: Components = useMemo(
    () => ({
      code({ className, children, ...rest }) {
        const match = /language-(\w+)/.exec(className ?? '');
        const codeString = String(children).replace(/\n$/, '');

        // If there is a language class, render as a full code block
        if (match) {
          return <CodeBlock code={codeString} language={match[1]} />;
        }

        // Inline code
        return (
          <code
            className={className}
            style={{
              backgroundColor: '#f5f5f5',
              padding: '1px 4px',
              borderRadius: 3,
              fontSize: 13,
              fontFamily: 'Consolas, "Courier New", monospace',
            }}
            {...rest}
          >
            {children}
          </code>
        );
      },
      // Style tables for readability
      table({ children }) {
        return (
          <table
            style={{
              borderCollapse: 'collapse',
              marginBlock: 8,
              width: '100%',
              fontSize: 13,
            }}
          >
            {children}
          </table>
        );
      },
      th({ children }) {
        return (
          <th
            style={{
              border: '1px solid #ddd',
              padding: '6px 10px',
              backgroundColor: '#f5f5f5',
              textAlign: 'left',
            }}
          >
            {children}
          </th>
        );
      },
      td({ children }) {
        return (
          <td style={{ border: '1px solid #ddd', padding: '6px 10px' }}>
            {children}
          </td>
        );
      },
    }),
    [],
  );

  const renderContent = () => {
    if (message.role === 'assistant' || message.role === 'tool') {
      return (
        <>
          <ReactMarkdown
            remarkPlugins={[remarkGfm]}
            components={markdownComponents}
          >
            {message.content}
          </ReactMarkdown>
          {message.isStreaming && (
            <>
              <BlinkStyle />
              <span style={styles.streamingCursor} />
            </>
          )}
        </>
      );
    }

    // User and system messages: plain text with whitespace preserved
    return (
      <span style={{ whiteSpace: 'pre-wrap' }}>{message.content}</span>
    );
  };

  return (
    <div
      style={{
        ...styles.wrapper,
        ...(isUser ? styles.wrapperUser : styles.wrapperOther),
      }}
    >
      <div style={bubbleStyle}>
        {!isUser && (
          <div style={styles.roleLabel}>
            {roleLabels[message.role] ?? message.role}
          </div>
        )}
        {renderContent()}
      </div>
    </div>
  );
};
