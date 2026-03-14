package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillRelayServiceTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private WebSocketSession skillSession;
    @Mock
    private WebSocketSession newServiceSession;

    private SkillRelayService service;

    @BeforeEach
    void setUp() {
        service = new SkillRelayService(redisMessageBroker, new ObjectMapper(), "gateway-local", 30);
    }

    @Test
    @DisplayName("relayToSkill only sends to local link in the same source domain")
    void relayToSkillOnlySendsToSameSourceDomainLocally() throws Exception {
        when(skillSession.getId()).thenReturn("skill-link");
        when(skillSession.getAttributes()).thenReturn(Map.of(SkillRelayService.SOURCE_ATTR, SkillRelayService.SKILL_SOURCE));
        when(skillSession.isOpen()).thenReturn(true);
        service.registerSkillSession(skillSession);

        when(newServiceSession.getId()).thenReturn("new-link");
        when(newServiceSession.getAttributes()).thenReturn(Map.of(SkillRelayService.SOURCE_ATTR, "new-service"));
        when(newServiceSession.isOpen()).thenReturn(true);
        service.registerSkillSession(newServiceSession);

        GatewayMessage message = GatewayMessage.builder()
                .type("tool_event")
                .ak("ak-1")
                .source("new-service")
                .build();

        boolean routed = service.relayToSkill(message);

        assertTrue(routed);
        verify(newServiceSession).sendMessage(any(TextMessage.class));
        verify(skillSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("relayToSkill only falls back inside the same source domain")
    void relayToSkillOnlyFallsBackInsideSameSourceDomain() {
        GatewayMessage message = GatewayMessage.builder()
                .type("tool_event")
                .ak("ak-1")
                .source("new-service")
                .build();
        when(redisMessageBroker.getActiveSourceOwners("new-service"))
                .thenReturn(Set.of("new-service:gateway-remote"));

        boolean routed = service.relayToSkill(message);

        assertTrue(routed);
        verify(redisMessageBroker).getActiveSourceOwners("new-service");
        verify(redisMessageBroker).publishToRelay(eq("gateway-remote"),
                argThat(forwarded -> "new-service".equals(forwarded.getSource())
                        && forwarded.getTraceId() != null
                        && "tool_event".equals(forwarded.getType())));
    }

    @Test
    @DisplayName("relayToSkill recovers source from gateway side agent binding")
    void relayToSkillRecoversSourceFromGatewaySideAgentBinding() {
        GatewayMessage message = GatewayMessage.builder()
                .type("tool_done")
                .ak("ak-1")
                .build();
        when(redisMessageBroker.getAgentSource("ak-1")).thenReturn("new-service");
        when(redisMessageBroker.getActiveSourceOwners("new-service"))
                .thenReturn(Set.of("new-service:gateway-remote"));

        boolean routed = service.relayToSkill(message);

        assertTrue(routed);
        verify(redisMessageBroker).publishToRelay(eq("gateway-remote"),
                argThat(forwarded -> "new-service".equals(forwarded.getSource())
                        && forwarded.getTraceId() != null
                        && "ak-1".equals(forwarded.getAk())));
    }

    @Test
    @DisplayName("relayToSkill rejects messages when source cannot be resolved")
    void relayToSkillRejectsWhenSourceCannotBeResolved() {
        GatewayMessage message = GatewayMessage.builder()
                .type("tool_done")
                .ak("ak-1")
                .build();
        when(redisMessageBroker.getAgentSource("ak-1")).thenReturn(null);

        boolean routed = service.relayToSkill(message);

        assertFalse(routed);
        verify(redisMessageBroker, never()).publishToRelay(any(), any());
    }

    @Test
    @DisplayName("relayToSkill falls back to the only active local source when agent source binding is missing")
    void relayToSkillFallsBackToSingleActiveLocalSource() throws Exception {
        when(skillSession.getId()).thenReturn("skill-link");
        when(skillSession.getAttributes()).thenReturn(Map.of(SkillRelayService.SOURCE_ATTR, SkillRelayService.SKILL_SOURCE));
        when(skillSession.isOpen()).thenReturn(true);
        service.registerSkillSession(skillSession);

        GatewayMessage message = GatewayMessage.builder()
                .type("tool_event")
                .ak("ak-1")
                .build();
        when(redisMessageBroker.getAgentSource("ak-1")).thenReturn(null);

        boolean routed = service.relayToSkill(message);

        assertTrue(routed);
        verify(skillSession).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("handleInvokeFromSkill rejects source mismatch before entering main route")
    void handleInvokeFromSkillRejectsSourceMismatch() throws Exception {
        when(skillSession.getId()).thenReturn("skill-link");
        when(skillSession.getAttributes()).thenReturn(Map.of(SkillRelayService.SOURCE_ATTR, SkillRelayService.SKILL_SOURCE));

        GatewayMessage message = GatewayMessage.builder()
                .type("invoke")
                .source("new-service")
                .ak("ak-1")
                .userId("user-1")
                .action("chat")
                .build();

        service.handleInvokeFromSkill(skillSession, message);

        verify(skillSession).sendMessage(argThat(sent -> sent instanceof TextMessage textMessage
                && textMessage.getPayload().contains("\"type\":\"register_rejected\"")
                && textMessage.getPayload().contains("\"reason\":\"source_mismatch\"")));
        verify(redisMessageBroker, never()).publishToAgent(any(), any());
        verify(redisMessageBroker, never()).bindAgentSource(any(), any());
    }
}
