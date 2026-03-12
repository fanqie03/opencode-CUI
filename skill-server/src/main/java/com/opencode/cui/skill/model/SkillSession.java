package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSession {

    @JsonProperty("welinkSessionId")
    @JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    private String userId;
    private String ak;
    private String toolSessionId;
    private String title;

    @Builder.Default
    private Status status = Status.ACTIVE;

    private String imGroupId;
    private LocalDateTime createdAt;
    @JsonProperty("updatedAt")
    private LocalDateTime lastActiveAt;

    public enum Status {
        ACTIVE, IDLE, CLOSED
    }

    /**
     * Touch the session to refresh last_active_at timestamp.
     */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }
}
