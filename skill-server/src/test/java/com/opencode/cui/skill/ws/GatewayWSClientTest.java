package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.SessionRouteService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GatewayWSClient unit tests: verify Gateway WebSocket client behavior. */
class GatewayWSClientTest {

    private GatewayWSClient createClient() {
        return new GatewayWSClient(
                Mockito.mock(GatewayRelayService.class),
                new ObjectMapper(),
                Mockito.mock(SessionRouteService.class),
                Mockito.mock(MeterRegistry.class));
    }

    @Test
    @DisplayName("buildAuthProtocol generates base64url subprotocol with source and instanceId")
    void buildAuthProtocolContainsSourceAndInstanceId() throws Exception {
        GatewayWSClient client = createClient();
        ReflectionTestUtils.setField(client, "internalToken", "secret-token");
        ReflectionTestUtils.setField(client, "instanceId", "ss-az1-2");

        String protocol = client.buildAuthProtocol();

        assertTrue(protocol.startsWith("auth."));
        String encoded = protocol.substring("auth.".length());
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper()
                .readTree(new String(decoded, StandardCharsets.UTF_8));
        assertEquals("skill-server", node.get("source").asText());
        assertEquals("secret-token", node.get("token").asText());
        assertEquals("ss-az1-2", node.get("instanceId").asText());
    }

    @Test
    @DisplayName("hasActiveConnection returns false when pool is not initialized")
    void hasActiveConnectionReturnsFalseWithNoConnections() {
        GatewayWSClient client = createClient();

        assertFalse(client.hasActiveConnection());
    }

    @Test
    @DisplayName("sendToGateway returns false when pool is not initialized")
    void sendToGatewayReturnsFalseWithNoConnections() {
        GatewayWSClient client = createClient();

        assertFalse(client.sendToGateway("test-message"));
    }

    @Test
    @DisplayName("messages with same toolSessionId are routed to the same pool slot")
    void startIndexForMessageUsesToolSessionIdStickyRouting() {
        GatewayWSClient client = createClient();
        String chat = "{\"type\":\"invoke\",\"action\":\"chat\",\"payload\":{\"toolSessionId\":\"ts-123\"}}";
        String abort = "{\"type\":\"invoke\",\"action\":\"abort_session\",\"payload\":{\"toolSessionId\":\"ts-123\"}}";

        int chatIndex = client.startIndexForMessage(chat, 5);
        int abortIndex = client.startIndexForMessage(abort, 5);

        assertEquals(chatIndex, abortIndex);
        assertEquals(Math.floorMod("ts-123".hashCode(), 5), chatIndex);
    }

    @Test
    @DisplayName("messages without session key still use round-robin")
    void startIndexForMessageFallsBackToRoundRobinWithoutRouteKey() {
        GatewayWSClient client = createClient();

        assertEquals(0, client.startIndexForMessage("not-json", 3));
        assertEquals(1, client.startIndexForMessage("not-json", 3));
    }
}
