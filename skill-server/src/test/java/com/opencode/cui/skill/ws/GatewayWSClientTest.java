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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GatewayWSClient 单元测试：验证 Gateway WebSocket 客户端的消息处理。 */
class GatewayWSClientTest {

    @Test
    @DisplayName("buildAuthProtocol 生成包含 source 和 instanceId 的 base64url 子协议")
    void buildAuthProtocolContainsSourceAndInstanceId() throws Exception {
        GatewayWSClient client = new GatewayWSClient(
                Mockito.mock(GatewayRelayService.class), new ObjectMapper(), null);
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
    @DisplayName("无连接时 hasActiveConnection 返回 false")
    void hasActiveConnectionReturnsFalseWithNoConnections() {
        GatewayWSClient client = new GatewayWSClient(
                Mockito.mock(GatewayRelayService.class), new ObjectMapper(), null);

        assertFalse(client.hasActiveConnection());
    }

    @Test
    @DisplayName("无连接时 sendToGateway 返回 false")
    void sendToGatewayReturnsFalseWithNoConnections() {
        GatewayWSClient client = new GatewayWSClient(
                Mockito.mock(GatewayRelayService.class), new ObjectMapper(), null);

        assertFalse(client.sendToGateway("test-message"));
    }

    @Test
    @DisplayName("无连接时 sendToGateway(instanceId, message) 返回 false")
    void sendToGatewayByInstanceReturnsFalseWithNoConnections() {
        GatewayWSClient client = new GatewayWSClient(
                Mockito.mock(GatewayRelayService.class), new ObjectMapper(), null);

        assertFalse(client.sendToGateway("gw-1", "test-message"));
    }

    @Test
    @DisplayName("无连接时 broadcastToAllGateways 返回 false")
    void broadcastToAllGatewaysReturnsFalseWithNoConnections() {
        GatewayWSClient client = new GatewayWSClient(
                Mockito.mock(GatewayRelayService.class), new ObjectMapper(), null);

        assertFalse(client.broadcastToAllGateways("test-message"));
    }

    @Test
    @DisplayName("getConnectedInstanceIds 初始为空")
    void getConnectedInstanceIdsInitiallyEmpty() {
        GatewayWSClient client = new GatewayWSClient(
                Mockito.mock(GatewayRelayService.class), new ObjectMapper(), null);

        assertTrue(client.getConnectedInstanceIds().isEmpty());
    }
}
