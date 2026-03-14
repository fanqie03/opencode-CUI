package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Agent 状态查询响应 DTO。
 * 替代 AgentController 中的 Map&lt;String, Object&gt;，提供类型安全。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStatusResponse(
        String ak,
        AgentConnection.AgentStatus status,
        Boolean opencodeOnline,
        int activeSessionCount) {
}
