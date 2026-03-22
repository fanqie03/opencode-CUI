package com.opencode.cui.gateway.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Agent 连接实体。
 * 对应数据库 agent_connection 表，记录 Agent 客户端的注册信息和在线状态。
 *
 * <p>
 * 每个 Agent 通过 WebSocket 连接到 Gateway 时，会创建或更新一条记录。
 * ak 作为 Agent 的唯一标识，macAddress 用于设备绑定校验。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentConnection {

    /** 数据库主键 */
    private Long id;

    /** 关联用户 ID（通过身份服务解析得到） */
    private String userId;

    /** Agent 的应用密钥（AK），用于路由和鉴权 */
    private String akId;

    /** 设备名称（Agent 注册时上报） */
    private String deviceName;

    /** MAC 地址（用于设备绑定校验） */
    private String macAddress;

    /** 操作系统信息（如 Windows、macOS、Linux） */
    private String os;

    /** 工具类型，默认 "channel" */
    @Builder.Default
    private String toolType = "channel";

    /** 工具版本号 */
    private String toolVersion;

    /** Agent 在线状态，默认 OFFLINE */
    @Builder.Default
    private AgentStatus status = AgentStatus.OFFLINE;

    /** 最后活跃时间（心跳更新） */
    private LocalDateTime lastSeenAt;

    /** 记录创建时间 */
    private LocalDateTime createdAt;

    /**
     * Agent 在线状态枚举。
     */
    public enum AgentStatus {
        /** 在线 */
        ONLINE,
        /** 离线 */
        OFFLINE
    }
}
