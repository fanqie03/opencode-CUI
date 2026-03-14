package com.opencode.cui.skill.service;

/**
 * Gateway 协议动作常量。
 * 集中管理所有 Skill Server → AI-Gateway 的调用动作字符串，
 * 避免在 Controller / Service 中散布魔法字符串。
 */
public final class GatewayActions {

    private GatewayActions() {
    }

    public static final String CREATE_SESSION = "create_session";
    public static final String CHAT = "chat";
    public static final String CLOSE_SESSION = "close_session";
    public static final String ABORT_SESSION = "abort_session";
    public static final String QUESTION_REPLY = "question_reply";
    public static final String PERMISSION_REPLY = "permission_reply";
}
