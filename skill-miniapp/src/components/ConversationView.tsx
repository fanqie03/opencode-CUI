import React, { useEffect, useRef } from 'react';
import { MessageBubble } from './MessageBubble';
import type { Message } from '../protocol/types';

interface ConversationViewProps {
  messages: Message[];
  loading?: boolean;
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    flex: 1,
    overflowY: 'auto',
    padding: '16px 16px 8px',
    display: 'flex',
    flexDirection: 'column',
  },
  empty: {
    flex: 1,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    color: '#9e9e9e',
    fontSize: 14,
  },
  spinner: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    color: '#9e9e9e',
    fontSize: 13,
  },
};

export const ConversationView: React.FC<ConversationViewProps> = ({
  messages,
  loading = false,
}) => {
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    if (bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages]);

  if (loading) {
    return (
      <div style={styles.spinner}>
        <LoadingDots /> Loading messages...
      </div>
    );
  }

  if (messages.length === 0) {
    return (
      <div style={styles.empty}>
        Start a conversation by typing a message below.
      </div>
    );
  }

  return (
    <div ref={containerRef} style={styles.container}>
      {messages.map((msg) => (
        <MessageBubble key={msg.id} message={msg} />
      ))}
      <div ref={bottomRef} />
    </div>
  );
};

/** Simple animated loading dots */
const LoadingDots: React.FC = () => (
  <>
    <style>{`
      @keyframes dotPulse {
        0%, 80%, 100% { opacity: 0.3; }
        40% { opacity: 1; }
      }
      .loading-dot {
        display: inline-block;
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background-color: #9e9e9e;
        margin-right: 3px;
        animation: dotPulse 1.4s infinite ease-in-out both;
      }
      .loading-dot:nth-child(1) { animation-delay: -0.32s; }
      .loading-dot:nth-child(2) { animation-delay: -0.16s; }
    `}</style>
    <span className="loading-dot" />
    <span className="loading-dot" />
    <span className="loading-dot" />
  </>
);
