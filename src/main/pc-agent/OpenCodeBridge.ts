/**
 * OpenCodeBridge — `@opencode-ai/sdk` bridge layer.
 *
 * Wraps the OpenCode SDK client and exposes the subset of operations
 * required by the PCAgent plugin:
 *
 *  - Event subscription (SSE stream)
 *  - Chat message sending
 *  - Session management (create / list)
 *  - Health checking
 *  - Automatic fallback to the global event stream when the main
 *    stream appears degraded (30 s of heartbeat-only traffic).
 */

import { OpenCode } from '@opencode-ai/sdk';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Minimal representation of an OpenCode event received from the stream. */
export interface OpenCodeEvent {
  type: string;
  properties?: {
    sessionId?: string;
    [key: string]: unknown;
  };
  [key: string]: unknown;
}

/** Subscription handle returned by {@link OpenCodeBridge.subscribeEvents}. */
export interface EventSubscription {
  /**
   * Async iterator that yields parsed events from the OpenCode event stream.
   * Automatically falls back to the global event stream when the main
   * stream is degraded.
   */
  stream: AsyncIterable<OpenCodeEvent>;

  /**
   * Signal the bridge to stop iterating.  The async iterator will
   * terminate on the next tick.
   */
  stop(): void;
}

/** Parameters accepted by {@link OpenCodeBridge.createSession}. */
export interface CreateSessionParams {
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Bridge to the local OpenCode instance via `@opencode-ai/sdk`.
 *
 * @example
 * ```ts
 * const bridge = new OpenCodeBridge('http://localhost:54321');
 * if (await bridge.healthCheck()) {
 *   const sub = await bridge.subscribeEvents();
 *   for await (const event of sub.stream) {
 *     console.log(event);
 *   }
 * }
 * ```
 */
export class OpenCodeBridge {
  private readonly client: InstanceType<typeof OpenCode>;

  /**
   * Threshold in milliseconds. If the main event stream produces only
   * heartbeat / server.connected events for this duration, the bridge
   * enables the global fallback stream.
   */
  private readonly degradedThresholdMs: number;

  /**
   * @param baseURL            Base URL of the local OpenCode server.
   * @param degradedThresholdMs  Fallback threshold (default 30 000 ms).
   */
  constructor(baseURL: string, degradedThresholdMs = 30_000) {
    this.client = new OpenCode({ baseURL });
    this.degradedThresholdMs = degradedThresholdMs;
  }

  // -----------------------------------------------------------------------
  // Event Subscription
  // -----------------------------------------------------------------------

  /**
   * Subscribe to the OpenCode event stream.
   *
   * Returns an {@link EventSubscription} whose `.stream` async iterable
   * yields every event from the primary `api.event.subscribe()` stream.
   * If the primary stream produces only heartbeat events for
   * {@link degradedThresholdMs}, the bridge additionally consumes
   * `api.global.event()` and merges its non-heartbeat events.
   *
   * @returns A subscription handle.
   */
  async subscribeEvents(): Promise<EventSubscription> {
    let stopped = false;

    const stop = () => {
      stopped = true;
    };

    const self = this;

    const stream: AsyncIterable<OpenCodeEvent> = {
      [Symbol.asyncIterator]() {
        return self.createMergedIterator(() => stopped);
      },
    };

    return { stream, stop };
  }

  // -----------------------------------------------------------------------
  // Session Operations
  // -----------------------------------------------------------------------

  /**
   * Send a chat message to an existing OpenCode session.
   *
   * @param toolSessionId  The OpenCode session identifier.
   * @param text           User message text.
   */
  async chat(toolSessionId: string, text: string): Promise<void> {
    await (this.client as any).session.chat(toolSessionId, { text });
  }

  /**
   * Create a new OpenCode session.
   *
   * @param params  Optional creation parameters forwarded to the SDK.
   * @returns The created session object.
   */
  async createSession(params?: CreateSessionParams): Promise<unknown> {
    return (this.client as any).session.create(params ?? {});
  }

  /**
   * List all existing OpenCode sessions.
   *
   * @returns An array of session objects.
   */
  async listSessions(): Promise<unknown[]> {
    const result = await (this.client as any).session.list();
    return Array.isArray(result) ? result : [];
  }

  // -----------------------------------------------------------------------
  // Health
  // -----------------------------------------------------------------------

  /**
   * Check whether the local OpenCode server is reachable.
   *
   * @returns `true` if the health endpoint responds successfully.
   */
  async healthCheck(): Promise<boolean> {
    try {
      await (this.client as any).global.health();
      return true;
    } catch {
      return false;
    }
  }

  // -----------------------------------------------------------------------
  // Internal — merged event iterator with fallback
  // -----------------------------------------------------------------------

  /**
   * Creates an async generator that yields events from the main stream
   * and, when degraded, also from the global event stream.
   */
  private async *createMergedIterator(
    isStopped: () => boolean,
  ): AsyncGenerator<OpenCodeEvent, void, undefined> {
    let lastRichEventAt = Date.now();
    let globalFallbackActive = false;
    let globalFallbackAnnounced = false;

    // Buffer for events arriving from the global stream.
    const globalBuffer: OpenCodeEvent[] = [];
    let globalStreamDone = false;

    // Start the global fallback listener in the background.
    const startGlobalFallback = async () => {
      if (globalFallbackActive) return;
      globalFallbackActive = true;

      try {
        const globalEvents = await (this.client as any).global.event();
        for await (const raw of globalEvents.stream) {
          if (isStopped()) break;
          const event = this.normalizeEvent(raw);
          if (!event) continue;
          if (event.type === 'server.heartbeat' || event.type === 'server.connected') continue;
          globalBuffer.push(event);
        }
      } catch {
        // Global stream ended or errored; non-fatal.
      } finally {
        globalStreamDone = true;
      }
    };

    // Main stream loop.
    try {
      const subscription = await (this.client as any).event.subscribe();

      for await (const raw of subscription.stream) {
        if (isStopped()) break;

        const event = this.normalizeEvent(raw);
        if (!event) continue;

        // Track whether the main stream is delivering real content.
        if (event.type !== 'server.heartbeat' && event.type !== 'server.connected') {
          lastRichEventAt = Date.now();
          globalFallbackAnnounced = false;
        }

        yield event;

        // Check degradation threshold.
        const elapsed = Date.now() - lastRichEventAt;
        if (elapsed >= this.degradedThresholdMs && !globalFallbackActive) {
          if (!globalFallbackAnnounced) {
            globalFallbackAnnounced = true;
          }
          // Fire and forget — we drain globalBuffer below.
          startGlobalFallback();
        }

        // Drain any events that arrived via the global fallback.
        while (globalBuffer.length > 0) {
          const buffered = globalBuffer.shift()!;
          yield buffered;
        }
      }
    } catch (err) {
      if (!isStopped()) {
        throw err;
      }
    }

    // Final drain of global buffer after main stream ends.
    while (globalBuffer.length > 0) {
      const buffered = globalBuffer.shift()!;
      yield buffered;
    }
  }

  /**
   * Normalize a raw SDK event into our minimal {@link OpenCodeEvent} shape.
   * Returns `null` if the event cannot be parsed.
   */
  private normalizeEvent(raw: unknown): OpenCodeEvent | null {
    if (!raw || typeof raw !== 'object') return null;
    const candidate = raw as Record<string, unknown>;
    if (typeof candidate.type !== 'string') return null;
    return candidate as OpenCodeEvent;
  }
}
