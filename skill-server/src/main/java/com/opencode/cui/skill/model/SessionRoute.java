package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话级路由记录。
 * 映射到 session_route 表，记录每个会话应该路由到哪个 Source 实例。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionRoute {
    private Long id;
    private String ak;
    private Long welinkSessionId;
    private String toolSessionId;
    private String sourceType;
    private String sourceInstance;
    private String userId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
