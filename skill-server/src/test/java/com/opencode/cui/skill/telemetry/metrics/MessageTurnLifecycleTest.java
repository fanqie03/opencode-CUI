package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.telemetry.chat.ChatFirstTokenTelemetryEvent;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageTurnLifecycleTest {

    private MeterRegistry registry;
    private ChatStreamMetricsService streamMetrics;
    private WelinkTelemetryReporter welinkReporter;
    private MessageTurnLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        streamMetrics = new ChatStreamMetricsService(registry, 10000, Duration.ofMinutes(30));
        welinkReporter = mock(WelinkTelemetryReporter.class);
        when(welinkReporter.isEffectiveEnabled()).thenReturn(true);
        lifecycle = new MessageTurnLifecycle(streamMetrics, welinkReporter, registry, true, 10000, Duration.ofMinutes(30));
    }

    @Test
    void onTurnStart_delegatesToStreamMetrics() {
        lifecycle.onTurnStart(new MessageTurnContext("msg-1", "brain-A", "sess-1", null, "user-1", "brain-A"));
        // First onToken call triggers first-token logic internally
        lifecycle.onToken(new MessageTurnContext("msg-1", "brain-A", "sess-1", "assistant-1", null, null), 5);
        assertNotNull(registry.find("chat_stream_ttft_seconds").tag("brain_tag", "brain-A").timer());
    }

    @Test
    void onFirstToken_reportsToWelinkWhenEnabled() {
        lifecycle.onTurnStart(new MessageTurnContext("msg-2", "brain-A", "sess-2", null, "user-2", "brain-A"));
        // First onToken triggers first-token reporting internally
        lifecycle.onToken(new MessageTurnContext("msg-2", "brain-A", "sess-2", "assistant-2", null, null), 5);

        verify(welinkReporter).report(argThat(event ->
            event instanceof ChatFirstTokenTelemetryEvent
            && "skill_chat_first_token".equals(event.eventId())));
    }

    @Test
    void onFirstToken_onlyFiresOncePerMessageId() {
        lifecycle.onTurnStart(new MessageTurnContext("msg-2b", "brain-A", "sess-2b", null, "user-2b", "brain-A"));
        lifecycle.onToken(new MessageTurnContext("msg-2b", "brain-A", "sess-2b", "assistant-2b", null, null), 5);
        lifecycle.onToken(new MessageTurnContext("msg-2b", "brain-A", "sess-2b", "assistant-2b", null, null), 3);
        lifecycle.onToken(new MessageTurnContext("msg-2b", "brain-A", "sess-2b", "assistant-2b", null, null), 7);

        // Welink should only be called once (first token only)
        verify(welinkReporter, times(1)).report(any(ChatFirstTokenTelemetryEvent.class));
    }

    @Test
    void onFirstToken_skipsWelinkWhenDisabled() {
        MessageTurnLifecycle disabledLifecycle = new MessageTurnLifecycle(streamMetrics, welinkReporter, registry, false, 10000, Duration.ofMinutes(30));
        disabledLifecycle.onTurnStart(new MessageTurnContext("msg-3", "brain-A", "sess-3", null, "user-3", "brain-A"));
        disabledLifecycle.onToken(new MessageTurnContext("msg-3", "brain-A", "sess-3", "assistant-3", null, null), 5);

        verify(welinkReporter, never()).report(any());
    }

    @Test
    void onTurnEnd_delegatesToStreamMetrics() {
        lifecycle.onTurnStart(new MessageTurnContext("msg-4", "brain-B", "sess-4", null, "user-4", "brain-B"));
        lifecycle.onToken(new MessageTurnContext("msg-4", "brain-B", "sess-4", "assistant-4", null, null), 5);
        lifecycle.onToken(new MessageTurnContext("msg-4", "brain-B", "sess-4", "assistant-4", null, null), 3);
        lifecycle.onTurnEnd(new MessageTurnContext("msg-4", "brain-B", "sess-4", "assistant-4", null, null));

        assertNotNull(registry.find("chat_stream_latency_seconds").tag("brain_tag", "brain-B").timer());
    }
}
