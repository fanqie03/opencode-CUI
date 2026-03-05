/**
 * EventFilter — Determines which OpenCode events should be relayed upstream.
 *
 * Events are categorized into two groups:
 * - **Relay events**: conversation-related events forwarded to AI-Gateway
 * - **Local events**: TUI/PTY/IDE events that stay on the local machine
 *
 * @see .gsd/phases/1/RESEARCH.md §5 for the complete event type catalog
 */

// ---------------------------------------------------------------------------
// Event type prefixes that should be relayed upstream
// ---------------------------------------------------------------------------

/**
 * Prefixes of event types that should be relayed to the AI-Gateway.
 *
 * These represent conversation-relevant events:
 * - message.* — AI response content stream
 * - permission.* — Permission request/reply flow
 * - session.* — Session lifecycle
 * - file.edited — File operations (NOT file.watcher.*)
 * - todo.updated — Task list changes
 * - command.executed — Command execution results
 */
const RELAY_PREFIXES = [
    'message.',
    'permission.',
    'session.',
] as const;

/**
 * Exact event types that should be relayed but don't follow prefix patterns.
 */
const RELAY_EXACT = new Set([
    'file.edited',
    'todo.updated',
    'command.executed',
]);

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Determine whether an OpenCode event should be relayed upstream to the
 * AI-Gateway.
 *
 * @param eventType  The `type` field from an OpenCode Event
 * @returns `true` if the event should be forwarded, `false` if it's local-only
 *
 * @example
 * ```ts
 * shouldRelay('message.updated')     // true
 * shouldRelay('permission.updated')  // true
 * shouldRelay('tui.prompt.append')   // false
 * shouldRelay('pty.created')         // false
 * ```
 */
export function shouldRelay(eventType: string): boolean {
    // Check prefix matches first (covers message.*, permission.*, session.*)
    for (const prefix of RELAY_PREFIXES) {
        if (eventType.startsWith(prefix)) {
            return true;
        }
    }

    // Check exact matches (file.edited, todo.updated, command.executed)
    return RELAY_EXACT.has(eventType);
}
