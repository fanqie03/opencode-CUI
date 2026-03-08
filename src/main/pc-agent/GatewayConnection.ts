/**
 * GatewayConnection — AI-Gateway WebSocket connection manager.
 *
 * Maintains a persistent WebSocket connection to the AI-Gateway with:
 *  - AK/SK query-parameter authentication on connect
 *  - Automatic reconnect with exponential backoff (1s -> 2s -> 4s -> ... -> 30s cap)
 *  - Periodic heartbeat messages
 *  - EventEmitter interface for connection lifecycle events
 */

import { EventEmitter } from 'node:events';
import WebSocket from 'ws';
import type { AuthParams } from './AkSkAuth';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Connection states exposed via {@link GatewayConnection.state}. */
export enum ConnectionState {
  CONNECTING = 'CONNECTING',
  CONNECTED = 'CONNECTED',
  RECONNECTING = 'RECONNECTING',
  CLOSED = 'CLOSED',
}

/**
 * Events emitted by {@link GatewayConnection}.
 *
 * | Event          | Payload                |
 * |----------------|------------------------|
 * | `connected`    | _none_                 |
 * | `disconnected` | `{ code, reason }`     |
 * | `message`      | parsed JSON object     |
 * | `error`        | `Error`                |
 */
export interface GatewayConnectionEvents {
  connected: [];
  disconnected: [info: { code: number; reason: string }];
  message: [data: unknown];
  error: [err: Error];
}

/** Type-safe EventEmitter for {@link GatewayConnectionEvents}. */
export declare interface GatewayConnection {
  on<K extends keyof GatewayConnectionEvents>(event: K, listener: (...args: GatewayConnectionEvents[K]) => void): this;
  off<K extends keyof GatewayConnectionEvents>(event: K, listener: (...args: GatewayConnectionEvents[K]) => void): this;
  once<K extends keyof GatewayConnectionEvents>(event: K, listener: (...args: GatewayConnectionEvents[K]) => void): this;
  emit<K extends keyof GatewayConnectionEvents>(event: K, ...args: GatewayConnectionEvents[K]): boolean;
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Manages a WebSocket connection to the AI-Gateway.
 *
 * @example
 * ```ts
 * const gw = new GatewayConnection('ws://gateway:8081/ws/agent');
 * gw.on('message', (msg) => console.log(msg));
 * await gw.connect(AkSkAuth.sign(ak, sk));
 * gw.send({ type: 'register', deviceName: 'MyPC', os: 'WINDOWS', toolType: 'OPENCODE' });
 * ```
 */
export class GatewayConnection extends EventEmitter {
  /** Callback used to produce fresh auth params for each connect attempt. */
  private authProvider: (() => AuthParams) | null = null;

  /** Current connection state. */
  private _state: ConnectionState = ConnectionState.CLOSED;

  /** Underlying WebSocket instance (null when closed). */
  private ws: WebSocket | null = null;

  /** Registered message handlers (in addition to EventEmitter listeners). */
  private messageHandlers: Array<(data: unknown) => void> = [];

  /** Heartbeat interval timer handle. */
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;

  /** Reconnect timeout timer handle. */
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  /** Current reconnect delay (doubles on each failure, capped). */
  private reconnectDelay: number;

  /** Whether the user explicitly called {@link close}. */
  private intentionallyClosed = false;

  /**
   * @param gatewayUrl  Base WebSocket URL of the AI-Gateway
   *                    (e.g. `ws://gateway-host:8081/ws/agent`).
   * @param heartbeatIntervalMs  Interval between heartbeat messages (default 30 000 ms).
   * @param reconnectBaseMs      Initial reconnect delay (default 1 000 ms).
   * @param reconnectMaxMs       Maximum reconnect delay cap (default 30 000 ms).
   */
  constructor(
    private readonly gatewayUrl: string,
    private readonly heartbeatIntervalMs: number = 30_000,
    private readonly reconnectBaseMs: number = 1_000,
    private readonly reconnectMaxMs: number = 30_000,
  ) {
    super();
    this.reconnectDelay = this.reconnectBaseMs;
  }

  /** Current connection state. */
  get state(): ConnectionState {
    return this._state;
  }

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /**
   * Establish the WebSocket connection to the AI-Gateway.
   *
   * Authentication parameters are appended as URL query params:
   * `?ak=...&ts=...&nonce=...&sign=...`
   *
   * @param authParams  Output of {@link AkSkAuth.sign}.
   * @returns Resolves once the connection is open; rejects on first-connect failure.
   */
  connect(auth: AuthParams | (() => AuthParams)): Promise<void> {
    this.authProvider = typeof auth === 'function' ? auth : () => auth;
    this.intentionallyClosed = false;
    return this.doConnect(this.authProvider());
  }

