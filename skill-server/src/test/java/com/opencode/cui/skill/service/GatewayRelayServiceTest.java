package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayRelayServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        String msg = "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":123,\"event\":{\"data\":\"hello\"}}";
        when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .sessionId("ses_internal_1")
                .partId("part-1")
                .content("hello")
                .build());

        service.handleGatewayMessage(msg);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-1"), payloadCaptor.capture());
        JsonNode published = readPublishedMessage(payloadCaptor.getValue());
        assertEquals("123", published.path("sessionId").asText());
        assertEquals(123L, published.path("message").path("welinkSessionId").asLong());
        verify(bufferService).accumulate(eq("123"), any(StreamMessage.class));
        verify(persistenceService).persistIfFinal(eq(123L), any(StreamMessage.class));
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("tool_done broadcasts via Skill Redis")
    void toolDoneBroadcasts() {
        String msg = "{\"type\":\"tool_done\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"usage\":{\"tokens\":100}}";

        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToUser(eq("user-1"), contains("session.status"));
        verify(bufferService).accumulate(eq("42"), any(StreamMessage.class));
        verify(persistenceService).persistIfFinal(eq(42L), any(StreamMessage.class));
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("tool event activation broadcasts busy status")
    void toolEventActivationBroadcastsBusyStatus() {
        when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .partId("part-1")
                .content("hello")
                .build());
        when(sessionService.activateSession(123L)).thenReturn(true);

        service.handleGatewayMessage("{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":123,\"event\":{\"data\":\"hello\"}}");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker, org.mockito.Mockito.atLeast(2)).publishToUser(eq("user-1"), payloadCaptor.capture());
        assertTrue(payloadCaptor.getAllValues().stream().anyMatch(payload -> payload.contains("\"sessionStatus\":\"busy\"")));
    }

    @Test
    @DisplayName("session rebuild broadcasts retry status")
    void sessionRebuildBroadcastsRetryStatus() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("agent-1");
        session.setUserId("user-42");
        session.setTitle("demo");
        when(sessionService.getSession(42L)).thenReturn(session);
        when(messageRepository.findLastUserMessage(42L)).thenReturn(null);
        when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

        service.handleGatewayMessage("{\"type\":\"tool_error\",\"welinkSessionId\":42,\"error\":\"session_not_found\"}");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-42"), payloadCaptor.capture());
        JsonNode published = readPublishedMessage(payloadCaptor.getValue());
        assertEquals("retry", published.path("message").path("sessionStatus").asText());
        assertEquals(42L, published.path("message").path("welinkSessionId").asLong());
    }

    @Test
    @DisplayName("tool_error persists and broadcasts via Skill Redis")
    void toolErrorPersistsAndBroadcasts() {
        String msg = "{\"type\":\"tool_error\",\"userId\":\"user-42\",\"welinkSessionId\":42,\"error\":\"timeout\"}";

        service.handleGatewayMessage(msg);

        verify(messageService).saveSystemMessage(eq(42L), contains("timeout"));
        verify(redisMessageBroker).publishToUser(eq("user-42"), contains("error"));
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("agent_online broadcasts to all agent sessions via Redis")
    void agentOnlineBroadcastsToSessions() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setUserId("user-1");
        when(sessionService.findByAk("99")).thenReturn(java.util.List.of(session));

        String msg = "{\"type\":\"agent_online\",\"ak\":\"99\",\"toolType\":\"channel\",\"toolVersion\":\"1.0\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToUser(eq("user-1"), contains("agent.online"));
    }

    @Test
    @DisplayName("agent_offline broadcasts to all agent sessions via Redis")
    void agentOfflineBroadcastsToSessions() {
        SkillSession session = new SkillSession();
        session.setId(2L);
        session.setUserId("user-2");
        when(sessionService.findByAk("99")).thenReturn(java.util.List.of(session));

        String msg = "{\"type\":\"agent_offline\",\"ak\":\"99\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToUser(eq("user-2"), contains("agent.offline"));
    }

    @Test
    @DisplayName("session_created updates toolSessionId")
    void sessionCreatedUpdatesToolSessionId() {
        String msg = "{\"type\":\"session_created\",\"ak\":\"1\",\"welinkSessionId\":42,\"toolSessionId\":\"ts-abc\"}";

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

        String msg = "{\"type\":\"permission_request\",\"userId\":\"user-42\",\"welinkSessionId\":42,\"permissionId\":\"p-1\",\"command\":\"rm -rf /\",\"workingDirectory\":\"/tmp\"}";
        service.handleGatewayMessage(msg);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-42"), payloadCaptor.capture());
        JsonNode published = readPublishedMessage(payloadCaptor.getValue());
        assertEquals("permission.ask", published.path("message").path("type").asText());
        assertEquals(42L, published.path("message").path("welinkSessionId").asLong());
    }

    @Test
    @DisplayName("tool_event with toolSessionId resolves via DB lookup")
    void toolEventLooksUpWelinkSessionId() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setUserId("user-42");
        when(sessionService.findByToolSessionId("ts-abc")).thenReturn(session);
        when(sessionService.getSession(42L)).thenReturn(session);
        when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .partId("part-1")
                .content("hello")
                .build());

        // Message has toolSessionId but NO welinkSessionId
        String msg = "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-abc\",\"event\":{\"data\":\"hello\"}}";
        service.handleGatewayMessage(msg);

        verify(sessionService).findByToolSessionId("ts-abc");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-42"), payloadCaptor.capture());
        JsonNode published = readPublishedMessage(payloadCaptor.getValue());
        assertEquals("42", published.path("sessionId").asText());
        assertEquals(42L, published.path("message").path("welinkSessionId").asLong());
    }

    @Test
    @DisplayName("unknown type logs warning without errors")
    void unknownTypeLogsWarning() {
        String msg = "{\"type\":\"unknown_type\",\"welinkSessionId\":42}";

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

        service.sendInvokeToGateway("agent-1", "user-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayRelayTarget).sendToGateway(contains("invoke"));
        verify(redisMessageBroker, never()).publishToUser(any(), any());
    }

    @Test
    @DisplayName("sendInvokeToGateway serializes string welinkSessionId for create_session")
    void sendInvokeSerializesNumericWelinkSessionId() {
        when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

        service.sendInvokeToGateway("agent-1", "user-1", "42", "create_session", "{\"title\":\"demo\"}");

        // welinkSessionId 应作为字符串序列化，防止 JavaScript IEEE 754 大数精度丢失
        verify(gatewayRelayTarget).sendToGateway(contains("\"welinkSessionId\":\"42\""));
    }

    @Test
    @DisplayName("sendInvokeToGateway drops when no active connection")
    void sendInvokeDropsWhenNoActiveConnection() {
        when(gatewayRelayTarget.hasActiveConnection()).thenReturn(false);

        service.sendInvokeToGateway("agent-1", "user-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayRelayTarget, never()).sendToGateway(any());
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("sendInvokeToGateway logs failed send without redis fallback")
    void sendInvokeDoesNotFallbackToRedis() {
        when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
        when(gatewayRelayTarget.sendToGateway(any())).thenReturn(false);

        service.sendInvokeToGateway("agent-1", "user-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayRelayTarget).sendToGateway(contains("invoke"));
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("tool_event resolves userId from session when message omits it")
    void toolEventResolvesUserIdFromSession() {
        SkillSession session = new SkillSession();
        session.setId(123L);
        session.setUserId("user-123");
        when(sessionService.getSession(123L)).thenReturn(session);
        when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .partId("part-1")
                .content("hello")
                .build());

        service.handleGatewayMessage("{\"type\":\"tool_event\",\"welinkSessionId\":123,\"event\":{\"data\":\"hello\"}}");

        verify(redisMessageBroker).publishToUser(eq("user-123"), contains("text.delta"));
    }

    @Test
    @DisplayName("tool_done with toolSessionId resolves via DB lookup and publishes welinkSessionId")
    void toolDoneUsesRecoveredSessionAffinity() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setUserId("user-42");
        when(sessionService.findByToolSessionId("ts-abc")).thenReturn(session);
        when(sessionService.getSession(42L)).thenReturn(session);

        service.handleGatewayMessage("{\"type\":\"tool_done\",\"toolSessionId\":\"ts-abc\"}");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-42"), payloadCaptor.capture());
        JsonNode published = readPublishedMessage(payloadCaptor.getValue());
        assertEquals("42", published.path("sessionId").asText());
        assertEquals(42L, published.path("message").path("welinkSessionId").asLong());
        assertEquals("idle", published.path("message").path("sessionStatus").asText());
    }

    @Test
    @DisplayName("tool_error with unresolved toolSessionId is dropped without side effects")
    void toolErrorWithoutResolvedSessionIsDropped() {
        when(sessionService.findByToolSessionId("missing")).thenReturn(null);

        service.handleGatewayMessage("{\"type\":\"tool_error\",\"toolSessionId\":\"missing\",\"error\":\"timeout\"}");

        verify(redisMessageBroker, never()).publishToUser(any(), any());
        verify(messageService, never()).saveSystemMessage(any(), any());
        verify(persistenceService, never()).finalizeActiveAssistantTurn(any());
    }

    @Test
    @DisplayName("tool_event publishes only to the user owning the resolved session")
    void toolEventPublishesOnlyToResolvedSessionOwner() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setUserId("user-a");
        when(sessionService.findByToolSessionId("ts-a")).thenReturn(session);
        when(sessionService.getSession(42L)).thenReturn(session);
        when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .sessionId("999")
                .partId("part-1")
                .content("hello")
                .build());

        service.handleGatewayMessage("{\"type\":\"tool_event\",\"toolSessionId\":\"ts-a\",\"event\":{\"data\":\"hello\"}}");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-a"), payloadCaptor.capture());
        JsonNode published = readPublishedMessage(payloadCaptor.getValue());
        assertEquals("42", published.path("sessionId").asText());
        assertEquals(42L, published.path("message").path("welinkSessionId").asLong());
    }

    private JsonNode readPublishedMessage(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse published payload", e);
        }
    }
}
