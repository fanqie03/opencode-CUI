package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSession {

    public static final String DOMAIN_MINIAPP = "miniapp";
    public static final String DOMAIN_IM = "im";
    public static final String SESSION_TYPE_GROUP = "group";
    public static final String SESSION_TYPE_DIRECT = "direct";

    @JsonProperty("welinkSessionId")
    @JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    private String userId;
    private String ak;
    private String toolSessionId;
    private String title;

    @Builder.Default
    private Status status = Status.ACTIVE;

    @Builder.Default
    private String businessSessionDomain = DOMAIN_MINIAPP;
    private String businessSessionType;
    private String businessSessionId;
    private String assistantAccount;
    private LocalDateTime createdAt;
    @JsonProperty("updatedAt")
    private LocalDateTime lastActiveAt;

    public enum Status {
        ACTIVE, IDLE, CLOSED
    }

    /**
     * Touch the session to refresh last_active_at timestamp.
     */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }

    public boolean isMiniappDomain() {
        return DOMAIN_MINIAPP.equalsIgnoreCase(businessSessionDomain);
    }

    public boolean isImDomain() {
        return DOMAIN_IM.equalsIgnoreCase(businessSessionDomain);
    }

    public boolean isImGroupSession() {
        return isImDomain() && SESSION_TYPE_GROUP.equalsIgnoreCase(businessSessionType);
    }

    public boolean isImDirectSession() {
        return isImDomain() && SESSION_TYPE_DIRECT.equalsIgnoreCase(businessSessionType);
    }
}
