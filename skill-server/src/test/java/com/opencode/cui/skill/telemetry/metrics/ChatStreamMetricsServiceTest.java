package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.AssistantInfoService;
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
import static org.mockito.Mockito.*;

class ChatStreamMetricsServiceTest {

    private MeterRegistry registry;
    private ChatStreamMetricsService service;
    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private AssistantInfoService assistantInfoService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter> welinkProvider =
                mock(ObjectProvider.class);
        when(welinkProvider.getIfAvailable()).thenReturn(null);

        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        assistantInfoService = mock(AssistantInfoService.class);

        service = new ChatStreamMetricsService(registry, welinkProvider, redisTemplate, assistantInfoService,
                10000, Duration.ofMinutes(30),
                "openplatform_service_chat_first_token",
                "openplatform_service_chat_turn_end");
    }

    private SkillSession mockSession(Long id, String userId) {
        SkillSession session = new SkillSession();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }

    @Test
    void firstToken_recordsTtftTimer() {
        SkillSession session = mockSession(1L, "user-1");
        when(valueOps.get("skill:metrics:stream:start:1")).thenReturn(String.valueOf(System.currentTimeMillis() - 50));
        service.turnStart(new MessageTurnContext(session, "msg-1", true));
        sleep(10);
        service.firstToken(new MessageTurnContext(session, "msg-1", true));

        Timer ttft = registry.find("chat_stream_ttft_seconds").tag("brain_tag", "UNKNOWN").timer();
        assertNotNull(ttft);
        assertEquals(1, ttft.count());
    }

    @Test
    void turnEnd_recordsLatencyAndTps() {
        SkillSession session = mockSession(2L, "user-2");
        long start = System.currentTimeMillis();
        when(valueOps.get("skill:metrics:stream:start:2")).thenReturn(String.valueOf(start));

        service.turnStart(new MessageTurnContext(session, "msg-2", true));
        sleep(5);
        service.firstToken(new MessageTurnContext(session, "msg-2", true));
        service.token(new MessageTurnContext(session, "msg-2", true), 5);
        service.token(new MessageTurnContext(session, "msg-2", true), 3);
        sleep(5);
        service.turnEnd(new MessageTurnContext(session, "msg-2", true));

        Timer latency = registry.find("chat_stream_latency_seconds").tag("brain_tag", "UNKNOWN").timer();
        assertNotNull(latency);
        assertEquals(1, latency.count());

        DistributionSummary tps = registry.find("chat_stream_tokens_per_second").tag("brain_tag", "UNKNOWN").summary();
        assertNotNull(tps);
        assertEquals(1, tps.count());
    }

    @Test
    void nullMessageId_skipsAllRecording() {
        service.turnStart(new MessageTurnContext(null, null, true));
        service.firstToken(new MessageTurnContext(null, null, true));
        service.token(new MessageTurnContext(null, null, true), 10);
        service.turnEnd(new MessageTurnContext(null, null, true));

        assertNull(registry.find("chat_stream_ttft_seconds").timer());
        assertNull(registry.find("chat_stream_latency_seconds").timer());
        assertNull(registry.find("chat_stream_tokens_per_second").summary());
    }

    @Test
    void turnEnd_recordsSuccessCounters() {
        SkillSession session = mockSession(3L, "user-success");
        when(valueOps.get("skill:metrics:stream:start:3")).thenReturn(String.valueOf(System.currentTimeMillis()));

        service.turnStart(new MessageTurnContext(session, "msg-success", true));
        sleep(5);
        service.firstToken(new MessageTurnContext(session, "msg-success", true));
        service.token(new MessageTurnContext(session, "msg-success", true), 4);
        sleep(5);
        service.turnEnd(new MessageTurnContext(session, "msg-success", true));

        assertNotNull(registry.find("chat_stream_turn_total").tag("brain_tag", "UNKNOWN").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_total").tag("brain_tag", "UNKNOWN").counter().count());
        assertNotNull(registry.find("chat_stream_turn_success_total").tag("brain_tag", "UNKNOWN").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_success_total").tag("brain_tag", "UNKNOWN").counter().count());
        assertNull(registry.find("chat_stream_turn_failure_total").tag("brain_tag", "UNKNOWN").counter());
    }

    @Test
    void turnEnd_recordsFailureCounters() {
        SkillSession session = mockSession(4L, "user-fail");
        when(valueOps.get("skill:metrics:stream:start:4")).thenReturn(String.valueOf(System.currentTimeMillis()));

        service.turnStart(new MessageTurnContext(session, "msg-fail", false));
        sleep(5);
        service.turnEnd(new MessageTurnContext(session, "msg-fail", false));

        assertNotNull(registry.find("chat_stream_turn_total").tag("brain_tag", "UNKNOWN").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_total").tag("brain_tag", "UNKNOWN").counter().count());
        assertNotNull(registry.find("chat_stream_turn_failure_total").tag("brain_tag", "UNKNOWN").counter());
        assertEquals(1.0, registry.find("chat_stream_turn_failure_total").tag("brain_tag", "UNKNOWN").counter().count());
        assertNull(registry.find("chat_stream_turn_success_total").tag("brain_tag", "UNKNOWN").counter());
    }

    @Test
    void turnStart_writesStartTimeToRedis() {
        SkillSession session = mockSession(5L, "user-redis");
        service.turnStart(new MessageTurnContext(session, "msg-redis", true));

        verify(valueOps).set(eq("skill:metrics:stream:start:5"), anyString(), any(Duration.class));
    }

    @Test
    void turnEnd_cleansUpRedisKey() {
        SkillSession session = mockSession(6L, "user-clean");
        when(valueOps.get("skill:metrics:stream:start:6")).thenReturn(String.valueOf(System.currentTimeMillis()));

        service.turnStart(new MessageTurnContext(session, "msg-clean", true));
        service.turnEnd(new MessageTurnContext(session, "msg-clean", true));

        verify(redisTemplate).delete("skill:metrics:stream:start:6");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
