/**
 * EventRelay — Bidirectional event relay between OpenCode and AI-Gateway.
 *
 * After Plugin refactor (Phase 1):
 * - Upstream: events are pushed by the Plugin event hook via `relayUpstream()`
 *   (no longer self-subscribes to SSE stream)
 * - Downstream: AI-Gateway commands → OpenCode SDK calls via `OpencodeClient`
 *   (no longer depends on `OpenCodeBridge`)
 */

import type { GatewayConnection } from './GatewayConnection';
import type { OpencodeClient } from '@opencode-ai/sdk';
import type { OpenCodeEvent } from './types/PluginTypes';
import { ProtocolAdapter } from './ProtocolAdapter';
import { mapPermissionResponse } from './PermissionMapper';
import { hasEnvelope, type MessageEnvelope } from './types/MessageEnvelope';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

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
 * After Plugin refactor:
 * - Upstream events are pushed via `relayUpstream()` (called by plugin event hook)
 * - Downstream commands use `OpencodeClient` (ctx.client) for SDK operations
 *
 * @example
 * ```ts
 * const relay = new EventRelay(gateway, ctx.client);
 * relay.startDownstream();
 *
 * // In plugin event hook:
 * relay.relayUpstream(event);
 * ```
 */
export class EventRelay {
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
   * @param client    The OpencodeClient from plugin context (ctx.client).
   * @param onError   Optional error callback (defaults to `console.error`).
   */
  constructor(
    private readonly gateway: GatewayConnection,
    private readonly client: OpencodeClient,
    onError?: RelayErrorHandler,
  ) {
    this.onError = onError ?? ((ctx, err) => console.error(`[EventRelay] ${ctx}:`, err));
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
  // Upstream: OpenCode --> Gateway (pushed by Plugin event hook)
  // -----------------------------------------------------------------------

  /**
   * Relay a single OpenCode event upstream to the AI-Gateway.
   *
   * This is called by the Plugin event hook — the EventRelay no longer
   * self-subscribes to SSE streams.
   *
   * @param event  The OpenCode event to relay
   */
  relayUpstream(event: OpenCodeEvent): void {
    const sessionId =
      (event.properties?.sessionId as string | undefined) ??
      (event as Record<string, unknown>).sessionId as string | undefined;

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
          await (this.client as any).session.prompt({
            path: { id: toolSessionId },
            body: { text },
          });
        } catch (err) {
          this.onError('invoke.chat', err);
          this.trySendError(msg.sessionId, err);
        }
        break;
      }

      case 'create_session': {
        try {
          const result = await (this.client as any).session.create({
            body: payload,
          });

          // SDK returns RequestResult wrapper: { data: Session, error, request, response }
          // Session object is inside result.data, NOT at the top level
          const sessionObj = result?.data ?? result;
          const extractedId = sessionObj?.id ?? sessionObj?.sessionId;

          if (!extractedId) {
            console.warn('[EventRelay] create_session: unable to extract toolSessionId. result keys:', Object.keys(result ?? {}), 'data keys:', Object.keys(result?.data ?? {}));
          }

          const sessionData = {
            type: 'session_created',
            sessionId: msg.sessionId,  // Echo back skill service session ID
            toolSessionId: extractedId,
            session: sessionObj,
          };

          // Wrap in envelope if protocol adapter is available
          const message = this.useEnvelope && this.protocolAdapter
            ? this.protocolAdapter.wrapSessionCreated(
              sessionData.toolSessionId as string,
              sessionData.session,
              msg.sessionId,  // Pass sessionId for envelope
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
        const toolSessionId = payload.toolSessionId as string | undefined;
        if (!toolSessionId) {
          this.onError('invoke.close_session', new Error('Missing toolSessionId in payload'));
          return;
        }
        try {
          await (this.client as any).session.abort({
            path: { id: toolSessionId },
          });
        } catch (err) {
          this.onError('invoke.close_session', err);
          this.trySendError(msg.sessionId, err);
        }
        break;
      }

      case 'permission_reply': {
        const toolSessionId = payload.toolSessionId as string | undefined;
        const permissionId = payload.permissionId as string | undefined;
        const response = payload.response as string | undefined;

        if (!toolSessionId || !permissionId || !response) {
          this.onError(
            'invoke.permission_reply',
            new Error('Missing toolSessionId, permissionId, or response in payload'),
          );
          return;
        }

        try {
          const sdkResponse = mapPermissionResponse(response);
          await (this.client as any).postSessionIdPermissionsPermissionId({
            body: { response: sdkResponse },
            path: { id: toolSessionId, permissionID: permissionId },
          });
        } catch (err) {
          this.onError('invoke.permission_reply', err);
          this.trySendError(msg.sessionId, err);
        }
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
      // Use SDK to check if OpenCode is reachable
      let online = false;
      try {
        await (this.client as any).app.health();
        online = true;
      } catch {
        online = false;
      }

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
   * Stop the downstream relay listener.
   * (Upstream is now push-based and doesn't need explicit stopping.)
   */
  stop(): void {
    this.downstreamActive = false;
    this.downstreamHandler = null;
  }
}
