package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.InvokeRouteStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SkillRelayService 单元测试：验证 V2 路由表 + 一致性哈希环路由、广播降级、连接管理。 */
@ExtendWith(MockitoExtension.class)
class SkillRelayServiceTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private WebSocketSession ss1Session;
    @Mock
    private WebSocketSession ss2Session;
    @Mock
    private WebSocketSession bpSession;

    private SkillRelayService service;
    private UpstreamRoutingTable routingTable;
    private GatewayMessageIdentityService messageIdentityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INSTANCE_ID = "gw-local";
    private static final String SOURCE_TYPE_SKILL = "skill-server";
    private static final String SOURCE_TYPE_BOT = "bot-platform";

    @BeforeEach
    void setUp() {
        routingTable = new UpstreamRoutingTable(100000, 30);
        messageIdentityService = new GatewayMessageIdentityService();
        service = new SkillRelayService(redisMessageBroker, objectMapper, INSTANCE_ID, routingTable,
                messageIdentityService, List.of());
    }

    /** Wait for AsyncSessionSender background thread to flush the send queue. */
    private static void awaitSend() throws InterruptedException {
        Thread.sleep(200);
    }

    private static Map<String, Object> mutableAttrs(String source, String instanceId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SkillRelayService.SOURCE_ATTR, source);
        if (instanceId != null) {
            attrs.put(SkillRelayService.INSTANCE_ID_ATTR, instanceId);
        }
        return attrs;
    }

    private void registerSs1() {
        lenient().when(ss1Session.getId()).thenReturn("ss1-link");
        lenient().when(ss1Session.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_SKILL, "ss-1"));
        lenient().when(ss1Session.isOpen()).thenReturn(true);
        service.registerSourceSession(ss1Session);
    }

    private void registerSs2() {
        lenient().when(ss2Session.getId()).thenReturn("ss2-link");
        lenient().when(ss2Session.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_SKILL, "ss-2"));
        lenient().when(ss2Session.isOpen()).thenReturn(true);
        service.registerSourceSession(ss2Session);
    }

    private void registerBp() {
        lenient().when(bpSession.getId()).thenReturn("bp-link");
        lenient().when(bpSession.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_BOT, "bp-1"));
        lenient().when(bpSession.isOpen()).thenReturn(true);
        service.registerSourceSession(bpSession);
    }

    // ==================== V2 路由表直推 ====================

    @Test
    @DisplayName("cloud abort with cloudProfile routes to business strategy even without assistantScope")
    void handleInvokeFromSkill_cloudAbortWithoutScopeRoutesBusiness() {
        InvokeRouteStrategy businessStrategy = mock(InvokeRouteStrategy.class);
        when(businessStrategy.getScope()).thenReturn("business");
        SkillRelayService serviceWithBusinessRoute = new SkillRelayService(
                redisMessageBroker, objectMapper, INSTANCE_ID, routingTable,
                messageIdentityService, List.of(businessStrategy));

        lenient().when(ss1Session.getId()).thenReturn("ss1-link");
        when(ss1Session.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_SKILL, "ss-1"));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("toolSessionId", "ts-1");
        payload.put("cloudProfile", "assistant_square");
        GatewayMessage msg = GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .ak("ak1")
                .userId("user1")
                .source(SOURCE_TYPE_SKILL)
                .action("abort_session")
                .payload(payload)
                .build();

        serviceWithBusinessRoute.handleInvokeFromSkill(ss1Session, msg);

        verify(businessStrategy).route(any(GatewayMessage.class), any());
        verify(redisMessageBroker).setSessionRoute("ts-1", SOURCE_TYPE_SKILL, "ss-1");
        verify(redisMessageBroker, never()).getAgentUser(anyString());
    }

    @Test
    @DisplayName("cloud-control relay routes through business strategy locally")
    void handleCloudControlRelayRoutesThroughBusinessStrategy() throws Exception {
        InvokeRouteStrategy businessStrategy = mock(InvokeRouteStrategy.class);
        when(businessStrategy.getScope()).thenReturn("business");
        SkillRelayService serviceWithBusinessRoute = new SkillRelayService(
                redisMessageBroker, objectMapper, INSTANCE_ID, routingTable,
                messageIdentityService, List.of(businessStrategy));
        GatewayMessage abort = GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .action("abort_session")
                .toolSessionId("tool-001")
                .traceId("trace-001")
                .build();
        ArgumentCaptor<GatewayMessage> messageCaptor = ArgumentCaptor.forClass(GatewayMessage.class);

        serviceWithBusinessRoute.handleCloudControlRelay(objectMapper.writeValueAsString(abort));

        verify(businessStrategy).route(messageCaptor.capture(), any());
        assertEquals("abort_session", messageCaptor.getValue().getAction());
        assertEquals("tool-001", messageCaptor.getValue().getToolSessionId());
        verify(redisMessageBroker, never()).setSessionRoute(anyString(), anyString(), anyString());
    }

    @Nested
    @DisplayName("V2 路由表直推")
    class RoutingTableHitTests {

        @Test
        @DisplayName("路由表命中时 hash 选择目标 SS 连接")
        void relayToSkill_routingTableHit_hashSelect() throws Exception {
            registerSs1();
            registerSs2();

            // V2: teach routing table
            routingTable.learnFromRelay(java.util.List.of("T1"), SOURCE_TYPE_SKILL);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            // At least one should receive (hash-selected)
            int sendCount = 0;
            try { verify(ss1Session, timeout(1000)).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
            try { verify(ss2Session, timeout(1000)).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
            assertTrue(sendCount >= 1);
        }

        @Test
        @DisplayName("通过 welinkSessionId 路由表命中")
        void relayToSkill_welinkRoutingTableHit() throws Exception {
            registerSs1();

            routingTable.learnFromRelay(java.util.List.of("w:W1"), SOURCE_TYPE_SKILL);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.SESSION_CREATED)
                    .welinkSessionId("W1")
                    .toolSessionId("T1")
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 广播降级 ====================

    @Nested
    @DisplayName("广播降级")
    class BroadcastFallbackTests {

        @Test
        @DisplayName("路由表未命中时 hash 选择同 source_type 的一个 SS")
        void relayToSkill_noRouteEntry_hashSelectOneFromSourceType() throws Exception {
            registerSs1();
            registerSs2();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T-unknown")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            awaitSend();
            // V2: hash-selects one connection from the source type's ring (not broadcast)
            int sendCount = 0;
            try { verify(ss1Session).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
            try { verify(ss2Session).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
            assertTrue(sendCount >= 1, "At least one session should receive the message");
        }

        @Test
        @DisplayName("广播时不发送到其他 source_type 的连接")
        void relayToSkill_noRouteEntry_doesNotBroadcastToOtherSourceType() throws Exception {
            registerSs1();
            registerBp();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T-unknown")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.relayToSkill(msg);

            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(bpSession, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("无任何连接时返回 false")
        void relayToSkill_noConnections_returnsFalse() {
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertFalse(result);
        }
    }

    // ==================== V2 路由学习 ====================

    @Nested
    @DisplayName("V2 路由学习")
    class LearnRouteTests {

        @Test
        @DisplayName("routing table learns toolSessionId -> sourceType")
        void routingTable_learnToolSessionId() throws Exception {
            registerSs1();
            registerSs2();

            // V2: learn via routing table
            routingTable.learnFromRelay(java.util.List.of("T2"), SOURCE_TYPE_SKILL);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T2")
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            awaitSend();
            // At least one skill-server session should receive
            int sendCount = 0;
            try { verify(ss1Session).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
            try { verify(ss2Session).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
            assertTrue(sendCount >= 1);
        }

        @Test
        @DisplayName("routing table learns welinkSessionId -> sourceType")
        void routingTable_learnWelinkSessionId() throws Exception {
            registerSs1();

            routingTable.learnFromRelay(java.util.List.of("w:W5"), SOURCE_TYPE_SKILL);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.SESSION_CREATED)
                    .welinkSessionId("W5")
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 连接断开处理 ====================

    @Nested
    @DisplayName("连接断开处理")
    class ConnectionDisconnectTests {

        @Test
        @DisplayName("SS 断连后消息路由到剩余连接")
        void removeSession_messageFallsToRemainingSession() throws Exception {
            registerSs1();
            registerSs2();

            // SS-2 disconnects
            service.removeSourceSession(ss2Session);

            // Messages should route to remaining SS-1
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.relayToSkill(msg);

            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(ss2Session, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("同 instanceId 的旧连接断开不误删新连接")
        void removeOldSession_doesNotRemoveNewSessionWithSameInstanceId() throws Exception {
            WebSocketSession oldSession = mock(WebSocketSession.class);
            WebSocketSession newSession = mock(WebSocketSession.class);

            Map<String, Object> oldAttrs = new HashMap<>();
            oldAttrs.put(SkillRelayService.SOURCE_ATTR, SOURCE_TYPE_SKILL);
            oldAttrs.put(SkillRelayService.INSTANCE_ID_ATTR, "skill-server-local");
            lenient().when(oldSession.getId()).thenReturn("seed-link");
            lenient().when(oldSession.getAttributes()).thenReturn(oldAttrs);
            lenient().when(oldSession.isOpen()).thenReturn(true);

            Map<String, Object> newAttrs = new HashMap<>();
            newAttrs.put(SkillRelayService.SOURCE_ATTR, SOURCE_TYPE_SKILL);
            newAttrs.put(SkillRelayService.INSTANCE_ID_ATTR, "skill-server-local");
            lenient().when(newSession.getId()).thenReturn("discovery-link");
            lenient().when(newSession.getAttributes()).thenReturn(newAttrs);
            lenient().when(newSession.isOpen()).thenReturn(true);

            service.registerSourceSession(oldSession);
            service.registerSourceSession(newSession);

            // Old connection disconnects
            service.removeSourceSession(oldSession);

            // New connection should still be usable
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            awaitSend();
            verify(newSession).sendMessage(any(TextMessage.class));
            verify(oldSession, never()).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 多 source_type 隔离 ====================

    @Nested
    @DisplayName("多 source_type 隔离")
    class SourceTypeIsolationTests {

        @Test
        @DisplayName("不同 source_type 的连接池通过路由表隔离")
        void registerMultipleSourceTypes_isolatedPools() throws Exception {
            registerSs1();
            registerBp();

            // V2: learn via routing table
            routingTable.learnFromRelay(java.util.List.of("T1"), SOURCE_TYPE_SKILL);
            routingTable.learnFromRelay(java.util.List.of("T3"), SOURCE_TYPE_BOT);

            GatewayMessage msg1 = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .build();
            service.relayToSkill(msg1);
            verify(ss1Session, timeout(1000)).sendMessage(any(TextMessage.class));
            verify(bpSession, never()).sendMessage(any(TextMessage.class));

            GatewayMessage msg2 = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T3")
                    .build();
            service.relayToSkill(msg2);
            verify(bpSession, timeout(1000)).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 连接管理 ====================

    @Nested
    @DisplayName("连接管理")
    class ConnectionManagementTests {

        @Test
        @DisplayName("Mesh 注册时不调用 Redis publishToAgent（无状态化）")
        void registerDoesNotPublishToAgent() {
            registerSs1();

            // v3: 注册 Mesh Source 连接不需要 publish 到 agent channel
            verify(redisMessageBroker, never()).publishToAgent(anyString(), any());
        }

        @Test
        @DisplayName("getActiveSourceConnectionCount 统计所有 Mesh 连接")
        void getActiveConnectionCount() {
            registerSs1();
            registerSs2();
            registerBp();

            assertTrue(service.getActiveSourceConnectionCount() >= 3);
        }
    }
}
