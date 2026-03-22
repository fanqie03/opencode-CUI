package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SkillRelayService 单元测试：验证缓存路由、广播回退、路由学习、缓存失效等逻辑。 */
@ExtendWith(MockitoExtension.class)
class SkillRelayServiceTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private LegacySkillRelayStrategy legacyStrategy;
    @Mock
    private WebSocketSession ss1Session;
    @Mock
    private WebSocketSession ss2Session;
    @Mock
    private WebSocketSession bpSession;

    private SkillRelayService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INSTANCE_ID = "gw-local";
    private static final String SOURCE_TYPE_SKILL = "skill-server";
    private static final String SOURCE_TYPE_BOT = "bot-platform";

    @BeforeEach
    void setUp() {
        service = new SkillRelayService(redisMessageBroker, objectMapper, INSTANCE_ID, legacyStrategy);
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

    // ==================== 路由缓存直推 ====================

    @Nested
    @DisplayName("路由缓存直推")
    class CacheHitTests {

        @Test
        @DisplayName("缓存命中时直推到目标 SS 连接")
        void relayToSkill_cacheHit_directPush() throws Exception {
            registerSs1();
            registerSs2();

            // 通过 learnRoute 建立缓存
            service.learnRoute("T1", "W1", ss2Session);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            verify(ss2Session).sendMessage(any(TextMessage.class));
            verify(ss1Session, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("通过 welinkSessionId 缓存命中")
        void relayToSkill_welinkCacheHit() throws Exception {
            registerSs1();

            service.learnRoute(null, "W1", ss1Session);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.SESSION_CREATED)
                    .welinkSessionId("W1")
                    .toolSessionId("T1")
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            verify(ss1Session).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 广播降级 ====================

    @Nested
    @DisplayName("广播降级")
    class BroadcastFallbackTests {

        @Test
        @DisplayName("缓存未命中时广播到同 source_type 所有 SS")
        void relayToSkill_cacheMiss_broadcastToSameSourceType() throws Exception {
            registerSs1();
            registerSs2();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T-unknown")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(ss2Session).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("广播时不发送到其他 source_type 的连接")
        void relayToSkill_cacheMiss_doesNotBroadcastToOtherSourceType() throws Exception {
            registerSs1();
            registerBp();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T-unknown")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.relayToSkill(msg);

            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(bpSession, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("无任何连接时 Mesh 和 Legacy 都返回 false")
        void relayToSkill_noConnections_returnsFalse() {
            when(legacyStrategy.relayToSkill(any(GatewayMessage.class))).thenReturn(false);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertFalse(result);
        }
    }

    // ==================== 路由学习 ====================

    @Nested
    @DisplayName("路由学习")
    class LearnRouteTests {

        @Test
        @DisplayName("learnRoute 缓存 toolSessionId")
        void learnRoute_cachesToolSessionId() throws Exception {
            registerSs1();
            registerSs2();

            service.learnRoute("T2", null, ss2Session);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T2")
                    .build();

            service.relayToSkill(msg);

            verify(ss2Session).sendMessage(any(TextMessage.class));
            verify(ss1Session, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("learnRoute 缓存 welinkSessionId")
        void learnRoute_cachesWelinkSessionId() throws Exception {
            registerSs1();

            service.learnRoute(null, "W5", ss1Session);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.SESSION_CREATED)
                    .welinkSessionId("W5")
                    .build();

            service.relayToSkill(msg);

            verify(ss1Session).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("learnRoute 同时缓存 toolSessionId 和 welinkSessionId")
        void learnRoute_cachesBothIds() throws Exception {
            registerSs2();

            service.learnRoute("T3", "W3", ss2Session);

            // toolSessionId 路由
            GatewayMessage msg1 = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T3")
                    .build();
            service.relayToSkill(msg1);
            verify(ss2Session).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("learnRoute 忽略 null/blank 参数")
        void learnRoute_ignoresNullParams() {
            registerSs1();

            // 不应抛异常
            service.learnRoute(null, null, ss1Session);
            service.learnRoute("", "", ss1Session);
        }

        @Test
        @DisplayName("session_created 通过 welinkSessionId 命中后自动学习 toolSessionId")
        void upstreamLearn_sessionCreated_learnToolSessionId() throws Exception {
            registerSs1();

            // 模拟下行 invoke 时只学习了 welinkSessionId
            service.learnRoute(null, "W10", ss1Session);

            // 上行 session_created 同时携带 welinkSessionId 和 toolSessionId
            GatewayMessage sessionCreated = GatewayMessage.builder()
                    .type(GatewayMessage.Type.SESSION_CREATED)
                    .welinkSessionId("W10")
                    .toolSessionId("T10")
                    .build();

            boolean result = service.relayToSkill(sessionCreated);
            assertTrue(result);

            // 验证 toolSessionId 已被学习：后续 tool_event 通过 T10 直接命中缓存
            reset(ss1Session);
            lenient().when(ss1Session.isOpen()).thenReturn(true);

            GatewayMessage toolEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T10")
                    .build();

            service.relayToSkill(toolEvent);
            verify(ss1Session).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("上行路由学习不覆盖已有的路由缓存")
        void upstreamLearn_doesNotOverwriteExisting() throws Exception {
            registerSs1();
            registerSs2();

            // T20 已学习指向 SS-2
            service.learnRoute("T20", null, ss2Session);
            // welinkSessionId 指向 SS-1
            service.learnRoute(null, "W20", ss1Session);

            // 上行消息通过 welinkSessionId 命中 SS-1，同时携带 T20
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.SESSION_CREATED)
                    .welinkSessionId("W20")
                    .toolSessionId("T20")
                    .build();

            service.relayToSkill(msg);

            // T20 的路由应该仍然指向 SS-2（putIfAbsent 不覆盖）
            reset(ss1Session, ss2Session);
            lenient().when(ss1Session.isOpen()).thenReturn(true);
            lenient().when(ss2Session.isOpen()).thenReturn(true);

            GatewayMessage toolEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T20")
                    .build();

            service.relayToSkill(toolEvent);
            verify(ss2Session).sendMessage(any(TextMessage.class));
            verify(ss1Session, never()).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 缓存失效 ====================

    @Nested
    @DisplayName("缓存失效")
    class CacheInvalidationTests {

        @Test
        @DisplayName("SS 断连时清除该实例的所有缓存条目")
        void invalidateRoutesForSession_clearsAllEntries() throws Exception {
            registerSs1();
            registerSs2();

            service.learnRoute("T1", "W1", ss2Session);
            service.learnRoute("T2", "W2", ss2Session);

            // 模拟 SS-2 断连
            service.removeSourceSession(ss2Session);

            // T1、T2 缓存应已清除 → cache miss → 广播到 SS-1
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.relayToSkill(msg);

            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(ss2Session, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("同 instanceId 的旧连接断开不误删新连接")
        void removeOldSession_doesNotRemoveNewSessionWithSameInstanceId() throws Exception {
            // 模拟 seed + discovery 双连接：同一 instanceId "skill-server-local"
            WebSocketSession oldSession = mock(WebSocketSession.class);
            WebSocketSession newSession = mock(WebSocketSession.class);

            Map<String, Object> oldAttrs = new HashMap<>();
            oldAttrs.put(SkillRelayService.SOURCE_ATTR, SOURCE_TYPE_SKILL);
            oldAttrs.put(SkillRelayService.INSTANCE_ID_ATTR, "skill-server-local");
            oldAttrs.put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.MESH);
            lenient().when(oldSession.getId()).thenReturn("seed-link");
            lenient().when(oldSession.getAttributes()).thenReturn(oldAttrs);
            lenient().when(oldSession.isOpen()).thenReturn(true);

            Map<String, Object> newAttrs = new HashMap<>();
            newAttrs.put(SkillRelayService.SOURCE_ATTR, SOURCE_TYPE_SKILL);
            newAttrs.put(SkillRelayService.INSTANCE_ID_ATTR, "skill-server-local");
            newAttrs.put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.MESH);
            lenient().when(newSession.getId()).thenReturn("discovery-link");
            lenient().when(newSession.getAttributes()).thenReturn(newAttrs);
            lenient().when(newSession.isOpen()).thenReturn(true);

            // 先注册旧连接，再注册新连接（新连接覆盖旧连接）
            service.registerSourceSession(oldSession);
            service.registerSourceSession(newSession);

            // 旧连接断开
            service.removeSourceSession(oldSession);

            // 新连接应仍然可用：通过广播投递到 newSession
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            verify(newSession).sendMessage(any(TextMessage.class));
            verify(oldSession, never()).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 多 source_type 隔离 ====================

    @Nested
    @DisplayName("多 source_type 隔离")
    class SourceTypeIsolationTests {

        @Test
        @DisplayName("不同 source_type 的连接池完全隔离")
        void registerMultipleSourceTypes_isolatedPools() throws Exception {
            registerSs1();
            registerBp();

            // 学到 T1 → SS-1 (skill-server) 和 T3 → BP-1 (bot-platform)
            service.learnRoute("T1", null, ss1Session);
            service.learnRoute("T3", null, bpSession);

            GatewayMessage msg1 = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .build();
            service.relayToSkill(msg1);
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(bpSession, never()).sendMessage(any(TextMessage.class));

            GatewayMessage msg2 = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T3")
                    .build();
            service.relayToSkill(msg2);
            verify(bpSession).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== 连接管理 ====================

    @Nested
    @DisplayName("连接管理")
    class ConnectionManagementTests {

        @Test
        @DisplayName("Mesh 注册时不调用 Redis 操作（无状态化）")
        void registerDoesNotTouchRedis() {
            registerSs1();

            // v3: 注册 Mesh Source 连接不需要任何 Redis 操作
            verify(redisMessageBroker, never()).publishToAgent(anyString(), any());
        }

        @Test
        @DisplayName("getActiveSourceConnectionCount 包含 Mesh + Legacy")
        void getActiveConnectionCount() {
            registerSs1();
            registerSs2();
            registerBp();

            assertTrue(service.getActiveSourceConnectionCount() >= 3);
        }
    }

    // ==================== 策略路由 ====================

    @Nested
    @DisplayName("策略路由")
    class StrategyRoutingTests {

        @Mock
        private WebSocketSession legacySession;

        @Test
        @DisplayName("无 instanceId 的连接走 Legacy 策略注册")
        void register_noInstanceId_delegatesToLegacy() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put(SkillRelayService.SOURCE_ATTR, SOURCE_TYPE_SKILL);
            // 不设置 INSTANCE_ID_ATTR → 旧版客户端
            lenient().when(legacySession.getId()).thenReturn("legacy-link");
            lenient().when(legacySession.getAttributes()).thenReturn(attrs);
            lenient().when(legacySession.isOpen()).thenReturn(true);

            service.registerSourceSession(legacySession);

            verify(legacyStrategy).registerSession(legacySession);
        }

        @Test
        @DisplayName("有 instanceId 的连接走 Mesh 策略注册（不调 Legacy）")
        void register_withInstanceId_meshOnly() {
            registerSs1();

            verify(legacyStrategy, never()).registerSession(any());
        }

        @Test
        @DisplayName("Legacy 连接的 invoke 委托到 Legacy 策略")
        void invoke_legacySession_delegatesToLegacy() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put(SkillRelayService.SOURCE_ATTR, SOURCE_TYPE_SKILL);
            attrs.put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.LEGACY);
            lenient().when(legacySession.getId()).thenReturn("legacy-link");
            lenient().when(legacySession.getAttributes()).thenReturn(attrs);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.handleInvokeFromSkill(legacySession, msg);

            verify(legacyStrategy).handleInvokeFromSkill(legacySession, msg);
        }

        @Test
        @DisplayName("Mesh 路由失败后 fallback 到 Legacy")
        void relayToSkill_meshFails_fallbackToLegacy() {
            // 无 Mesh 连接 → Mesh 路由失败
            when(legacyStrategy.relayToSkill(any(GatewayMessage.class))).thenReturn(true);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            verify(legacyStrategy).relayToSkill(any(GatewayMessage.class));
        }

        @Test
        @DisplayName("Mesh 路由成功时不调用 Legacy")
        void relayToSkill_meshSucceeds_noLegacy() throws Exception {
            registerSs1();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.relayToSkill(msg);

            verify(legacyStrategy, never()).relayToSkill(any(GatewayMessage.class));
        }

        @Test
        @DisplayName("Legacy 连接移除委托到 Legacy 策略")
        void remove_legacySession_delegatesToLegacy() {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put(SkillRelayService.SOURCE_ATTR, SOURCE_TYPE_SKILL);
            attrs.put(SkillRelayStrategy.STRATEGY_ATTR, SkillRelayStrategy.LEGACY);
            lenient().when(legacySession.getId()).thenReturn("legacy-link");
            lenient().when(legacySession.getAttributes()).thenReturn(attrs);

            service.removeSourceSession(legacySession);

            verify(legacyStrategy).removeSession(legacySession);
        }
    }
}
