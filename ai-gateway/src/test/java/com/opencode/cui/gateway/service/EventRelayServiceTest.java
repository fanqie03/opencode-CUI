package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EventRelayService v1 protocol routing (方案5).
 */
@ExtendWith(MockitoExtension.class)
class EventRelayServiceTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private WebSocketSession wsSession;
    @Mock
    private EventRelayService.SkillServerRelayTarget skillServerRelay;

    private ObjectMapper objectMapper;
    private EventRelayService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new EventRelayService(objectMapper, redisMessageBroker);
    }

    // ==================== Agent Session Management ====================

    @Test
    @DisplayName("registerAgentSession subscribes to Redis and stores session")
    void registerAgentSessionSubscribesAndStores() {
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-1");

        service.registerAgentSession("agent-1", wsSession);

        verify(redisMessageBroker).subscribeToAgent(eq("agent-1"), any());
        assertTrue(service.hasAgentSession("agent-1"));
    }

    @Test
    @DisplayName("registerAgentSession closes old session when re-registering")
    void registerAgentSessionClosesOldSession() throws Exception {
        WebSocketSession oldSession = mock(WebSocketSession.class);
        when(oldSession.isOpen()).thenReturn(true);
        when(oldSession.getId()).thenReturn("ws-old");
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-new");

        service.registerAgentSession("agent-1", oldSession);
        service.registerAgentSession("agent-1", wsSession);

        verify(oldSession).close();
        assertTrue(service.hasAgentSession("agent-1"));
    }

    @Test
    @DisplayName("removeAgentSession unsubscribes from Redis and removes session")
    void removeAgentSessionUnsubscribesAndRemoves() {
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-1");

        service.registerAgentSession("agent-1", wsSession);
        service.removeAgentSession("agent-1");

        verify(redisMessageBroker).unsubscribeFromAgent("agent-1");
        assertFalse(service.hasAgentSession("agent-1"));
    }

    @Test
    @DisplayName("hasAgentSession returns false for unregistered agent")
    void hasAgentSessionReturnsFalseForUnregistered() {
        assertFalse(service.hasAgentSession("unknown-agent"));
    }

    // ==================== Upstream: PCAgent �?Skill Server ====================

    @Test
    @DisplayName("relayToSkillServer attaches agentId and forwards via WS")
    void relayToSkillServerAttachesAgentIdAndForwards() {
        service.setSkillServerRelay(skillServerRelay);
        GatewayMessage msg = GatewayMessage.builder().type("tool_event").sessionId("42").build();

        service.relayToSkillServer("agent-1", msg);

        verify(skillServerRelay)
                .sendToSkillServer(argThat(m -> "agent-1".equals(m.getAgentId()) && "tool_event".equals(m.getType())));
    }

    @Test
    @DisplayName("relayToSkillServer warns when relay target not set")
    void relayToSkillServerWarnsWhenNoTarget() {
        // Don't set relay target
        GatewayMessage msg = GatewayMessage.builder().type("tool_event").build();

        // Should not throw
        service.relayToSkillServer("agent-1", msg);
        verifyNoInteractions(redisMessageBroker);
    }

    // ==================== Downstream: Skill �?PCAgent ====================

    @Test
    @DisplayName("relayToAgent publishes invoke to Gateway Redis agent:{id}")
    void relayToAgentPublishesToRedis() {
        GatewayMessage msg = GatewayMessage.builder().type("invoke").agentId("agent-1").build();

        service.relayToAgent("agent-1", msg);

        verify(redisMessageBroker).publishToAgent(eq("agent-1"), eq(msg));
    }

    @Test
    @DisplayName("getActiveSessionCount returns correct count")
    void getActiveSessionCountReturnsCorrectCount() {
        assertEquals(0, service.getActiveSessionCount());

        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-1");
        service.registerAgentSession("agent-1", wsSession);

        assertEquals(1, service.getActiveSessionCount());
    }
}
