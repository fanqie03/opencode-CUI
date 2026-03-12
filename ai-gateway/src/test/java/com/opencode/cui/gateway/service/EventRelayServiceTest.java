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
 * Tests for EventRelayService — agent sessions keyed by AK.
 */
@ExtendWith(MockitoExtension.class)
class EventRelayServiceTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private WebSocketSession wsSession;
    @Mock
    private SkillRelayService skillRelayService;

    private ObjectMapper objectMapper;
    private EventRelayService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new EventRelayService(objectMapper, redisMessageBroker, skillRelayService);
    }

    // ==================== Agent Session Management ====================

    @Test
    @DisplayName("registerAgentSession subscribes to Redis agent:{ak} and stores session")
    void registerAgentSessionSubscribesAndStores() {
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-1");

        service.registerAgentSession("ak_test_001", "user-1", wsSession);

        verify(redisMessageBroker).bindAgentUser("ak_test_001", "user-1");
        verify(redisMessageBroker).subscribeToAgent(eq("ak_test_001"), any());
        assertTrue(service.hasAgentSession("ak_test_001"));
    }

    @Test
    @DisplayName("registerAgentSession closes old session when re-registering same ak")
    void registerAgentSessionClosesOldSession() throws Exception {
        WebSocketSession oldSession = mock(WebSocketSession.class);
        when(oldSession.isOpen()).thenReturn(true);
        when(oldSession.getId()).thenReturn("ws-old");
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-new");

        service.registerAgentSession("ak_test_001", "user-1", oldSession);
        service.registerAgentSession("ak_test_001", "user-1", wsSession);

        verify(oldSession).close();
        assertTrue(service.hasAgentSession("ak_test_001"));
    }

    @Test
    @DisplayName("removeAgentSession unsubscribes from Redis and removes session")
    void removeAgentSessionUnsubscribesAndRemoves() {
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-1");

        service.registerAgentSession("ak_test_001", "user-1", wsSession);
        service.removeAgentSession("ak_test_001");

        verify(redisMessageBroker).removeAgentUser("ak_test_001");
        verify(redisMessageBroker).removeAgentSource("ak_test_001");
        verify(redisMessageBroker).unsubscribeFromAgent("ak_test_001");
        assertFalse(service.hasAgentSession("ak_test_001"));
    }

    @Test
    @DisplayName("hasAgentSession returns false for unregistered ak")
    void hasAgentSessionReturnsFalseForUnregistered() {
        assertFalse(service.hasAgentSession("unknown-ak"));
    }

    // ==================== Upstream: PCAgent → Skill Server ====================

    @Test
    @DisplayName("relayToSkillServer attaches ak and routes to skill relay service")
    void relayToSkillServerAttachesAkAndRoutes() {
        when(skillRelayService.relayToSkill(any())).thenReturn(true);
        when(redisMessageBroker.getAgentUser("ak_test_001")).thenReturn("user-1");
        when(redisMessageBroker.getAgentSource("ak_test_001")).thenReturn("skill-server");
        GatewayMessage msg = GatewayMessage.builder().type("tool_event").welinkSessionId("42").build();

        service.relayToSkillServer("ak_test_001", msg);

        verify(skillRelayService)
                .relayToSkill(argThat(m -> "ak_test_001".equals(m.getAk())
                        && "user-1".equals(m.getUserId())
                        && "skill-server".equals(m.getSource())
                        && m.getTraceId() != null
                        && "tool_event".equals(m.getType())));
    }

    @Test
    @DisplayName("relayToSkillServer tolerates missing skill route")
    void relayToSkillServerToleratesMissingRoute() {
        when(skillRelayService.relayToSkill(any())).thenReturn(false);
        when(redisMessageBroker.getAgentUser("ak_test_001")).thenReturn("user-1");
        when(redisMessageBroker.getAgentSource("ak_test_001")).thenReturn("new-service");
        GatewayMessage msg = GatewayMessage.builder().type("tool_event").build();

        service.relayToSkillServer("ak_test_001", msg);
        verify(skillRelayService).relayToSkill(any());
        verify(redisMessageBroker).getAgentUser("ak_test_001");
        verify(redisMessageBroker).getAgentSource("ak_test_001");
    }

    // ==================== Downstream: Skill → PCAgent ====================

    @Test
    @DisplayName("relayToAgent publishes invoke to Gateway Redis agent:{ak}")
    void relayToAgentPublishesToRedis() {
        GatewayMessage msg = GatewayMessage.builder()
                .type("invoke")
                .ak("ak_test_001")
                .source("skill-server")
                .userId("user-1")
                .build();

        service.relayToAgent("ak_test_001", msg);

        verify(redisMessageBroker).publishToAgent(eq("ak_test_001"),
                argThat(forwarded -> "invoke".equals(forwarded.getType())
                        && "ak_test_001".equals(forwarded.getAk())
                        && forwarded.getUserId() == null
                        && forwarded.getSource() == null));
    }

    @Test
    @DisplayName("getActiveSessionCount returns correct count")
    void getActiveSessionCountReturnsCorrectCount() {
        assertEquals(0, service.getActiveSessionCount());

        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-1");
        service.registerAgentSession("ak_test_001", "user-1", wsSession);

        assertEquals(1, service.getActiveSessionCount());
    }
}
