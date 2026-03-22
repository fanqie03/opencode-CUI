package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Redis 消息代理，用于 Gateway 多实例协调。
 *
 * <h3>Key 模式（v3 重构后）</h3>
 * <ul>
 *   <li>{@code conn:ak:{ak}} — Agent 连接在哪个 Gateway 实例上（KV + TTL）</li>
 *   <li>{@code gw:instance:{id}} — Gateway 实例注册（由 GatewayInstanceRegistry 管理）</li>
 *   <li>{@code gw:agent:user:{ak}} — AK→userId 绑定（保留）</li>
 *   <li>{@code auth:nonce:{nonce}} — 认证防重放（保留，由 AkSkAuthService 管理）</li>
 * </ul>
 *
 * <h3>Channel 模式</h3>
 * <ul>
 *   <li>{@code agent:{ak}} — 路由 invoke 命令到持有 Agent WS 的 Gateway 实例（保留，Phase 2 后可废弃）</li>
 * </ul>
 */
@Slf4j
@Service
public class RedisMessageBroker {

    // ==================== 新增 Key 前缀（v3） ====================

    private static final String CONN_AK_KEY_PREFIX = "conn:ak:";

    // ==================== 保留的 Key/Channel 前缀 ====================

    private static final String AGENT_CHANNEL_PREFIX = "agent:";
    private static final String AGENT_USER_KEY_PREFIX = "gw:agent:user:";

    // ==================== 废弃的 Key/Channel 前缀（Phase 1.3 重写后移除） ====================

    @Deprecated
    private static final String RELAY_CHANNEL_PREFIX = "gw:relay:";
    @Deprecated
    private static final String SOURCE_OWNER_KEY_PREFIX = "gw:source:owner:";
    @Deprecated
    private static final String SOURCE_OWNERS_SET_PREFIX = "gw:source:owners:";
    @Deprecated
    private static final String AGENT_SOURCE_KEY_PREFIX = "gw:agent:source:";

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    /** Track active subscriptions for cleanup */
    private final Map<String, MessageListener> activeListeners = new ConcurrentHashMap<>();

    public RedisMessageBroker(StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
    }

    // ==================== conn:ak 连接注册表（v3 新增） ====================

    /**
     * 绑定 AK 到 Gateway 实例 ID。Agent 注册成功时调用。
     *
     * @param ak               Agent Access Key
     * @param gatewayInstanceId 持有该 Agent WS 连接的 Gateway 实例 ID
     * @param ttl              过期时间（心跳刷新间隔 + 缓冲）
     */
    public void bindConnAk(String ak, String gatewayInstanceId, Duration ttl) {
        if (ak == null || ak.isBlank() || gatewayInstanceId == null || gatewayInstanceId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(connAkKey(ak), gatewayInstanceId, ttl);
    }

    /**
     * 查询 AK 连接在哪个 Gateway 实例上。
     *
     * @return gatewayInstanceId，不存在返回 null
     */
    public String getConnAk(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(connAkKey(ak));
    }

    /**
     * 无条件删除 AK 的连接注册。
     */
    public void removeConnAk(String ak) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.delete(connAkKey(ak));
    }

    /** Lua 脚本：原子 CAS 删除，解决 GET + 比较 + DELETE 的 TOCTOU 竞态 */
    private static final DefaultRedisScript<Long> CONDITIONAL_DELETE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 条件删除：仅当当前值等于 expectedInstanceId 时才删除（原子操作）。
     * 防止误删已重连到其他 Gateway 实例的 Agent。
     *
     * @param ak                 Agent Access Key
     * @param expectedInstanceId 预期的 Gateway 实例 ID（本实例 ID）
     */
    public void conditionalRemoveConnAk(String ak, String expectedInstanceId) {
        if (ak == null || ak.isBlank() || expectedInstanceId == null) {
            return;
        }
        redisTemplate.execute(CONDITIONAL_DELETE_SCRIPT,
                java.util.List.of(connAkKey(ak)), expectedInstanceId);
    }

