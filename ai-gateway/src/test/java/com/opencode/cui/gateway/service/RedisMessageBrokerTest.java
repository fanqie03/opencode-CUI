package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOperations);
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

    // ==================== cloud stream route ====================

    @Nested
    @DisplayName("cloud stream 路由表")
    class CloudStreamRouteTests {

        @Test
        @DisplayName("setCloudStreamRoute writes owner gateway with TTL")
        void setCloudStreamRouteWritesOwnerWithTtl() {
            broker.setCloudStreamRoute("tool-001", "gw-owner", Duration.ofSeconds(660));

            verify(valueOperations).set("gw:cloud-stream:tool-001", "gw-owner", Duration.ofSeconds(660));
            verify(setOperations).add("gw:cloud-stream:tool-001:owners", "gw-owner");
            verify(redisTemplate).expire("gw:cloud-stream:tool-001:owners", Duration.ofSeconds(660));
        }

        @Test
        @DisplayName("getCloudStreamRoute returns stored owner gateway")
        void getCloudStreamRouteReturnsStoredOwner() {
            when(valueOperations.get("gw:cloud-stream:tool-001")).thenReturn("gw-owner");

            assertEquals("gw-owner", broker.getCloudStreamRoute("tool-001"));
        }

        @Test
        @DisplayName("getCloudStreamOwners returns all owner gateways")
        void getCloudStreamOwnersReturnsAllOwners() {
            when(setOperations.members("gw:cloud-stream:tool-001:owners"))
                    .thenReturn(Set.of("gw-a", "gw-b"));

            assertEquals(Set.of("gw-a", "gw-b"), broker.getCloudStreamOwners("tool-001"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("removeCloudStreamRoute uses conditional owner delete")
        void removeCloudStreamRouteUsesConditionalOwnerDelete() {
            broker.removeCloudStreamRoute("tool-001", "gw-owner");

            verify(redisTemplate).execute(any(), anyList(), any(Object[].class));
            verify(setOperations).remove("gw:cloud-stream:tool-001:owners", "gw-owner");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("cloud stream route ignores blank params")
        void cloudStreamRouteIgnoresBlankParams() {
            broker.setCloudStreamRoute("", "gw-owner", Duration.ofSeconds(660));
            broker.setCloudStreamRoute("tool-001", "", Duration.ofSeconds(660));
            broker.setCloudStreamRoute("tool-001", "gw-owner", null);
            assertNull(broker.getCloudStreamRoute(""));
            broker.removeCloudStreamRoute("", "gw-owner");
            broker.removeCloudStreamRoute("tool-001", "");

            verify(valueOperations, never()).set(
                    eq("gw:cloud-stream:"), eq("gw-owner"), any(Duration.class));
            verify(setOperations, never()).add(eq("gw:cloud-stream:tool-001:owners"), eq(""));
            verify(setOperations, never()).remove(eq("gw:cloud-stream:tool-001:owners"), eq(""));
            verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
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

    @Nested
    @DisplayName("source L2 stream")
    class SourceL2StreamTests {

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Test
        @DisplayName("enqueueSourceL2Work writes one target mailbox stream record and ensures group")
        void enqueueSourceL2WorkWritesMailboxStreamRecord() {
            when(streamOperations.add(eq("gw:l2:source:skill-server:gw-remote"), any(Map.class)))
                    .thenReturn(RecordId.of("1-0"));

            String streamId = broker.enqueueSourceL2Work(
                    "skill-server", "gw-remote", "{\"type\":\"tool_done\"}", "msg-1", "trace-1", "tool_done", 10000);

            assertEquals("1-0", streamId);
            verify(streamOperations).add(eq("gw:l2:source:skill-server:gw-remote"), any(Map.class));
            verify(streamOperations).createGroup(
                    eq("gw:l2:source:skill-server:gw-remote"), any(ReadOffset.class), eq("gw-l2-skill-server:gw-remote"));
            verify(streamOperations).trim("gw:l2:source:skill-server:gw-remote", 10000, true);
            verify(redisTemplate).expire("gw:l2:source:skill-server:gw-remote", Duration.ofHours(2));
        }

        @Test
        @DisplayName("ackSourceL2Work acknowledges the target mailbox stream group")
        void ackSourceL2WorkAcknowledgesMailboxGroup() {
            broker.ackSourceL2Work("skill-server", "gw-remote", "1-0");

            verify(streamOperations).acknowledge("gw:l2:source:skill-server:gw-remote", "gw-l2-skill-server:gw-remote", "1-0");
        }

        @Test
        @DisplayName("cleanupOrphanSourceL2Mailboxes deletes empty inactive target mailbox")
        void cleanupOrphanSourceL2MailboxesDeletesEmptyInactiveMailbox() {
            when(redisTemplate.keys("gw:l2:source:skill-server:*"))
                    .thenReturn(Set.of(
                            "gw:l2:source:skill-server:gw-live",
                            "gw:l2:source:skill-server:gw-old",
                            "gw:l2:source:skill-server:gw-old:dead"));
            when(streamOperations.size("gw:l2:source:skill-server:gw-old")).thenReturn(0L);

            RedisMessageBroker.SourceL2MailboxCleanupResult result = broker.cleanupOrphanSourceL2Mailboxes(
                    "skill-server", Set.of("gw-live"), Duration.ofSeconds(30));

            assertEquals(2, result.scanned());
            assertEquals(1, result.active());
            assertEquals(1, result.emptyDeleted());
            assertEquals(0, result.orphanExpiring());
            assertEquals(0, result.errors());
            verify(redisTemplate).delete("gw:l2:source:skill-server:gw-old");
            verify(streamOperations, never()).size("gw:l2:source:skill-server:gw-live");
        }

        @Test
        @DisplayName("cleanupOrphanSourceL2Mailboxes keeps non-empty inactive target mailbox with short TTL")
        void cleanupOrphanSourceL2MailboxesKeepsNonEmptyInactiveMailboxWithTtl() {
            when(redisTemplate.keys("gw:l2:source:skill-server:*"))
                    .thenReturn(Set.of("gw:l2:source:skill-server:gw-old"));
            when(streamOperations.size("gw:l2:source:skill-server:gw-old")).thenReturn(3L);

            RedisMessageBroker.SourceL2MailboxCleanupResult result = broker.cleanupOrphanSourceL2Mailboxes(
                    "skill-server", Set.of("gw-live"), Duration.ofSeconds(30));

            assertEquals(1, result.scanned());
            assertEquals(0, result.active());
            assertEquals(0, result.emptyDeleted());
            assertEquals(1, result.orphanExpiring());
            assertEquals(0, result.errors());
            verify(redisTemplate).expire("gw:l2:source:skill-server:gw-old", Duration.ofSeconds(30));
            verify(redisTemplate, never()).delete("gw:l2:source:skill-server:gw-old");
        }
    }

}
