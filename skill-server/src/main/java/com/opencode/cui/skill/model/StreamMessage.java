package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill Server → Frontend WebSocket message DTO.
 * <p>
 * Semantic, flat-structured message translated from OpenCode events.
 * Frontend consumes this directly without needing to understand OpenCode
 * internals.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamMessage {

    // ─── Required ───
    private String type; // StreamMessageType value
    private Long seq; // Sequence number for ordering

    // ─── Content fields (text/thinking) ───
    private String partId; // Part unique ID for incremental updates
    private String content; // Text content (delta or full)

    // ─── Tool fields ───
    private String toolName; // bash / edit / question / ...
    private String toolCallId; // OpenCode callID
    private String status; // pending / running / completed / error
    private Object input; // Tool input parameters
    private String output; // Tool output result
    private String title; // Tool / permission title

    // ─── Question fields ───
    private String header; // Question header
    private String question; // Question text
    private List<String> options; // Option list

    // ─── Permission fields ───
    private String permissionId;
    private String permType;
    private Object metadata;

    // ─── Stats fields (step.done) ───
    private Map<String, Object> tokens;
    private Double cost;
    private String reason; // Finish reason

    // ─── Error ───
    private String error;

    // ─── Session fields ───
    private String sessionStatus; // busy / idle

    /**
     * All supported StreamMessage types.
     */
    public static final class Types {
        // Content
        public static final String TEXT_DELTA = "text.delta";
        public static final String TEXT_DONE = "text.done";
        public static final String THINKING_DELTA = "thinking.delta";
        public static final String THINKING_DONE = "thinking.done";
        public static final String TOOL_UPDATE = "tool.update";
        public static final String QUESTION = "question";
        public static final String FILE = "file";

        // Status
        public static final String STEP_START = "step.start";
        public static final String STEP_DONE = "step.done";
        public static final String SESSION_STATUS = "session.status";
        public static final String SESSION_TITLE = "session.title";
        public static final String SESSION_ERROR = "session.error";

        // Interaction
        public static final String PERMISSION_ASK = "permission.ask";
        public static final String PERMISSION_REPLY = "permission.reply";

        // System
        public static final String AGENT_ONLINE = "agent.online";
        public static final String AGENT_OFFLINE = "agent.offline";
        public static final String ERROR = "error";

        // Special (for resume)
        public static final String SNAPSHOT = "snapshot";
        public static final String STREAMING = "streaming";

        private Types() {
        }
    }
}
