package com.opencode.cui.gateway.model;

/**
 * Invoke 操作结果 DTO。
 * 替代 AgentController invoke 端点中的 Map&lt;String, Object&gt;。
 */
public record InvokeResult(
        boolean success,
        String message) {
}
