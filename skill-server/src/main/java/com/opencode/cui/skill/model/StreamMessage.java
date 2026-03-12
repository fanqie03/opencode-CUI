package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill Server -> frontend WebSocket message DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamMessage {

    private String type;
    private Long seq;

    @JsonIgnore
    private String sessionId;
    private String welinkSessionId;
    private String emittedAt;
    private Object raw;

    private String messageId;
    private Integer messageSeq;
    private String role;
    private String sourceMessageId;

    private String partId;
    private Integer partSeq;
    private String content;

    private String toolName;
    private String toolCallId;
    private String status;
    private Object input;
    private String output;
    private String title;

    private String header;
    private String question;
    private List<String> options;

    private String permissionId;
    private String permType;
    private Object metadata;
    private String response;

    private Map<String, Object> tokens;
    private Double cost;
    private String reason;

    private String error;
    private String sessionStatus;

    private String fileName;
    private String fileUrl;
    private String fileMime;

    private List<Object> messages;
    private List<Object> parts;

    public static final class Types {
        public static final String TEXT_DELTA = "text.delta";
        public static final String TEXT_DONE = "text.done";
        public static final String THINKING_DELTA = "thinking.delta";
        public static final String THINKING_DONE = "thinking.done";
        public static final String TOOL_UPDATE = "tool.update";
        public static final String QUESTION = "question";
        public static final String FILE = "file";

        public static final String STEP_START = "step.start";
        public static final String STEP_DONE = "step.done";
        public static final String SESSION_STATUS = "session.status";
        public static final String SESSION_TITLE = "session.title";
        public static final String SESSION_ERROR = "session.error";

        public static final String PERMISSION_ASK = "permission.ask";
        public static final String PERMISSION_REPLY = "permission.reply";

        public static final String AGENT_ONLINE = "agent.online";
        public static final String AGENT_OFFLINE = "agent.offline";
        public static final String ERROR = "error";

        public static final String SNAPSHOT = "snapshot";
        public static final String STREAMING = "streaming";

        private Types() {
        }
    }

    @JsonProperty("welinkSessionId")
    public String getWelinkSessionId() {
        if (welinkSessionId != null) {
            return welinkSessionId;
        }
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : null;
    }
}
