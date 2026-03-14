package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill Server -> frontend WebSocket message DTO.
 * <p>
 * 字段按语义分为 5 个嵌套组（ToolInfo / PermissionInfo / QuestionInfo / UsageInfo /
 * FileInfo），
 * 通过 {@code @JsonUnwrapped} 保持 JSON 平铺格式不变。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamMessage {

    // ==================== 公共字段 ====================

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

    /** 跨消息类型共享的状态字段（tool/question/permission 均使用） */
    private String status;

    /** 跨消息类型共享的标题字段（tool/session.title/permission 均使用） */
    private String title;

    private String error;
    private String sessionStatus;

    private List<Object> messages;
    private List<Object> parts;

    // ==================== 嵌套分组 ====================

    @JsonUnwrapped
    private ToolInfo tool;

    @JsonUnwrapped
    private PermissionInfo permission;

    @JsonUnwrapped
    private QuestionInfo questionInfo;

    @JsonUnwrapped
    private UsageInfo usage;

    @JsonUnwrapped
    private FileInfo file;

    // ==================== 嵌套类定义 ====================

    /** 工具调用相关字段 (tool.update 消息) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolInfo {
        private String toolName;
        private String toolCallId;
        private Object input;
        private String output;
    }

    /** 权限请求/应答相关字段 (permission.ask / permission.reply 消息) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PermissionInfo {
        private String permissionId;
        private String permType;
        private Object metadata;
        private String response;
    }

    /** 交互式问答相关字段 (question 消息) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuestionInfo {
        private String header;
        private String question;
        private List<String> options;
    }

    /** 用量统计相关字段 (step.done 消息) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UsageInfo {
        private Map<String, Object> tokens;
        private Double cost;
        private String reason;
    }

    /** 文件相关字段 (file 消息) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileInfo {
        private String fileName;
        private String fileUrl;
        private String fileMime;
    }

    // ==================== 类型常量 ====================

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

    // ==================== 静态工厂方法 ====================

    /**
     * 创建 session.status 消息。
     */
    public static StreamMessage sessionStatus(String status) {
        return StreamMessage.builder()
                .type(Types.SESSION_STATUS)
                .sessionStatus(status)
                .build();
    }

    /**
     * 创建 error 消息。
     */
    public static StreamMessage error(String errorMessage) {
        return StreamMessage.builder()
                .type(Types.ERROR)
                .error(errorMessage)
                .build();
    }

    /**
     * 创建 agent.online 消息。
     */
    public static StreamMessage agentOnline() {
        return StreamMessage.builder()
                .type(Types.AGENT_ONLINE)
                .build();
    }

    /**
     * 创建 agent.offline 消息。
     */
    public static StreamMessage agentOffline() {
        return StreamMessage.builder()
                .type(Types.AGENT_OFFLINE)
                .build();
    }

    @JsonProperty("welinkSessionId")
    public String getWelinkSessionId() {
        if (welinkSessionId != null) {
            return welinkSessionId;
        }
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : null;
    }
}
