package com.opencode.cui.skill.model.event;

import com.opencode.cui.skill.model.SkillSession;

/**
 * Published after {@code handleToolError} completes, so that the unread badge
 * listener can update the per-session maxSeq count asynchronously.
 * MDC context (including traceId) is carried by {@code unreadExecutor}'s
 * {@code MdcTaskDecorator} rather than through this event.
 */
public record ToolErrorEvent(Long sessionId, String userId, SkillSession session) {
}
