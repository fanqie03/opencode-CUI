package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatStreamMetricsServiceTest {

    private MeterRegistry registry;
    private ChatStreamMetricsService service;
    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter> welinkProvider =
                mock(ObjectProvider.class);
        when(welinkProvider.getIfAvailable()).thenReturn(null);

        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new ChatStreamMetricsService(registry, welinkProvider, redisTemplate,
                10000, Duration.ofMinutes(30),
                "openplatform_service_chat_first_token",
                "openplatform_service_chat_turn_end");
    }

    @Test
    void firstToken_recordsTtftTimer() {
        when(valueOps.get("skill:metrics:stream:start:msg-1")).thenReturn(String.valueOf(System.currentTimeMillis() - 50));
        service.turnStart(MessageTurnContext.of("msg-1", "brain-A"));
        sleep(10);
        service.firstToken(MessageTurnContext.of("msg-1", "brain-A"));

        Timer ttft = registry.find("chat_stream_ttft_seconds").tag("brain_tag", "brain-A").timer();
        assertNotNull(ttft);
        assertEquals(1, ttft.count());
    }

    @Test
    void turnEnd_recordsLatencyAndTps() {
        long start = System.currentTimeMillis();
        when(valueOps.get("skill:metrics:stream:start:msg-2")).thenReturn(String.valueOf(start));

        service.turnStart(MessageTurnContext.of("msg-2", "brain-B"));
        sleep(5);
        service.firstToken(MessageTurnContext.of("msg-2", "brain-B"));
        service.token(MessageTurnContext.of("msg-2", "brain-B"), 5);
        service.token(MessageTurnContext.of("msg-2", "brain-B"), 3);
        sleep(5);
        service.turnEnd(MessageTurnContext.of("msg-2", "brain-B"));

        Timer latency = registry.find("chat_stream_latency_seconds").tag("brain_tag", "brain-B").timer();
        assertNotNull(latency);
        assertEquals(1, latency.count());

        DistributionSummary tps = registry.find("chat_stream_tokens_per_second").tag("brain_tag", "brain-B").summary();
        assertNotNull(tps);
        assertEquals(1, tps.count());
    }

    @Test
    void nullMessageId_skipsAllRecording() {
        service.turnStart(MessageTurnContext.of(null, "brain-A"));
        service.firstToken(MessageTurnContext.of(null, "brain-A"));
        service.token(MessageTurnContext.of(null, "brain-A"), 10);
        service.turnEnd(MessageTurnContext.of(null, "brain-A"));

        assertNull(registry.find("chat_stream_ttft_seconds").timer());
        assertNull(registry.find("chat_stream_latency_seconds").timer());
        assertNull(registry.find("chat_stream_tokens_per_second").summary());
    }

    @Test
    void missingBrainTag_usesUnknownFallback() {
        when(valueOps.get("skill:metrics:stream:start:msg-3")).thenReturn(String.valueOf(System.currentTimeMillis()));
        service.turnStart(MessageTurnContext.of("msg-3", null));
        sleep(5);
        service.firstToken(MessageTurnContext.of("msg-3", null));

        Timer ttft = registry.find("chat_stream_ttft_seconds").tag("brain_tag", "UNKNOWN").timer();
        assertNotNull(ttft);
    }

    @Test
    void turnEnd_recordsSuccessCounters() {
        when(valueOps.get("skill:metrics:stream:start:msg-success")).thenReturn(String.valueOf(System.currentTimeMillis()));

        service.turnStart(MessageTurnContext.of("msg-success", "brain-S"));
        sleep(5);
        service.firstToken(MessageTurnContext.of("msg-success", "brain-S"));
        service.token(MessageTurnContext.of("msg-success", "brain-S"), 4);
        sleep(5);
        service.turnEnd(MessageTurnContext.of("msg-success", "brain-S"));

        assertNotNull(registry.find("chat_stream_turn_total").tag("brain_tag", "brain-S").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_total").tag("brain_tag", "brain-S").counter().count());
        assertNotNull(registry.find("chat_stream_turn_success_total").tag("brain_tag", "brain-S").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_success_total").tag("brain_tag", "brain-S").counter().count());
        assertNull(registry.find("chat_stream_turn_failure_total").tag("brain_tag", "brain-S").counter());
    }

    @Test
    void turnEnd_recordsFailureCounters() {
        when(valueOps.get("skill:metrics:stream:start:msg-fail")).thenReturn(String.valueOf(System.currentTimeMillis()));

        service.turnStart(new MessageTurnContext("msg-fail", "brain-F", null, null, null, null, false));
        sleep(5);
        service.turnEnd(new MessageTurnContext("msg-fail", "brain-F", null, null, null, null, false));

        assertNotNull(registry.find("chat_stream_turn_total").tag("brain_tag", "brain-F").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_total").tag("brain_tag", "brain-F").counter().count());
        assertNotNull(registry.find("chat_stream_turn_failure_total").tag("brain_tag", "brain-F").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_failure_total").tag("brain_tag", "brain-F").counter().count());
        assertNull(registry.find("chat_stream_turn_success_total").tag("brain_tag", "brain-F").counter());
    }

    @Test
    void turnStart_writesStartTimeToRedis() {
        service.turnStart(MessageTurnContext.of("msg-redis", "brain-A"));

        verify(valueOps).set(eq("skill:metrics:stream:start:msg-redis"), anyString(), any(Duration.class));
    }

    @Test
    void turnEnd_cleansUpRedisKey() {
        when(valueOps.get("skill:metrics:stream:start:msg-clean")).thenReturn(String.valueOf(System.currentTimeMillis()));

        service.turnStart(MessageTurnContext.of("msg-clean", "brain-A"));
        service.turnEnd(MessageTurnContext.of("msg-clean", "brain-A"));

        verify(redisTemplate).delete("skill:metrics:stream:start:msg-clean");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
