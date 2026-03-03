/**
 * EventRelay — Bidirectional event relay between OpenCode and AI-Gateway.
 *
 * Upstream:  OpenCode event stream  -->  AI-Gateway  (tool_event messages)
 * Downstream:  AI-Gateway commands  -->  OpenCode SDK calls  (chat, create_session, etc.)
 */

import type { GatewayConnection } from './GatewayConnection';
import type { OpenCodeBridge, EventSubscription, OpenCodeEvent } from './OpenCodeBridge';
import { ProtocolAdapter } from './ProtocolAdapter';
import { hasEnvelope, type MessageEnvelope } from './types/MessageEnvelope';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Shape of an upstream message sent to the gateway. */
interface UpstreamToolEvent {
  type: 'tool_event';
  sessionId: string | undefined;
  event: OpenCodeEvent;
}

/** Shape of a downstream invoke message received from the gateway. */
interface InvokeMessage {
  type: 'invoke';
  sessionId?: string;
  action: string;
  payload: Record<string, unknown>;
}

/** Shape of a status_query message from the gateway. */
interface StatusQueryMessage {
  type: 'status_query';
}

/** Union of all recognized downstream message shapes. */
type DownstreamMessage = InvokeMessage | StatusQueryMessage;

/** Callback for reporting relay errors without crashing the relay loop. */
export type RelayErrorHandler = (context: string, err: unknown) => void;

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Relays events bidirectionally between the local OpenCode instance
 * and the remote AI-Gateway.
 *
 * @example
 * ```ts
 * const relay = new EventRelay(gateway, opencode, agentId);
 * await relay.startUpstream();
 * relay.startDownstream();
 * // ... later
 * relay.stop();
 * ```
 */
export class EventRelay {
  /** Active upstream event subscription (null when not running). */
  private upstreamSubscription: EventSubscription | null = null;

  /** Whether the downstream listener has been attached. */
  private downstreamActive = false;

  /** Bound downstream handler reference (for removal on stop). */
  private downstreamHandler: ((data: unknown) => void) | null = null;

  /** Optional error handler. Defaults to console.error. */
  private onError: RelayErrorHandler;

  /** Protocol adapter for envelope wrapping/unwrapping. */
  private protocolAdapter: ProtocolAdapter | null = null;

  /** Whether to use envelope protocol (enabled after agentId is set). */
  private useEnvelope = false;

  /**
   * @param gateway   The AI-Gateway WebSocket connection.
   * @param opencode  The OpenCode SDK bridge.
   * @param agentId   Optional agent ID for envelope protocol (can be set later).
   * @param onError   Optional error callback (defaults to `console.error`).
   */
  constructor(
    private readonly gateway: GatewayConnection,
    private readonly opencode: OpenCodeBridge,
    agentId?: string,
    onError?: RelayErrorHandler,
  ) {
    this.onError = onError ?? ((ctx, err) => console.error(`[EventRelay] ${ctx}:`, err));
    if (agentId) {
      this.setAgentId(agentId);
    }
  }

  /**
   * Set the agent ID and enable envelope protocol.
   * Should be called after successful registration with the gateway.
   *
   * @param agentId  The agent connection ID assigned by the gateway
   */
  setAgentId(agentId: string): void {
    this.protocolAdapter = new ProtocolAdapter('OPENCODE', agentId);
    this.useEnvelope = true;
  }

  // -----------------------------------------------------------------------
  // Upstream: OpenCode --> Gateway
  // -----------------------------------------------------------------------

  /**
   * Subscribe to the OpenCode event stream and forward every event
   * to the AI-Gateway as a `tool_event` message.
   *
   * This method runs an async loop that only resolves when
   * {@link stop} is called or the event stream ends.
   */
  async startUpstream(): Promise<void> {
    if (this.upstreamSubscription) return;

    const subscription = await this.opencode.subscribeEvents();
    this.upstreamSubscription = subscription;

    // Run the relay loop without blocking the caller by using a detached
    // promise.  Errors are reported via the error handler.
    this.runUpstreamLoop(subscription).catch((err) => {
      this.onError('upstream loop', err);
    });
  }

  /**
   * Internal upstream relay loop.
   */
  private async runUpstreamLoop(subscription: EventSubscription): Promise<void> {
    try {
      for await (const event of subscription.stream) {
        const sessionId = event.properties?.sessionId ?? (event as Record<string, unknown>).sessionId as string | undefined;

        // Wrap in envelope if protocol adapter is available
        const message = this.useEnvelope && this.protocolAdapter
          ? this.protocolAdapter.wrapToolEvent(event, sessionId)
          : {
              type: 'tool_event',
              sessionId,
              event,
            };

        try {
          this.gateway.send(message);
        } catch (err) {
          this.onError('upstream send', err);
        }
      }
    } catch (err) {
      this.onError('upstream iteration', err);
    }
  }

