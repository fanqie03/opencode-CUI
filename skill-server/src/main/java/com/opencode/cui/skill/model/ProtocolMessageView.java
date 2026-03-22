package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 协议层消息视图 DTO。
 * 前端展示用的消息结构，包含消息基本信息和其下的分片列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProtocolMessageView {

    /** 消息 ID */
    private String id;

    /** Skill 侧会话 ID */
    private String welinkSessionId;

    /** 全局序号 */
    private Integer seq;

    /** 消息内部序号 */
    private Integer messageSeq;

    /** 消息角色（user/assistant/system/tool） */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 内容类型（markdown/code/plain） */
    private String contentType;

    /** 创建时间 */
    private String createdAt;

    /** 元数据（JSON 格式） */
    private Object meta;

    /** 消息分片列表 */
    private List<ProtocolMessagePart> parts;
}
