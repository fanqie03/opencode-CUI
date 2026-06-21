package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
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
    private final MeterRegistry meterRegistry;
    /** Track which messageIds have already reported their first token (bounded, TTL-evicted). */
    private final Cache<String, Boolean> processedFirstToken;

     public MessageTurnLifecycle(ChatStreamMetricsService streamMetrics,
                                 WelinkTelemetryReporter welinkReporter,
                                 MeterRegistry meterRegistry,
                                 @Value("${telemetry.welink.enabled:false}") boolean welinkEnabled,
                                 @Value("${telemetry.chatstream.max-sessions:10000}") long maxSessions,
                                 @Value("${telemetry.chatstream.session-ttl-minutes:30}") Duration sessionTtl) {
        this.streamMetrics = streamMetrics;
        this.welinkReporter = welinkReporter;
        this.welinkEnabled = welinkEnabled;
        this.meterRegistry = meterRegistry;
        this.processedFirstToken = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        CaffeineCacheMetrics.monitor(meterRegistry, processedFirstToken, "processedFirstToken");
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

    public void onToken(String messageId, String brainTag, String sessionId, String assistantAccount, int contentLength) {
        Boolean wasFirst = processedFirstToken.asMap().putIfAbsent(messageId, Boolean.TRUE);
        if (wasFirst == null) {
            // This thread won the race — it's the first token
            onFirstToken(messageId, brainTag, sessionId, assistantAccount);
        }
        streamMetrics.onToken(messageId, brainTag, contentLength);
    }

    public void onTurnEnd(String messageId, String brainTag, String sessionId,
                          String assistantAccount) {
        streamMetrics.onStreamEnd(messageId, brainTag);
        processedFirstToken.invalidate(messageId);
    }
}
