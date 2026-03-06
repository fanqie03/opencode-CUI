// WebSocket clients for gateway and skill-server connections (v1 protocol)
import type { GatewayMessage, StreamMessage } from '../types';

export type WebSocketEventHandler = (event: MessageEvent) => void;
export type WebSocketErrorHandler = (error: Event) => void;
export type WebSocketCloseHandler = (event: CloseEvent) => void;
export type WebSocketOpenHandler = () => void;

export interface WebSocketClientOptions {
  url: string;
  reconnectDelay?: number;
  maxReconnectAttempts?: number;
  onMessage?: WebSocketEventHandler;
  onError?: WebSocketErrorHandler;
  onClose?: WebSocketCloseHandler;
  onOpen?: WebSocketOpenHandler;
}

/**
 * Base WebSocket client with reconnection logic
 */
export class BaseWebSocketClient {
  protected ws: WebSocket | null = null;
  protected url: string;
  protected reconnectDelay: number;
  protected maxReconnectAttempts: number;
  protected reconnectAttempts = 0;
  protected reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  protected isManualClose = false;

  protected onMessageHandler?: WebSocketEventHandler;
  protected onErrorHandler?: WebSocketErrorHandler;
  protected onCloseHandler?: WebSocketCloseHandler;
  protected onOpenHandler?: WebSocketOpenHandler;

  constructor(options: WebSocketClientOptions) {
    this.url = options.url;
    this.reconnectDelay = options.reconnectDelay || 1000;
    this.maxReconnectAttempts = options.maxReconnectAttempts || 5;
    this.onMessageHandler = options.onMessage;
    this.onErrorHandler = options.onError;
    this.onCloseHandler = options.onClose;
    this.onOpenHandler = options.onOpen;
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.isManualClose = false;
        this.ws = new WebSocket(this.url);

        this.ws.onopen = () => {
          this.reconnectAttempts = 0;
          this.onOpenHandler?.();
          resolve();
        };

        this.ws.onmessage = (event) => {
          this.onMessageHandler?.(event);
        };

        this.ws.onerror = (error) => {
          this.onErrorHandler?.(error);
          reject(new Error('WebSocket connection error'));
        };

        this.ws.onclose = (event) => {
          this.onCloseHandler?.(event);
          if (!this.isManualClose) {
            this.scheduleReconnect();
          }
        };
      } catch (error) {
        reject(error);
      }
    });
  }

  protected scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('Max reconnect attempts reached');
      return;
    }

    if (this.reconnectTimer) {
      return;
    }

    const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts);
    this.reconnectAttempts++;

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect().catch((error) => {
        console.error('Reconnect failed:', error);
      });
    }, delay);
  }

  send(data: string | object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      const message = typeof data === 'string' ? data : JSON.stringify(data);
      this.ws.send(message);
    } else {
      throw new Error('WebSocket is not connected');
    }
  }

  disconnect(): void {
    this.isManualClose = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  getReconnectAttempts(): number {
    return this.reconnectAttempts;
  }
}

/**
 * Gateway WebSocket client for agent connections (v1 protocol).
 * Handles flat GatewayMessage format instead of nested MessageEnvelope.
 */
export class GatewayWebSocketClient extends BaseWebSocketClient {
  private agentId: string;
  private messageHandlers: Map<string, (msg: GatewayMessage) => void> = new Map();

  constructor(
    agentId: string,
    options: Omit<WebSocketClientOptions, 'url'> & { gatewayUrl: string }
  ) {
    const url = `${options.gatewayUrl}/ws/agent/${agentId}`;
    super({
      ...options,
      url,
      onMessage: (event) => {
        this.handleMessage(event);
        options.onMessage?.(event);
      },
    });
    this.agentId = agentId;
  }

  private handleMessage(event: MessageEvent): void {
    try {
      const msg: GatewayMessage = JSON.parse(event.data);
      // v1: flat format, route by msg.type
      const handler = this.messageHandlers.get(msg.type);
      if (handler) {
        handler(msg);
      }
    } catch (error) {
      console.error('Failed to parse gateway message:', error);
    }
  }

  sendMessage(msg: GatewayMessage): void {
    this.send(msg);
  }

  onMessageType(type: string, handler: (msg: GatewayMessage) => void): void {
    this.messageHandlers.set(type, handler);
  }

  removeMessageTypeHandler(type: string): void {
    this.messageHandlers.delete(type);
  }

  getAgentId(): string {
    return this.agentId;
  }
}

/**
 * Skill Server WebSocket client for stream subscriptions.
 */
export class SkillWebSocketClient extends BaseWebSocketClient {
  private streamHandlers: Map<string, (message: StreamMessage) => void> = new Map();

  constructor(options: Omit<WebSocketClientOptions, 'url'> & { skillServerUrl: string }) {
    super({
      ...options,
      url: options.skillServerUrl,
      onMessage: (event) => {
        this.handleStreamMessage(event);
        options.onMessage?.(event);
      },
    });
  }

  private handleStreamMessage(event: MessageEvent): void {
    try {
      const message: StreamMessage = JSON.parse(event.data);
      const handler = this.streamHandlers.get(message.type);
      if (handler) {
        handler(message);
      }
    } catch (error) {
      console.error('Failed to parse stream message:', error);
    }
  }

  onStreamType(type: string, handler: (message: StreamMessage) => void): void {
    this.streamHandlers.set(type, handler);
  }

  removeStreamTypeHandler(type: string): void {
    this.streamHandlers.delete(type);
  }
}
