package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiMetricsInterceptorTest {

    private MeterRegistry registry;
    private ApiMetricsInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        interceptor = new ApiMetricsInterceptor(registry);
    }

    @Test
    void postHandle_recordsTimerWithUrlTag() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/skill/sessions/123/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        Thread.sleep(10);
        interceptor.postHandle(request, response, new Object(), null);

        Timer timer = registry.find("common_interface_duration_seconds").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void preHandle_setsStartTimeAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        assertNotNull(request.getAttribute("metrics.startTime"));
        assertTrue((long) request.getAttribute("metrics.startTime") > 0);
    }
}