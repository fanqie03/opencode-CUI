/**
 * PluginTypes — Local type definitions for the OpenCode Plugin API.
 *
 * Since `@opencode-ai/plugin` is not yet published as a standalone package,
 * we define the Plugin type locally based on SDK research.
 *
 * These types mirror the OpenCode Plugin specification:
 * - Plugin is an async function receiving PluginInput (ctx)
 * - ctx.client is an OpencodeClient instance
 * - The plugin returns a PluginResult with an event hook
 *
 * @see .gsd/phases/1/RESEARCH.md §1 for source research
 */

import type { OpencodeClient } from '@opencode-ai/sdk';

// ---------------------------------------------------------------------------
// OpenCode Event Types (from SDK Event union)
// ---------------------------------------------------------------------------

/** Minimal representation of an OpenCode event received via plugin hook. */
export interface OpenCodeEvent {
  type: string;
  properties?: Record<string, unknown>;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// Plugin API Types
// ---------------------------------------------------------------------------

/** Context passed to the plugin initializer. */
export interface PluginInput {
  /** The OpencodeClient instance — provides access to all SDK operations. */
  client: OpencodeClient;
}

/** The argument passed to the event hook callback. */
export interface PluginEventArg {
  /** The OpenCode event received. */
  event: OpenCodeEvent;
}

/** The return value from the plugin initializer. */
export interface PluginResult {
  /** Event hook — called for every OpenCode event. */
  event?: (arg: PluginEventArg) => void | Promise<void>;
}

/**
 * OpenCode Plugin type.
 *
 * A Plugin is an async function that receives a PluginInput context
 * and returns a PluginResult with optional event hooks.
 *
 * @example
 * ```ts
 * export const MyPlugin: Plugin = async (ctx) => {
 *   // ctx.client is the OpencodeClient
 *   return {
 *     event: async ({ event }) => {
 *       console.log('Received:', event.type);
 *     }
 *   };
 * };
 * ```
 */
export type Plugin = (ctx: PluginInput) => Promise<PluginResult>;
