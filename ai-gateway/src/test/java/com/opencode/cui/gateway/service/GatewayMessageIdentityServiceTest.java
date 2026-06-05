package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayMessageIdentityServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayMessageIdentityService service = new GatewayMessageIdentityService();

    @Test
    void normalizePromotesEventPropertiesMessageId() throws Exception {
        GatewayMessage event = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .traceId("trace-1")
                .event(objectMapper.readTree("""
                        {"type":"text.delta","properties":{"messageId":"msg-1","content":"hi"}}
                        """))
                .build();

        GatewayMessage normalized = service.normalizeForSkillRelay(event);

        assertEquals("msg-1", normalized.getMessageId());
        assertEquals("msg-1", normalized.getEvent().path("properties").path("messageId").asText());
    }

    @Test
    void normalizeRecoversTerminalMessageIdByTraceId() throws Exception {
        GatewayMessage event = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .traceId("trace-1")
                .event(objectMapper.readTree("""
                        {"type":"text.delta","properties":{"messageId":"msg-1","content":"hi"}}
                        """))
                .build();
        service.normalizeForSkillRelay(event);

        GatewayMessage done = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_DONE)
                .traceId("trace-1")
                .toolSessionId("tool-1")
                .build();

        GatewayMessage normalized = service.normalizeForSkillRelay(done);

        assertEquals("msg-1", normalized.getMessageId());
    }

    @Test
    void normalizeGeneratesTraceIdButDoesNotInventMessageId() {
        GatewayMessage error = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .toolSessionId("tool-1")
                .error("boom")
                .build();

        GatewayMessage normalized = service.normalizeForSkillRelay(error);

        assertNotNull(normalized.getTraceId());
        assertTrue(normalized.getTraceId().length() > 0);
        assertNull(normalized.getMessageId());
    }
}
