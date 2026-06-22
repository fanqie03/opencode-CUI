package com.opencode.cui.skill.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import com.opencode.cui.skill.model.SyncType;
import com.opencode.cui.skill.service.RedisMessageBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WsMultiDeviceSyncServiceTest {

    @Mock
    private RedisMessageBroker broker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WsMultiDeviceSyncService service;

    @BeforeEach
    void setUp() {
        service = new WsMultiDeviceSyncService(broker, objectMapper);
    }

    @Test
    @DisplayName("getSyncMode returns WS")
    void getSyncModeReturnsWs() {
        assertEquals(SyncMode.WS, service.getSyncMode());
    }

    @Test
    @DisplayName("publishes JSON with type, sessionId, and content to user stream")
    void publishesCorrectEnvelope() {
        SyncRequest request = new SyncRequest(
                SyncMode.WS,
                SyncType.SESSION_UNREAD,
                Map.of("welinkSessionId", "123", "unreadCount", 1, "maxSeq", 15),
                "user-1");

        service.push(request);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(broker).publishToUser(eq("user-1"), captor.capture());
        String message = captor.getValue();

        assertTrue(message.contains("\"type\":\"session.unread\""));
        assertTrue(message.contains("\"sessionId\":\"123\""));
        assertTrue(message.contains("\"unreadCount\":1"));
        assertTrue(message.contains("\"maxSeq\":15"));
    }

    @Test
    @DisplayName("omits sessionId when welinkSessionId not in content")
    void omitsSessionIdWhenAbsent() {
        SyncRequest request = new SyncRequest(
                SyncMode.WS,
                SyncType.SESSION_UNREAD,
                Map.of("other", "value"),
                "user-1");

        service.push(request);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(broker).publishToUser(eq("user-1"), captor.capture());
        assertTrue(captor.getValue().contains("\"type\":\"session.unread\""));
    }
}
