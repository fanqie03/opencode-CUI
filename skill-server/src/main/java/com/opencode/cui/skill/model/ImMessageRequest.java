package com.opencode.cui.skill.model;

import java.util.List;

public record ImMessageRequest(
        String businessDomain,
        String sessionType,
        String sessionId,
        String assistantAccount,
        String content,
        String msgType,
        String imageUrl,
        List<ChatMessage> chatHistory) {

    public boolean isTextMessage() {
        return msgType == null || msgType.isBlank() || "text".equalsIgnoreCase(msgType);
    }

    public record ChatMessage(
            String senderAccount,
            String senderName,
            String content,
            long timestamp) {
    }
}
