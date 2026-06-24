package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A single session entry in the unread list.
 */
@Data
@AllArgsConstructor
public class UnreadSessionItem {

    /** Session ID (welinkSessionId). */
    private String sessionId;

    /** Current maximum message seq in this session. */
    private int maxSeq;
}
