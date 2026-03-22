package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 摘要信息 DTO，用于 API-10 在线 Agent 列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentSummary {

    /** Agent 应用密钥（AK） */
    private String ak;
    /** 状态，如 ONLINE */
    private String status;
    /** 设备名 */
    private String deviceName;
    /** 操作系统 */
    private String os;
    /** 工具类型（小写，如 opencode） */
    private String toolType;
    /** 工具版本 */
    private String toolVersion;
    /** 连接时间 */
    private String connectedAt;
}
