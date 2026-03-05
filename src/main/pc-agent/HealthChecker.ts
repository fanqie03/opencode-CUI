/**
 * HealthChecker — Periodic OpenCode reachability monitor.
 *
 * After Plugin refactor (Phase 1):
 * - Uses OpencodeClient (from ctx.client) instead of OpenCodeBridge
 * - Health checks via the SDK client's app.health() endpoint
 * - Reports agent_online/agent_offline status changes to AI-Gateway
 */

import type { GatewayConnection } from './GatewayConnection';
import type { OpencodeClient } from '@opencode-ai/sdk';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Health status reported by the checker. */
export type HealthStatus = 'online' | 'offline';

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Monitors the local OpenCode server and reports status changes
 * to the AI-Gateway.
 *
 * @example
 * ```ts
 * const checker = new HealthChecker(ctx.client, gateway);
 * checker.start(30_000); // check every 30 seconds
 * // ... later
 * checker.stop();
 * ```
 */
export class HealthChecker {
  /** Handle to the periodic interval timer (null when stopped). */
  private intervalTimer: ReturnType<typeof setInterval> | null = null;

  /** Last reported status. `null` before the first check completes. */
  private previousStatus: HealthStatus | null = null;

  /**
   * @param client   The OpencodeClient from plugin context (ctx.client).
   * @param gateway  The AI-Gateway connection used to report status changes.
   */
  constructor(
    private readonly client: OpencodeClient,
    private readonly gateway: GatewayConnection,
  ) { }

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /**
   * Start periodic health checking.
   *
   * An initial check is performed immediately (without waiting for the
   * first interval to elapse).
   *
   * @param intervalMs  Milliseconds between consecutive health checks.
   */
  start(intervalMs: number): void {
    if (this.intervalTimer) return; // already running

    // Immediate first check.
    this.performCheck();

    this.intervalTimer = setInterval(() => {
      this.performCheck();
    }, intervalMs);
  }

  /**
   * Stop periodic health checking.
   */
  stop(): void {
    if (this.intervalTimer) {
      clearInterval(this.intervalTimer);
      this.intervalTimer = null;
    }
  }

  /**
   * Execute a single health check and return the result.
   *
   * This does **not** send a status-change message to the gateway;
   * use {@link start} for automatic reporting.
   *
   * @returns `'online'` if OpenCode is reachable, `'offline'` otherwise.
   */
  async check(): Promise<HealthStatus> {
    try {
      await (this.client as any).app.health();
      return 'online';
    } catch {
      return 'offline';
    }
  }

  /** Returns the last known health status, or `null` before the first check. */
  get lastStatus(): HealthStatus | null {
    return this.previousStatus;
  }

  // -----------------------------------------------------------------------
  // Internal
  // -----------------------------------------------------------------------

  /**
   * Perform a single health check and, if the status has changed,
   * notify the AI-Gateway.
   */
  private async performCheck(): Promise<void> {
    const current = await this.check();

    if (current !== this.previousStatus) {
      this.previousStatus = current;
      this.reportStatusChange(current);
    }
  }

  /**
   * Send an `agent_online` or `agent_offline` message to the gateway.
   * Failures are silently swallowed (the gateway may be disconnected).
   */
  private reportStatusChange(status: HealthStatus): void {
    const messageType = status === 'online' ? 'agent_online' : 'agent_offline';
    try {
      this.gateway.send({ type: messageType });
    } catch {
      // Gateway may not be connected yet; ignore.
    }
  }
}
