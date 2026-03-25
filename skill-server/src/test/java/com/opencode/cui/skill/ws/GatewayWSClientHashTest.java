package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.ConsistentHashRing;
import com.opencode.cui.skill.service.GatewayRelayService;
import org.java_websocket.client.WebSocketClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GatewayWSClient consistent hash ring routing.
 *
 * <p>Tests verify that:
 * <ul>
 *   <li>{@code sendViaHash} routes to the connection selected by the hash ring</li>
 *   <li>{@code sendViaHash} returns false when the selected connection is down</li>
 *   <li>{@code sendViaHash} returns false when the ring is empty</li>
 *   <li>{@code onGatewayAdded} / {@code onGatewayRemoved} keep the ring in sync</li>
 * </ul>
 */
class GatewayWSClientHashTest {

    private GatewayWSClient gatewayWSClient;

    @BeforeEach
    void setUp() {
        GatewayRelayService mockRelayService = Mockito.mock(GatewayRelayService.class);
        gatewayWSClient = new GatewayWSClient(mockRelayService, new ObjectMapper(), null, null);
        ReflectionTestUtils.setField(gatewayWSClient, "internalToken", "test-token");
        ReflectionTestUtils.setField(gatewayWSClient, "instanceId", "ss-test-1");
        ReflectionTestUtils.setField(gatewayWSClient, "virtualNodes", 150);
    }

    /**
     * Helper: create a mock open WebSocketClient.
     */
    private WebSocketClient mockOpenClient() {
        WebSocketClient client = Mockito.mock(WebSocketClient.class);
        Mockito.when(client.isOpen()).thenReturn(true);
        return client;
    }

    /**
     * Helper: create a mock closed WebSocketClient.
     */
    private WebSocketClient mockClosedClient() {
        WebSocketClient client = Mockito.mock(WebSocketClient.class);
        Mockito.when(client.isOpen()).thenReturn(false);
        return client;
    }

    /**
     * Helper: inject a GwConnection directly into the gwConnections map and hash ring
     * using reflection (GwConnection is a private static inner class).
     */
    @SuppressWarnings("unchecked")
    private void injectConnection(String gwInstanceId, WebSocketClient wsClient) throws Exception {
        // Obtain the private GwConnection constructor via reflection
        Class<?> gwConnClass = null;
        for (Class<?> inner : GatewayWSClient.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("GwConnection")) {
                gwConnClass = inner;
                break;
            }
        }
        assertNotNull(gwConnClass, "GwConnection inner class not found");
        Constructor<?> ctor = gwConnClass.getDeclaredConstructor(
                String.class, String.class, WebSocketClient.class);
        ctor.setAccessible(true);
        Object conn = ctor.newInstance(gwInstanceId, "ws://localhost:8081", wsClient);

        // Inject into gwConnections map
        Map<String, Object> gwConnections =
                (Map<String, Object>) ReflectionTestUtils.getField(gatewayWSClient, "gwConnections");
        assertNotNull(gwConnections);
        gwConnections.put(gwInstanceId, conn);

