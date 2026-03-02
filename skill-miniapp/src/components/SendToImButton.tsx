import React, { useEffect, useState, useCallback, useRef } from 'react';

interface SendToImButtonProps {
  onSend: (selectedText: string) => void;
  sending?: boolean;
  success?: boolean;
  error?: string | null;
  containerRef?: React.RefObject<HTMLElement | null>;
}

const styles: Record<string, React.CSSProperties> = {
  button: {
    position: 'fixed',
    zIndex: 1000,
    padding: '6px 14px',
    borderRadius: 6,
    border: '1px solid #1976d2',
    backgroundColor: '#1976d2',
    color: '#ffffff',
    fontSize: 12,
    fontWeight: 600,
    cursor: 'pointer',
    boxShadow: '0 2px 8px rgba(0,0,0,0.18)',
    whiteSpace: 'nowrap',
    transition: 'opacity 0.2s',
  },
  sending: {
    backgroundColor: '#64b5f6',
    cursor: 'wait',
  },
  success: {
    backgroundColor: '#4caf50',
    borderColor: '#4caf50',
  },
  error: {
    backgroundColor: '#ef5350',
    borderColor: '#ef5350',
  },
};

export const SendToImButton: React.FC<SendToImButtonProps> = ({
  onSend,
  sending = false,
  success = false,
  error = null,
  containerRef,
}) => {
  const [visible, setVisible] = useState(false);
  const [position, setPosition] = useState({ top: 0, left: 0 });
  const selectedTextRef = useRef('');

  const updatePosition = useCallback(() => {
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || !selection.rangeCount) {
      setVisible(false);
      return;
    }

    const text = selection.toString().trim();
    if (!text) {
      setVisible(false);
      return;
    }

    // Only show if selection is inside the container (assistant messages area)
    if (containerRef?.current) {
      const anchorNode = selection.anchorNode;
      if (anchorNode && !containerRef.current.contains(anchorNode)) {
        setVisible(false);
        return;
      }
    }

    selectedTextRef.current = text;
    const range = selection.getRangeAt(0);
    const rect = range.getBoundingClientRect();

    setPosition({
      top: rect.top - 40,
      left: rect.left + rect.width / 2 - 50,
    });
    setVisible(true);
  }, [containerRef]);

  useEffect(() => {
    document.addEventListener('selectionchange', updatePosition);
    document.addEventListener('mouseup', updatePosition);
    return () => {
      document.removeEventListener('selectionchange', updatePosition);
      document.removeEventListener('mouseup', updatePosition);
    };
  }, [updatePosition]);

  const handleClick = useCallback(() => {
    if (sending) return;
    const text = selectedTextRef.current;
    if (text) {
      onSend(text);
    }
  }, [onSend, sending]);

  if (!visible) return null;

  let label = '发送到聊天';
  let extraStyle: React.CSSProperties = {};

  if (sending) {
    label = 'Sending...';
    extraStyle = styles.sending;
  } else if (success) {
    label = 'Sent!';
    extraStyle = styles.success;
  } else if (error) {
    label = 'Failed';
    extraStyle = styles.error;
  }

  return (
    <button
      type="button"
      style={{
        ...styles.button,
        ...extraStyle,
        top: position.top,
        left: position.left,
      }}
      onClick={handleClick}
      onMouseDown={(e) => e.preventDefault()} // prevent losing selection
    >
      {label}
    </button>
  );
};
