package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
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
public class ChatStreamMetricsService {

    private final MeterRegistry meterRegistry;
    private final Cache<String, Long> sessionStartTimes;
    private final Cache<String, Long> firstTokenTimestamps;
    private final Cache<String, AtomicInteger> tokenCounts;

    public ChatStreamMetricsService(MeterRegistry meterRegistry, long maxSessions, Duration sessionTtl) {
        this.meterRegistry = meterRegistry;
        this.sessionStartTimes = Caffeine.newBuilder()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        this.firstTokenTimestamps = Caffeine.newBuilder()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        this.tokenCounts = Caffeine.newBuilder()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
    }

    public void onStreamStart(String messageId, String brainTag) {
        if (messageId == null) return;
        sessionStartTimes.put(messageId, System.currentTimeMillis());
    }

    public void onFirstToken(String messageId, String brainTag) {
        if (messageId == null) return;
        Long startTime = sessionStartTimes.getIfPresent(messageId);
        if (startTime == null) return;
        long now = System.currentTimeMillis();
        firstTokenTimestamps.put(messageId, now);
        long ttft = now - startTime;
        String tag = resolveBrainTag(brainTag);
        meterRegistry.timer("chat_stream_ttft_seconds", Tags.of("brain_tag", tag))
                .record(ttft, TimeUnit.MILLISECONDS);
    }

    public void onToken(String messageId, String brainTag) {
        if (messageId == null) return;
        AtomicInteger count = tokenCounts.get(messageId, k -> new AtomicInteger(0));
        count.incrementAndGet();
    }

    public void onStreamEnd(String messageId, String brainTag) {
        if (messageId == null) return;
        Long startTime = sessionStartTimes.getIfPresent(messageId);
        if (startTime == null) return;
        long now = System.currentTimeMillis();
        long latency = now - startTime;
        String tag = resolveBrainTag(brainTag);

        meterRegistry.timer("chat_stream_latency_seconds", Tags.of("brain_tag", tag))
                .record(latency, TimeUnit.MILLISECONDS);

        AtomicInteger tokenCount = tokenCounts.getIfPresent(messageId);
        int tokens = tokenCount != null ? tokenCount.get() : 0;
        if (latency > 0 && tokens > 0) {
            double tps = (tokens * 1000.0) / latency;
            meterRegistry.summary("chat_stream_tokens_per_second", Tags.of("brain_tag", tag))
                    .record(tps);
        }

        sessionStartTimes.invalidate(messageId);
        firstTokenTimestamps.invalidate(messageId);
        tokenCounts.invalidate(messageId);
    }

    private String resolveBrainTag(String brainTag) {
        return (brainTag != null && !brainTag.isBlank()) ? brainTag : "UNKNOWN";
    }
}