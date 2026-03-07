package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillMessageView {

    private Long id;
    private Long sessionId;
    private Integer seq;
    private SkillMessage.Role role;
    private String content;
    private SkillMessage.ContentType contentType;
    private LocalDateTime createdAt;
    private String meta;
    private List<SkillMessagePart> parts;

    public static SkillMessageView from(SkillMessage message, List<SkillMessagePart> parts) {
        return SkillMessageView.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .seq(message.getSeq())
                .role(message.getRole())
                .content(message.getContent())
                .contentType(message.getContentType())
                .createdAt(message.getCreatedAt())
                .meta(message.getMeta())
                .parts(parts)
                .build();
    }
}
