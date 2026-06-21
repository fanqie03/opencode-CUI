package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.*;

class MessageTurnLifecycleTest {

    private SimpleMeterRegistry registry;
    private MessageTurnHandler mockHandler;
    private MessageTurnLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        mockHandler = mock(MessageTurnHandler.class);
        lifecycle = new MessageTurnLifecycle(List.of(mockHandler), registry, 10000, Duration.ofMinutes(30));
    }

    @Test
    void onTurnStart_callsHandlerTurnStart() {
        MessageTurnContext ctx = new MessageTurnContext("msg-1", "brain-A", "sess-1", "assistant-1", "user-1", "brain-A");
        lifecycle.onTurnStart(ctx);
        verify(mockHandler).turnStart(ctx);
    }

    @Test
    void onToken_firstTokenFiresOncePerMessageId() {
        MessageTurnContext ctx = new MessageTurnContext("msg-2", "brain-A", "sess-2", "assistant-2", "user-2", "brain-A");
        lifecycle.onTurnStart(ctx);

        lifecycle.onToken(ctx, 5);
        lifecycle.onToken(ctx, 3);
        lifecycle.onToken(ctx, 7);

        // firstToken must be called exactly once
        verify(mockHandler, times(1)).firstToken(ctx);
        // token should be called for each onToken invocation
        verify(mockHandler, times(3)).token(eq(ctx), anyInt());
    }

    @Test
    void turnEnd_invalidatesCache_allowingFirstTokenToFireAgain() {
        MessageTurnContext ctx = new MessageTurnContext("msg-3", "brain-B", "sess-3", "assistant-3", "user-3", "brain-B");

        // First turn
        lifecycle.onTurnStart(ctx);
        lifecycle.onToken(ctx, 5);
        lifecycle.onTurnEnd(ctx);

        // Second turn with same messageId — firstToken should fire again because cache was invalidated
        lifecycle.onTurnStart(ctx);
        lifecycle.onToken(ctx, 4);

        verify(mockHandler, times(2)).firstToken(ctx);
        verify(mockHandler, times(2)).turnStart(ctx);
        verify(mockHandler, times(1)).turnEnd(ctx);
    }

    @Test
    void onTurnEnd_callsHandlerTurnEnd() {
        MessageTurnContext ctx = new MessageTurnContext("msg-4", "brain-B", "sess-4", "assistant-4", "user-4", "brain-B");
        lifecycle.onTurnStart(ctx);
        lifecycle.onToken(ctx, 5);
        lifecycle.onTurnEnd(ctx);
        verify(mockHandler).turnEnd(ctx);
    }

    @Test
    void multipleHandlers_allCalled() {
        MessageTurnHandler handler1 = mock(MessageTurnHandler.class);
        MessageTurnHandler handler2 = mock(MessageTurnHandler.class);
        MessageTurnLifecycle multiLifecycle = new MessageTurnLifecycle(
                List.of(handler1, handler2), new SimpleMeterRegistry(), 10000, Duration.ofMinutes(30));

        MessageTurnContext ctx = new MessageTurnContext("msg-5", "brain-C", "sess-5", "assistant-5", "user-5", "brain-C");
        multiLifecycle.onTurnStart(ctx);
        multiLifecycle.onToken(ctx, 10);
        multiLifecycle.onTurnEnd(ctx);

        verify(handler1).turnStart(ctx);
        verify(handler2).turnStart(ctx);
        verify(handler1).firstToken(ctx);
        verify(handler2).firstToken(ctx);
        verify(handler1).token(ctx, 10);
        verify(handler2).token(ctx, 10);
        verify(handler1).turnEnd(ctx);
        verify(handler2).turnEnd(ctx);
    }

    @Test
    void emptyHandlerList_doesNotThrow() {
        MessageTurnLifecycle emptyLifecycle = new MessageTurnLifecycle(
                List.of(), new SimpleMeterRegistry(), 10000, Duration.ofMinutes(30));

        MessageTurnContext ctx = new MessageTurnContext("msg-6", "brain-D", "sess-6", "assistant-6", "user-6", "brain-D");
        emptyLifecycle.onTurnStart(ctx);
        emptyLifecycle.onToken(ctx, 5);
        emptyLifecycle.onTurnEnd(ctx);
        // No exception means pass
    }
}
