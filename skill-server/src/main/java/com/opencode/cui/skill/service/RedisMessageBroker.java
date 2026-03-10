package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Redis pub/sub message broker for multi-instance coordination.
 *
 * Channel patterns:
 * - agent:{agentId} - messages to specific agent
 * - session:{sessionId} - messages to specific session
 *
 * Sequence tracking:
 * - Each session maintains a sequence number for message ordering
 * - Sequence numbers are included in published messages
 */
@Slf4j
@Service
public class RedisMessageBroker {

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    /** Track sequence numbers per session */
    private final Map<String, AtomicLong> sessionSequences = new ConcurrentHashMap<>();

    /** Track active subscriptions for cleanup */
    private final Map<String, MessageListener> activeListeners = new ConcurrentHashMap<>();

    public RedisMessageBroker(StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish a message to an agent channel.
     *
     * @param agentId the target agent ID
     * @param message the message to publish (as JSON string)
     */
    public void publishToAgent(String agentId, String message) {
        String channel = "agent:" + agentId;
        publishMessage(channel, message, null);
    }

    /**
     * Publish a message to a session channel with sequence number.
     *
     * @param sessionId the target session ID
     * @param message   the message to publish (as JSON string)
     */
    public void publishToSession(String sessionId, String message) {
        String channel = "session:" + sessionId;

        // Get and increment sequence number for this session
        AtomicLong sequence = sessionSequences.computeIfAbsent(
                sessionId, k -> new AtomicLong(0));
        long seqNum = sequence.incrementAndGet();

        publishMessage(channel, message, seqNum);
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

    /**
     * Subscribe to a session channel.
     *
     * @param sessionId the session ID to subscribe to
     * @param handler   callback to handle received messages (JSON string)
     */
    public void subscribeToSession(String sessionId, Consumer<String> handler) {
        String channel = "session:" + sessionId;
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

    /**
     * Unsubscribe from a session channel.
     *
     * @param sessionId the session ID to unsubscribe from
     */
    public void unsubscribeFromSession(String sessionId) {
        String channel = "session:" + sessionId;
        unsubscribe(channel);

        // Clean up sequence tracker
        sessionSequences.remove(sessionId);
    }

    // ========== Internal methods ==========

    private void publishMessage(String channel, String message, Long sequenceNumber) {
        try {
            // Add sequence number if provided
            String enriched = message;
            if (sequenceNumber != null) {
                JsonNode node = objectMapper.readTree(message);
                if (node.isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                            .put("sequenceNumber", sequenceNumber);
                    enriched = objectMapper.writeValueAsString(node);
                }
            }

            redisTemplate.convertAndSend(channel, enriched);

            log.debug("Published to Redis channel {}: seq={}", channel, sequenceNumber);
        } catch (JsonProcessingException e) {
            log.error("Failed to process message for channel {}: {}", channel, e.getMessage(), e);
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

                log.debug("Received from Redis channel {}", channel);
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

    /**
     * Check if a session channel has an active subscription.
     *
     * @param sessionId the session ID to check
     * @return true if the session channel is currently subscribed
     */
    public boolean isSessionSubscribed(String sessionId) {
        String channel = "session:" + sessionId;
        return activeListeners.containsKey(channel);
    }

    /**
     * Get the current sequence number for a session (for testing/monitoring).
     */
    public long getSessionSequence(String sessionId) {
        AtomicLong sequence = sessionSequences.get(sessionId);
        return sequence != null ? sequence.get() : 0;
    }
}
