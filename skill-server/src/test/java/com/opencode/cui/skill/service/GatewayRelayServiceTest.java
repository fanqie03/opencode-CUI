package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayRelayServiceTest {

    @Mock
    private SkillStreamHandler skillStreamHandler;
    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private com.opencode.cui.skill.repository.SkillMessageRepository messageRepository;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private SequenceTracker sequenceTracker;
    @Mock
    private OpenCodeEventTranslator translator;
    @Mock
    private MessagePersistenceService persistenceService;
    @Mock
    private StreamBufferService bufferService;
    @Mock
    private GatewayRelayService.GatewayRelayTarget gatewayRelayTarget;

    private GatewayRelayService service;

    @BeforeEach
    void setUp() {
        service = new GatewayRelayService(
                new ObjectMapper(),
                skillStreamHandler,
                messageService,
                sessionService,
                messageRepository,
                redisMessageBroker,
                sequenceTracker,
                translator,
                persistenceService,
                bufferService);
        service.setGatewayRelayTarget(gatewayRelayTarget);
    }

    @Test
    @DisplayName("tool_event persists and broadcasts to Skill Redis")
    void toolEventPersistsAndBroadcasts() {
        String msg = "{\"type\":\"tool_event\",\"welinkSessionId\":\"123\",\"event\":{\"data\":\"hello\"}}";
        when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .sessionId("ses_internal_1")
                .partId("part-1")
                .content("hello")
                .build());

        service.handleGatewayMessage(msg);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToSession(eq("123"), payloadCaptor.capture());
        assertTrue(payloadCaptor.getValue().contains("\"welinkSessionId\":\"123\""));
        verify(bufferService).accumulate(eq("123"), any(StreamMessage.class));
        verify(persistenceService).persistIfFinal(eq(123L), any(StreamMessage.class));
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("tool_done broadcasts via Skill Redis")
    void toolDoneBroadcasts() {
        String msg = "{\"type\":\"tool_done\",\"welinkSessionId\":\"42\",\"usage\":{\"tokens\":100}}";

        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("42"), contains("session.status"));
        verify(bufferService).accumulate(eq("42"), any(StreamMessage.class));
        verify(persistenceService).persistIfFinal(eq(42L), any(StreamMessage.class));
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("tool_error persists and broadcasts via Skill Redis")
    void toolErrorPersistsAndBroadcasts() {
        String msg = "{\"type\":\"tool_error\",\"welinkSessionId\":\"42\",\"error\":\"timeout\"}";

        service.handleGatewayMessage(msg);

        verify(messageService).saveSystemMessage(eq(42L), contains("timeout"));
        verify(redisMessageBroker).publishToSession(eq("42"), contains("error"));
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("agent_online broadcasts to all agent sessions via Redis")
    void agentOnlineBroadcastsToSessions() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        when(sessionService.findByAk("99")).thenReturn(java.util.List.of(session));

        String msg = "{\"type\":\"agent_online\",\"ak\":\"99\",\"toolType\":\"channel\",\"toolVersion\":\"1.0\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("1"), contains("agent.online"));
    }

    @Test
    @DisplayName("agent_offline broadcasts to all agent sessions via Redis")
    void agentOfflineBroadcastsToSessions() {
        SkillSession session = new SkillSession();
        session.setId(2L);
        when(sessionService.findByAk("99")).thenReturn(java.util.List.of(session));

        String msg = "{\"type\":\"agent_offline\",\"ak\":\"99\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("2"), contains("agent.offline"));
    }

    @Test
    @DisplayName("session_created updates toolSessionId")
    void sessionCreatedUpdatesToolSessionId() {
        String msg = "{\"type\":\"session_created\",\"ak\":\"1\",\"welinkSessionId\":\"42\",\"toolSessionId\":\"ts-abc\"}";

        service.handleGatewayMessage(msg);

        verify(sessionService).updateToolSessionId(eq(42L), eq("ts-abc"));
    }

    @Test
    @DisplayName("permission_request broadcasts via Redis")
    void permissionRequestBroadcasts() {
        when(translator.translatePermissionFromGateway(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_ASK)
                .permissionId("p-1")
                .build());

        String msg = "{\"type\":\"permission_request\",\"sessionId\":\"42\",\"permissionId\":\"p-1\",\"command\":\"rm -rf /\",\"workingDirectory\":\"/tmp\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("42"), contains("permission.ask"));
    }

    @Test
    @DisplayName("tool_event with toolSessionId resolves via DB lookup")
    void toolEventLooksUpWelinkSessionId() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        when(sessionService.findByToolSessionId("ts-abc")).thenReturn(session);
        when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .partId("part-1")
                .content("hello")
                .build());

        // Message has toolSessionId but NO welinkSessionId
        String msg = "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-abc\",\"event\":{\"data\":\"hello\"}}";
        service.handleGatewayMessage(msg);

        verify(sessionService).findByToolSessionId("ts-abc");
        verify(redisMessageBroker).publishToSession(eq("42"), contains("text.delta"));
    }

    @Test
    @DisplayName("unknown type logs warning without errors")
    void unknownTypeLogsWarning() {
        String msg = "{\"type\":\"unknown_type\",\"welinkSessionId\":\"42\"}";

        service.handleGatewayMessage(msg);

        verifyNoInteractions(skillStreamHandler);
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("malformed JSON does not throw")
    void malformedJsonDoesNotThrow() {
        service.handleGatewayMessage("not json at all");
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("tool_event with missing welinkSessionId logs warning")
    void toolEventMissingWelinkSessionId() {
        String msg = "{\"type\":\"tool_event\",\"event\":{\"data\":\"hello\"}}";

        service.handleGatewayMessage(msg);

        verifyNoInteractions(skillStreamHandler);
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("sendInvokeToGateway uses active WS connection")
    void sendInvokeUsesGatewayWs() {
        when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

        service.sendInvokeToGateway("agent-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayRelayTarget).sendToGateway(contains("invoke"));
        verify(redisMessageBroker, never()).publishToSession(any(), any());
    }

    @Test
    @DisplayName("sendInvokeToGateway drops when no active connection")
    void sendInvokeDropsWhenNoActiveConnection() {
        when(gatewayRelayTarget.hasActiveConnection()).thenReturn(false);

        service.sendInvokeToGateway("agent-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayRelayTarget, never()).sendToGateway(any());
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("sendInvokeToGateway logs failed send without redis fallback")
    void sendInvokeDoesNotFallbackToRedis() {
        when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        when(gatewayRelayTarget.sendToGateway(any())).thenReturn(false);

        service.sendInvokeToGateway("agent-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayRelayTarget).sendToGateway(contains("invoke"));
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("subscribeToSessionBroadcast delegates to RedisMessageBroker")
    void subscribeToSessionBroadcastDelegatesToBroker() {
        service.subscribeToSessionBroadcast("42");
        verify(redisMessageBroker).subscribeToSession(eq("42"), any());
    }

    @Test
    @DisplayName("unsubscribeFromSession delegates and resets tracker")
    void unsubscribeFromSessionDelegatesAndResets() {
        service.unsubscribeFromSession("42");
        verify(redisMessageBroker).unsubscribeFromSession(eq("42"));
        verify(sequenceTracker).resetSession(eq("42"));
    }
}
