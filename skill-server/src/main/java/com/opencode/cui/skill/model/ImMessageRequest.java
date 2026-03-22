package com.opencode.cui.skill.model;

import java.util.List;

/**
 * IM 入站消息请求体。
 * 由 IM 平台通过 REST API 推送，包含消息内容和会话上下文信息。
 *
 * @param businessDomain   业务域（如 im）
 * @param sessionType      会话类型（group=群聊, direct=单聊）
 * @param sessionId        IM 侧会话 ID
 * @param assistantAccount 助手账号（用于反查绑定的 AK）
 * @param content          消息文本内容
 * @param msgType          消息类型（text=文本, image=图片；默认 text）
 * @param imageUrl         图片 URL（仅 msgType=image 时有值）
 * @param chatHistory      聊天上下文历史（群聊场景使用）
 */
public record ImMessageRequest(
        String businessDomain,
        String sessionType,
        String sessionId,
        String assistantAccount,
        String content,
        String msgType,
        String imageUrl,
        List<ChatMessage> chatHistory) {

    /**
     * 判断是否为文本消息。
     * msgType 为 null、空白、或 "text" 时均视为文本消息。
     *
     * @return true=文本消息
     */
    public boolean isTextMessage() {
        return msgType == null || msgType.isBlank() || "text".equalsIgnoreCase(msgType);
    }

    /**
     * 聊天上下文中的单条消息。
     *
     * @param senderAccount 发送者账号
     * @param senderName    发送者显示名
     * @param content       消息内容
     * @param timestamp     消息时间戳（毫秒）
     */
    public record ChatMessage(
            String senderAccount,
            String senderName,
            String content,
            long timestamp) {
    }
}
