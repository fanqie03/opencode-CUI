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
public class SkillMessage {

    private Long id;
    private Long sessionId;
    private Integer seq;
    private Role role;
    private String content;

    @Builder.Default
    private ContentType contentType = ContentType.MARKDOWN;

    private LocalDateTime createdAt;
    private String meta;

    public enum Role {
        USER, ASSISTANT, SYSTEM, TOOL
    }

    public enum ContentType {
        MARKDOWN, CODE, PLAIN
    }
}
