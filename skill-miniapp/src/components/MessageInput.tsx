import React, { useState, useRef, useCallback, useEffect } from 'react';

interface MessageInputProps {
  onSend: (text: string) => void;
  disabled?: boolean;
  placeholder?: string;
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    display: 'flex',
    alignItems: 'flex-end',
    gap: 8,
    padding: '8px 12px',
    borderTop: '1px solid #e0e0e0',
    backgroundColor: '#fafafa',
  },
  textarea: {
    flex: 1,
    resize: 'none',
    border: '1px solid #d0d0d0',
    borderRadius: 8,
    padding: '8px 12px',
    fontSize: 14,
    lineHeight: 1.5,
    fontFamily: 'inherit',
    outline: 'none',
    minHeight: 38,
    maxHeight: 150,
    overflowY: 'auto',
    backgroundColor: '#ffffff',
  },
  sendBtn: {
    padding: '8px 18px',
    borderRadius: 8,
    border: 'none',
    backgroundColor: '#1976d2',
    color: '#ffffff',
    fontSize: 14,
    fontWeight: 600,
    cursor: 'pointer',
    whiteSpace: 'nowrap',
    flexShrink: 0,
    height: 38,
  },
  sendBtnDisabled: {
    backgroundColor: '#bdbdbd',
    cursor: 'not-allowed',
  },
};

export const MessageInput: React.FC<MessageInputProps> = ({
  onSend,
  disabled = false,
  placeholder = '输入消息... (Shift+Enter 换行)',
}) => {
  const [text, setText] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const isEmpty = text.trim().length === 0;
  const isDisabled = disabled || isEmpty;

  // Auto-resize the textarea based on content
  const adjustHeight = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 150)}px`;
  }, []);

  useEffect(() => {
    adjustHeight();
  }, [text, adjustHeight]);

  const handleSend = useCallback(() => {
    const trimmed = text.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setText('');
    // Reset height after clearing
    requestAnimationFrame(() => {
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    });
  }, [text, disabled, onSend]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  return (
    <div style={styles.container}>
      <textarea
        ref={textareaRef}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        style={styles.textarea}
        rows={1}
        disabled={disabled}
      />
      <button
        type="button"
        style={{
          ...styles.sendBtn,
          ...(isDisabled ? styles.sendBtnDisabled : {}),
        }}
        onClick={handleSend}
        disabled={isDisabled}
      >
        Send
      </button>
    </div>
  );
};
