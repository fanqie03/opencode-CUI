package com.opencode.cui.skill.telemetry.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 消息轮次生命周期编排器。
 * 遍历所有注册的 {@link MessageTurnHandler} 插件，分发 turnStart / firstToken / token / turnEnd 事件。
 * firstToken 事件由本类基于 {@code processedFirstToken} 缓存做幂等去重（每 messageId 仅触发一次）。
 * turnEnd 时清理缓存，允许同一 messageId 的后续轮次重新触发 firstToken。
 */
@Component
public class MessageTurnLifecycle {

    private final List<MessageTurnHandler> handlers;
    /** Track which messageIds have already reported their first token (bounded, TTL-evicted). */
    private final Cache<String, Boolean> processedFirstToken;

    public MessageTurnLifecycle(List<MessageTurnHandler> handlers,
                                MeterRegistry meterRegistry,
                                @Value("${telemetry.chatstream.max-sessions:10000}") long maxSessions,
                                @Value("${telemetry.chatstream.session-ttl-minutes:30}") Duration sessionTtl) {
        this.handlers = handlers;
        this.processedFirstToken = Caffeine.newBuilder()
                .recordStats()
                .maximumSize(maxSessions).expireAfterWrite(sessionTtl).build();
        CaffeineCacheMetrics.monitor(meterRegistry, processedFirstToken, "processedFirstToken");
        log.info("MessageTurnLifecycle init handlers: {}", handlers);
    }

    public void onTurnStart(MessageTurnContext ctx) {
        for (MessageTurnHandler handler : handlers) {
            handler.turnStart(ctx);
        }
    }

    private void onFirstToken(MessageTurnContext ctx) {
        for (MessageTurnHandler handler : handlers) {
            handler.firstToken(ctx);
        }
    }

    public void onToken(MessageTurnContext ctx, int contentLength) {
        Boolean wasFirst = processedFirstToken.asMap().putIfAbsent(ctx.messageId(), Boolean.TRUE);
        if (wasFirst == null) {
            // This thread won the race — it's the first token
            onFirstToken(ctx);
        }
        for (MessageTurnHandler handler : handlers) {
            handler.token(ctx, contentLength);
        }
    }

    public void onTurnEnd(MessageTurnContext ctx) {
        for (MessageTurnHandler handler : handlers) {
            handler.turnEnd(ctx);
        }
        processedFirstToken.invalidate(ctx.messageId());
    }
}
