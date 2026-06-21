package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ChatStreamMetricsServiceTest {

    private MeterRegistry registry;
    private ChatStreamMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ChatStreamMetricsService(registry, 10000, Duration.ofMinutes(30));
    }

    @Test
    void onFirstToken_recordsTtftTimer() {
        service.onStreamStart(MessageTurnContext.of("msg-1", "brain-A"));
        sleep(10);
        service.onFirstToken(MessageTurnContext.of("msg-1", "brain-A"));

        Timer ttft = registry.find("chat_stream_ttft_seconds").tag("brain_tag", "brain-A").timer();
        assertNotNull(ttft);
        assertEquals(1, ttft.count());
    }

    @Test
    void onStreamEnd_recordsLatencyAndTps() {
        service.onStreamStart(MessageTurnContext.of("msg-2", "brain-B"));
        sleep(5);
        service.onFirstToken(MessageTurnContext.of("msg-2", "brain-B"));
        service.onToken(MessageTurnContext.of("msg-2", "brain-B"), 5);
        service.onToken(MessageTurnContext.of("msg-2", "brain-B"), 3);
        sleep(5);
        service.onStreamEnd(MessageTurnContext.of("msg-2", "brain-B"));

        Timer latency = registry.find("chat_stream_latency_seconds").tag("brain_tag", "brain-B").timer();
        assertNotNull(latency);
        assertEquals(1, latency.count());

        DistributionSummary tps = registry.find("chat_stream_tokens_per_second").tag("brain_tag", "brain-B").summary();
        assertNotNull(tps);
        assertEquals(1, tps.count());
    }

    @Test
    void nullMessageId_skipsAllRecording() {
        service.onStreamStart(MessageTurnContext.of(null, "brain-A"));
        service.onFirstToken(MessageTurnContext.of(null, "brain-A"));
        service.onToken(MessageTurnContext.of(null, "brain-A"), 10);
        service.onStreamEnd(MessageTurnContext.of(null, "brain-A"));

        // Cache metrics are registered at construction time; null messageId should produce no chat_stream business metrics
        assertNull(registry.find("chat_stream_ttft_seconds").timer());
        assertNull(registry.find("chat_stream_latency_seconds").timer());
        assertNull(registry.find("chat_stream_tokens_per_second").summary());
    }

    @Test
    void missingBrainTag_usesUnknownFallback() {
        service.onStreamStart(MessageTurnContext.of("msg-3", null));
        sleep(5);
        service.onFirstToken(MessageTurnContext.of("msg-3", null));

        Timer ttft = registry.find("chat_stream_ttft_seconds").tag("brain_tag", "UNKNOWN").timer();
        assertNotNull(ttft);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
