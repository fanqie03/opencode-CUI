package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式对话效率指标服务。
 * 按 messageId 维护每轮问答状态（Caffeine Cache），记录 TTFT / Latency / TPS。
 * messageId 为 null 时直接 return。brainTag 不存在则用 UNKNOWN 兜底。
 */
@Slf4j
@Service
@Order(10)
public class ChatStreamMetricsService implements MessageTurnHandler {

    private final MeterRegistry meterRegistry;
    private final WelinkTelemetryReporter welinkReporter;
    private final Cache<String, Long> sessionStartTimes;
    private final Cache<String, Long> firstTokenTimestamps;
    private final Cache<String, AtomicInteger> tokenCounts;

    public ChatStreamMetricsService(MeterRegistry meterRegistry,
                                   ObjectProvider<WelinkTelemetryReporter> welinkReporterProvider,
                                   @Value("${telemetry.chatstream.max-sessions:10000}") long maxSessions,
                                   @Value("${telemetry.chatstream.session-ttl-minutes:60}") Duration sessionTtl) {
        this.meterRegistry = meterRegistry;
        this.welinkReporter = welinkReporterProvider.getIfAvailable();
        this.sessionStartTimes = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        CaffeineCacheMetrics.monitor(meterRegistry, sessionStartTimes, "sessionStartTimes");
        this.firstTokenTimestamps = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        CaffeineCacheMetrics.monitor(meterRegistry, firstTokenTimestamps, "firstTokenTimestamps");
        this.tokenCounts = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        CaffeineCacheMetrics.monitor(meterRegistry, tokenCounts, "tokenCounts");
    }

    @Override
    public void turnStart(MessageTurnContext ctx) {
        String messageId = ctx.messageId();
        if (messageId == null) {
            log.warn("turnStart: messageId is null, skipping");
            return;
        }
        sessionStartTimes.put(messageId, System.currentTimeMillis());
    }

    @Override
    public void firstToken(MessageTurnContext ctx) {
        String messageId = ctx.messageId();
        if (messageId == null) {
            log.warn("firstToken: messageId is null, skipping");
            return;
        }
        Long startTime = sessionStartTimes.getIfPresent(messageId);
        if (startTime == null) {
            log.warn("firstToken: startTime not found for messageId={}, skipping", messageId);
            return;
        }
        long now = System.currentTimeMillis();
        firstTokenTimestamps.put(messageId, now);
        long ttft = now - startTime;
        String tag = resolveBrainTag(ctx.brainTag());
        meterRegistry.timer("chat_stream_ttft_seconds", Tags.of("brain_tag", tag))
                .record(ttft, TimeUnit.MILLISECONDS);
        // Report TTFT to Welink if reporter is available
        if (welinkReporter != null) {
            try {
                welinkReporter.report(new ChatFirstTokenTelemetryEvent(
                        ctx.sessionId(), ctx.assistantAccount(), ctx.brainTag(), ctx.messageId(), ttft));
            } catch (Throwable t) {
                log.warn("[ChatStreamMetricsService] Welink firstToken report failed: messageId={}, error={}", messageId, t.getMessage());
            }
        }
    }

    @Override
    public void token(MessageTurnContext ctx, int contentLength) {
        String messageId = ctx.messageId();
        if (messageId == null) return;
        AtomicInteger count = tokenCounts.get(messageId, k -> new AtomicInteger(0));
        count.addAndGet(contentLength);
    }

    @Override
    public void turnEnd(MessageTurnContext ctx) {
        String messageId = ctx.messageId();
        if (messageId == null) {
            log.warn("turnEnd: messageId is null, skipping");
            return;
        }
        Long startTime = sessionStartTimes.getIfPresent(messageId);
        if (startTime == null) {
            log.warn("turnEnd: startTime not found for messageId={}, skipping", messageId);
            return;
        }
        long now = System.currentTimeMillis();
        long latency = now - startTime;
        String tag = resolveBrainTag(ctx.brainTag());

        meterRegistry.timer("chat_stream_latency_seconds", Tags.of("brain_tag", tag))
                .record(latency, TimeUnit.MILLISECONDS);

        AtomicInteger tokenCount = tokenCounts.getIfPresent(messageId);
        int tokens = tokenCount != null ? tokenCount.get() : 0;
        if (latency > 0 && tokens > 0) {
            double tps = (tokens * 1000.0) / latency;
            meterRegistry.summary("chat_stream_tokens_per_second", Tags.of("brain_tag", tag))
                    .record(tps);
        } else {
            log.warn("turnEnd: skipped TPS calculation for messageId={}, latency={}, tokens={}", messageId, latency, tokens);
        }

        // Turn success/failure
        Tags turnTags = Tags.of("brain_tag", tag);
        meterRegistry.counter("chat_stream_turn_total", turnTags).increment();
        if (ctx.success()) {
            meterRegistry.counter("chat_stream_turn_success_total", turnTags).increment();
        } else {
            meterRegistry.counter("chat_stream_turn_failure_total", turnTags).increment();
        }

        sessionStartTimes.invalidate(messageId);
        firstTokenTimestamps.invalidate(messageId);
        tokenCounts.invalidate(messageId);
    }

    private String resolveBrainTag(String brainTag) {
        return (brainTag != null && !brainTag.isBlank()) ? brainTag : "UNKNOWN";
    }
}
