import { useState, useRef, useCallback } from 'react';
import type { GatewayMessage, StreamMessage } from '../types';
import { GatewayWebSocketClient } from '../services/WebSocketClient';
import { config } from '../config';

interface UseAgentWebSocketProps {
  agentId: string;
  gatewayUrl?: string;
}

interface UseAgentWebSocketReturn {
  isConnected: boolean;
  sendMessage: (msg: GatewayMessage) => void;
  messages: StreamMessage[];
  error: string | null;
  reconnectCount: number;
  connect: () => void;
  disconnect: () => void;
}

/**
 * React hook for managing a Gateway WebSocket connection (v1 protocol).
 * Does NOT auto-connect — call connect() manually.
 * Gateway requires AK/SK auth, so this is only for real agent scenarios.
 */
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

    // Disconnect existing
    if (clientRef.current) {
      clientRef.current.disconnect();
      clientRef.current = null;
    }

    setError(null);

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
          const msg: GatewayMessage = JSON.parse(event.data);

          if (msg.type === 'tool_event') {
            const delta = (msg.event as Record<string, unknown>)?.delta;
            const streamMsg: StreamMessage = {
              type: 'delta',
              seq: msg.sequenceNumber,
              content: typeof delta === 'string' ? delta : undefined,
            };
            setMessages((prev) => [...prev, streamMsg]);
          } else if (msg.type === 'tool_done') {
            const streamMsg: StreamMessage = {
              type: 'done',
              seq: msg.sequenceNumber,
              usage: msg.usage,
            };
            setMessages((prev) => [...prev, streamMsg]);
          } else if (msg.type === 'tool_error') {
            const streamMsg: StreamMessage = {
              type: 'error',
              message: msg.error,
            };
            setMessages((prev) => [...prev, streamMsg]);
          } else if (msg.type === 'agent_offline') {
            setMessages((prev) => [...prev, { type: 'agent_offline' }]);
          } else if (msg.type === 'agent_online') {
            setMessages((prev) => [...prev, { type: 'agent_online' }]);
          }
        } catch (err) {
          setError(`Failed to parse message: ${err}`);
        }
      },
      onError: () => {
        setError('WebSocket connection error — Gateway requires AK/SK auth');
      },
      onClose: () => {
        setIsConnected(false);
      },
    });

    clientRef.current = client;

    client.connect().catch((err) => {
      setError(`Connection failed: ${err}. Gateway requires AK/SK authentication.`);
    });
  }, [agentId, gatewayUrl]);

  const disconnect = useCallback(() => {
    if (clientRef.current) {
      clientRef.current.disconnect();
      clientRef.current = null;
    }
    setIsConnected(false);
    setError(null);
  }, []);

  const sendMessage = useCallback((msg: GatewayMessage) => {
    if (clientRef.current?.isConnected()) {
      try {
        clientRef.current.sendMessage(msg);
      } catch (err) {
        setError(`Failed to send message: ${err}`);
      }
    } else {
      setError('WebSocket not connected');
    }
  }, []);

  return {
    isConnected,
    sendMessage,
    messages,
    error,
    reconnectCount,
    connect,
    disconnect,
  };
}
