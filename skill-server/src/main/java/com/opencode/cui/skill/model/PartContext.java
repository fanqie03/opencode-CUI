package com.opencode.cui.skill.model;

/**
 * 消息分片上下文。
 * 封装 OpenCodeEventTranslator 中 translateXxx 方法间传递的公共上下文字段，
 * 减少方法签名参数数量。
 *
 * @param sessionId Skill 侧会话 ID
 * @param messageId 消息 ID
 * @param partId    分片 ID
 * @param partSeq   分片序号
 * @param role      消息角色（user/assistant/tool）
 */
public record PartContext(
                String sessionId,
                String messageId,
                String partId,
                Integer partSeq,
                String role) {
}
