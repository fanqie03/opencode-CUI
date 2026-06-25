package com.opencode.cui.skill.telemetry.metrics;

/**
 * 消息轮次上下文 — 包装一次问答轮次中不变的标识信息。
 *
 * <p>
 * 在 {@link MessageTurnLifecycle} 和 {@link ChatStreamMetricsService} 的生命周期方法间传递，
 * 避免方法签名参数过多。{@code contentLength} 不在此上下文中，因为它每次 token 调用都不同。
 * </p>
 *
 * @param messageId         消息 ID（messageId），统计维度 key
 * @param brainTag          大脑标签（AssistantInfo.businessTag），不存在则 null（下游兜底 UNKNOWN）
 * @param sessionId         会话 ID
 * @param assistantAccount  助手账号
 * @param senderUserAccount 发送者用户账号
 * @param businessTag       业务标签（与 brainTag 同源，Welink 上报用）
 * @param robotId           机器人 ID（AssistantInfo.id），不存在则 null
 * @param success           本轮问答是否成功（handleToolDone=true，handleToolError=false）
 */
public record MessageTurnContext(
        String messageId,
        String brainTag,
        String sessionId,
        String assistantAccount,
        String senderUserAccount,
        String businessTag,
        String robotId,
        boolean success
) {
    /**
     * 便捷工厂方法：用 messageId + brainTag 构建最小上下文（ChatStreamMetricsService 只需要这两个字段）。
     */
    public static MessageTurnContext of(String messageId, String brainTag) {
        return new MessageTurnContext(messageId, brainTag, null, null, null, null, null, true);
    }
}
