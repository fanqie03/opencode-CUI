package com.opencode.cui.skill.telemetry.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatFirstTokenTelemetryEventTest {

    private final ChatFirstTokenTelemetryEvent event = new ChatFirstTokenTelemetryEvent(
            "openplatform_service_chat_first_token", "sess-1", "user-1", "assistant-1", "brain-A", "robot-1", "msg-1", 150L);

    @Test
    void eventId_returnsConfiguredEventId() {
        assertEquals("openplatform_service_chat_first_token", event.eventId());
    }

    @Test
    void eventLabel_isFirstTokenArrived() {
        assertEquals("首token到达", event.eventLabel());
    }

    @Test
    void userId_returnsSenderUserAccount() {
        assertEquals("user-1", event.userId());
    }

    @Test
    void sessionId_returnsSessionId() {
        assertEquals("sess-1", event.sessionId());
    }

    @Test
    void extendData_includesBusinessTagAndMessageIdAndAssistantAccount() {
        Map<String, Object> data = event.extendData();
        assertEquals("brain-A", data.get("businessTag"));
        assertEquals("msg-1", data.get("messageId"));
        assertEquals("user-1", data.get("senderUserAccount"));
        assertEquals("assistant-1", data.get("assistantAccount"));
        assertEquals("robot-1", data.get("robotId"));
        assertEquals(150L, data.get("ttftMs"));
        assertNotNull(data.get("ttftReportedAt"));
    }

    @Test
    void nullBusinessTag_usesUnknownFallback() {
        ChatFirstTokenTelemetryEvent e = new ChatFirstTokenTelemetryEvent(
                "openplatform_service_chat_first_token", "sess-1", "user-1", "assistant-1", null, null, "msg-1", 150L);
        assertEquals("UNKNOWN", e.extendData().get("businessTag"));
    }
}
