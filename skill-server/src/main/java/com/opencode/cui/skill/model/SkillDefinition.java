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
public class SkillDefinition {

    private Long id;
    private String skillCode;
    private String skillName;
    private String toolType;
    private String description;
    private String iconUrl;

    @Builder.Default
    private Status status = Status.ACTIVE;

    @Builder.Default
    private Integer sortOrder = 0;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        ACTIVE, DISABLED
    }
}
