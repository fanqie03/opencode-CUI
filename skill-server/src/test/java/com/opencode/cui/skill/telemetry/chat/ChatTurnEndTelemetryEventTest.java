package com.opencode.cui.skill.telemetry.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatTurnEndTelemetryEventTest {

    private final ChatTurnEndTelemetryEvent event = new ChatTurnEndTelemetryEvent(
            "sess-1", "user-1", "assistant-1", "brain-A", "msg-1", 42, 3500L, true);

    @Test
    void eventId_isSkillChatTurnEnd() {
        assertEquals("skill_chat_turn_end", event.eventId());
    }

    @Test
    void eventLabel_isTurnEnd() {
        assertEquals("对话轮次结束", event.eventLabel());
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
    void extendData_includesAllMetrics() {
        Map<String, Object> data = event.extendData();
        assertEquals("msg-1", data.get("messageId"));
        assertEquals("user-1", data.get("senderUserAccount"));
        assertEquals("assistant-1", data.get("assistantAccount"));
        assertEquals("brain-A", data.get("businessTag"));
        assertEquals(42, data.get("contentLength"));
        assertEquals(3500L, data.get("durationMs"));
        assertEquals(true, data.get("success"));
        assertNotNull(data.get("turnEndReportedAt"));
    }

    @Test
    void nullBusinessTag_usesUnknownFallback() {
        ChatTurnEndTelemetryEvent e = new ChatTurnEndTelemetryEvent(
                "sess-1", "user-1", "assistant-1", null, "msg-1", 0, 100L, false);
        assertEquals("UNKNOWN", e.extendData().get("businessTag"));
    }

    @Test
    void failedTurn_successIsFalse() {
        ChatTurnEndTelemetryEvent e = new ChatTurnEndTelemetryEvent(
                "sess-1", "user-1", "assistant-1", "brain-A", "msg-fail", 0, 500L, false);
        assertEquals(false, e.extendData().get("success"));
    }
}
