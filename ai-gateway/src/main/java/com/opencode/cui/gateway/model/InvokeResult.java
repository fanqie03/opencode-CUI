package com.opencode.cui.gateway.model;

/**
 * Invoke 操作结果 DTO。
 * 用于 AgentController invoke 端点的返回值，替代 Map&lt;String, Object&gt; 提供类型安全。
 *
 * @param success 操作是否成功
 * @param message 结果描述信息（成功或失败原因）
 */
public record InvokeResult(
                boolean success,
                String message) {
}
