package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Handler that reports first-token (TTFT) events to Welink telemetry.
 * Only registered when a {@link WelinkTelemetryReporter} bean is present.
 */
@Slf4j
@Component
@Order(20)
@ConditionalOnBean(WelinkTelemetryReporter.class)
@RequiredArgsConstructor
public class WelinkTelemetryHandler implements MessageTurnHandler {

    private final WelinkTelemetryReporter welinkReporter;

    @Override
    public void firstToken(MessageTurnContext ctx) {
        try {
            welinkReporter.report(new ChatFirstTokenTelemetryEvent(
                    ctx.sessionId(), ctx.assistantAccount(), ctx.brainTag(), ctx.messageId()));
        } catch (Throwable t) {
            log.warn("[WelinkTelemetryHandler] firstToken report failed: sessionId={}, messageId={}, error={}",
                    ctx.sessionId(), ctx.messageId(), t.getMessage());
        }
    }
}
