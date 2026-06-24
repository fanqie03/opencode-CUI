package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.logging.MdcConstants;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import com.opencode.cui.skill.telemetry.metrics.WsConnectionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalStreamHandlerTest {

    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private ExternalWsRegistry wsRegistry;
    @Mock private SkillInstanceRegistry instanceRegistry;
    @Mock private DeliveryProperties deliveryProperties;
    @Mock private ServerHttpRequest request;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler wsHandler;
    @Mock private WebSocketSession wsSession;

    private ObjectMapper objectMapper = new ObjectMapper();
    private ExternalStreamHandler handler;

    @BeforeEach
    void setUp() {
        when(instanceRegistry.getInstanceId()).thenReturn("test-instance-1");
        handler = new ExternalStreamHandler(objectMapper, redisMessageBroker, "test-token",
                wsRegistry, instanceRegistry, deliveryProperties,
                new WsConnectionMetrics(new SimpleMeterRegistry()));
        // 模拟 Spring ApplicationReadyEvent 触发后的订阅注册（生产路径）
        handler.subscribeRelayChannel(mock(ApplicationReadyEvent.class));
    }

    @AfterEach
    void tearDown() {
        MdcHelper.clearAll();
    }

    @Test
    @DisplayName("subscribeRelayChannel 仅在 ApplicationReadyEvent 触发后才调 broker.subscribeToExternalRelay")
    void subscribeRelayChannel_onlyRegistersAfterApplicationReadyEvent() {
        // setUp 复用 broker mock；此用例独立构造一份新 handler，避免 setUp 已触发的订阅干扰断言
        RedisMessageBroker freshBroker = mock(RedisMessageBroker.class);
        SkillInstanceRegistry freshRegistry = mock(SkillInstanceRegistry.class);
        when(freshRegistry.getInstanceId()).thenReturn("fresh-instance");
        ExternalStreamHandler freshHandler = new ExternalStreamHandler(
                objectMapper, freshBroker, "test-token",
                wsRegistry, freshRegistry, deliveryProperties,
                new WsConnectionMetrics(new SimpleMeterRegistry()));

        // 事件触发前：broker 没收到任何 subscribe 调用
        verifyNoInteractions(freshBroker);

        // 事件触发后：恰好对本实例 external relay 调一次 subscribeToExternalRelay
        freshHandler.subscribeRelayChannel(mock(ApplicationReadyEvent.class));
        verify(freshBroker, times(1)).subscribeToExternalRelay(eq("fresh-instance"), any());
    }

    @Test
    @DisplayName("external relay restores MDC context from envelope")
    @SuppressWarnings("unchecked")
    void externalRelayRestoresMdcContextFromEnvelope() {
        ArgumentCaptor<Consumer<String>> relayHandlerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(redisMessageBroker).subscribeToExternalRelay(eq("test-instance-1"), relayHandlerCaptor.capture());

        String envelope = "{\"domain\":\"im\",\"payload\":\"{\\\"type\\\":\\\"text.done\\\"}\","
                + "\"traceId\":\"trace-001\",\"welinkSessionId\":\"sess-001\","
                + "\"ak\":\"ak-001\",\"userId\":\"user-001\"}";

        relayHandlerCaptor.getValue().accept(envelope);

        assertEquals("trace-001", MDC.get(MdcConstants.TRACE_ID));
        assertEquals("sess-001", MDC.get(MdcConstants.SESSION_ID));
        assertEquals("ak-001", MDC.get(MdcConstants.AK));
        assertEquals("user-001", MDC.get(MdcConstants.USER_ID));
        assertEquals("external-relay-rx", MDC.get(MdcConstants.SCENARIO));
    }

    private String buildAuthProtocol(String token, String source, String instanceId) {
        String json = "{\"token\":\"" + token + "\",\"source\":\"" + source
                + "\",\"instanceId\":\"" + instanceId + "\"}";
        return "auth." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("valid handshake registers connection")
    void validHandshakeAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Sec-WebSocket-Protocol", List.of(buildAuthProtocol("test-token", "im", "im-1")));
        when(request.getHeaders()).thenReturn(headers);
        HttpHeaders respHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(respHeaders);
        Map<String, Object> attrs = new HashMap<>();
        boolean result = handler.beforeHandshake(request, response, wsHandler, attrs);
        assertTrue(result);
        assertEquals("im", attrs.get("source"));
        assertEquals("im-1", attrs.get("instanceId"));
    }

    @Test
    @DisplayName("invalid token rejects handshake")
    void invalidTokenRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Sec-WebSocket-Protocol", List.of(buildAuthProtocol("wrong-token", "im", "im-1")));
        when(request.getHeaders()).thenReturn(headers);
        Map<String, Object> attrs = new HashMap<>();
        boolean result = handler.beforeHandshake(request, response, wsHandler, attrs);
        assertFalse(result);
    }

    @Test
    @DisplayName("hasActiveConnections returns true after connection established")
    void hasActiveConnectionsAfterConnect() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("source", "im");
        attrs.put("instanceId", "im-1");
        when(wsSession.getAttributes()).thenReturn(attrs);
        when(wsSession.getId()).thenReturn("session-1");
        when(wsSession.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(wsSession);
        assertTrue(handler.hasActiveConnections("im"));
        assertFalse(handler.hasActiveConnections("crm"));
    }

    @Test
    @DisplayName("hasActiveConnections returns false after connection closed")
    void noActiveConnectionsAfterClose() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("source", "im");
        attrs.put("instanceId", "im-1");
        when(wsSession.getAttributes()).thenReturn(attrs);
        when(wsSession.getId()).thenReturn("session-2");
        handler.afterConnectionEstablished(wsSession);
        handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
        assertFalse(handler.hasActiveConnections("im"));
    }

    @Test
    @DisplayName("ping message receives pong reply")
    void pingReceivesPong() throws Exception {
        when(wsSession.getId()).thenReturn("session-3");
        handler.handleTextMessage(wsSession, new TextMessage("{\"action\":\"ping\"}"));
        verify(wsSession).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("multiple connections from same source+instanceId coexist")
    void multipleConnectionsCoexist() throws Exception {
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        Map<String, Object> attrs2 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        when(s1.getAttributes()).thenReturn(attrs1);
        when(s2.getAttributes()).thenReturn(attrs2);
        when(s1.getId()).thenReturn("ws-1");
        when(s2.getId()).thenReturn("ws-2");
        lenient().when(s1.isOpen()).thenReturn(true);
        lenient().when(s2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        assertTrue(handler.hasActiveConnections("im"));
        // 注册调用：第一次 count=1，第二次 count=2
        verify(wsRegistry).register("im", 1);
        verify(wsRegistry).register("im", 2);
    }

    @Test
    @DisplayName("closing one connection does not remove others")
    void closingOneKeepsOthers() throws Exception {
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        Map<String, Object> attrs2 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        when(s1.getAttributes()).thenReturn(attrs1);
        when(s2.getAttributes()).thenReturn(attrs2);
        when(s1.getId()).thenReturn("ws-1");
        when(s2.getId()).thenReturn("ws-2");
        lenient().when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        handler.afterConnectionClosed(s1, CloseStatus.NORMAL);

        assertTrue(handler.hasActiveConnections("im"));
        // closingOneKeepsOthers verifies register("im", 1) was called.
        // Note: register("im", 1) is called BOTH during afterConnectionEstablished(s1) AND afterConnectionClosed(s1).
        // So it's called at least twice with these args. Use atLeast(1):
        verify(wsRegistry, atLeast(1)).register("im", 1);
    }

    @Test
    @DisplayName("pushToOne retries on send failure")
    void pushToOneRetriesOnFailure() throws Exception {
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        Map<String, Object> attrs2 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        when(s1.getAttributes()).thenReturn(attrs1);
        when(s2.getAttributes()).thenReturn(attrs2);
        when(s1.getId()).thenReturn("ws-fail");
        when(s2.getId()).thenReturn("ws-ok");
        when(s1.isOpen()).thenReturn(true);
        when(s2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);

        // s1 发送抛异常，s2 发送成功
        doThrow(new RuntimeException("connection reset")).when(s1).sendMessage(any(TextMessage.class));

        boolean result = handler.pushToOne("im", "{\"type\":\"test\"}");
        assertTrue(result);
        // s2 应该收到消息
        verify(s2).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("checkHeartbeatTimeouts: snapshot 非空 → wsRegistry.heartbeatBatch(snapshot)")
    void checkHeartbeatTimeouts_nonEmptySnapshot_callsHeartbeatBatch() throws Exception {
        // 建立一条 im connection 让 snapshot 非空
        WebSocketSession s1 = mock(WebSocketSession.class);
        Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        when(s1.getAttributes()).thenReturn(attrs1);
        when(s1.getId()).thenReturn("ws-1");
        lenient().when(s1.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(s1);

        handler.checkHeartbeatTimeouts();

        // 期望 heartbeatBatch 收到 {"im" -> 1}
        verify(wsRegistry).heartbeatBatch(Map.of("im", 1));
    }

    @Test
    @DisplayName("checkHeartbeatTimeouts: snapshot 为空 → wsRegistry.heartbeatBatch(empty) 触发 DEL key")
    void checkHeartbeatTimeouts_emptySnapshot_callsHeartbeatBatchEmpty() {
        // 没有任何连接
        handler.checkHeartbeatTimeouts();
        verify(wsRegistry).heartbeatBatch(Map.of());
    }

    @Test
    @DisplayName("cleanupHeldBy (@PreDestroy): 调用 wsRegistry.clearOnShutdown()")
    void cleanupHeldBy_callsClearOnShutdown() {
        handler.cleanupHeldBy();
        verify(wsRegistry).clearOnShutdown();
    }

    @Test
    @DisplayName("pushToOne returns false when all connections fail")
    void pushToOneAllFail() throws Exception {
        WebSocketSession s1 = mock(WebSocketSession.class);
        Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
        when(s1.getAttributes()).thenReturn(attrs1);
        when(s1.getId()).thenReturn("ws-fail");
        when(s1.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s1);

        doThrow(new RuntimeException("broken")).when(s1).sendMessage(any(TextMessage.class));

        boolean result = handler.pushToOne("im", "{\"type\":\"test\"}");
        assertFalse(result);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("ConnectionPool")
    class ConnectionPoolTest {

        private ExternalStreamHandler.ConnectionPool pool;

        @BeforeEach
        void setUp() {
            pool = new ExternalStreamHandler.ConnectionPool();
        }

        @Test
        @DisplayName("add and countBySource tracks multiple sessions per instanceId")
        void addMultipleSessions() {
            WebSocketSession s1 = mockSession("s1", true);
            WebSocketSession s2 = mockSession("s2", true);
            pool.add("im", "im-1", s1);
            pool.add("im", "im-1", s2);
            assertEquals(2, pool.countBySource("im"));
        }

        @Test
        @DisplayName("add sessions from different instanceIds under same source")
        void addDifferentInstances() {
            WebSocketSession s1 = mockSession("s1", true);
            WebSocketSession s2 = mockSession("s2", true);
            pool.add("im", "im-1", s1);
            pool.add("im", "im-2", s2);
            assertEquals(2, pool.countBySource("im"));
        }

        @Test
        @DisplayName("remove by session reference decrements count")
        void removeBySession() {
            WebSocketSession s1 = mockSession("s1", true);
            WebSocketSession s2 = mockSession("s2", true);
            pool.add("im", "im-1", s1);
            pool.add("im", "im-1", s2);
            int remaining = pool.remove("im", "im-1", s1);
            assertEquals(1, remaining);
            assertEquals(1, pool.countBySource("im"));
        }

        @Test
        @DisplayName("remove last session returns 0 and cleans up source")
        void removeLastSession() {
            WebSocketSession s1 = mockSession("s1", true);
            pool.add("im", "im-1", s1);
            int remaining = pool.remove("im", "im-1", s1);
            assertEquals(0, remaining);
            assertEquals(0, pool.countBySource("im"));
            assertFalse(pool.hasActiveConnections("im"));
        }

        @Test
        @DisplayName("removeBySessionId removes by wsSessionId string")
        void removeBySessionId() {
            WebSocketSession s1 = mockSession("s1", true);
            WebSocketSession s2 = mockSession("s2", true);
            pool.add("im", "im-1", s1);
            pool.add("im", "im-1", s2);
            int remaining = pool.removeBySessionId("im", "im-1", "s1");
            assertEquals(1, remaining);
        }

        @Test
        @DisplayName("pickOne returns an open session")
        void pickOneReturnsOpen() {
            WebSocketSession s1 = mockSession("s1", false);
            WebSocketSession s2 = mockSession("s2", true);
            pool.add("im", "im-1", s1);
            pool.add("im", "im-1", s2);
            WebSocketSession picked = pool.pickOne("im");
            assertNotNull(picked);
            assertTrue(picked.isOpen());
        }

        @Test
        @DisplayName("pickOne returns null when no open sessions")
        void pickOneReturnsNullWhenAllClosed() {
            WebSocketSession s1 = mockSession("s1", false);
            pool.add("im", "im-1", s1);
            assertNull(pool.pickOne("im"));
        }

        @Test
        @DisplayName("pickOne returns null for unknown source")
        void pickOneUnknownSource() {
            assertNull(pool.pickOne("unknown"));
        }

        @Test
        @DisplayName("hasActiveConnections returns true only if open session exists")
        void hasActiveConnections() {
            WebSocketSession s1 = mockSession("s1", false);
            pool.add("im", "im-1", s1);
            assertFalse(pool.hasActiveConnections("im"));

            WebSocketSession s2 = mockSession("s2", true);
            pool.add("im", "im-1", s2);
            assertTrue(pool.hasActiveConnections("im"));
        }

        @Test
        @DisplayName("sources returns all registered sources")
        void sourcesReturnsAll() {
            pool.add("im", "im-1", mockSession("s1", true));
            pool.add("crm", "crm-1", mockSession("s2", true));
            assertEquals(2, pool.sources().size());
            assertTrue(pool.sources().contains("im"));
            assertTrue(pool.sources().contains("crm"));
        }

        @Test
        @DisplayName("countBySource returns 0 for unknown source")
        void countBySourceUnknown() {
            assertEquals(0, pool.countBySource("unknown"));
        }

        @Test
        @DisplayName("snapshotCountsBySource: 返回 {source -> totalCount}")
        void snapshotCountsBySource() {
            pool.add("im", "im-1", mockSession("s1", true));
            pool.add("im", "im-2", mockSession("s2", true));
            pool.add("crm", "crm-1", mockSession("s3", true));
            Map<String, Integer> snap = pool.snapshotCountsBySource();
            assertEquals(2, snap.get("im"));
            assertEquals(1, snap.get("crm"));
        }

        @Test
        @DisplayName("snapshotCountsBySource: 无连接时返回空 map")
        void snapshotCountsBySource_empty() {
            Map<String, Integer> snap = pool.snapshotCountsBySource();
            assertTrue(snap.isEmpty());
        }

        private WebSocketSession mockSession(String id, boolean open) {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn(id);
            lenient().when(session.isOpen()).thenReturn(open);
            return session;
        }
    }
}
