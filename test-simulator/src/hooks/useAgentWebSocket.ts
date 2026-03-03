import { useState, useEffect, useRef, useCallback } from 'react';
import type { MessageEnvelope, StreamMessage } from '../types';
import { GatewayWebSocketClient } from '../services/WebSocketClient';
import { config } from '../config';

interface UseAgentWebSocketProps {
  agentId: string;
  gatewayUrl?: string;
}

interface UseAgentWebSocketReturn {
  isConnected: boolean;
  sendMessage: (envelope: MessageEnvelope) => void;
  messages: StreamMessage[];
  error: string | null;
  reconnectCount: number;
}

export function useAgentWebSocket({
  agentId,
  gatewayUrl = config.gatewayUrl,
}: UseAgentWebSocketProps): UseAgentWebSocketReturn {
  const [isConnected, setIsConnected] = useState(false);
  const [messages, setMessages] = useState<StreamMessage[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [reconnectCount, setReconnectCount] = useState(0);

  const clientRef = useRef<GatewayWebSocketClient | null>(null);

  const connect = useCallback(() => {
    if (!agentId) return;

    const client = new GatewayWebSocketClient(agentId, {
      gatewayUrl,
      reconnectDelay: config.reconnectDelay,
      maxReconnectAttempts: config.maxReconnectAttempts,
      onOpen: () => {
        setIsConnected(true);
        setError(null);
        if (client.getReconnectAttempts() > 0) {
          setReconnectCount((prev) => prev + 1);
        }
      },
      onMessage: (event) => {
        try {
          const envelope: MessageEnvelope = JSON.parse(event.data);

          // Convert MessageEnvelope to StreamMessage for compatibility
          if (envelope.payload.type === 'stream_delta') {
            const streamMsg: StreamMessage = {
              type: 'delta',
              seq: envelope.metadata?.sequenceNumber,
              content: (envelope.payload.data as { content?: string })?.content,
            };
            setMessages((prev) => [...prev, streamMsg]);
          } else if (envelope.payload.type === 'stream_done') {
            const streamMsg: StreamMessage = {
              type: 'done',
              usage: (envelope.payload.data as { usage?: { input_tokens: number; output_tokens: number } })?.usage,
            };
            setMessages((prev) => [...prev, streamMsg]);
          } else if (envelope.payload.type === 'error') {
            const streamMsg: StreamMessage = {
              type: 'error',
              message: (envelope.payload.data as { message?: string })?.message,
            };
            setMessages((prev) => [...prev, streamMsg]);
          }
        } catch (err) {
          setError(`Failed to parse message: ${err}`);
        }
      },
      onError: (evt) => {
        setError('WebSocket connection error');
        console.error('WebSocket error:', evt);
      },
      onClose: () => {
        setIsConnected(false);
      },
    });

    clientRef.current = client;

    client.connect().catch((err) => {
      setError(`Connection failed: ${err}`);
      console.error('Connection error:', err);
    });
  }, [agentId, gatewayUrl]);

  const sendMessage = useCallback((envelope: MessageEnvelope) => {
    if (clientRef.current?.isConnected()) {
      try {
        clientRef.current.sendMessage(envelope);
      } catch (err) {
        setError(`Failed to send message: ${err}`);
      }
    } else {
      setError('WebSocket not connected');
    }
  }, []);

  useEffect(() => {
    connect();

    return () => {
      if (clientRef.current) {
        clientRef.current.disconnect();
        clientRef.current = null;
      }
    };
  }, [connect]);

  return {
    isConnected,
    sendMessage,
    messages,
    error,
    reconnectCount,
  };
}
