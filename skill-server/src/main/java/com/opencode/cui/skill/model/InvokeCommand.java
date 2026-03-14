package com.opencode.cui.skill.model;

/**
 * Encapsulates all parameters for a Gateway invoke command.
 * Replaces 5-parameter signature (ak, userId, sessionId, action, payload)
 * across DownstreamSender, RebuildCallback, and GatewayRelayService.
 */
public record InvokeCommand(
        String ak,
        String userId,
        String sessionId,
        String action,
        String payload) {
}
