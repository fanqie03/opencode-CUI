package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill 会话实体。
 * 对应数据库 skill_session 表，记录用户与 Agent 之间的会话状态。
 *
 * <p>
 * 会话分为两种域：
 * <ul>
 * <li>{@code miniapp} — MiniApp 前端发起的会话</li>
 * <li>{@code im} — IM 消息触发的会话（支持群聊和单聊）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSession {

    /** 业务域常量：MiniApp */
    public static final String DOMAIN_MINIAPP = "miniapp";

    /** 业务域常量：IM */
    public static final String DOMAIN_IM = "im";

    /** 会话类型常量：群聊 */
    public static final String SESSION_TYPE_GROUP = "group";

    /** 会话类型常量：单聊 */
    public static final String SESSION_TYPE_DIRECT = "direct";

    /** 会话 ID（Skill 侧主键，JSON 序列化为 welinkSessionId，使用 String 防止 JS 精度丢失） */
    @JsonProperty("welinkSessionId")
    @JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;

    /** 用户 ID */
    private String userId;

    /** Agent 应用密钥（AK） */
    private String ak;

    /** OpenCode 侧会话 ID */
    private String toolSessionId;

    /** 会话标题（由 AI 自动生成） */
    private String title;

    /** 会话状态，默认 ACTIVE */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 业务会话域，默认 miniapp */
    @Builder.Default
    private String businessSessionDomain = DOMAIN_MINIAPP;

    /** 业务会话类型（group=群聊, direct=单聊；仅 IM 域使用） */
    private String businessSessionType;

    /** 业务侧会话 ID（IM 群 ID 或单聊 ID） */
    private String businessSessionId;

    /** 助手账号（仅 IM 域使用） */
    private String assistantAccount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后活跃时间（JSON 序列化为 updatedAt） */
    @JsonProperty("updatedAt")
    private LocalDateTime lastActiveAt;

    /**
     * 会话状态枚举。
     */
    public enum Status {
        /** 活跃中 */
        ACTIVE,
        /** 空闲（无活跃消息） */
        IDLE,
        /** 已关闭 */
        CLOSED
    }

    /**
     * 刷新会话的最后活跃时间为当前时间。
     */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }

    /** 判断是否为 MiniApp 域会话 */
    public boolean isMiniappDomain() {
        return DOMAIN_MINIAPP.equalsIgnoreCase(businessSessionDomain);
    }

    /** 判断是否为 IM 域会话 */
    public boolean isImDomain() {
        return DOMAIN_IM.equalsIgnoreCase(businessSessionDomain);
    }

    /** 判断是否为 IM 群聊会话 */
    public boolean isImGroupSession() {
        return isImDomain() && SESSION_TYPE_GROUP.equalsIgnoreCase(businessSessionType);
    }

    /** 判断是否为 IM 单聊会话 */
    public boolean isImDirectSession() {
        return isImDomain() && SESSION_TYPE_DIRECT.equalsIgnoreCase(businessSessionType);
    }
}