    /**
     * 刷新 AK 连接注册的 TTL。Agent 心跳时调用。
     */
    public void refreshConnAkTtl(String ak, Duration ttl) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.expire(connAkKey(ak), ttl);
    }

    // ==================== Agent pub/sub（保留） ====================

    public void publishToAgent(String agentId, GatewayMessage message) {
        String channel = AGENT_CHANNEL_PREFIX + agentId;
        publishMessage(channel, message);
    }

    public void subscribeToAgent(String agentId, Consumer<GatewayMessage> handler) {
        String channel = AGENT_CHANNEL_PREFIX + agentId;
        subscribe(channel, handler);
    }

    public void unsubscribeFromAgent(String agentId) {
        String channel = AGENT_CHANNEL_PREFIX + agentId;
        unsubscribe(channel);
    }

    // ==================== agentUser（保留） ====================

    public void bindAgentUser(String ak, String userId) {
        if (ak == null || ak.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(agentUserKey(ak), userId);
    }

    public String getAgentUser(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(agentUserKey(ak));
    }

    public void removeAgentUser(String ak) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.delete(agentUserKey(ak));
    }

    // ==================== 废弃方法（Phase 1.3 SkillRelayService 重写后删除） ====================

    @Deprecated
    public void publishToRelay(String instanceId, GatewayMessage message) {
        publishMessage(relayChannel(instanceId), message);
    }

    @Deprecated
    public void subscribeToRelay(String instanceId, Consumer<GatewayMessage> handler) {
        subscribe(relayChannel(instanceId), handler);
    }

    @Deprecated
    public void unsubscribeFromRelay(String instanceId) {
        unsubscribe(relayChannel(instanceId));
    }

    @Deprecated
    public void refreshSourceOwner(String source, String instanceId, Duration ttl) {
        if (source == null || source.isBlank() || instanceId == null || instanceId.isBlank()) {
            return;
        }
        String ownerKey = sourceOwnerMember(source, instanceId);
        redisTemplate.opsForValue().set(sourceOwnerKey(ownerKey), "alive", ttl);
        redisTemplate.opsForSet().add(sourceOwnersSetKey(source), ownerKey);
    }

    @Deprecated
    public void removeSourceOwner(String source, String instanceId) {
        if (source == null || source.isBlank() || instanceId == null || instanceId.isBlank()) {
            return;
        }
        String ownerKey = sourceOwnerMember(source, instanceId);
        redisTemplate.delete(sourceOwnerKey(ownerKey));
        redisTemplate.opsForSet().remove(sourceOwnersSetKey(source), ownerKey);
    }

    @Deprecated
    public boolean hasActiveSourceOwner(String source, String instanceId) {
        if (source == null || source.isBlank() || instanceId == null || instanceId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(sourceOwnerKey(sourceOwnerMember(source, instanceId))));
    }

    @Deprecated
    public Set<String> getActiveSourceOwners(String source) {
        if (source == null || source.isBlank()) {
            return Set.of();
        }
        Set<String> owners = redisTemplate.opsForSet().members(sourceOwnersSetKey(source));
        if (owners == null || owners.isEmpty()) {
            return Set.of();
        }
        Set<String> activeOwners = new LinkedHashSet<>();
        for (String owner : owners) {
            if (owner == null || owner.isBlank()) {
                continue;
            }
            String ownerSource = sourceFromOwnerKey(owner);
            String ownerInstanceId = instanceIdFromOwnerKey(owner);
            if (!source.equals(ownerSource) || ownerInstanceId == null) {
                redisTemplate.opsForSet().remove(sourceOwnersSetKey(source), owner);
                continue;
            }
            if (hasActiveSourceOwner(ownerSource, ownerInstanceId)) {
                activeOwners.add(owner);
            } else {
                redisTemplate.opsForSet().remove(sourceOwnersSetKey(source), owner);
            }
        }
        return activeOwners;
    }

    @Deprecated
    public static String sourceOwnerMember(String source, String instanceId) {
        return source + ":" + instanceId;
    }

    @Deprecated
    public static String sourceFromOwnerKey(String ownerKey) {
        int separatorIndex = ownerKey != null ? ownerKey.indexOf(':') : -1;
        if (separatorIndex <= 0) {
            return null;
        }
        return ownerKey.substring(0, separatorIndex);
    }

    @Deprecated
    public static String instanceIdFromOwnerKey(String ownerKey) {
        int separatorIndex = ownerKey != null ? ownerKey.indexOf(':') : -1;
        if (separatorIndex <= 0 || separatorIndex == ownerKey.length() - 1) {
            return null;
        }
        return ownerKey.substring(separatorIndex + 1);
    }

    @Deprecated
    public void bindAgentSource(String ak, String source) {
        if (ak == null || ak.isBlank() || source == null || source.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(agentSourceKey(ak), source);
    }

    @Deprecated
    public String getAgentSource(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(agentSourceKey(ak));
    }

    @Deprecated
    public void removeAgentSource(String ak) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.delete(agentSourceKey(ak));
    }

    // ==================== Internal methods ====================

    private void publishMessage(String channel, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, json);
            log.debug("Published to Redis channel {}: type={}", channel, message.getType());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message for channel {}: {}", channel, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to publish to Redis channel {}: {}", channel, e.getMessage(), e);
        }
    }

    private void subscribe(String channel, Consumer<GatewayMessage> handler) {
        unsubscribe(channel);
        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody());
                GatewayMessage gatewayMessage = objectMapper.readValue(json, GatewayMessage.class);
                handler.accept(gatewayMessage);
                log.debug("Received from Redis channel {}: type={}", channel, gatewayMessage.getType());
            } catch (Exception e) {
                log.error("Failed to process message from channel {}: {}", channel, e.getMessage(), e);
            }
        };
        activeListeners.put(channel, listener);
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        log.info("Subscribed to Redis channel: {}", channel);
    }

    private void unsubscribe(String channel) {
        MessageListener listener = activeListeners.remove(channel);
        if (listener != null) {
            listenerContainer.removeMessageListener(listener);
            log.info("Unsubscribed from Redis channel: {}", channel);
        }
    }

    // ==================== Key 构造方法 ====================

    private String connAkKey(String ak) {
        return CONN_AK_KEY_PREFIX + ak;
    }

    private String agentUserKey(String ak) {
        return AGENT_USER_KEY_PREFIX + ak;
    }

    @Deprecated
    private String relayChannel(String instanceId) {
        return RELAY_CHANNEL_PREFIX + instanceId;
    }

    @Deprecated
    private String sourceOwnerKey(String ownerKey) {
        return SOURCE_OWNER_KEY_PREFIX + ownerKey;
    }

    @Deprecated
    private String sourceOwnersSetKey(String source) {
        return SOURCE_OWNERS_SET_PREFIX + source;
    }

    @Deprecated
    private String agentSourceKey(String ak) {
        return AGENT_SOURCE_KEY_PREFIX + ak;
    }
}
