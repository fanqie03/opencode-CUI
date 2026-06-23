package com.opencode.cui.skill.model.event;

import com.opencode.cui.skill.model.SkillSession;

import java.time.Instant;

/**
 * 会话删除事件。
 * 主流程删除会话后发布，供监听器异步处理 WS 推送、Gateway 通知和异步任务创建。
 */
public record SessionDeletedEvent(
        SkillSession session,
        String deletedBy,
        Instant deletedAt,
        int messageCount) {
}
