package com.opencode.cui.skill.telemetry.metrics;

/**
 * Pluggable handler for message-turn lifecycle events.
 *
 * <p>Implementations are ordered via {@link org.springframework.core.annotation.Order @Order}
 * and collected by {@link MessageTurnLifecycle}, which iterates them on each lifecycle event.
 * All methods have default no-op implementations so handlers only override what they need.
 */
public interface MessageTurnHandler {

    /** Called when a new message turn starts (before any tokens). */
    default void turnStart(MessageTurnContext ctx) {
    }

    /** Called exactly once per messageId when the first token arrives. */
    default void firstToken(MessageTurnContext ctx) {
    }

    /** Called on every token (including the first). {@code contentLength} is the token's byte/char length. */
    default void token(MessageTurnContext ctx, int contentLength) {
    }

    /** Called when the message turn ends (stream complete). */
    default void turnEnd(MessageTurnContext ctx) {
    }
}
