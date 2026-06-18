package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import org.springframework.stereotype.Component;

/**
 * 消息轮次生命周期编排器。
 * 委托给 ChatStreamMetricsService（Prometheus）+ WelinkTelemetryReporter（TTFT 双上报）。
 * 不接管现有 ChatRequestTelemetryEvent / ChatReplyTelemetryEvent，避免双重上报。
 */
@Component
public class MessageTurnLifecycle {

    private final ChatStreamMetricsService streamMetrics;
    private final WelinkTelemetryReporter welinkReporter;
    private final boolean welinkEnabled;

    public MessageTurnLifecycle(ChatStreamMetricsService streamMetrics,
                                WelinkTelemetryReporter welinkReporter,
                                boolean welinkEnabled) {
        this.streamMetrics = streamMetrics;
        this.welinkReporter = welinkReporter;
        this.welinkEnabled = welinkEnabled;
    }

    public void onTurnStart(String messageId, String brainTag, String sessionId,
                            String senderUserAccount, String businessTag) {
        streamMetrics.onStreamStart(messageId, brainTag);
    }

    public void onFirstToken(String messageId, String brainTag, String sessionId,
                             String assistantAccount) {
        streamMetrics.onFirstToken(messageId, brainTag);
        if (welinkEnabled) {
            welinkReporter.report(new ChatFirstTokenTelemetryEvent(
                sessionId, assistantAccount, brainTag, messageId));
        }
    }

    public void onToken(String messageId, String brainTag) {
        streamMetrics.onToken(messageId, brainTag);
    }

    public void onTurnEnd(String messageId, String brainTag, String sessionId,
                          String assistantAccount) {
        streamMetrics.onStreamEnd(messageId, brainTag);
    }
}