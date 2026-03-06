package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.ws.GatewayWSHandler;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for GatewayRelayService v1 protocol (方案5) message routing.
 */
@ExtendWith(MockitoExtension.class)
class GatewayRelayServiceTest {

    @Mock
    private SkillStreamHandler skillStreamHandler;
    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private SequenceTracker sequenceTracker;
    @Mock
    private GatewayWSHandler gatewayWSHandler;

    private ObjectMapper objectMapper;
    private GatewayRelayService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new GatewayRelayService(
                objectMapper, skillStreamHandler, messageService,
                sessionService, redisMessageBroker, sequenceTracker,
                gatewayWSHandler);
    }

    // ==================== Upstream: Gateway �?Skill ====================

    @Test
    @DisplayName("tool_event persists and broadcasts to Skill Redis")
    void toolEventPersistsAndBroadcasts() {
        String msg = "{\"type\":\"tool_event\",\"sessionId\":\"123\",\"event\":{\"data\":\"hello\"}}";
        service.handleGatewayMessage(msg);

        // Should persist to DB
        verify(messageService).saveAssistantMessage(eq(123L), contains("hello"), isNull());
        // Should broadcast via Redis (not direct pushToSession)
        verify(redisMessageBroker).publishToSession(eq("123"), contains("delta"));
        // Should NOT call pushToSession directly (that's done by the broadcast
        // callback)
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("tool_done broadcasts via Skill Redis")
    void toolDoneBroadcasts() {
        String msg = "{\"type\":\"tool_done\",\"sessionId\":\"42\",\"usage\":{\"tokens\":100}}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("42"), contains("done"));
        verifyNoInteractions(skillStreamHandler);
    }

    @Test
    @DisplayName("tool_error persists and broadcasts via Skill Redis")
    void toolErrorPersistsAndBroadcasts() {
        String msg = "{\"type\":\"tool_error\",\"sessionId\":\"42\",\"error\":\"timeout\"}";
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
        when(sessionService.findByAgentId(99L)).thenReturn(java.util.List.of(session));

        String msg = "{\"type\":\"agent_online\",\"agentId\":\"99\",\"toolType\":\"opencode\",\"toolVersion\":\"1.0\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("1"), contains("agent_online"));
    }

    @Test
    @DisplayName("agent_offline broadcasts to all agent sessions via Redis")
    void agentOfflineBroadcastsToSessions() {
        SkillSession session = new SkillSession();
        session.setId(2L);
        when(sessionService.findByAgentId(99L)).thenReturn(java.util.List.of(session));

        String msg = "{\"type\":\"agent_offline\",\"agentId\":\"99\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("2"), contains("agent_offline"));
    }

    @Test
    @DisplayName("session_created updates toolSessionId")
    void sessionCreatedUpdatesToolSessionId() {
        String msg = "{\"type\":\"session_created\",\"agentId\":\"1\",\"sessionId\":\"42\",\"toolSessionId\":\"ts-abc\"}";
        service.handleGatewayMessage(msg);

        verify(sessionService).updateToolSessionId(eq(42L), eq("ts-abc"));
    }

    @Test
    @DisplayName("permission_request broadcasts via Redis")
    void permissionRequestBroadcasts() {
        String msg = "{\"type\":\"permission_request\",\"sessionId\":\"42\",\"permissionId\":\"p-1\",\"command\":\"rm -rf /\",\"workingDirectory\":\"/tmp\"}";
        service.handleGatewayMessage(msg);

        verify(redisMessageBroker).publishToSession(eq("42"), contains("permission_request"));
    }

    @Test
    @DisplayName("unknown type logs warning without errors")
    void unknownTypeLogsWarning() {
        String msg = "{\"type\":\"unknown_type\",\"sessionId\":\"42\"}";
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
    @DisplayName("tool_event with missing sessionId logs warning")
    void toolEventMissingSessionId() {
        String msg = "{\"type\":\"tool_event\",\"event\":{\"data\":\"hello\"}}";
        service.handleGatewayMessage(msg);
        verifyNoInteractions(skillStreamHandler);
        verifyNoInteractions(redisMessageBroker);
    }

    // ==================== Downstream: Skill �?Gateway ====================

    @Test
    @DisplayName("sendInvokeToGateway uses WS direct path when available")
    void sendInvokeUsesWSDirectPath() {
        when(gatewayWSHandler.hasActiveConnection()).thenReturn(true);
        when(gatewayWSHandler.sendToGateway(anyString())).thenReturn(true);

        service.sendInvokeToGateway("agent-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayWSHandler).sendToGateway(contains("invoke"));
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("sendInvokeToGateway falls back to invoke_relay when no WS")
    void sendInvokeFallsBackToInvokeRelay() {
        when(gatewayWSHandler.hasActiveConnection()).thenReturn(false);

        service.sendInvokeToGateway("agent-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(redisMessageBroker).publishInvokeRelay(eq("agent-1"), contains("invoke"));
    }

    @Test
    @DisplayName("sendInvokeToGateway falls back when WS send fails")
    void sendInvokeFallsBackOnWSSendFailure() {
        when(gatewayWSHandler.hasActiveConnection()).thenReturn(true);
        when(gatewayWSHandler.sendToGateway(anyString())).thenReturn(false);

        service.sendInvokeToGateway("agent-1", "session-1", "chat", "{\"text\":\"hello\"}");

        verify(gatewayWSHandler).sendToGateway(contains("invoke"));
        verify(redisMessageBroker).publishInvokeRelay(eq("agent-1"), contains("invoke"));
    }

    // ==================== Redis Subscriptions ====================

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

    @Test
    @DisplayName("subscribeToInvokeRelay delegates to RedisMessageBroker")
    void subscribeToInvokeRelayDelegatesToBroker() {
        service.subscribeToInvokeRelay("agent-1");
        verify(redisMessageBroker).subscribeInvokeRelay(eq("agent-1"), any());
    }
}
