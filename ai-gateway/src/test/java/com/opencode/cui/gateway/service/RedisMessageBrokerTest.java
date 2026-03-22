package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RedisMessageBroker 单元测试：验证 conn:ak 连接注册表和 agentUser 方法。 */
@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        broker = new RedisMessageBroker(redisTemplate, listenerContainer, new ObjectMapper());
    }

    // ==================== conn:ak 连接注册表 ====================

    @Nested
    @DisplayName("conn:ak 连接注册表")
    class ConnAkTests {

        @Test
        @DisplayName("bindConnAk 写入 Redis key conn:ak:{ak} 并设置 TTL")
        void bindConnAkWritesKeyWithTtl() {
            broker.bindConnAk("agent-001", "gw-az1-1", Duration.ofSeconds(120));

            verify(valueOperations).set("conn:ak:agent-001", "gw-az1-1", Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("getConnAk 返回存储的 gatewayInstanceId")
        void getConnAkReturnsStoredValue() {
            when(valueOperations.get("conn:ak:agent-001")).thenReturn("gw-az1-1");

            assertEquals("gw-az1-1", broker.getConnAk("agent-001"));
        }

        @Test
        @DisplayName("getConnAk 对 null ak 返回 null")
        void getConnAkReturnsNullForNullAk() {
            assertNull(broker.getConnAk(null));
            assertNull(broker.getConnAk(""));
        }

        @Test
        @DisplayName("bindConnAk 对 null/blank 参数不执行操作")
        void bindConnAkIgnoresNullParams() {
            broker.bindConnAk(null, "gw-1", Duration.ofSeconds(120));
            broker.bindConnAk("ak-1", null, Duration.ofSeconds(120));
            broker.bindConnAk("", "gw-1", Duration.ofSeconds(120));

            verify(valueOperations, never()).set(
                    eq("conn:ak:"), eq("gw-1"), eq(Duration.ofSeconds(120)));
        }

        @Test
        @DisplayName("removeConnAk 删除 Redis key")
        void removeConnAkDeletesKey() {
            broker.removeConnAk("agent-001");

            verify(redisTemplate).delete("conn:ak:agent-001");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("conditionalRemoveConnAk 通过 Lua 脚本原子删除")
        void conditionalRemoveConnAkExecutesLuaScript() {
            broker.conditionalRemoveConnAk("agent-001", "gw-az1-1");

            // 验证调用了 execute（Lua 脚本），不再验证 get+delete 分步操作
            verify(redisTemplate).execute(any(), anyList(), any(Object[].class));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("conditionalRemoveConnAk null/blank 参数不执行")
        void conditionalRemoveConnAkSkipsNullArgs() {
            broker.conditionalRemoveConnAk(null, "gw-az1-1");
            broker.conditionalRemoveConnAk("", "gw-az1-1");
            broker.conditionalRemoveConnAk("agent-001", null);

            verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
        }

        @Test
        @DisplayName("refreshConnAkTtl 刷新 TTL")
        void refreshConnAkTtlExtendsTtl() {
            broker.refreshConnAkTtl("agent-001", Duration.ofSeconds(120));

            verify(redisTemplate).expire("conn:ak:agent-001", Duration.ofSeconds(120));
        }
    }

    // ==================== agentUser（保留的方法） ====================

    @Nested
    @DisplayName("agentUser 方法")
    class AgentUserTests {

        @Test
        @DisplayName("bindAgentUser 写入 Redis key")
        void bindAgentUserWritesKey() {
            broker.bindAgentUser("ak-001", "user-123");

            verify(valueOperations).set("gw:agent:user:ak-001", "user-123");
        }

        @Test
        @DisplayName("getAgentUser 返回存储的 userId")
        void getAgentUserReturnsStoredValue() {
            when(valueOperations.get("gw:agent:user:ak-001")).thenReturn("user-123");

            assertEquals("user-123", broker.getAgentUser("ak-001"));
        }

        @Test
        @DisplayName("removeAgentUser 删除 key")
        void removeAgentUserDeletesKey() {
            broker.removeAgentUser("ak-001");

            verify(redisTemplate).delete("gw:agent:user:ak-001");
        }

        @Test
        @DisplayName("agentUser 对 null/blank 参数安全处理")
        void agentUserHandlesNullParams() {
            broker.bindAgentUser(null, "user-1");
            broker.bindAgentUser("ak-1", null);
            assertNull(broker.getAgentUser(null));
            assertNull(broker.getAgentUser(""));
        }
    }
}
