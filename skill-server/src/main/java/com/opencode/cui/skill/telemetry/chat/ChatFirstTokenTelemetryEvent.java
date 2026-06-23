package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.telemetry.core.TelemetryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首 token 到达事件（skill_chat_first_token）。
 * 实现 TelemetryEvent 接口，供 MessageTurnLifecycle.onFirstToken 上报到 WelinkTelemetryReporter。
 *
 * <p>维度：messageId、sessionId、userId（发送者账号）、assistantAccount（助手账号）。
 */
public record ChatFirstTokenTelemetryEvent(
        String sessionId,
        String senderUserAccount,
        String assistantAccount,
        String businessTag,
        String messageId,
        long ttftMs
) implements TelemetryEvent {

    @Override
    public String eventId() {
        return "skill_chat_first_token";
    }

    @Override
    public String eventLabel() {
        return "首token到达";
    }

    @Override
    public String userId() {
        return senderUserAccount;
    }

    @Override
    public Map<String, Object> extendData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("senderUserAccount", nullToEmpty(senderUserAccount));
        data.put("assistantAccount", nullToEmpty(assistantAccount));
        data.put("businessTag", businessTag != null ? businessTag : "UNKNOWN");
        data.put("ttftMs", ttftMs);
        data.put("ttftReportedAt", System.currentTimeMillis());
        return data;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
