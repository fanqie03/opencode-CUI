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
        lifecycle = new MessageTurnLifecycle(streamMetrics, welinkReporter, true);
    }

    @Test
    void onTurnStart_delegatesToStreamMetrics() {
        lifecycle.onTurnStart("msg-1", "brain-A", "sess-1", "user-1", "brain-A");
        lifecycle.onFirstToken("msg-1", "brain-A", "sess-1", "assistant-1");
        assertNotNull(registry.find("chat_stream_ttft_seconds").tag("brain_tag", "brain-A").timer());
    }

    @Test
    void onFirstToken_reportsToWelinkWhenEnabled() {
        lifecycle.onTurnStart("msg-2", "brain-A", "sess-2", "user-2", "brain-A");
        lifecycle.onFirstToken("msg-2", "brain-A", "sess-2", "assistant-2");

        verify(welinkReporter).report(argThat(event ->
            event instanceof ChatFirstTokenTelemetryEvent
            && "skill_chat_first_token".equals(event.eventId())));
    }

    @Test
    void onFirstToken_skipsWelinkWhenDisabled() {
        MessageTurnLifecycle disabledLifecycle = new MessageTurnLifecycle(streamMetrics, welinkReporter, false);
        disabledLifecycle.onTurnStart("msg-3", "brain-A", "sess-3", "user-3", "brain-A");
        disabledLifecycle.onFirstToken("msg-3", "brain-A", "sess-3", "assistant-3");

        verify(welinkReporter, never()).report(any());
    }

    @Test
    void onTurnEnd_delegatesToStreamMetrics() {
        lifecycle.onTurnStart("msg-4", "brain-B", "sess-4", "user-4", "brain-B");
        lifecycle.onFirstToken("msg-4", "brain-B", "sess-4", "assistant-4");
        lifecycle.onToken("msg-4", "brain-B");
        lifecycle.onTurnEnd("msg-4", "brain-B", "sess-4", "assistant-4");

        assertNotNull(registry.find("chat_stream_latency_seconds").tag("brain_tag", "brain-B").timer());
    }
}