package com.opencode.cui.skill.telemetry.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatFirstTokenTelemetryEventTest {

    private final ChatFirstTokenTelemetryEvent event = new ChatFirstTokenTelemetryEvent(
            "sess-1", "assistant-1", "brain-A", "msg-1", 150L);

    @Test
    void eventId_isSkillChatFirstToken() {
        assertEquals("skill_chat_first_token", event.eventId());
    }

    @Test
    void eventLabel_isFirstTokenArrived() {
        assertEquals("首token到达", event.eventLabel());
    }

    @Test
    void userId_returnsAssistantAccount() {
        assertEquals("assistant-1", event.userId());
    }

    @Test
    void sessionId_returnsSessionId() {
        assertEquals("sess-1", event.sessionId());
    }

    @Test
    void extendData_includesBusinessTagAndMessageId() {
        Map<String, Object> data = event.extendData();
        assertEquals("brain-A", data.get("businessTag"));
        assertEquals("msg-1", data.get("messageId"));
        assertNotNull(data.get("ttftReportedAt"));
    }

    @Test
    void nullBusinessTag_usesUnknownFallback() {
        ChatFirstTokenTelemetryEvent e = new ChatFirstTokenTelemetryEvent(
                "sess-1", "assistant-1", null, "msg-1", 150L);
        assertEquals("UNKNOWN", e.extendData().get("businessTag"));
    }
}