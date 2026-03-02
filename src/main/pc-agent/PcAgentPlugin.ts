/**
 * PcAgentPlugin — Main entry point for the PCAgent plugin.
 *
 * Orchestrates all sub-modules to bridge the local OpenCode instance
 * with the remote AI-Gateway:
 *
 *  1. Generate AK/SK authentication parameters
 *  2. Establish WebSocket connection to AI-Gateway
 *  3. Initialize OpenCode SDK bridge
 *  4. Start bidirectional event relay
 *  5. Start periodic health monitoring
 *  6. Send a `register` message to the gateway
 *
 * Shutdown reverses this sequence gracefully.
 */

import { AkSkAuth } from './AkSkAuth';
import { GatewayConnection, ConnectionState } from './GatewayConnection';
import { OpenCodeBridge } from './OpenCodeBridge';
import { EventRelay } from './EventRelay';
import { HealthChecker } from './HealthChecker';
import type { AgentConfig } from './config/AgentConfig';
import { resolveConfig } from './config/AgentConfig';
import * as os from 'node:os';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Snapshot of the plugin's current operational state. */
export interface PluginStatus {
  /** Whether the plugin has been started and has not been stopped. */
  running: boolean;
  /** Current AI-Gateway WebSocket connection state. */
  gatewayState: ConnectionState;
  /** Last known OpenCode reachability status. */
  opencodeStatus: 'online' | 'offline' | 'unknown';
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * PCAgent plugin that bridges local OpenCode with the AI-Gateway.
 *
 * @example
 * ```ts
 * const plugin = new PcAgentPlugin();
 * await plugin.start({
 *   ak: 'my-access-key',
 *   sk: 'my-secret-key',
 *   gatewayUrl: 'ws://gateway-host:8081/ws/agent',
 * });
 *
 * console.log(plugin.getStatus());
 * // { running: true, gatewayState: 'CONNECTED', opencodeStatus: 'online' }
 *
 * await plugin.stop();
 * ```
 */
export class PcAgentPlugin {
  private gateway: GatewayConnection | null = null;
  private opencode: OpenCodeBridge | null = null;
  private relay: EventRelay | null = null;
  private healthChecker: HealthChecker | null = null;
  private running = false;
  private config: AgentConfig | null = null;

  // -----------------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------------

  /**
   * Start the plugin — connect to AI-Gateway, initialize OpenCode bridge,
   * and begin bidirectional event relay + health monitoring.
   *
   * @param partialConfig  Plugin configuration.  Only `ak`, `sk`, and
   *                       `gatewayUrl` are required; all other fields
   *                       have sensible defaults.
   * @throws If the initial gateway connection fails.
   */
  async start(
    partialConfig: Partial<AgentConfig> & Pick<AgentConfig, 'ak' | 'sk' | 'gatewayUrl'>,
  ): Promise<void> {
    if (this.running) {
      throw new Error('PcAgentPlugin is already running. Call stop() first.');
    }

    const config = resolveConfig(partialConfig);
    this.config = config;

    try {
      // ----- Step 1: Generate AK/SK auth parameters -----
      const authParams = AkSkAuth.sign(config.ak, config.sk);

      // ----- Step 2: Connect to AI-Gateway -----
      this.gateway = new GatewayConnection(
        config.gatewayUrl,
        config.heartbeatIntervalMs,
        config.reconnectBaseMs,
        config.reconnectMaxMs,
      );

      // Wire up logging for connection events.
      this.gateway.on('connected', () => {
        console.log('[PcAgentPlugin] Gateway connected');
      });
      this.gateway.on('disconnected', ({ code, reason }) => {
        console.warn(`[PcAgentPlugin] Gateway disconnected: code=${code} reason=${reason}`);
      });
      this.gateway.on('error', (err) => {
        console.error('[PcAgentPlugin] Gateway error:', err.message);
      });

      await this.gateway.connect(authParams);

      // ----- Step 3: Initialize OpenCode bridge -----
      this.opencode = new OpenCodeBridge(config.opencodeBaseUrl);

      // ----- Step 4: Start bidirectional event relay -----
      this.relay = new EventRelay(this.gateway, this.opencode, (ctx, err) => {
        console.error(`[PcAgentPlugin][EventRelay] ${ctx}:`, err);
      });

      await this.relay.startUpstream();
      this.relay.startDownstream();

      // ----- Step 5: Start health monitoring -----
      this.healthChecker = new HealthChecker(this.opencode, this.gateway);
      this.healthChecker.start(config.healthCheckIntervalMs);

      // ----- Step 6: Send register message to gateway -----
      this.sendRegisterMessage();

      this.running = true;
      console.log('[PcAgentPlugin] Started successfully');
    } catch (err) {
      // If any step fails, clean up anything that was partially initialized.
      await this.cleanup();
      throw err;
    }
  }

  /**
   * Gracefully stop the plugin.
   *
   * Shuts down modules in reverse order:
   * health checker -> event relay -> gateway connection.
   */
  async stop(): Promise<void> {
    if (!this.running) return;
    console.log('[PcAgentPlugin] Stopping...');
    await this.cleanup();
    this.running = false;
    console.log('[PcAgentPlugin] Stopped');
  }

  /**
   * Return a snapshot of the plugin's current operational state.
   */
  getStatus(): PluginStatus {
    return {
      running: this.running,
      gatewayState: this.gateway?.state ?? ConnectionState.CLOSED,
      opencodeStatus: this.healthChecker?.lastStatus ?? 'unknown',
    };
  }

  // -----------------------------------------------------------------------
  // Internal
  // -----------------------------------------------------------------------

  /**
   * Send the initial `register` message after a successful connection.
   *
   * Includes device name, OS, tool type, and a placeholder version.
   */
  private sendRegisterMessage(): void {
    if (!this.gateway) return;

    try {
      this.gateway.send({
        type: 'register',
        deviceName: os.hostname(),
        os: this.detectOS(),
        toolType: 'OPENCODE',
        toolVersion: '1.0.0',
      });
    } catch (err) {
      console.error('[PcAgentPlugin] Failed to send register message:', err);
    }
  }

  /**
   * Detect the current operating system for the register message.
   */
  private detectOS(): string {
    const platform = os.platform();
    switch (platform) {
      case 'win32':
        return 'WINDOWS';
      case 'darwin':
        return 'MAC';
      case 'linux':
        return 'LINUX';
      default:
        return platform.toUpperCase();
    }
  }

  /**
   * Tear down all sub-modules.  Safe to call even when partially initialized.
   */
  private async cleanup(): Promise<void> {
    // 1. Stop health checker
    if (this.healthChecker) {
      this.healthChecker.stop();
      this.healthChecker = null;
    }

    // 2. Stop event relay
    if (this.relay) {
      this.relay.stop();
      this.relay = null;
    }

    // 3. Close gateway connection
    if (this.gateway) {
      this.gateway.close();
      this.gateway = null;
    }

    // 4. Discard opencode bridge reference
    this.opencode = null;
    this.config = null;
  }
}
