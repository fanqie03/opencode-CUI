package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response body for {@code POST /api/skill/sessions/unread}.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UnreadResponse {

    /** Number of sessions with unread messages. */
    private int unreadSessionCount;

    /** List of unread session details. */
    private List<UnreadSessionItem> unreadSessionList;
}
