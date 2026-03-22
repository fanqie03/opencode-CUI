package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息分片持久化实体。
 * 对应数据库 skill_message_part 表。
 *
 * <p>
 * 一条助手消息可能包含多个分片：
 * 文本块、推理块、工具调用、文件引用等。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMessagePart {

    /** 数据库主键 */
    private Long id;

    /** 所属消息 ID */
    private Long messageId;

    /** 所属会话 ID */
    private Long sessionId;

    /** 分片唯一标识 */
    private String partId;

    /** 分片序号 */
    private Integer seq;

    /** 分片类型（text/reasoning/tool/file/step-start/step-finish） */
    private String partType;

    // ==================== 文本/推理内容 ====================

    /** 文本内容 */
    private String content;

    // ==================== 工具调用字段 ====================

    /** 工具名称 */
    private String toolName;

    /** 工具调用 ID */
    private String toolCallId;

    /** 工具执行状态（pending/running/completed/error） */
    private String toolStatus;

    /** 工具输入参数（JSON 字符串） */
    private String toolInput;

    /** 工具输出结果 */
    private String toolOutput;

    /** 工具执行错误信息 */
    private String toolError;

    /** 工具标题 */
    private String toolTitle;

    // ==================== 文件字段 ====================

    /** 文件名 */
    private String fileName;

    /** 文件 URL */
    private String fileUrl;

    /** 文件 MIME 类型 */
    private String fileMime;

    // ==================== step.done 统计字段 ====================

    /** 输入 Token 数 */
    private Integer tokensIn;

    /** 输出 Token 数 */
    private Integer tokensOut;

    /** 费用（美元） */
    private Double cost;

    /** 结束原因 */
    private String finishReason;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
