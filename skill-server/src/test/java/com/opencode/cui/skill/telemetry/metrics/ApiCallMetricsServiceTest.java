package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiCallMetricsServiceTest {

    private MeterRegistry registry;
    private ApiCallMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ApiCallMetricsService(registry);
    }

    @Test
    void recordApiCallSuccess_incrementsTotalAndSuccessCounters() {
        service.recordApiCall(MetricServiceEnum.IM_GROUP_CHAT, "/v1/chat/app-group-chat", true, 150);

        Counter total = registry.find("external_api_call_total").tag("serviceId", "im_group_chat").counter();
        Counter success = registry.find("external_api_call_success_total").tag("serviceId", "im_group_chat").counter();
        Counter failure = registry.find("external_api_call_failure_total").tag("serviceId", "im_group_chat").counter();

        assertNotNull(total);
        assertEquals(1.0, total.count());
        assertNotNull(success);
        assertEquals(1.0, success.count());
        assertNull(failure);
    }

    @Test
    void recordApiCallFailure_incrementsTotalAndFailureCounters() {
        service.recordApiCall(MetricServiceEnum.GATEWAY_WS_INVOKE, "ws://gateway/ws/skill", false, 50);

        Counter total = registry.find("external_api_call_total").tag("serviceId", "gateway_ws_invoke").counter();
        Counter failure = registry.find("external_api_call_failure_total").tag("serviceId", "gateway_ws_invoke").counter();

        assertNotNull(total);
        assertEquals(1.0, total.count());
        assertNotNull(failure);
        assertEquals(1.0, failure.count());
    }

    @Test
    void recordApiCall_recordsDurationTimer() {
        service.recordApiCall(MetricServiceEnum.IM_MESSAGE_SEND, "/messages/send", true, 200);

        Timer timer = registry.find("external_api_call_duration_seconds").tag("serviceId", "im_message_send").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void recordApiCall_stripsQueryParamsFromUrl() {
        service.recordApiCall(MetricServiceEnum.GATEWAY_AGENTS_LIST,
                "/api/gateway/agents?userId=user123&ak=ak456", true, 100);

        Counter total = registry.find("external_api_call_total")
                .tag("url", "/api/gateway/agents").counter();
        assertNotNull(total);
        assertEquals(1.0, total.count());
    }

    @Test
    void recordApiCall_preservesPathTemplateInUrl() {
        service.recordApiCall(MetricServiceEnum.BUSINESS_CENTER_INSTANCE_QUERY,
                "/instance/query?partnerAccount={account}", true, 80);

        Counter total = registry.find("external_api_call_total")
                .tag("url", "/instance/query").counter();
        assertNotNull(total);
    }

    @Test
    void recordApiCall_includesServiceCommentTag() {
        service.recordApiCall(MetricServiceEnum.TELEMETRY_WELINK_UPLOAD, "/producer", true, 30);

        Counter total = registry.find("external_api_call_total")
                .tag("serviceComment", "WeLink 埋码上报").counter();
        assertNotNull(total);
    }
}