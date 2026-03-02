/**
 * PCAgent Plugin Configuration
 *
 * Defines the configuration interface and sensible defaults for the
 * PCAgent plugin that bridges local OpenCode with the remote AI-Gateway.
 */

/** Configuration required to start the PCAgent plugin. */
export interface AgentConfig {
  /** Access Key issued by the chat platform's open platform. */
  ak: string;

  /** Secret Key used to compute HMAC-SHA256 signatures. */
  sk: string;

  /**
   * AI-Gateway WebSocket URL.
   * @example "ws://gateway-host:8081/ws/agent"
   */
  gatewayUrl: string;

  /**
   * Base URL of the local OpenCode HTTP server.
   * @default "http://localhost:54321"
   */
  opencodeBaseUrl: string;

  /**
   * Interval in milliseconds between heartbeat messages sent to the gateway.
   * @default 30000
   */
  heartbeatIntervalMs: number;

  /**
   * Interval in milliseconds between OpenCode health checks.
   * @default 30000
   */
  healthCheckIntervalMs: number;

  /**
   * Base delay in milliseconds for the first reconnect attempt (exponential backoff).
   * @default 1000
   */
  reconnectBaseMs: number;

  /**
   * Maximum delay in milliseconds for reconnect backoff.
   * @default 30000
   */
  reconnectMaxMs: number;
}

/** Returns a full AgentConfig by merging partial user input with defaults. */
export function resolveConfig(partial: Partial<AgentConfig> & Pick<AgentConfig, 'ak' | 'sk' | 'gatewayUrl'>): AgentConfig {
  return {
    opencodeBaseUrl: 'http://localhost:54321',
    heartbeatIntervalMs: 30_000,
    healthCheckIntervalMs: 30_000,
    reconnectBaseMs: 1_000,
    reconnectMaxMs: 30_000,
    ...partial,
  };
}
