package com.opencode.cui.skill.telemetry.metrics;

import com.opencode.cui.skill.model.SkillSession;
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

    private SkillSession mockSession(Long id) {
        SkillSession session = new SkillSession();
        session.setId(id);
        session.setUserId("user-" + id);
        return session;
    }

    @Test
    void onTurnStart_callsHandlerTurnStart() {
        MessageTurnContext ctx = new MessageTurnContext(mockSession(1L), "msg-1", true);
        lifecycle.onTurnStart(ctx);
        verify(mockHandler).turnStart(ctx);
    }

    @Test
    void onToken_firstTokenFiresOncePerSessionId() {
        SkillSession session = mockSession(2L);
        MessageTurnContext ctx = new MessageTurnContext(session, "msg-2", true);
        lifecycle.onTurnStart(ctx);

        lifecycle.onToken(ctx, 5);
        lifecycle.onToken(ctx, 3);
        lifecycle.onToken(ctx, 7);

        verify(mockHandler, times(1)).firstToken(ctx);
        verify(mockHandler, times(3)).token(eq(ctx), anyInt());
    }

    @Test
    void turnEnd_invalidatesCache_allowingFirstTokenToFireAgain() {
        SkillSession session = mockSession(3L);
        MessageTurnContext ctx = new MessageTurnContext(session, "msg-3", true);

        lifecycle.onTurnStart(ctx);
        lifecycle.onToken(ctx, 5);
        lifecycle.onTurnEnd(ctx);

        lifecycle.onTurnStart(ctx);
        lifecycle.onToken(ctx, 4);

        verify(mockHandler, times(2)).firstToken(ctx);
        verify(mockHandler, times(2)).turnStart(ctx);
        verify(mockHandler, times(1)).turnEnd(ctx);
    }

    @Test
    void onTurnEnd_callsHandlerTurnEnd() {
        SkillSession session = mockSession(4L);
        MessageTurnContext ctx = new MessageTurnContext(session, "msg-4", false);
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

        MessageTurnContext ctx = new MessageTurnContext(mockSession(5L), "msg-5", true);
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

        MessageTurnContext ctx = new MessageTurnContext(mockSession(6L), "msg-6", true);
        emptyLifecycle.onTurnStart(ctx);
        emptyLifecycle.onToken(ctx, 5);
        emptyLifecycle.onTurnEnd(ctx);
    }
}
