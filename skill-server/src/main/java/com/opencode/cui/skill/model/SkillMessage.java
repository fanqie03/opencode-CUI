package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill 消息实体。
 * 对应数据库 skill_message 表，记录会话中的每条消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMessage {

    /** 数据库主键 */
    private Long id;

    /** 消息 UUID（用于跨系统追踪） */
    private String messageId;

    /** 所属会话 ID */
    private Long sessionId;

    /** 全局序号（用于排序） */
    private Integer seq;

    /** 消息内部序号 */
    private Integer messageSeq;

    /** 消息角色 */
    private Role role;

    /** 消息文本内容 */
    private String content;

    /** 内容类型，默认 MARKDOWN */
    @Builder.Default
    private ContentType contentType = ContentType.MARKDOWN;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 元数据（JSON 格式） */
    private String meta;

    /**
     * 消息角色枚举。
     */
    public enum Role {
        /** 用户消息 */
        USER,
        /** 助手回复 */
        ASSISTANT,
        /** 系统消息 */
        SYSTEM,
        /** 工具输出 */
        TOOL
    }

    /**
     * 内容类型枚举。
     */
    public enum ContentType {
        /** Markdown 格式 */
        MARKDOWN,
        /** 代码格式 */
        CODE,
        /** 纯文本 */
        PLAIN
    }
}
