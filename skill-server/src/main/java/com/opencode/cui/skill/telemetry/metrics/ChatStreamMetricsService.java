package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.AssistantInfoService;
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
 * 按 sessionId 维护每轮问答状态，记录 TTFT / Latency / TPS / TPOT。
 * brainTag 不存在则用 UNKNOWN 兜底。
 *
 * <p>
 * 多 pod 部署说明：
 * sessionStartTimes 存储在 Redis 中，因为 turnStart 可能来自 HTTP 入口（pod1），
 * 而 firstToken / turnEnd 通过 WS sticky routing 落在另一个 pod 上。
 * Redis Key: {@code skill:metrics:stream:start:{sessionId}}，TTL = session-ttl
 * </p>
 *
 * <p>
 * firstTokenTimestamps 和 tokenCounts 使用本地 Caffeine cache，
 * 因为 firstToken / token / turnEnd 走同一 WS 连接，sticky routing 保证落在同一 pod。
 * </p>
 *
 * <p>
 * 业务维度字段（brainTag、assistantAccount、robotId、businessSessionDomain 等）
 * 从 MessageTurnContext.session 中获取，参考 ChatTelemetryEventListener 的做法。
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
    private final AssistantInfoService assistantInfoService;
    private final Duration sessionTtl;
    private final String firstTokenEventId;
    private final String turnEndEventId;

    private final Cache<String, Long> firstTokenTimestamps;
    private final Cache<String, AtomicInteger> tokenCounts;

    public ChatStreamMetricsService(MeterRegistry meterRegistry,
                                   ObjectProvider<WelinkTelemetryReporter> welinkReporterProvider,
                                   StringRedisTemplate redisTemplate,
                                   AssistantInfoService assistantInfoService,
                                   @Value("${skill.metrics.stream.max-sessions:10000}") long maxSessions,
                                   @Value("${skill.metrics.stream.session-ttl:30m}") Duration sessionTtl,
                                   @Value("${skill.metrics.stream.event-id.first-token:openplatform_service_chat_first_token}") String firstTokenEventId,
                                   @Value("${skill.metrics.stream.event-id.turn-end:openplatform_service_chat_turn_end}") String turnEndEventId) {
        this.meterRegistry = meterRegistry;
        this.welinkReporter = welinkReporterProvider.getIfAvailable();
        this.redisTemplate = redisTemplate;
        this.assistantInfoService = assistantInfoService;
        this.sessionTtl = sessionTtl;
        this.firstTokenEventId = firstTokenEventId;
        this.turnEndEventId = turnEndEventId;

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
        String sessionId = resolveSessionId(ctx);
        if (sessionId == null) {
            log.warn("turnStart: sessionId is null, skipping");
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    REDIS_KEY_PREFIX_START + sessionId,
                    String.valueOf(System.currentTimeMillis()),
                    sessionTtl);
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] turnStart: failed to write start time to Redis: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    @Override
    public void firstToken(MessageTurnContext ctx) {
        String sessionId = resolveSessionId(ctx);
        if (sessionId == null) {
            log.warn("firstToken: sessionId is null, skipping");
            return;
        }
        Long startTime = getStartTimeFromRedis(sessionId);
        if (startTime == null) {
            log.warn("firstToken: startTime not found for sessionId={}, skipping", sessionId);
            return;
        }
        long now = System.currentTimeMillis();
        firstTokenTimestamps.put(sessionId, now);
        long ttft = now - startTime;
        SessionMetadata meta = resolveSessionMetadata(ctx);
        String brainTag = resolveBrainTag(meta);
        meterRegistry.timer("chat_stream_ttft_seconds", Tags.of("brain_tag", brainTag))
                .record(ttft, TimeUnit.MILLISECONDS);
        if (welinkReporter != null) {
            try {
                log.info("[ChatStreamMetricsService] Welink firstToken report: eventId={}, messageId={}, sessionId={}", firstTokenEventId, ctx.messageId(), sessionId);
                welinkReporter.report(new ChatFirstTokenTelemetryEvent(
                        firstTokenEventId,
                        sessionId, meta.senderUserAccount, meta.assistantAccount,
                        meta.brainTag, meta.robotId,
                        meta.businessSessionDomain, meta.businessSessionType, meta.businessSessionId,
                        ctx.messageId(), ttft));
            } catch (Throwable t) {
                log.warn("[ChatStreamMetricsService] Welink firstToken report failed: messageId={}, error={}", ctx.messageId(), t.getMessage());
            }
        }
    }

    @Override
    public void token(MessageTurnContext ctx, int contentLength) {
        String sessionId = resolveSessionId(ctx);
        if (sessionId == null) return;
        AtomicInteger count = tokenCounts.get(sessionId, k -> new AtomicInteger(0));
        count.addAndGet(contentLength);
    }

    @Override
    public void turnEnd(MessageTurnContext ctx) {
        String sessionId = resolveSessionId(ctx);
        if (sessionId == null) {
            log.warn("turnEnd: sessionId is null, skipping");
            return;
        }
        Long startTime = getStartTimeFromRedis(sessionId);
        if (startTime == null) {
            log.warn("turnEnd: startTime not found for sessionId={}, skipping", sessionId);
            return;
        }
        long now = System.currentTimeMillis();
        long latency = now - startTime;
        SessionMetadata meta = resolveSessionMetadata(ctx);
        String brainTag = resolveBrainTag(meta);

        meterRegistry.timer("chat_stream_latency_seconds", Tags.of("brain_tag", brainTag))
                .record(latency, TimeUnit.MILLISECONDS);

        AtomicInteger tokenCount = tokenCounts.getIfPresent(sessionId);
        int tokens = tokenCount != null ? tokenCount.get() : 0;
        if (latency > 0 && tokens > 0) {
            double tps = (tokens * 1000.0) / latency;
            meterRegistry.summary("chat_stream_tokens_per_second", Tags.of("brain_tag", brainTag))
                    .record(tps);

            Long firstTokenTs = firstTokenTimestamps.getIfPresent(sessionId);
            if (firstTokenTs != null && tokens > 1) {
                long generationTime = now - firstTokenTs;
                if (generationTime > 0) {
                    double tpotMs = (double) generationTime / (tokens - 1);
                    meterRegistry.timer("chat_stream_tpot_seconds", Tags.of("brain_tag", brainTag))
                            .record((long) tpotMs, TimeUnit.MILLISECONDS);
                }
            }
        } else {
            log.warn("turnEnd: skipped TPS calculation for sessionId={}, latency={}, tokens={}", sessionId, latency, tokens);
        }

        Tags turnTags = Tags.of("brain_tag", brainTag);
        meterRegistry.counter("chat_stream_turn_total", turnTags).increment();
        if (ctx.success()) {
            meterRegistry.counter("chat_stream_turn_success_total", turnTags).increment();
        } else {
            meterRegistry.counter("chat_stream_turn_failure_total", turnTags).increment();
        }

        if (welinkReporter != null) {
            try {
                log.info("[ChatStreamMetricsService] Welink turnEnd report: eventId={}, messageId={}, sessionId={}, tokens={}, latency={}", turnEndEventId, ctx.messageId(), sessionId, tokens, latency);
                welinkReporter.report(new ChatTurnEndTelemetryEvent(
                        turnEndEventId,
                        sessionId, meta.senderUserAccount, meta.assistantAccount,
                        meta.brainTag, meta.robotId,
                        meta.businessSessionDomain, meta.businessSessionType, meta.businessSessionId,
                        ctx.messageId(), tokens, latency, ctx.success()));
            } catch (Throwable t) {
                log.warn("[ChatStreamMetricsService] Welink turnEnd report failed: messageId={}, error={}", ctx.messageId(), t.getMessage());
            }
        }

        cleanupRedis(sessionId);
        firstTokenTimestamps.invalidate(sessionId);
        tokenCounts.invalidate(sessionId);
    }

    private Long getStartTimeFromRedis(String sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX_START + sessionId);
            return value != null ? Long.parseLong(value) : null;
        } catch (NumberFormatException e) {
            log.warn("[ChatStreamMetricsService] failed to parse startTime from Redis: sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] failed to get startTime from Redis: sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    private void cleanupRedis(String sessionId) {
        try {
            redisTemplate.delete(REDIS_KEY_PREFIX_START + sessionId);
        } catch (Exception e) {
            log.warn("[ChatStreamMetricsService] cleanup failed for sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    private String resolveSessionId(MessageTurnContext ctx) {
        if (ctx.session() != null && ctx.session().getId() != null) {
            return String.valueOf(ctx.session().getId());
        }
        return ctx.messageId();
    }

    private String resolveBrainTag(SessionMetadata meta) {
        return (meta.brainTag != null && !meta.brainTag.isBlank()) ? meta.brainTag : "UNKNOWN";
    }

    /**
     * 从 session + AssistantInfo 解析业务维度字段。
     * 参考 ChatTelemetryEventListener.resolveAssistantInfo 的做法。
     */
    private SessionMetadata resolveSessionMetadata(MessageTurnContext ctx) {
        SessionMetadata meta = new SessionMetadata();
        SkillSession session = ctx.session();
        if (session == null) {
            return meta;
        }
        meta.senderUserAccount = session.getUserId();
        meta.assistantAccount = session.getAssistantAccount();
        meta.businessSessionDomain = session.getBusinessSessionDomain();
        meta.businessSessionType = session.getBusinessSessionType();
        meta.businessSessionId = session.getBusinessSessionId();

        AssistantInfo info = resolveAssistantInfo(session);
        if (info != null) {
            meta.brainTag = info.getBusinessTag();
            meta.robotId = info.getId();
        }
        return meta;
    }

    private AssistantInfo resolveAssistantInfo(SkillSession session) {
        if (session == null) {
            return null;
        }
        try {
            if (session.getAssistantAccount() != null && !session.getAssistantAccount().isBlank()) {
                return assistantInfoService.getAssistantInfo(session.getAk(), session.getAssistantAccount());
            }
            if (session.getAk() == null || session.getAk().isBlank()) {
                return null;
            }
            return assistantInfoService.getAssistantInfo(session.getAk());
        } catch (Throwable t) {
            log.warn("[ChatStreamMetricsService] resolveAssistantInfo failed: ak={}, assistantAccount={}, error={}",
                    session.getAk(), session.getAssistantAccount(), t.getMessage());
            return null;
        }
    }

    private static class SessionMetadata {
        String senderUserAccount;
        String assistantAccount;
        String brainTag;
        String robotId;
        String businessSessionDomain;
        String businessSessionType;
        String businessSessionId;
    }
}
