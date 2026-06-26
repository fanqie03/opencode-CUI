package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.telemetry.core.TelemetryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话轮次结束事件。
 * 在 {@code ChatStreamMetricsService.turnEnd()} 中上报到 WelinkTelemetryReporter。
 *
 * <p>
 * 携带三个核心指标：
 * contentLength — 内容长度（本轮输出 token 总数）
 * durationMs — 对话时长（从 turnStart 到 turnEnd 的总耗时）
 * success — 本轮对话是否成功
 * </p>
 *
 * <p>
 * 上报维度：messageId、userId（senderUserAccount）、assistantId（assistantAccount）、sessionId。
 * {@link #userId()} 返回 {@code senderUserAccount}，助手账号作为独立维度放在 {@link #extendData()} 中。
 * </p>
 */
public record ChatTurnEndTelemetryEvent(
        String eventId,
        String sessionId,
        String senderUserAccount,
        String assistantAccount,
        String businessTag,
        String robotId,
        String messageId,
        int contentLength,
        long durationMs,
        boolean success
) implements TelemetryEvent {

    @Override
    public String eventId() {
        return eventId;
    }

    @Override
    public String eventLabel() {
        return "对话轮次结束";
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
        data.put("robotId", nullToEmpty(robotId));
        data.put("contentLength", contentLength);
        data.put("durationMs", durationMs);
        data.put("success", success);
        data.put("turnEndReportedAt", System.currentTimeMillis());
        return data;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
