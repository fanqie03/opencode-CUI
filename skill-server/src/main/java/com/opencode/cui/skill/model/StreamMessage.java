package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill Server → 前端 WebSocket 消息 DTO。
 *
 * <p>
 * 字段按语义分为 5 个嵌套组（ToolInfo / PermissionInfo / QuestionInfo / UsageInfo /
 * FileInfo），通过 {@code @JsonUnwrapped} 保持 JSON 平铺格式不变。
 * </p>
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

    // ===== Subagent 字段 =====
    private String subagentSessionId;
    private String subagentName;

    // 云端扩展字段
    private List<String> keywords;                  // searching
    private List<SearchResultItem> searchResults;   // search_result
    private List<ReferenceItem> references;         // reference
    private List<String> askMoreQuestions;           // ask_more
    private List<SlashCommandItem> slashCommands;    // slash_commands_result

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
        /** 选项 label 列表；与 OpenCode question 工具 input.options 对齐 */
        private List<String> options;
        /** 是否多选（false=单选；true=多选）。云端缺省时按 false */
        private Boolean multiSelect;
        private List<QuestionItem> questions;
        private JsonNode extParam;
        /**
         * opencode question request id。来源：question.asked event 的 properties.id。
         *
         * <p>与 {@link StreamMessage#partId} 同源（partId 保留给跨事件配对使用）。
         * 通过 {@code @JsonUnwrapped} 平铺到 StreamMessage JSON 顶层，miniapp 侧字段名 {@code questionId}。</p>
         *
         * <p>新版 plugin 收到 question_reply payload 含 questionId 时可走快路径
         * (POST /question/{requestID}/reply)，跳过 GET /question 反查。</p>
         */
        @JsonProperty("questionId")
        private String questionId;
    }

    /** 多问题列表中的单个问题项（QuestionInfo.questions 元素） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuestionItem {
        private String header;
        private String question;
        private List<String> options;
        /** 是否多选（false=单选；true=多选）。云端缺省时按 false */
        private Boolean multiSelect;
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResultItem {
        private String index;
        private String title;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferenceItem {
        private String index;
        private String title;
        private String source;
        private String url;
        private String content;
    }

    /** Slash 命令项（slash_commands_result 消息） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlashCommandItem {
        private String command;
        private String description;
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
        public static final String MESSAGE_USER = "message.user";

        public static final String SNAPSHOT = "snapshot";
        public static final String STREAMING = "streaming";

        // 云端扩展
        public static final String PLANNING_DELTA = "planning.delta";
        public static final String PLANNING_DONE = "planning.done";
        public static final String SEARCHING = "searching";
        public static final String SEARCH_RESULT = "search_result";
        public static final String REFERENCE = "reference";
        public static final String ASK_MORE = "ask_more";
        public static final String SLASH_COMMANDS_RESULT = "slash_commands_result";

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

    /**
     * 创建 message.user 消息（多端同步用户消息）。
     */
    public static StreamMessage userMessage(String messageId, Integer messageSeq,
                                            String content, String welinkSessionId) {
        return StreamMessage.builder()
                .type(Types.MESSAGE_USER)
                .messageId(messageId)
                .messageSeq(messageSeq)
                .role("user")
                .content(content)
                .welinkSessionId(welinkSessionId)
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
