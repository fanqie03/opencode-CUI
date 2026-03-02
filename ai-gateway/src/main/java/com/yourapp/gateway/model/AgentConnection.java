package com.yourapp.gateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "agent_connection", indexes = {
        @Index(name = "idx_user", columnList = "user_id"),
        @Index(name = "idx_ak", columnList = "ak_id"),
        @Index(name = "idx_status", columnList = "status")
})
public class AgentConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ak_id", nullable = false, length = 64)
    private String akId;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "os", length = 50)
    private String os;

    @Column(name = "tool_type", nullable = false, length = 50)
    @Builder.Default
    private String toolType = "OPENCODE";

    @Column(name = "tool_version", length = 50)
    private String toolVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private AgentStatus status = AgentStatus.OFFLINE;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum AgentStatus {
        ONLINE, OFFLINE
    }
}
