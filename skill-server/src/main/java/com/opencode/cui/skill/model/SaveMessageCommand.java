package com.opencode.cui.skill.model;

/**
 * 封装 saveMessage 的所有参数，减少方法签名复杂度。
 */
public record SaveMessageCommand(
        Long sessionId,
        String messageId,
        SkillMessage.Role role,
        String content,
        SkillMessage.ContentType contentType,
        String meta) {
    /**
     * 简化构造：无 messageId 时自动生成。
     */
    public SaveMessageCommand(Long sessionId, SkillMessage.Role role, String content,
            SkillMessage.ContentType contentType, String meta) {
        this(sessionId, null, role, content, contentType, meta);
    }
}
