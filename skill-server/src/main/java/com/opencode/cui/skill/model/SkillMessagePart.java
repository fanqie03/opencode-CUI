package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a structured part within a message.
 * Maps to the skill_message_part table.
 * <p>
 * A single assistant message may contain multiple parts:
 * text blocks, reasoning blocks, tool calls, file references, etc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMessagePart {

    private Long id;
    private Long messageId;
    private Long sessionId;
    private String partId;
    private Integer seq;
    private String partType; // text / reasoning / tool / file / step-start / step-finish

    // Content (text / reasoning)
    private String content;

    // Tool fields
    private String toolName;
    private String toolCallId;
    private String toolStatus; // pending / running / completed / error
    private String toolInput; // JSON string
    private String toolOutput;
    private String toolError;
    private String toolTitle;

    // File fields
    private String fileName;
    private String fileUrl;
    private String fileMime;

    // Step finish fields
    private Integer tokensIn;
    private Integer tokensOut;
    private Double cost;
    private String finishReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
