package com.opencode.cui.skill.model;

import lombok.Data;

import java.util.List;

/**
 * Request body for {@code POST /api/skill/sessions/unread}.
 */
@Data
public class UnreadRequest {

    /** Assistant account, used to construct the Redis key. Required. */
    private String assistantAccount;

    /** Optional list of session IDs. If omitted, all unread sessions are returned. */
    private List<String> sessionIds;
}
