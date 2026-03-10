/**
 * plugin — OpenCode Plugin entry point for the Platform Agent.
 *
 * This is the main entry point that conforms to the OpenCode Plugin API.
 * It replaces the old standalone `PcAgentPlugin` class.
 *
 * Lifecycle:
 *   1. Plugin initializer receives ctx.client (OpencodeClient)
 *   2. Initializes GatewayConnection with AK/SK auth
 *   3. Initializes EventRelay for bidirectional message routing
 *   4. Starts HealthChecker for periodic status reporting
 *   5. Returns event hook that filters and relays OpenCode events
 *
 * @see .gsd/phases/1/RESEARCH.md for Plugin API research
 * @see .gsd/phases/1/1-PLAN.md for implementation plan
 */

import type { Plugin, OpenCodeEvent } from './types/PluginTypes';
import { AkSkAuth } from './AkSkAuth';
import { GatewayConnection } from './GatewayConnection';
import { EventRelay } from './EventRelay';
import { HealthChecker } from './HealthChecker';
import { shouldRelay } from './EventFilter';
import { resolveConfig } from './config/AgentConfig';
import * as os from 'node:os';
import { networkInterfaces } from 'node:os';

// ---------------------------------------------------------------------------
// Plugin Entry Point
// ---------------------------------------------------------------------------

/**
 * PlatformAgent — OpenCode Plugin for bridging local OpenCode with AI-Gateway.
 *
 * Conforms to the OpenCode Plugin specification:
 * - Receives ctx.client (OpencodeClient) from the Plugin framework
 * - Returns an event hook for processing OpenCode events
 * - Does NOT create its own OpencodeClient or subscribe to event streams
 *
 * Configuration is read from environment variables:
 * - AGENT_AK — Access Key
 * - AGENT_SK — Secret Key
 * - AGENT_GATEWAY_URL — Gateway WebSocket URL
 *
 * @example
 * In opencode.json:
 * ```json
 * { "plugin": ["@opencode-cui/platform-agent"] }
 * ```
 */
export const PlatformAgent: Plugin = async (ctx) => {
    // ----- Read configuration (env vars with dev defaults) -----
    const ak = process.env.AGENT_AK ?? 'test-ak-001';
    const sk = process.env.AGENT_SK ?? 'test-sk-secret-001';
    const gatewayUrl = process.env.AGENT_GATEWAY_URL ?? 'ws://localhost:8081/ws/agent';

    console.log(`[PlatformAgent] Using ak=${ak}, gatewayUrl=${gatewayUrl}`);

    const config = resolveConfig({ ak, sk, gatewayUrl });

    // ----- Step 1: Generate AK/SK auth parameters -----
    // ----- Step 2: Connect to AI-Gateway -----
    const gateway = new GatewayConnection(
        config.gatewayUrl,
        config.heartbeatIntervalMs,
        config.reconnectBaseMs,
        config.reconnectMaxMs,
    );

    gateway.on('connected', () => {
        console.log('[PlatformAgent] Gateway connected');
        sendRegisterMessage(gateway);
    });
    gateway.on('disconnected', ({ code, reason }) => {
        console.warn(`[PlatformAgent] Gateway disconnected: code=${code} reason=${reason}`);
    });
    gateway.on('rejected', ({ code, reason }) => {
        console.error(`[PlatformAgent] Connection rejected: code=${code} reason=${reason}`);
        console.error('[PlatformAgent] Another instance may already be connected with this AK.');
    });
    gateway.on('error', (err) => {
        console.error('[PlatformAgent] Gateway error:', err.message);
    });

    await gateway.connect(() => AkSkAuth.sign(config.ak, config.sk));

    // ----- Step 3: Initialize bidirectional event relay -----
    const relay = new EventRelay(gateway, ctx.client, (context, err) => {
        console.error(`[PlatformAgent][EventRelay] ${context}:`, err);
    });
    relay.startDownstream();

    // ----- Step 4: Start health monitoring -----
    const healthChecker = new HealthChecker(ctx.client, gateway);
    healthChecker.start(config.healthCheckIntervalMs);

    console.log('[PlatformAgent] Initialized successfully');

    // ----- Return event hook -----
    return {
        event: async ({ event }) => {
            // Filter: only relay conversation-related events
            if (!shouldRelay(event.type)) {
                return;
            }

            // Relay upstream via EventRelay
            try {
                relay.relayUpstream(event);
            } catch (err) {
                console.error(`[PlatformAgent] Failed to relay event ${event.type}:`, err);
            }
        },
    };
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Send the initial `register` message after a successful connection.
 */
function sendRegisterMessage(gateway: GatewayConnection): void {
    try {
        gateway.send({
            type: 'register',
            deviceName: os.hostname(),
            macAddress: getMacAddress(),
            os: detectOS(),
            toolType: 'channel',
            toolVersion: '1.0.0',
        });
    } catch (err) {
        console.error('[PlatformAgent] Failed to send register message:', err);
    }
}

/**
 * Get the first non-internal MAC address of the device.
 */
function getMacAddress(): string {
    const nets = networkInterfaces();
    for (const name of Object.keys(nets)) {
        for (const net of nets[name] ?? []) {
            if (!net.internal && net.mac !== '00:00:00:00:00:00') {
                return net.mac;
            }
        }
    }
    return 'unknown';
}

/**
 * Detect the current operating system for the register message.
 */
function detectOS(): string {
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

// Default export for plugin registration
export default PlatformAgent;
