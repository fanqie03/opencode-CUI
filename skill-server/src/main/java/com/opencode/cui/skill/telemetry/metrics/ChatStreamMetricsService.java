package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.chat.ChatTurnEndTelemetryEvent;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式对话效率指标服务。
 * 按 messageId 维护每轮问答状态，记录 TTFT / Latency / TPS / TPOT。
 * messageId 为 null 时直接 return。brainTag 不存在则用 UNKNOWN 兜底。
 *
 * <p>
 * 多 pod 部署说明：
 * sessionStartTimes 存储在 Redis 中，因为 turnStart 可能来自 HTTP 入口（pod1），
 * 而 firstToken / turnEnd 通过 WS sticky routing 落在另一个 pod 上。
 * Redis Key: {@code skill:metrics:stream:start:{messageId}}，TTL = session-ttl
 * </p>
 *
 * <p>
 * firstTokenTimestamps 和 tokenCounts 使用本地 Caffeine cache，
 * 因为 firstToken / token / turnEnd 走同一 WS 连接，sticky routing 保证落在同一 pod。
 * </p>
 */
@Slf4j
@Service
@Order(10)
public class ChatStreamMetricsService implements MessageTurnHandler {

    private static final String REDIS_KEY_PREFIX_START = "skill:metrics:stream:start:";

    private final MeterRegistry meterRegistry;
    private final WelinkTelemetryReporter welinkReporter;
    private final StringRedisTemplate redisTemplate;
    private final Duration sessionTtl;

    /** 首 token 时间戳 — 本地 cache，WS sticky routing 保证同 pod */
    private final Cache<String, Long> firstTokenTimestamps;
    /** token 计数 — 本地 cache，WS sticky routing 保证同 pod */
    private final Cache<String, AtomicInteger> tokenCounts;

    public ChatStreamMetricsService(MeterRegistry meterRegistry,
                                   ObjectProvider<WelinkTelemetryReporter> welinkReporterProvider,
                                   StringRedisTemplate redisTemplate,
                                   @Value("${skill.metrics.stream.max-sessions:10000}") long maxSessions,
                                   @Value("${skill.metrics.stream.session-ttl:30m}") Duration sessionTtl) {
        this.meterRegistry = meterRegistry;
        this.welinkReporter = welinkReporterProvider.getIfAvailable();
        this.redisTemplate = redisTemplate;
        this.sessionTtl = sessionTtl;

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
        try {
            redisTemplate.opsForValue().set(
                    REDIS_KEY_PREFIX_START + messageId,
                    String.valueOf(System.currentTimeMillis()),
                    sessionTtl);
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] turnStart: failed to write start time to Redis: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    @Override
    public void firstToken(MessageTurnContext ctx) {
        String messageId = ctx.messageId();
        if (messageId == null) {
            log.warn("firstToken: messageId is null, skipping");
            return;
        }
        Long startTime = getStartTimeFromRedis(messageId);
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
                        ctx.sessionId(), ctx.senderUserAccount(), ctx.assistantAccount(),
                        ctx.brainTag(), ctx.messageId(), ttft));
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
        Long startTime = getStartTimeFromRedis(messageId);
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

            // TPOT: time per output token = (latency - ttft) / (tokens - 1)
            // Only meaningful when there are tokens after the first one (tokens > 1)
            Long firstTokenTs = firstTokenTimestamps.getIfPresent(messageId);
            if (firstTokenTs != null && tokens > 1) {
                long generationTime = now - firstTokenTs;
                if (generationTime > 0) {
                    double tpotMs = (double) generationTime / (tokens - 1);
                    meterRegistry.timer("chat_stream_tpot_seconds", Tags.of("brain_tag", tag))
                            .record((long) tpotMs, TimeUnit.MILLISECONDS);
                }
            }
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

        // Report turn-end metrics to Welink: content length, duration, success/failure
        if (welinkReporter != null) {
            try {
                welinkReporter.report(new ChatTurnEndTelemetryEvent(
                        ctx.sessionId(), ctx.senderUserAccount(), ctx.assistantAccount(),
                        ctx.brainTag(), ctx.messageId(), tokens, latency, ctx.success()));
            } catch (Throwable t) {
                log.warn("[ChatStreamMetricsService] Welink turnEnd report failed: messageId={}, error={}", messageId, t.getMessage());
            }
        }

        // Cleanup: Redis key + local caches
        cleanupRedis(messageId);
        firstTokenTimestamps.invalidate(messageId);
        tokenCounts.invalidate(messageId);
    }

    /**
     * 从 Redis 读取 turnStart 写入的开始时间戳。
     * 多 pod 场景下 turnStart 可能落在不同 pod，必须走 Redis。
     */
    private Long getStartTimeFromRedis(String messageId) {
        try {
            String value = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX_START + messageId);
            return value != null ? Long.parseLong(value) : null;
        } catch (NumberFormatException e) {
            log.warn("[ChatStreamMetricsService] failed to parse startTime from Redis: messageId={}, error={}", messageId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] failed to get startTime from Redis: messageId={}, error={}", messageId, e.getMessage());
            return null;
        }
    }

    private void cleanupRedis(String messageId) {
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX_START + messageId);
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] cleanup failed for messageId={}, error={}", messageId, e.getMessage());
        }
    }

    private String resolveBrainTag(String brainTag) {
        return (brainTag != null && !brainTag.isBlank()) ? brainTag : "UNKNOWN";
    }
}
