package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Agent 状态查询响应 DTO。
 * 用于 AgentController 的状态查询接口，替代 Map&lt;String, Object&gt; 提供类型安全。
 *
 * @param ak                 Agent 的应用密钥
 * @param status             Agent 在线状态（ONLINE / OFFLINE）
 * @param opencodeOnline     OpenCode 工具是否在线（可为 null 表示未知）
 * @param activeSessionCount 活跃会话数量
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStatusResponse(
                String ak,
                AgentConnection.AgentStatus status,
                Boolean opencodeOnline,
                int activeSessionCount) {
}
