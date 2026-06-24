package com.opencode.cui.skill.model.event;

/**
 * Published after the frontend reports a read position for a session.
 * The listener pushes a {@code session.read} sync to other devices.
 */
public record ReadReportedEvent(Long sessionId, int readSeq, String userId, String assistantAccount) {
}
