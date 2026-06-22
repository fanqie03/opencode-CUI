package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.telemetry.core.TelemetryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首 token 到达事件（skill_chat_first_token）。
 * 实现 TelemetryEvent 接口，供 MessageTurnLifecycle.onFirstToken 上报到 WelinkTelemetryReporter。
 */
public record ChatFirstTokenTelemetryEvent(
        String sessionId,
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
        return assistantAccount;
    }

    @Override
    public Map<String, Object> extendData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("businessTag", businessTag != null ? businessTag : "UNKNOWN");
        data.put("messageId", messageId);
        data.put("ttftMs", ttftMs);
        data.put("ttftReportedAt", System.currentTimeMillis());
        return data;
    }
}