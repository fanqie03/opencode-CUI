package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.GatewayRelayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayWSClientTest {

    @Test
    @DisplayName("buildAuthProtocol generates base64url token subprotocol")
    void buildAuthProtocolGeneratesBase64UrlTokenSubprotocol() {
        GatewayWSClient client = new GatewayWSClient(Mockito.mock(GatewayRelayService.class), new ObjectMapper());
        ReflectionTestUtils.setField(client, "internalToken", "secret-token");

        String protocol = client.buildAuthProtocol();

        assertTrue(protocol.startsWith("auth."));
        String encoded = protocol.substring("auth.".length());
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        assertEquals("{\"token\":\"secret-token\"}", new String(decoded, StandardCharsets.UTF_8));
    }
}
