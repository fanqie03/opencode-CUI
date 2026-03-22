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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LegacySkillRelayStrategy 单元测试：验证连接管理、上行路由、invoke 处理和心跳逻辑。 */
@ExtendWith(MockitoExtension.class)
class LegacySkillRelayStrategyTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private WebSocketSession ss1;
    @Mock
    private WebSocketSession ss2;

    private LegacySkillRelayStrategy strategy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INSTANCE_ID = "gw-test";
    private static final String SOURCE = "skill-server";

    @BeforeEach
    void setUp() {
        strategy = new LegacySkillRelayStrategy(redisMessageBroker, objectMapper, INSTANCE_ID, 30);
    }

    private Map<String, Object> attrs(String source) {
        Map<String, Object> map = new HashMap<>();
        map.put(SkillRelayService.SOURCE_ATTR, source);
        return map;
    }

    private void registerSs1() {
        lenient().when(ss1.getId()).thenReturn("link-1");
        lenient().when(ss1.getAttributes()).thenReturn(attrs(SOURCE));
        lenient().when(ss1.isOpen()).thenReturn(true);
        strategy.registerSession(ss1);
    }

    private void registerSs2() {
        lenient().when(ss2.getId()).thenReturn("link-2");
        lenient().when(ss2.getAttributes()).thenReturn(attrs(SOURCE));
        lenient().when(ss2.isOpen()).thenReturn(true);
        strategy.registerSession(ss2);
    }

    // ==================== 连接管理 ====================

    @Nested
    @DisplayName("连接管理")
    class ConnectionTests {

        @Test
        @DisplayName("注册时刷新 owner 心跳")
        void register_refreshesOwner() {
            registerSs1();

            verify(redisMessageBroker).refreshSourceOwner(eq(SOURCE), eq(INSTANCE_ID), any(Duration.class));
        }

        @Test
        @DisplayName("注册时订阅 relay channel")
        void register_subscribesRelay() {
            registerSs1();

            verify(redisMessageBroker).subscribeToRelay(eq(INSTANCE_ID), any());
        }

        @Test
        @DisplayName("移除最后一个连接时清理 owner 和 relay 订阅")
        void remove_lastSession_clearsOwnerAndRelay() {
            registerSs1();
            strategy.removeSession(ss1);

            verify(redisMessageBroker).removeSourceOwner(SOURCE, INSTANCE_ID);
            verify(redisMessageBroker).unsubscribeFromRelay(INSTANCE_ID);
        }

        @Test
        @DisplayName("getActiveConnectionCount 返回正确数量")
        void activeConnectionCount() {
            registerSs1();
            registerSs2();

            assertEquals(2, strategy.getActiveConnectionCount());
        }
    }

    // ==================== 上行路由 ====================

    @Nested
    @DisplayName("上行路由 (relayToSkill)")
    class RelayTests {

        @Test
        @DisplayName("通过本地 defaultLink 投递")
        void relay_localDelivery() throws Exception {
            registerSs1();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE)
                    .build();

            boolean result = strategy.relayToSkill(msg);

            assertTrue(result);
            verify(ss1).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("通过 getAgentSource 解析 source")
        void relay_resolvesSourceFromAgentBinding() throws Exception {
            registerSs1();
            when(redisMessageBroker.getAgentSource("ak1")).thenReturn(SOURCE);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .ak("ak1")
                    .build();

            boolean result = strategy.relayToSkill(msg);

            assertTrue(result);
            verify(redisMessageBroker).getAgentSource("ak1");
        }

        @Test
        @DisplayName("无连接时通过 owner 中继")
        void relay_noLocalSession_relaysToOwner() {
            // 没有本地连接，但有远程 owner
            String remoteOwnerKey = RedisMessageBroker.sourceOwnerMember(SOURCE, "gw-remote");
            when(redisMessageBroker.getActiveSourceOwners(SOURCE)).thenReturn(Set.of(remoteOwnerKey));

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE)
                    .build();

            boolean result = strategy.relayToSkill(msg);

            assertTrue(result);
            verify(redisMessageBroker).publishToRelay(eq("gw-remote"), any(GatewayMessage.class));
        }

        @Test
        @DisplayName("无连接也无 owner 时返回 false")
        void relay_noConnectionsNoOwner_returnsFalse() {
            when(redisMessageBroker.getActiveSourceOwners(SOURCE)).thenReturn(Set.of());

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE)
                    .build();

            boolean result = strategy.relayToSkill(msg);

            assertFalse(result);
        }

        @Test
        @DisplayName("无法解析 source 时返回 false")
        void relay_noSource_returnsFalse() {
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .build();

            boolean result = strategy.relayToSkill(msg);

            assertFalse(result);
        }
    }

    // ==================== invoke 处理 ====================

    @Nested
    @DisplayName("invoke 处理")
    class InvokeTests {

        @Test
        @DisplayName("invoke 成功时 bindAgentSource")
        void invoke_bindsAgentSource() {
            registerSs1();
            when(redisMessageBroker.getAgentUser("ak1")).thenReturn("user1");

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .userId("user1")
                    .source(SOURCE)
                    .build();

            strategy.handleInvokeFromSkill(ss1, msg);

            verify(redisMessageBroker).bindAgentSource("ak1", SOURCE);
            verify(redisMessageBroker).publishToAgent(eq("ak1"), any(GatewayMessage.class));
        }

        @Test
        @DisplayName("source 不匹配时拒绝 invoke")
        void invoke_sourceMismatch_rejected() {
            registerSs1();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .source("wrong-source")
                    .build();

            strategy.handleInvokeFromSkill(ss1, msg);

            verify(redisMessageBroker, never()).publishToAgent(anyString(), any());
        }

        @Test
        @DisplayName("userId 不匹配时拒绝 invoke")
        void invoke_userIdMismatch_rejected() {
            registerSs1();
            when(redisMessageBroker.getAgentUser("ak1")).thenReturn("expectedUser");

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .userId("wrongUser")
                    .source(SOURCE)
                    .build();

            strategy.handleInvokeFromSkill(ss1, msg);

            verify(redisMessageBroker, never()).publishToAgent(anyString(), any());
        }
    }

    // ==================== 心跳 ====================

    @Nested
    @DisplayName("Owner 心跳")
    class HeartbeatTests {

        @Test
        @DisplayName("refreshOwnerHeartbeat 刷新所有 source 的心跳")
        void heartbeat_refreshesAllSources() {
            registerSs1();
            // 清除注册时的调用记录
            org.mockito.Mockito.clearInvocations(redisMessageBroker);

            strategy.refreshOwnerHeartbeat();

            verify(redisMessageBroker).refreshSourceOwner(eq(SOURCE), eq(INSTANCE_ID), any(Duration.class));
        }
    }
}
