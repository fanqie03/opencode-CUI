package com.opencode.cui.gateway.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncSessionSenderTest {

    @Test
    @DisplayName("enqueue drains on the sender worker")
    void enqueueDrainsOnSenderWorker() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("link-1");
        when(session.isOpen()).thenReturn(true);
        AsyncSessionSender sender = new AsyncSessionSender(session, 10);

        try {
            sender.start();
            boolean accepted = sender.enqueue(new TextMessage("hello"));

            assertTrue(accepted);
            ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(session, timeout(1000)).sendMessage(messageCaptor.capture());
            assertEquals("hello", messageCaptor.getValue().getPayload());
            assertTrue(sender.isRunning());
        } finally {
            sender.shutdown();
        }
    }

    @Test
    @DisplayName("queue full rejects message")
    void queueFullRejectsMessage() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("link-1");
        AsyncSessionSender sender = new AsyncSessionSender(session, 1);

        assertTrue(sender.enqueue(new TextMessage("one")));
        assertFalse(sender.enqueue(new TextMessage("two")));
    }

    @Test
    @DisplayName("session close marks sender failed")
    void sessionCloseMarksSenderFailed() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("link-1");
        when(session.isOpen()).thenReturn(false);
        AtomicBoolean failureNotified = new AtomicBoolean(false);
        CountDownLatch failureLatch = new CountDownLatch(1);
        AsyncSessionSender sender = new AsyncSessionSender(session, 10, () -> {
            failureNotified.set(true);
            failureLatch.countDown();
        });

        sender.start();
        assertTrue(sender.enqueue(new TextMessage("hello")));
        assertTrue(failureLatch.await(1, TimeUnit.SECONDS));
        assertFalse(sender.isRunning());
        assertTrue(failureNotified.get());
    }

    @Test
    @DisplayName("factory reuses one sender per websocket session")
    void factoryReusesOneSenderPerSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("link-1");
        when(session.isOpen()).thenReturn(true);
        AsyncSessionSenderFactory factory = new AsyncSessionSenderFactory(10);

        AsyncSessionSender first = factory.getOrCreate(session);
        AsyncSessionSender second = factory.getOrCreate(session, () -> {
        });

        assertSame(first, second);
        factory.remove("link-1");
    }

    @Test
    @DisplayName("factory updates failure handler while reusing sender")
    void factoryUpdatesFailureHandlerWhileReusingSender() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.getId()).thenReturn("link-1");
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        AsyncSessionSenderFactory factory = new AsyncSessionSenderFactory(10);
        AtomicBoolean firstFailureHandlerCalled = new AtomicBoolean(false);
        AtomicBoolean secondFailureHandlerCalled = new AtomicBoolean(false);
        CountDownLatch secondFailureLatch = new CountDownLatch(1);

        AsyncSessionSender first = factory.getOrCreate(session, () -> firstFailureHandlerCalled.set(true));
        AsyncSessionSender second = factory.getOrCreate(session, () -> {
            secondFailureHandlerCalled.set(true);
            secondFailureLatch.countDown();
        });

        assertSame(first, second);
        open.set(false);
        assertTrue(second.enqueue(new TextMessage("hello")));
        assertTrue(secondFailureLatch.await(1, TimeUnit.SECONDS));
        assertFalse(firstFailureHandlerCalled.get());
        assertTrue(secondFailureHandlerCalled.get());
        assertFalse(second.isRunning());
    }

    @Test
    @DisplayName("send runtime exception marks sender failed and removes factory entry")
    void sendRuntimeExceptionMarksSenderFailedAndRemovesFactoryEntry() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean failureHandlerCalled = new AtomicBoolean(false);
        when(session.getId()).thenReturn("link-1");
        when(session.isOpen()).thenReturn(true);
        doThrow(new IllegalStateException("session not writable"))
                .when(session).sendMessage(any(TextMessage.class));
        AsyncSessionSenderFactory factory = new AsyncSessionSenderFactory(10);
        CountDownLatch failureLatch = new CountDownLatch(1);

        AsyncSessionSender sender = factory.getOrCreate(session, () -> {
            failureHandlerCalled.set(true);
            failureLatch.countDown();
        });

        assertTrue(sender.enqueue(new TextMessage("hello")));
        assertTrue(failureLatch.await(1, TimeUnit.SECONDS));
        assertFalse(sender.isRunning());
        assertTrue(failureHandlerCalled.get());
        assertNull(factory.get("link-1"));
        assertFalse(sender.enqueue(new TextMessage("again")));
    }
}
