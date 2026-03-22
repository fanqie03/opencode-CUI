package com.opencode.cui.skill.model;

/**
 * Gateway Invoke 指令参数封装。
 * 将原来 5 个分散参数（ak, userId, sessionId, action, payload）封装为不可变对象，
 * 在 GatewayRelayService、DownstreamSender、RebuildCallback 之间统一传递。
 *
 * @param ak        Agent 应用密钥
 * @param userId    用户 ID
 * @param sessionId Skill 侧会话 ID
 * @param action    调用动作（chat、create_session、close_session 等）
 * @param payload   JSON 格式的载荷数据
 */
public record InvokeCommand(
                String ak,
                String userId,
                String sessionId,
                String action,
                String payload) {
}
