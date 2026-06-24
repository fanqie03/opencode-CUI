package com.opencode.cui.skill.model;

import lombok.Data;

/**
 * Request body for {@code POST /api/skill/sessions/{id}/read}.
 */
@Data
public class ReadReportRequest {

    /** The maximum message seq that the frontend has rendered. Must be positive. */
    private int readSeq;
}
