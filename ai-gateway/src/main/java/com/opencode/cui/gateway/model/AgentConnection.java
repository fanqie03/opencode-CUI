package com.opencode.cui.gateway.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentConnection {

    private Long id;

    private Long userId;

    private String akId;

    private String deviceName;

    private String macAddress;

    private String os;

    @Builder.Default
    private String toolType = "channel";

    private String toolVersion;

    @Builder.Default
    private AgentStatus status = AgentStatus.OFFLINE;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createdAt;

    public enum AgentStatus {
        ONLINE, OFFLINE
    }
}
