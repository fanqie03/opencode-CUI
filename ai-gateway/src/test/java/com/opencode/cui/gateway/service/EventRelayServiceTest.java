package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
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
 * EventRelayService 单元测试：验证 Agent 会话的注册/移除、上行路由和下行消息分发。
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
    private UpstreamRoutingTable routingTable;
    private EventRelayService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        routingTable = new UpstreamRoutingTable(100000, 30);
        service = new EventRelayService(objectMapper, redisMessageBroker, skillRelayService, routingTable, "gw-test-01");
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
    @DisplayName("relayToSkillServer 注入 ak/userId 并路由到 SkillRelayService")
    void relayToSkillServerAttachesAkAndRoutes() {
        when(skillRelayService.relayToSkill(any())).thenReturn(true);
        when(redisMessageBroker.getAgentUser("ak_test_001")).thenReturn("user-1");
        GatewayMessage msg = GatewayMessage.builder().type("tool_event").welinkSessionId("42").build();

        service.relayToSkillServer("ak_test_001", msg);

        verify(skillRelayService)
                .relayToSkill(argThat(m -> "ak_test_001".equals(m.getAk())
                        && "user-1".equals(m.getUserId())
                        && m.getTraceId() != null
                        && "tool_event".equals(m.getType())));
    }

    @Test
    @DisplayName("relayToSkillServer marks routed agent events as skill-server source")
    void relayToSkillServerPayloadToolSessionMarksSkillSource() {
        when(skillRelayService.relayToSkill(any())).thenReturn(true);
        when(redisMessageBroker.getAgentUser("ak_test_001")).thenReturn("user-1");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("toolSessionId", "tool-payload-1");
        GatewayMessage msg = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .payload(payload)
                .build();

        service.relayToSkillServer("ak_test_001", msg);

        verify(skillRelayService)
                .relayToSkill(argThat(m -> "skill-server".equals(m.getSource())
                        && "tool-payload-1".equals(m.getPayload().path("toolSessionId").asText())));
    }

    @Test
    @DisplayName("relayToSkillServer 路由失败时不抛异常")
    void relayToSkillServerToleratesMissingRoute() {
        when(skillRelayService.relayToSkill(any())).thenReturn(false);
        when(redisMessageBroker.getAgentUser("ak_test_001")).thenReturn("user-1");
        GatewayMessage msg = GatewayMessage.builder().type("tool_event").build();

        service.relayToSkillServer("ak_test_001", msg);
        verify(skillRelayService).relayToSkill(any());
        verify(redisMessageBroker).getAgentUser("ak_test_001");
    }

    @Test
    @DisplayName("to-source relay delegates delivery to SkillRelayService")
    void toSourceRelayDelegatesToSkillRelayService() throws Exception {
        GatewayMessage payloadMessage = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .traceId("trace-1")
                .build();
        String payload = objectMapper.writeValueAsString(payloadMessage);
        String rawRelay = objectMapper.writeValueAsString(
                RelayMessage.toSource("skill-server", "ss-1", payload));
        when(skillRelayService.sendToLocalSourceConnection("skill-server", "ss-1", payload))
                .thenReturn(true);

        service.handleGwRelayMessage(rawRelay);

        verify(skillRelayService).sendToLocalSourceConnection("skill-server", "ss-1", payload);
        verify(skillRelayService, never()).findLocalSourceConnection(anyString(), anyString());
    }

    // ==================== Downstream: Skill → PCAgent ====================

    @Test
    @DisplayName("ensureAgentEventTraceId recovers traceId by toolSessionId")
    void ensureAgentEventTraceIdRecoversByToolSessionId() {
        service.rememberAgentTrace(GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .toolSessionId("tool-1")
                .traceId("trace-1")
                .build());

        GatewayMessage event = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .toolSessionId("tool-1")
                .build();

        GatewayMessage traced = service.ensureAgentEventTraceId(event);

        assertEquals("trace-1", traced.getTraceId());
    }

    @Test
    @DisplayName("ensureAgentEventTraceId generates once then reuses for later events")
    void ensureAgentEventTraceIdGeneratesOnceThenReuses() {
        GatewayMessage first = GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .toolSessionId("tool-2")
                .build();

        GatewayMessage firstTraced = service.ensureAgentEventTraceId(first);
        GatewayMessage nextTraced = service.ensureAgentEventTraceId(GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_DONE)
                .toolSessionId("tool-2")
                .build());

        assertNotNull(firstTraced.getTraceId());
        assertEquals(firstTraced.getTraceId(), nextTraced.getTraceId());
    }

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

    @Test
    @DisplayName("cloud-control relay is routed to SkillRelayService")
    void handleGwRelayMessageCloudControlRoutesToSkillRelayService() throws Exception {
        GatewayMessage abort = GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .action("abort_session")
                .toolSessionId("tool-001")
                .build();
        String payload = objectMapper.writeValueAsString(abort);
        String relayJson = objectMapper.writeValueAsString(RelayMessage.toCloudControl(payload));

        service.handleGwRelayMessage(relayJson);

        verify(skillRelayService).handleCloudControlRelay(payload);
        verify(skillRelayService, never()).relayToSkill(any());
    }
}
