package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.StreamBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillStreamHandlerTest {

    private SkillStreamHandler handler;
    private ObjectMapper objectMapper;
    private StreamBufferService bufferService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        bufferService = mock(StreamBufferService.class);
        handler = new SkillStreamHandler(objectMapper, bufferService);
    }

    @Test
    @DisplayName("afterConnectionEstablished registers subscriber")
    void connectionEstablishedRegistersSubscriber() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("/ws/skill/stream/42"));
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);

        // Push should reach this subscriber
        handler.pushToSession("42", "delta", "hello");
        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("pushToSession sends to multiple clients")
    void pushToMultipleClients() throws Exception {
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session1.getUri()).thenReturn(new URI("/ws/skill/stream/42"));
        when(session2.getUri()).thenReturn(new URI("/ws/skill/stream/42"));
        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);

        handler.pushToSession("42", "delta", "content");
        verify(session1).sendMessage(any(TextMessage.class));
        verify(session2).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("pushToSession with no subscribers does not throw")
    void pushToNoSubscribers() {
        // No sessions registered for this sessionId
        handler.pushToSession("999", "delta", "content");
        // Should not throw �?just log
    }

    @Test
    @DisplayName("afterConnectionClosed removes subscriber")
    void connectionClosedRemovesSubscriber() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("/ws/skill/stream/42"));
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, null);

        // Reset to track further interactions
        reset(session);
        handler.pushToSession("42", "delta", "content");
        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("pushToSession skips closed sessions gracefully")
    void pushSkipsClosedSessions() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("/ws/skill/stream/42"));
        when(session.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(session);
        handler.pushToSession("42", "delta", "content");

        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("connection with missing sessionId in URI is handled")
    void connectionWithMissingSessionId() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("/ws/skill/stream/"));

        handler.afterConnectionEstablished(session);
        verify(session).close(any());
    }
}
