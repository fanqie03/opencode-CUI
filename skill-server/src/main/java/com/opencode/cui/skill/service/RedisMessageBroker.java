package com.opencode.cui.skill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Redis pub/sub message broker for multi-instance coordination.
 *
 * Channel patterns:
 * - agent:{agentId} - messages to specific agent
 * - user-stream:{userId} - realtime messages to all instances holding the
 * user's stream link
 */
@Slf4j
@Service
public class RedisMessageBroker {

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    /** Track active subscriptions for cleanup */
    private final Map<String, MessageListener> activeListeners = new ConcurrentHashMap<>();

    public RedisMessageBroker(StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
    }

    /**
     * Publish a message to an agent channel.
     *
     * @param agentId the target agent ID
     * @param message the message to publish (as JSON string)
     */
    public void publishToAgent(String agentId, String message) {
        String channel = "agent:" + agentId;
        publishMessage(channel, message);
    }

    public void publishToUser(String userId, String message) {
        String channel = "user-stream:" + userId;
        publishMessage(channel, message);
    }

    /**
     * Subscribe to an agent channel.
     *
     * @param agentId the agent ID to subscribe to
     * @param handler callback to handle received messages (JSON string)
     */
    public void subscribeToAgent(String agentId, Consumer<String> handler) {
        String channel = "agent:" + agentId;
        subscribe(channel, handler);
    }

    public void subscribeToUser(String userId, Consumer<String> handler) {
        String channel = "user-stream:" + userId;
        subscribe(channel, handler);
    }

    /**
     * Unsubscribe from an agent channel.
     *
     * @param agentId the agent ID to unsubscribe from
     */
    public void unsubscribeFromAgent(String agentId) {
        String channel = "agent:" + agentId;
        unsubscribe(channel);
    }

    public void unsubscribeFromUser(String userId) {
        String channel = "user-stream:" + userId;
        unsubscribe(channel);
    }

    // ========== Internal methods ==========

    private void publishMessage(String channel, String message) {
        try {
            redisTemplate.convertAndSend(channel, message);
            log.info("Published to Redis channel {}", channel);
        } catch (Exception e) {
            log.error("Failed to publish to Redis channel {}: {}", channel, e.getMessage(), e);
        }
    }

    private void subscribe(String channel, Consumer<String> handler) {
        unsubscribe(channel);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody(), StandardCharsets.UTF_8);
                handler.accept(json);

                log.info("Received from Redis channel {}", channel);
            } catch (Exception e) {
                log.error("Failed to process message from channel {}: {}",
                        channel, e.getMessage(), e);
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

    public boolean isUserSubscribed(String userId) {
        String channel = "user-stream:" + userId;
        return activeListeners.containsKey(channel);
    }

    // ==================== SS relay pub/sub (Task 2.6) ====================

    private static final String SS_RELAY_CHANNEL_PREFIX = "ss:relay:";

    /**
     * Subscribe to this instance's SS relay channel.
     * Messages relayed from other SS instances arrive here.
     *
     * @param instanceId the local SS instance ID
     * @param handler    callback to handle received relay messages (JSON string)
     */
    public void subscribeToSsRelay(String instanceId, Consumer<String> handler) {
        String channel = SS_RELAY_CHANNEL_PREFIX + instanceId;
        subscribe(channel, handler);
    }

    /**
     * Publish a message to the target SS instance's relay channel.
     * Uses Redis PUBLISH which returns the number of subscribers that received the message.
     *
     * @param targetInstanceId the target SS instance ID
     * @param message          the message to relay (JSON string)
     * @return number of subscribers that received the message; 0 means nobody is listening
     */
    public long publishToSsRelay(String targetInstanceId, String message) {
        String channel = SS_RELAY_CHANNEL_PREFIX + targetInstanceId;
        try {
            Long receivers = redisTemplate.convertAndSend(channel, message);
            log.info("Published to SS relay channel: target={}, receivers={}", targetInstanceId, receivers);
            return receivers != null ? receivers : 0;
        } catch (Exception e) {
            log.error("Failed to publish to SS relay channel: target={}, error={}",
                    targetInstanceId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Unsubscribe from this instance's SS relay channel.
     *
     * @param instanceId the local SS instance ID
     */
    public void unsubscribeFromSsRelay(String instanceId) {
        String channel = SS_RELAY_CHANNEL_PREFIX + instanceId;
        unsubscribe(channel);
    }

    // ==================== Gateway 实例发现（v3 新增） ====================

    /**
     * 扫描 Redis 中所有 Gateway 实例注册 key。
     *
     * @return key → value 映射（key 格式: gw:instance:{id}，value: JSON）
     */
    private static final String GW_INSTANCE_KEY_PREFIX = "gw:instance:";

    public Map<String, String> scanGatewayInstances() {
        Map<String, String> result = new HashMap<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(GW_INSTANCE_KEY_PREFIX + "*")
                .count(100)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    String instanceId = key.substring(GW_INSTANCE_KEY_PREFIX.length());
                    result.put(instanceId, value);
                }
            }
        }
        return result;
    }

    // ==================== conn:ak 查询（v3 新增） ====================

    /**
     * 查询 AK 连接在哪个 Gateway 实例上。
     *
     * @return gatewayInstanceId，不存在返回 null
     */
    public String getConnAk(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get("conn:ak:" + ak);
    }
}
