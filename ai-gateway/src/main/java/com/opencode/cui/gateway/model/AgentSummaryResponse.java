package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Agent 概要信息 DTO。
 * 用于列表接口返回 Agent 的核心信息，替代 Map&lt;String, Object&gt; 提供类型安全。
 *
 * @param ak          Agent 的应用密钥
 * @param status      Agent 在线状态
 * @param deviceName  设备名称
 * @param os          操作系统信息
 * @param toolType    工具类型（如 channel）
 * @param toolVersion 工具版本号
 * @param connectedAt 连接时间
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

    /**
     * 从 AgentConnection 实体创建概要响应。
     * toolType 统一转为小写，connectedAt 映射自 createdAt。
     *
     * @param agent Agent 连接实体
     * @return 概要响应 DTO
     */
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
