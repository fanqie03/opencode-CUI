package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.chat.ChatTurnEndTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 流式对话效率指标服务。
 * 按 messageId 维护每轮问答状态（Redis），记录 TTFT / Latency / TPS / TPOT。
 * messageId 为 null 时直接 return。brainTag 不存在则用 UNKNOWN 兜底。
 *
 * <p>所有状态存储在 Redis 中，支持多 pod 部署场景下 turnStart / firstToken / token / turnEnd
 * 落在不同 pod 上的情况。Redis Key 模式：
 * <ul>
 *   <li>{@code skill:metrics:stream:start:{messageId}} — 轮次开始时间戳</li>
 *   <li>{@code skill:metrics:stream:ftok:{messageId}} — 首 token 时间戳</li>
 *   <li>{@code skill:metrics:stream:tokens:{messageId}} — token 计数（INCRBY 原子递增）</li>
 * </ul>
 * 所有 key 统一使用 {@code session-ttl} 过期时间（默认 30 分钟）。
 */
@Slf4j
@Service
@Order(10)
public class ChatStreamMetricsService implements MessageTurnHandler {

    private static final String KEY_PREFIX_START = "skill:metrics:stream:start:";
    private static final String KEY_PREFIX_FTOK = "skill:metrics:stream:ftok:";
    private static final String KEY_PREFIX_TOKENS = "skill:metrics:stream:tokens:";

    private final MeterRegistry meterRegistry;
    private final WelinkTelemetryReporter welinkReporter;
    private final StringRedisTemplate redisTemplate;
    private final Duration sessionTtl;

    public ChatStreamMetricsService(MeterRegistry meterRegistry,
                                   ObjectProvider<WelinkTelemetryReporter> welinkReporterProvider,
                                   StringRedisTemplate redisTemplate,
                                   @Value("${skill.metrics.stream.max-sessions:10000}") long maxSessions,
                                   @Value("${skill.metrics.stream.session-ttl:30m}") Duration sessionTtl) {
        this.meterRegistry = meterRegistry;
        this.welinkReporter = welinkReporterProvider.getIfAvailable();
        this.redisTemplate = redisTemplate;
        this.sessionTtl = sessionTtl;
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
                    KEY_PREFIX_START + messageId,
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
        Long startTime = getLong(KEY_PREFIX_START + messageId);
        if (startTime == null) {
            log.warn("firstToken: startTime not found for messageId={}, skipping", messageId);
            return;
        }
        long now = System.currentTimeMillis();
        setLong(KEY_PREFIX_FTOK + messageId, now);
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
        try {
            redisTemplate.opsForValue().increment(KEY_PREFIX_TOKENS + messageId, contentLength);
            redisTemplate.expire(KEY_PREFIX_TOKENS + messageId, sessionTtl);
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] token: failed to increment token count in Redis: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    @Override
    public void turnEnd(MessageTurnContext ctx) {
        String messageId = ctx.messageId();
        if (messageId == null) {
            log.warn("turnEnd: messageId is null, skipping");
            return;
        }
        Long startTime = getLong(KEY_PREFIX_START + messageId);
        if (startTime == null) {
            log.warn("turnEnd: startTime not found for messageId={}, skipping", messageId);
            return;
        }
        long now = System.currentTimeMillis();
        long latency = now - startTime;
        String tag = resolveBrainTag(ctx.brainTag());

        meterRegistry.timer("chat_stream_latency_seconds", Tags.of("brain_tag", tag))
                .record(latency, TimeUnit.MILLISECONDS);

        int tokens = getInt(KEY_PREFIX_TOKENS + messageId);
        if (latency > 0 && tokens > 0) {
            double tps = (tokens * 1000.0) / latency;
            meterRegistry.summary("chat_stream_tokens_per_second", Tags.of("brain_tag", tag))
                    .record(tps);

            // TPOT: time per output token = (latency - ttft) / (tokens - 1)
            // Only meaningful when there are tokens after the first one (tokens > 1)
            Long firstTokenTs = getLong(KEY_PREFIX_FTOK + messageId);
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

        // Cleanup Redis keys for this message turn
        cleanup(messageId);
    }

    private Long getLong(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : null;
        } catch (NumberFormatException e) {
            log.warn("[ChatStreamMetricsService] failed to parse long from Redis: key={}, value={}", key, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] failed to get long from Redis: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    private int getInt(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value != null ? Integer.parseInt(value) : 0;
        } catch (NumberFormatException e) {
            log.warn("[ChatStreamMetricsService] failed to parse int from Redis: key={}, value={}", key, e.getMessage());
            return 0;
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] failed to get int from Redis: key={}, error={}", key, e.getMessage());
            return 0;
        }
    }

    private void setLong(String key, long value) {
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(value), sessionTtl);
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] failed to set long to Redis: key={}, error={}", key, e.getMessage());
        }
    }

    private void cleanup(String messageId) {
        try {
            redisTemplate.delete(KEY_PREFIX_START + messageId);
            redisTemplate.delete(KEY_PREFIX_FTOK + messageId);
            redisTemplate.delete(KEY_PREFIX_TOKENS + messageId);
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] cleanup failed for messageId={}, error={}", messageId, e.getMessage());
        }
    }

    private String resolveBrainTag(String brainTag) {
        return (brainTag != null && !brainTag.isBlank()) ? brainTag : "UNKNOWN";
    }
}
