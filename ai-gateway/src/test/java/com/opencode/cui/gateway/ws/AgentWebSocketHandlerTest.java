package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.AkSkAuthService;
import com.opencode.cui.gateway.service.DeviceBindingService;
import com.opencode.cui.gateway.service.EventRelayService;
import com.opencode.cui.gateway.service.RedisMessageBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentWebSocketHandlerTest {

    private static final String GATEWAY_INSTANCE_ID = "gw-local";

    @Mock
    private AkSkAuthService akSkAuthService;
    @Mock
    private AgentRegistryService agentRegistryService;
    @Mock
    private DeviceBindingService deviceBindingService;
    @Mock
    private EventRelayService eventRelayService;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private WebSocketSession session;

    private ObjectMapper objectMapper;
    private AgentWebSocketHandler handler;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        handler = new AgentWebSocketHandler(
                akSkAuthService,
                agentRegistryService,
                deviceBindingService,
                eventRelayService,
                redisMessageBroker,
                GATEWAY_INSTANCE_ID,
                objectMapper,
                redisTemplate);

        attributes = new HashMap<>();
        attributes.put("userId", "user-1");
        attributes.put("akId", "ak-1");
        lenient().when(session.getAttributes()).thenReturn(attributes);
        lenient().when(session.getId()).thenReturn("ws-1");
    }

    @Test
    @DisplayName("register rejects when conn:ak shows the AK belongs to another gateway")
    void registerRejectsRemoteConnAkOwner() throws Exception {
        allowRegisterLock();
        when(deviceBindingService.validate("ak-1", "AA:BB:CC:DD:EE:FF", "openx")).thenReturn(true);
        when(redisMessageBroker.getConnAk("ak-1")).thenReturn("gw-remote");

        handleRegister();

        assertRejectedDuplicate();
        verify(redisMessageBroker).getConnAk("ak-1");
        verify(redisMessageBroker, never()).getInternalAgentInstance(anyString());
        verify(eventRelayService, never()).hasAgentSession("ak-1");
        verify(agentRegistryService, never()).register(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(eventRelayService, never()).registerAgentSession(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("register rejects when internal route shows the AK belongs to another gateway")
    void registerRejectsRemoteInternalOwner() throws Exception {
        allowRegisterLock();
        when(deviceBindingService.validate("ak-1", "AA:BB:CC:DD:EE:FF", "openx")).thenReturn(true);
        when(redisMessageBroker.getConnAk("ak-1")).thenReturn(null);
        when(redisMessageBroker.getInternalAgentInstance("ak-1")).thenReturn("gw-remote");

        handleRegister();

        assertRejectedDuplicate();
        verify(redisMessageBroker).getConnAk("ak-1");
        verify(redisMessageBroker).getInternalAgentInstance("ak-1");
        verify(eventRelayService, never()).hasAgentSession("ak-1");
        verify(agentRegistryService, never()).register(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("register continues when no remote gateway owns the AK")
    void registerContinuesWhenNoRemoteOwner() throws Exception {
        allowRegisterLock();
        when(deviceBindingService.validate("ak-1", "AA:BB:CC:DD:EE:FF", "openx")).thenReturn(true);
        when(redisMessageBroker.getConnAk("ak-1")).thenReturn(null);
        when(redisMessageBroker.getInternalAgentInstance("ak-1")).thenReturn(null);
        when(eventRelayService.hasAgentSession("ak-1")).thenReturn(false);
        when(agentRegistryService.register(
                "user-1",
                "ak-1",
                "MacBook Pro",
                "AA:BB:CC:DD:EE:FF",
                "macOS",
                "openx",
                "1.0.0",
                null,
                null))
                .thenReturn(AgentConnection.builder().id(100L).akId("ak-1").build());
        when(redisMessageBroker.drainPending("ak-1")).thenReturn(List.of());

        handleRegister();

        assertEquals(100L, attributes.get("agentId"));
        verify(eventRelayService).registerAgentSession("ak-1", "user-1", session);
        verify(redisMessageBroker).bindConnAk("ak-1", GATEWAY_INSTANCE_ID, Duration.ofSeconds(120));
        verify(redisMessageBroker).bindInternalAgent("ak-1", GATEWAY_INSTANCE_ID, Duration.ofSeconds(120));
        verify(eventRelayService).relayToSkillServer(eq("ak-1"),
                org.mockito.ArgumentMatchers.argThat(message -> GatewayMessage.Type.AGENT_ONLINE.equals(message.getType())));
        assertSentMessageType(GatewayMessage.Type.REGISTER_OK);
    }

    @Test
    @DisplayName("close skips offline notification when AK already belongs to a newer gateway owner")
    void closeSkipsOfflineWhenNewerOwnerExists() throws Exception {
        allowRegisterLock();
        when(deviceBindingService.validate("ak-1", "AA:BB:CC:DD:EE:FF", "openx")).thenReturn(true);
        when(redisMessageBroker.getConnAk("ak-1")).thenReturn(null, "gw-remote");
        when(redisMessageBroker.getInternalAgentInstance("ak-1")).thenReturn(null, null);
        when(eventRelayService.hasAgentSession("ak-1")).thenReturn(false);
        when(agentRegistryService.register(
                "user-1",
                "ak-1",
                "MacBook Pro",
                "AA:BB:CC:DD:EE:FF",
                "macOS",
                "openx",
                "1.0.0",
                null,
                null))
                .thenReturn(AgentConnection.builder().id(100L).akId("ak-1").build());
        when(redisMessageBroker.drainPending("ak-1")).thenReturn(List.of());
        when(redisMessageBroker.conditionalRemoveConnAk("ak-1", GATEWAY_INSTANCE_ID)).thenReturn(false);
        when(redisMessageBroker.conditionalRemoveInternalAgent("ak-1", GATEWAY_INSTANCE_ID)).thenReturn(false);

        handleRegister();
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(agentRegistryService).markOffline(100L);
        verify(eventRelayService).removeAgentSession("ak-1");
        verify(eventRelayService, never()).relayToSkillServer(eq("ak-1"),
                org.mockito.ArgumentMatchers.argThat(message -> GatewayMessage.Type.AGENT_OFFLINE.equals(message.getType())));
    }

    private void allowRegisterLock() {
        when(valueOperations.setIfAbsent(
                eq("gw:register:lock:ak-1"),
                anyString(),
                eq(10L),
                eq(TimeUnit.SECONDS)))
                .thenReturn(true);
    }

    private void handleRegister() throws Exception {
        GatewayMessage register = GatewayMessage.register(
                "MacBook Pro",
                "AA:BB:CC:DD:EE:FF",
                "macOS",
                "openx",
                "1.0.0");
        handler.handleTextMessage(session, new TextMessage(objectMapper.writeValueAsString(register)));
    }

    private void assertRejectedDuplicate() throws Exception {
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        GatewayMessage response = objectMapper.readValue(messageCaptor.getValue().getPayload(), GatewayMessage.class);

        assertEquals(GatewayMessage.Type.REGISTER_REJECTED, response.getType());
        assertEquals("duplicate_connection", response.getReason());
        verify(session).close(org.mockito.ArgumentMatchers.argThat(status ->
                status.getCode() == 4409 && "duplicate_connection".equals(status.getReason())));
    }

    private void assertSentMessageType(String expectedType) throws Exception {
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        GatewayMessage response = objectMapper.readValue(messageCaptor.getValue().getPayload(), GatewayMessage.class);
        assertEquals(expectedType, response.getType());
        verify(session, never()).close(any(CloseStatus.class));
    }
}
