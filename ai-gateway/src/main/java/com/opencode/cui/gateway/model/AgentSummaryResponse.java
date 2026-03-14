package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Agent 概要信息 DTO。
 * 替代 AgentController.toAgentSummary() 中的 Map&lt;String, Object&gt;。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSummaryResponse(
        String ak,
        AgentConnection.AgentStatus status,
        String deviceName,
        String os,
        String toolType,
        String toolVersion,
        LocalDateTime connectedAt) {

    public static AgentSummaryResponse fromAgent(AgentConnection agent) {
        return new AgentSummaryResponse(
                agent.getAkId(),
                agent.getStatus(),
                agent.getDeviceName(),
                agent.getOs(),
                agent.getToolType() != null ? agent.getToolType().toLowerCase() : null,
                agent.getToolVersion(),
                agent.getCreatedAt());
    }
}
