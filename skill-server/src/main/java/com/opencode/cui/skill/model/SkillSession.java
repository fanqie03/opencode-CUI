package com.opencode.cui.skill.model;

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

    private Long id;
    private Long userId;
    private Long skillDefinitionId;
    private Long agentId;
    private String toolSessionId;
    private String title;

    @Builder.Default
    private Status status = Status.ACTIVE;

    private String imChatId;
    private LocalDateTime createdAt;
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
