package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.model.SkillSession;

/**
 * 消息轮次上下文 — 包装一次问答轮次中不变的标识信息。
 *
 * <p>
 * 在 {@link MessageTurnLifecycle} 和 {@link ChatStreamMetricsService} 的生命周期方法间传递。
 * 业务维度字段（brainTag、assistantAccount、robotId 等）从 {@code session} 中获取，
 * 参考 {@code ChatTelemetryEventListener} 的做法，避免参数冗余。
 * </p>
 *
 * @param session   会话对象，提供 userId、assistantAccount、businessSessionDomain 等维度
 * @param messageId 消息 ID，统计维度 key
 * @param success   本轮问答是否成功（handleToolDone=true，handleToolError=false）
 */
public record MessageTurnContext(
        SkillSession session,
        String messageId,
        boolean success
) {
    /**
     * 便捷工厂方法：用 messageId 构建最小上下文。
     */
    public static MessageTurnContext of(String messageId) {
        return new MessageTurnContext(null, messageId, true);
    }
}
