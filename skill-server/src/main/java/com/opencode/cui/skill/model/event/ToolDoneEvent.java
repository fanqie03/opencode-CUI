package com.opencode.cui.skill.model.event;

import com.opencode.cui.skill.model.SkillSession;

/**
 * Published after {@code tool_done} is fully processed in {@code GatewayMessageRouter}.
 * The listener uses this to update the per-session unread cache asynchronously.
 * MDC context (including traceId) is carried by {@code unreadExecutor}'s
 * {@code MdcTaskDecorator} rather than through this event.
 */
public record ToolDoneEvent(Long sessionId, String userId, SkillSession session) {
}
