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
import { mapPermissionResponse } from './PermissionMapper';
import * as fs from 'node:fs';
import * as path from 'node:path';

// Debug file logger (console output is invisible in plugin context)
const DEBUG_LOG_PATH = path.join(process.env.TEMP || process.env.TMP || '/tmp', 'pc-agent-debug.log');
function debugLog(context: string, ...args: unknown[]): void {
  try {
    const ts = new Date().toISOString();
    const msg = args.map(a => typeof a === 'object' ? JSON.stringify(a, null, 2) : String(a)).join(' ');
    fs.appendFileSync(DEBUG_LOG_PATH, `[${ts}] [${context}] ${msg}\n`);
  } catch { /* ignore */ }
}

function estimatePayloadBytes(payload: unknown): number | undefined {
  try {
    return Buffer.byteLength(JSON.stringify(payload), 'utf8');
  } catch {
    return undefined;
  }
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Shape of a downstream invoke message received from the gateway. */
interface InvokeMessage {
  type: 'invoke';
  welinkSessionId?: string;
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

function readString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function readRecord(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object'
    ? value as Record<string, unknown>
    : undefined;
}

function extractToolSessionId(event: OpenCodeEvent): string | undefined {
  const eventRecord = event as Record<string, unknown>;
  const properties = readRecord(event.properties);
  const info = readRecord(eventRecord.info);
  const part = readRecord(properties?.part);
  const session = readRecord(properties?.session);
  const status = readRecord(properties?.status);

  return [
    properties?.sessionId,
    properties?.sessionID,
    part?.sessionId,
    part?.sessionID,
    session?.sessionId,
    session?.sessionID,
    status?.sessionId,
    status?.sessionID,
    eventRecord.sessionId,
    eventRecord.sessionID,
    info?.sessionId,
    info?.sessionID,
  ].map(readString).find((value): value is string => Boolean(value));
}

function extractPromptResultParts(result: unknown): Array<Record<string, unknown>> {
  const resultRecord = readRecord(result);
  const data = readRecord(resultRecord?.data) ?? resultRecord;
  const parts = data?.parts;
  return Array.isArray(parts)
    ? parts.filter((part): part is Record<string, unknown> => part !== null && typeof part === 'object')
    : [];
}

function extractResultData<T>(result: unknown): T | undefined {
  const resultRecord = readRecord(result);
  if (!resultRecord) {
    return undefined;
  }

  if ('data' in resultRecord) {
    return resultRecord.data as T;
  }

  return result as T;
}

function getRawClient(client: unknown): Record<string, unknown> | undefined {
  const clientRecord = readRecord(client);
  const rawClient = readRecord(clientRecord?._client);
  return rawClient;
}

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

  /** Sessions currently waiting for prompt results to be relayed upstream. */
  private readonly pendingPromptSessions = new Set<string>();

  /** Sessions already completed via prompt fallback; suppress duplicate idle. */
  private readonly completedPromptSessions = new Set<string>();

  /** Log payload sizes at or above this threshold. */
  private readonly largePayloadLogThresholdBytes = Number(process.env.AGENT_LARGE_PAYLOAD_LOG_THRESHOLD_BYTES ?? '32768');



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

  private resolveToolSessionId(event: OpenCodeEvent): string | undefined {
    const explicitToolSessionId = extractToolSessionId(event);
    if (explicitToolSessionId) {
      return explicitToolSessionId;
    }

    if (!this.isPromptScopedEvent(event) || this.pendingPromptSessions.size !== 1) {
      return undefined;
    }

    const [fallbackToolSessionId] = this.pendingPromptSessions;
    debugLog('relayUpstream', `using pending prompt fallback toolSessionId=${fallbackToolSessionId} for eventType=${event.type}`);
    return fallbackToolSessionId;
  }

  private isPromptScopedEvent(event: OpenCodeEvent): boolean {
    const eventType = readString(event.type);
    if (!eventType) {
      return false;
    }

    return eventType.startsWith('message.')
      || eventType.startsWith('question.')
      || eventType.startsWith('permission.')
      || eventType.startsWith('session.');
  }

  private async findPendingQuestionRequestId(
    toolSessionId: string,
    toolCallId?: string,
  ): Promise<string | undefined> {
    const rawClient = getRawClient(this.client);
    const getFn = rawClient?.get as ((options: Record<string, unknown>) => Promise<unknown>) | undefined;
    if (!getFn) {
      debugLog('invoke.question_reply', 'raw client GET unavailable on client');
      return undefined;
    }

    const listResult = await getFn({
      url: '/question',
    });
    const pendingQuestions = extractResultData<unknown>(listResult);
    const requests = Array.isArray(pendingQuestions)
      ? pendingQuestions.filter((item): item is Record<string, unknown> => item !== null && typeof item === 'object')
      : [];

    const matched = requests.find((request) => {
      const sessionID = readString(request.sessionID);
      if (sessionID !== toolSessionId) {
        return false;
      }

      if (!toolCallId) {
        return true;
      }

      const tool = readRecord(request.tool);
      return readString(tool?.callID) === toolCallId;
    });

    const requestId = readString(matched?.id);
    debugLog(
      'invoke.question_reply',
      `resolved requestID=${requestId} from pending questions for toolSessionId=${toolSessionId}, toolCallId=${toolCallId}`,
    );
    return requestId;
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
    const toolSessionId = this.resolveToolSessionId(event);
    const isSessionIdle = event.type === 'session.idle';

    debugLog('relayUpstream', `eventType=${event.type} toolSessionId=${toolSessionId}`);

    if (event.type === 'session.diff') {
      debugLog('relayUpstream', `skipping high-volume eventType=session.diff toolSessionId=${toolSessionId}`);
      return;
    }

    if (isSessionIdle && toolSessionId && this.pendingPromptSessions.has(toolSessionId)) {
      debugLog('relayUpstream', `deferring session.idle for toolSessionId=${toolSessionId}`);
      return;
    }

    if (isSessionIdle && toolSessionId && this.completedPromptSessions.delete(toolSessionId)) {
      debugLog('relayUpstream', `dropping duplicate session.idle for toolSessionId=${toolSessionId}`);
      return;
    }

    // Send upstream with toolSessionId only — Skill Server resolves
    // welinkSessionId via DB lookup (findByToolSessionId)
    const message = {
      type: 'tool_event',
      toolSessionId,
      event,
    };

    try {
      this.sendGatewayMessage(message, `relayUpstream:${event.type}`);
      if (isSessionIdle && toolSessionId) {
        this.sendGatewayMessage({
          type: 'tool_done',
          toolSessionId,
        }, 'relayUpstream:tool_done');
      }
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

    debugLog('downstream', 'Received raw message:', raw);

    const msg = raw as Record<string, unknown>;

    const type = msg.type as string | undefined;
    debugLog('downstream', 'Message type:', type);

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
        debugLog('invoke.chat', `toolSessionId=${toolSessionId}, text=${text}, payload keys:`, Object.keys(payload));

        if (!toolSessionId || !text) {
          debugLog('invoke.chat', 'MISSING fields! toolSessionId:', toolSessionId, 'text:', text);
          this.onError('invoke.chat', new Error('Missing toolSessionId or text in payload'));
          return;
        }
        try {
          this.pendingPromptSessions.add(toolSessionId);
          const promptArgs = {
            path: { id: toolSessionId },
            body: {
              parts: [{ type: 'text' as const, text }],
            },
          };
          debugLog('invoke.chat', 'Calling session.prompt with:', promptArgs);
          const result = await (this.client as any).session.prompt(promptArgs);
          debugLog('invoke.chat', 'session.prompt result:', result);
          this.relayPromptResultParts(toolSessionId, result);
          this.completedPromptSessions.add(toolSessionId);
          this.gateway.send({
            type: 'tool_done',
            toolSessionId,
          });
        } catch (err) {
          debugLog('invoke.chat', 'session.prompt ERROR:', err instanceof Error ? { message: err.message, stack: err.stack } : err);
          this.onError('invoke.chat', err);
          this.trySendError(msg.welinkSessionId, toolSessionId, err);
        } finally {
          this.pendingPromptSessions.delete(toolSessionId);
        }
        break;
      }

      case 'question_reply': {
        const toolSessionId = payload.toolSessionId as string | undefined;
        const answer = payload.answer as string | undefined;
        const toolCallId = payload.toolCallId as string | undefined;
        debugLog('invoke.question_reply', `toolSessionId=${toolSessionId}, answer=${answer}, toolCallId=${toolCallId}`);

        if (!toolSessionId || !answer) {
          this.onError('invoke.question_reply', new Error('Missing toolSessionId or answer in payload'));
          return;
        }
        try {
          const requestID = await this.findPendingQuestionRequestId(toolSessionId, toolCallId);
          if (!requestID) {
            throw new Error(`Unable to resolve pending question request for toolSessionId=${toolSessionId}, toolCallId=${toolCallId ?? 'unknown'}`);
          }

          const rawClient = getRawClient(this.client);
          const postFn = rawClient?.post as ((options: Record<string, unknown>) => Promise<unknown>) | undefined;
          if (!postFn) {
            throw new Error('raw client POST unavailable on client');
          }

          const replyArgs = {
            url: '/question/{requestID}/reply',
            path: { requestID },
            body: { answers: [[answer]] },
            headers: {
              'Content-Type': 'application/json',
            },
          };
          debugLog('invoke.question_reply', 'Calling raw question reply with:', replyArgs);
          const result = await postFn(replyArgs);
          debugLog('invoke.question_reply', 'raw question reply result:', result);
        } catch (err) {
          debugLog('invoke.question_reply', 'question.reply ERROR:', err instanceof Error ? { message: err.message, stack: err.stack } : err);
          this.onError('invoke.question_reply', err);
          this.trySendError(msg.welinkSessionId, toolSessionId, err);
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
          const extractedId = sessionObj?.id ?? sessionObj?.sessionId ?? sessionObj?.sessionID;

          if (!extractedId) {
            console.warn('[EventRelay] create_session: unable to extract toolSessionId. result keys:', Object.keys(result ?? {}), 'data keys:', Object.keys(result?.data ?? {}));
          }

          const sessionData = {
            type: 'session_created',
            welinkSessionId: msg.welinkSessionId,
            toolSessionId: extractedId,
            session: sessionObj,
          };

          this.gateway.send(sessionData);
        } catch (err) {
          this.onError('invoke.create_session', err);
          this.trySendError(msg.welinkSessionId, undefined, err);
        }
        break;
      }

      case 'abort_session': {
        const toolSessionId = payload.toolSessionId as string | undefined;
        if (!toolSessionId) {
          this.onError('invoke.abort_session', new Error('Missing toolSessionId in payload'));
          return;
        }
        try {
          debugLog('invoke.abort_session', `Aborting session: ${toolSessionId}`);
          await (this.client as any).session.abort({
            path: { id: toolSessionId },
          });
          debugLog('invoke.abort_session', 'session.abort succeeded');
        } catch (err) {
          debugLog('invoke.abort_session', 'session.abort ERROR:', err);
          this.onError('invoke.abort_session', err);
          this.trySendError(msg.welinkSessionId, toolSessionId, err);
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
          debugLog('invoke.close_session', `Deleting session: ${toolSessionId}`);
          await (this.client as any).session.delete({
            path: { id: toolSessionId },
          });
          debugLog('invoke.close_session', 'session.delete succeeded');
        } catch (err) {
          this.onError('invoke.close_session', err);
          this.trySendError(msg.welinkSessionId, toolSessionId, err);
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
          this.trySendError(msg.welinkSessionId, toolSessionId, err);
        }
        break;
      }

      default:
        this.onError('invoke.unknown', new Error(`Unknown invoke action: ${action}`));
    }
  }

  private relayPromptResultParts(toolSessionId: string, result: unknown): void {
    const parts = extractPromptResultParts(result);
    if (parts.length === 0) {
      debugLog('relayPromptResultParts', `no final parts for toolSessionId=${toolSessionId}`);
      return;
    }

    let relayedCount = 0;
    for (const part of parts) {
      const partType = readString(part.type);
      if (!partType || !['text', 'reasoning', 'tool', 'file'].includes(partType)) {
        debugLog(
          'relayPromptResultParts',
          `skip part type=${partType ?? 'unknown'} toolSessionId=${toolSessionId}`,
        );
        continue;
      }

      this.gateway.send({
        type: 'tool_event',
        toolSessionId,
        event: {
          type: 'message.part.updated',
          properties: {
            sessionID: readString(part.sessionID) ?? toolSessionId,
            messageID: readString(part.messageID),
            part,
          },
        },
      });
      relayedCount += 1;
    }

    debugLog('relayPromptResultParts', `relayed ${relayedCount} final parts for toolSessionId=${toolSessionId}`);
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
      }, 'status_query:status_response');
    } catch (err) {
      this.onError('status_query', err);
    }
  }

  /**
   * Best-effort: send a `tool_error` to the gateway when an SDK call fails.
   */
  private trySendError(
    welinkSessionId: string | undefined,
    toolSessionId: string | undefined,
    err: unknown,
  ): void {
    try {
      const errorMsg = err instanceof Error ? err.message : String(err);
      this.gateway.send({
        type: 'tool_error',
        welinkSessionId,
        toolSessionId,
        error: errorMsg,
      }, 'tool_error');
    } catch {
      // If the gateway is disconnected we cannot report the error upstream.
    }
  }

  private sendGatewayMessage(message: unknown, context: string): void {
    const payloadBytes = estimatePayloadBytes(message);
    if (payloadBytes !== undefined && payloadBytes >= this.largePayloadLogThresholdBytes) {
      const messageRecord = readRecord(message);
      debugLog(
        'gateway.send',
        `large payload context=${context} bytes=${payloadBytes} type=${readString(messageRecord?.type) ?? 'unknown'}`,
      );
    }
    this.gateway.send(message);
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
