package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response body for {@code POST /api/skill/sessions/{id}/read}.
 */
@Data
@AllArgsConstructor
public class ReadReportResponse {

    /** Session ID (welinkSessionId). */
    private String welinkSessionId;

    /** Remaining unread count (always 0 after successful report). */
    private int unreadCount;
}