  // -----------------------------------------------------------------------
  // Downstream: Gateway --> OpenCode
  // -----------------------------------------------------------------------

  /**
   * Start listening for downstream messages from the AI-Gateway
   * and dispatch them to the appropriate OpenCode SDK calls.
   */
  startDownstream(): void {
    if (this.downstreamActive) return;
    this.downstreamActive = true;

    this.downstreamHandler = (raw: unknown) => {
      this.handleDownstreamMessage(raw).catch((err) => {
        this.onError('downstream handler', err);
      });
    };

    this.gateway.onMessage(this.downstreamHandler);
  }

  /**
   * Route a single downstream message to the correct SDK operation.
   */
  private async handleDownstreamMessage(raw: unknown): Promise<void> {
    if (!raw || typeof raw !== 'object') return;

    // Unwrap envelope if present (backward compatibility)
    let msg: Record<string, unknown>;
    if (hasEnvelope(raw)) {
      const envelope = raw as MessageEnvelope;
      msg = {
        type: envelope.type,
        ...(typeof envelope.payload === 'object' && envelope.payload !== null
          ? (envelope.payload as Record<string, unknown>)
          : { payload: envelope.payload }),
      };
    } else {
      msg = raw as Record<string, unknown>;
    }

    const type = msg.type as string | undefined;

    if (type === 'invoke') {
      await this.handleInvoke(msg as unknown as InvokeMessage);
    } else if (type === 'status_query') {
      await this.handleStatusQuery();
    }
    // Unknown message types are silently ignored (forward compatibility).
  }

  /**
   * Handle an `invoke` message from the gateway.
   */
  private async handleInvoke(msg: InvokeMessage): Promise<void> {
    const { action, payload } = msg;

    switch (action) {
      case 'chat': {
        const toolSessionId = payload.toolSessionId as string | undefined;
        const text = payload.text as string | undefined;
        if (!toolSessionId || !text) {
          this.onError('invoke.chat', new Error('Missing toolSessionId or text in payload'));
          return;
        }
        try {
          await this.opencode.chat(toolSessionId, text);
        } catch (err) {
          this.onError('invoke.chat', err);
          this.trySendError(msg.sessionId, err);
        }
        break;
      }

      case 'create_session': {
        try {
          const session = await this.opencode.createSession(
            payload as Record<string, unknown> | undefined,
          );
          const sessionData = {
            type: 'session_created',
            toolSessionId: (session as Record<string, unknown>)?.id ?? (session as Record<string, unknown>)?.sessionId,
            session,
          };

          // Wrap in envelope if protocol adapter is available
          const message = this.useEnvelope && this.protocolAdapter
            ? this.protocolAdapter.wrapSessionCreated(
                sessionData.toolSessionId as string,
                sessionData.session,
              )
            : sessionData;

          this.gateway.send(message);
        } catch (err) {
          this.onError('invoke.create_session', err);
          this.trySendError(msg.sessionId, err);
        }
        break;
      }

      case 'close_session': {
        // Close session is a best-effort operation.  OpenCode SDK does not
        // expose a dedicated close — we simply acknowledge.
        // Future: if the SDK adds session.close(), call it here.
        break;
      }

      default:
        this.onError('invoke.unknown', new Error(`Unknown invoke action: ${action}`));
    }
  }

  /**
   * Respond to a `status_query` from the gateway with current health info.
   */
  private async handleStatusQuery(): Promise<void> {
    try {
      const online = await this.opencode.healthCheck();
      this.gateway.send({
        type: 'status_response',
        opencodeOnline: online,
      });
    } catch (err) {
      this.onError('status_query', err);
    }
  }

  /**
   * Best-effort: send a `tool_error` to the gateway when an SDK call fails.
   */
  private trySendError(sessionId: string | undefined, err: unknown): void {
    try {
      const errorMsg = err instanceof Error ? err.message : String(err);
      const message = this.useEnvelope && this.protocolAdapter
        ? this.protocolAdapter.wrapToolError(errorMsg, sessionId)
        : {
            type: 'tool_error',
            sessionId,
            error: errorMsg,
          };

      this.gateway.send(message);
    } catch {
      // If the gateway is disconnected we cannot report the error upstream.
    }
  }

  // -----------------------------------------------------------------------
  // Lifecycle
  // -----------------------------------------------------------------------

  /**
   * Stop both upstream and downstream relay loops.
   */
  stop(): void {
    // Stop upstream.
    if (this.upstreamSubscription) {
      this.upstreamSubscription.stop();
      this.upstreamSubscription = null;
    }

    // Stop downstream.
    this.downstreamActive = false;
    this.downstreamHandler = null;
  }
}
