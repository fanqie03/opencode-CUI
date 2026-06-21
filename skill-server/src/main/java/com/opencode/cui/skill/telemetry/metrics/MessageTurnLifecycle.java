package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
    /** Track which messageIds have already reported their first token (bounded, TTL-evicted). */
    private final Cache<String, Boolean> processedFirstToken;

     public MessageTurnLifecycle(ChatStreamMetricsService streamMetrics,
                                 WelinkTelemetryReporter welinkReporter,
                                 @Value("${telemetry.welink.enabled:false}") boolean welinkEnabled,
                                 @Value("${telemetry.chatstream.max-sessions:10000}") long maxSessions,
                                 @Value("${telemetry.chatstream.session-ttl-minutes:30}") Duration sessionTtl) {
        this.streamMetrics = streamMetrics;
        this.welinkReporter = welinkReporter;
        this.welinkEnabled = welinkEnabled;
        this.processedFirstToken = Caffeine.newBuilder()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
    }

    public void onTurnStart(String messageId, String brainTag, String sessionId,
                            String senderUserAccount, String businessTag) {
        streamMetrics.onStreamStart(messageId, brainTag);
    }

    private void onFirstToken(String messageId, String brainTag, String sessionId,
                              String assistantAccount) {
        streamMetrics.onFirstToken(messageId, brainTag);
        if (welinkEnabled) {
            welinkReporter.report(new ChatFirstTokenTelemetryEvent(
                sessionId, assistantAccount, brainTag, messageId));
        }
    }

    public void onToken(String messageId, String brainTag, String sessionId, String assistantAccount) {
        if (processedFirstToken.getIfPresent(messageId) == null) {
            onFirstToken(messageId, brainTag, sessionId, assistantAccount);
            processedFirstToken.put(messageId, Boolean.TRUE);
        }
        streamMetrics.onToken(messageId, brainTag);
    }

    public void onTurnEnd(String messageId, String brainTag, String sessionId,
                          String assistantAccount) {
        streamMetrics.onStreamEnd(messageId, brainTag);
        processedFirstToken.invalidate(messageId);
    }
}