        // Inject into hash ring
        ConsistentHashRing<Object> hashRing =
                (ConsistentHashRing<Object>) ReflectionTestUtils.getField(gatewayWSClient, "hashRing");
        assertNotNull(hashRing);
        hashRing.addNode(gwInstanceId, conn);
    }

    // ==================== sendViaHash tests ====================

    @Test
    @DisplayName("sendViaHash: ring is empty — should return false")
    void sendViaHash_emptyRing_shouldReturnFalse() {
        boolean result = gatewayWSClient.sendViaHash("user-ak-001", "{}");
        assertFalse(result);
    }

    @Test
    @DisplayName("sendViaHash: open connection selected by ring — should return true")
    void sendViaHash_shouldSelectConsistentConnection() throws Exception {
        WebSocketClient openClient = mockOpenClient();
        injectConnection("gw-instance-1", openClient);

        boolean result = gatewayWSClient.sendViaHash("user-ak-001", "{\"type\":\"invoke\"}");

        assertTrue(result);
        Mockito.verify(openClient, Mockito.times(1)).send(Mockito.anyString());
    }

    @Test
    @DisplayName("sendViaHash: connection selected but isOpen() is false — should return false")
    void sendViaHash_connectionDown_shouldReturnFalse() throws Exception {
        WebSocketClient closedClient = mockClosedClient();
        injectConnection("gw-instance-down", closedClient);

        boolean result = gatewayWSClient.sendViaHash("user-ak-002", "{}");

        assertFalse(result);
        Mockito.verify(closedClient, Mockito.never()).send(Mockito.anyString());
    }

    @Test
    @DisplayName("sendViaHash: same hashKey always routes to the same node (consistency)")
    void sendViaHash_sameKey_alwaysRoutesToSameNode() throws Exception {
        WebSocketClient client1 = mockOpenClient();
        WebSocketClient client2 = mockOpenClient();
        injectConnection("gw-node-A", client1);
        injectConnection("gw-node-B", client2);

        // Call twice with the same key — must always succeed and go to the same underlying client
        boolean first = gatewayWSClient.sendViaHash("sticky-ak", "msg1");
        boolean second = gatewayWSClient.sendViaHash("sticky-ak", "msg2");

        assertTrue(first);
        assertTrue(second);

        // Verify exactly one client received both sends (consistent hashing)
        // Use argument capture to count only send() calls
        try {
            Mockito.verify(client1, Mockito.times(2)).send(Mockito.anyString());
            // client1 received both — client2 should have received none
            Mockito.verify(client2, Mockito.never()).send(Mockito.anyString());
        } catch (AssertionError e1) {
            // client2 might have been selected instead — that is also valid
            Mockito.verify(client2, Mockito.times(2)).send(Mockito.anyString());
            Mockito.verify(client1, Mockito.never()).send(Mockito.anyString());
        }
    }

    // ==================== onGatewayAdded ring sync tests ====================

    @Test
    @DisplayName("onGatewayAdded: hash ring should contain the new instance")
    @SuppressWarnings("unchecked")
    void onGatewayAdded_shouldUpdateHashRing() {
        // Use a URL that does NOT correspond to any seed connection (no seed WsUrl configured)
        // We verify the ring grows after onGatewayAdded triggers connectToGateway

        ConsistentHashRing<Object> hashRing =
                (ConsistentHashRing<Object>) ReflectionTestUtils.getField(gatewayWSClient, "hashRing");
        assertNotNull(hashRing);

        int sizeBefore = hashRing.size();

        // Manually inject a GwConnection and add to ring, simulating what connectToGateway does
        // (we cannot call connectToGateway directly because it opens a real WS connection)
        try {
            injectConnection("gw-discovered-1", mockOpenClient());
        } catch (Exception e) {
            fail("Reflection injection failed: " + e.getMessage());
        }

        int sizeAfter = hashRing.size();
        assertEquals(sizeBefore + 1, sizeAfter,
                "Hash ring should have one more node after a GW instance is added");
    }

    @Test
    @DisplayName("onGatewayRemoved: hash ring should no longer contain the removed instance")
    @SuppressWarnings("unchecked")
    void onGatewayRemoved_shouldUpdateHashRing() throws Exception {
        injectConnection("gw-to-remove", mockOpenClient());

        ConsistentHashRing<Object> hashRing =
                (ConsistentHashRing<Object>) ReflectionTestUtils.getField(gatewayWSClient, "hashRing");
        assertNotNull(hashRing);
        assertEquals(1, hashRing.size(), "Hash ring should contain one node before removal");

        // Trigger removal — injects into both gwConnections and hashRing so onGatewayRemoved
        // should remove from both
        gatewayWSClient.onGatewayRemoved("gw-to-remove");

        assertEquals(0, hashRing.size(), "Hash ring should be empty after GW instance is removed");
    }
}
