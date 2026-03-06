package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.EventRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SkillServerWSClient message handling (v1 protocol).
 * Tests the handleSkillServerMessage method via reflection since it's private.
 */
@ExtendWith(MockitoExtension.class)
class SkillServerWSClientTest {

    @Mock
    private EventRelayService eventRelayService;

    private ObjectMapper objectMapper;
    private SkillServerWSClient client;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        client = new SkillServerWSClient(eventRelayService, objectMapper);
    }

    private void invokeHandleMessage(String rawMessage) throws Exception {
        Method method = SkillServerWSClient.class.getDeclaredMethod("handleSkillServerMessage", String.class);
        method.setAccessible(true);
        method.invoke(client, rawMessage);
    }

    @Test
    @DisplayName("invoke message routes to relayToAgent via Gateway Redis")
    void invokeMessageRoutesToAgent() throws Exception {
        String json = objectMapper.writeValueAsString(
                GatewayMessage.invoke("agent-1", "sess-42", "chat",
                        objectMapper.readTree("{\"text\":\"hello\"}")));

        invokeHandleMessage(json);

        verify(eventRelayService).relayToAgent(eq("agent-1"),
                argThat(m -> "invoke".equals(m.getType()) && "agent-1".equals(m.getAgentId())));
    }

    @Test
    @DisplayName("invoke without agentId is ignored")
    void invokeWithoutAgentIdIsIgnored() throws Exception {
        String json = "{\"type\":\"invoke\",\"sessionId\":\"42\",\"action\":\"chat\"}";

        invokeHandleMessage(json);

        verifyNoInteractions(eventRelayService);
    }

    @Test
    @DisplayName("unknown message type does not throw")
    void unknownTypeDoesNotThrow() throws Exception {
        String json = "{\"type\":\"unknown_type\",\"agentId\":\"1\"}";

        invokeHandleMessage(json);

        verifyNoInteractions(eventRelayService);
    }

    @Test
    @DisplayName("malformed JSON does not throw")
    void malformedJsonDoesNotThrow() throws Exception {
        invokeHandleMessage("not valid json");
        verifyNoInteractions(eventRelayService);
    }

    @Test
    @DisplayName("message without type is ignored")
    void messageWithoutTypeIsIgnored() throws Exception {
        String json = "{\"agentId\":\"1\",\"sessionId\":\"42\"}";

        invokeHandleMessage(json);

        verifyNoInteractions(eventRelayService);
    }
}