  /**
   * Send a JSON message to the gateway.
   *
   * @param message  Any JSON-serializable object.
   * @throws If the connection is not in the CONNECTED state.
   */
  send(message: unknown): void {
    if (this._state !== ConnectionState.CONNECTED || !this.ws) {
      throw new Error(`Cannot send: connection is ${this._state}`);
    }
    this.ws.send(JSON.stringify(message));
  }

  /**
   * Register an additional message handler (convenience wrapper).
   *
   * Equivalent to `gw.on('message', handler)` but kept for API parity
   * with the design specification.
   */
  onMessage(handler: (data: unknown) => void): void {
    this.messageHandlers.push(handler);
  }

  /**
   * Gracefully close the connection.
   *
   * Stops heartbeats, cancels pending reconnects, and closes the WebSocket.
   */
  close(): void {
    this.intentionallyClosed = true;
    this.stopHeartbeat();
    this.clearReconnectTimer();
    this._state = ConnectionState.CLOSED;

    if (this.ws) {
      this.ws.removeAllListeners();
      if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
        this.ws.close(1000, 'client close');
      }
      this.ws = null;
    }
  }

  // -----------------------------------------------------------------------
  // Internal
  // -----------------------------------------------------------------------

  /**
   * Build the full WebSocket URL with auth query parameters.
   */
  private buildUrl(auth: AuthParams): string {
    const sep = this.gatewayUrl.includes('?') ? '&' : '?';
    return `${this.gatewayUrl}${sep}ak=${encodeURIComponent(auth.ak)}&ts=${encodeURIComponent(auth.timestamp)}&nonce=${encodeURIComponent(auth.nonce)}&sign=${encodeURIComponent(auth.signature)}`;
  }

  /**
   * Core connection logic, used for both initial connect and reconnect.
   */
  private doConnect(auth: AuthParams): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this._state = ConnectionState.CONNECTING;
      const url = this.buildUrl(auth);

      const ws = new WebSocket(url);
      this.ws = ws;

      ws.on('open', () => {
        this._state = ConnectionState.CONNECTED;
        this.reconnectDelay = this.reconnectBaseMs; // reset backoff
        this.startHeartbeat();
        this.emit('connected');
        resolve();
      });

      ws.on('message', (raw: WebSocket.Data) => {
        try {
          const parsed: unknown = JSON.parse(raw.toString());
          this.emit('message', parsed);
          for (const handler of this.messageHandlers) {
            try {
              handler(parsed);
            } catch (err) {
              this.emit('error', err instanceof Error ? err : new Error(String(err)));
            }
          }
        } catch {
          this.emit('error', new Error(`Failed to parse gateway message: ${String(raw)}`));
        }
      });

      ws.on('close', (code: number, reason: Buffer) => {
        this.stopHeartbeat();
        const reasonStr = reason.toString();
        this.emit('disconnected', { code, reason: reasonStr });

        if (!this.intentionallyClosed) {
          this.scheduleReconnect();
        } else {
          this._state = ConnectionState.CLOSED;
        }
      });

      ws.on('error', (err: Error) => {
        this.emit('error', err);
        // On initial connect, reject the promise so the caller knows.
        // The 'close' event that follows will trigger reconnect logic.
        if (this._state === ConnectionState.CONNECTING) {
          reject(err);
        }
      });
    });
  }

  /**
   * Schedule a reconnect with exponential backoff.
   */
  private scheduleReconnect(): void {
    if (this.intentionallyClosed) return;

    this._state = ConnectionState.RECONNECTING;
    const delay = this.reconnectDelay;
    // Exponential backoff: double the delay, cap at max.
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.reconnectMaxMs);

    this.reconnectTimer = setTimeout(async () => {
      if (this.intentionallyClosed || !this.authProvider) return;
      try {
        await this.doConnect(this.authProvider());
      } catch {
        // doConnect rejection triggers 'error' + 'close' which re-schedules.
      }
    }, delay);
  }

  /**
   * Start the periodic heartbeat sender.
   */
  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this._state === ConnectionState.CONNECTED && this.ws) {
        try {
          this.send({ type: 'heartbeat' });
        } catch {
          // send will throw if state changed between check and call; ignore.
        }
      }
    }, this.heartbeatIntervalMs);
  }

  /**
   * Stop the heartbeat interval.
   */
  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /**
   * Clear any pending reconnect timer.
   */
  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}
