package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息视图 DTO。
 * 用于 API 接口返回，包含消息基本信息及其关联的分片列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillMessageView {

    /** 数据库主键 */
    private Long id;

    /** 消息 UUID */
    private String messageId;

    /** 所属会话 ID */
    private Long sessionId;

    /** 全局序号 */
    private Integer seq;

    /** 消息内部序号 */
    private Integer messageSeq;

    /** 消息角色 */
    private SkillMessage.Role role;

    /** 消息文本内容 */
    private String content;

    /** 内容类型 */
    private SkillMessage.ContentType contentType;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 元数据（JSON 格式） */
    private String meta;

    /** 关联的消息分片列表 */
    private List<SkillMessagePart> parts;

    /**
     * 从 SkillMessage 实体和分片列表创建视图 DTO。
     * messageSeq 为空时回退使用 seq。
     *
     * @param message 消息实体
     * @param parts   关联的分片列表
     * @return 消息视图 DTO
     */
    public static SkillMessageView from(SkillMessage message, List<SkillMessagePart> parts) {
        return SkillMessageView.builder()
                .id(message.getId())
                .messageId(message.getMessageId())
                .sessionId(message.getSessionId())
                .seq(message.getSeq())
                .messageSeq(message.getMessageSeq() != null ? message.getMessageSeq() : message.getSeq())
                .role(message.getRole())
                .content(message.getContent())
                .contentType(message.getContentType())
                .createdAt(message.getCreatedAt())
                .meta(message.getMeta())
                .parts(parts)
                .build();
    }
}
