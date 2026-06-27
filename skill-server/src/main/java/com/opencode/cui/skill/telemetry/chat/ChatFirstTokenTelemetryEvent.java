package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.telemetry.core.TelemetryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首 token 到达事件。
 * 实现 TelemetryEvent 接口，供 MessageTurnLifecycle.onFirstToken 上报到 WelinkTelemetryReporter。
 *
 * <p>
 * 扩展字段参考 ChatTelemetryEventListener.buildExtendData，包含 businessSessionDomain、
 * businessSessionType、businessSessionId、senderUserAccount、assistantAccount、businessTag、robotId。
 * </p>
 */
public record ChatFirstTokenTelemetryEvent(
        String eventId,
        String sessionId,
        String senderUserAccount,
        String assistantAccount,
        String businessTag,
        String robotId,
        String businessSessionDomain,
        String businessSessionType,
        String businessSessionId,
        String messageId,
        long ttftMs
) implements TelemetryEvent {

    @Override
    public String eventId() {
        return eventId;
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
        data.put("businessSessionDomain", nullToEmpty(businessSessionDomain));
        data.put("businessSessionType", nullToEmpty(businessSessionType));
        data.put("businessSessionId", nullToEmpty(businessSessionId));
        data.put("senderUserAccount", nullToEmpty(senderUserAccount));
        data.put("assistantAccount", nullToEmpty(assistantAccount));
        data.put("businessTag", businessTag != null ? businessTag : "UNKNOWN");
        data.put("robotId", nullToEmpty(robotId));
        data.put("ttftMs", ttftMs);
        data.put("ttftReportedAt", System.currentTimeMillis());
        return data;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
