package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Redis 消息代理，用于 Gateway 多实例协调。
 *
 * <h3>Key 模式（v3 重构后）</h3>
 * <ul>
 *   <li>{@code conn:ak:{ak}} — Agent 连接在哪个 Gateway 实例上（KV + TTL，供 SS 查询）</li>
 *   <li>{@code gw:internal:agent:{ak}} — GW 内部中转用的 Agent 位置注册（KV + TTL，与 conn:ak 双写）</li>
 *   <li>{@code gw:source-conn:{sourceType}:{sourceInstanceId}} — Source 连接注册 HASH（gwInstanceId → timestamp）</li>
 *   <li>{@code gw:l2:source:{sourceType}:{targetGw}} — Source L2 单目标 mailbox Stream</li>
 *   <li>{@code gw:route:{toolSessionId}} — Session 路由映射（sourceType:sourceInstanceId）</li>
 *   <li>{@code gw:route:w:{welinkSessionId}} — WeLink Session 路由映射（sourceType:sourceInstanceId）</li>
 *   <li>{@code gw:cloud-stream:{toolSessionId}} — 云端流持有者（gatewayInstanceId）</li>
 *   <li>{@code gw:agent:user:{ak}} — AK→userId 绑定（保留）</li>
 *   <li>{@code auth:nonce:{nonce}} — 认证防重放（保留，由 AkSkAuthService 管理）</li>
 * </ul>
 *
 * <h3>Channel 模式</h3>
 * <ul>
 *   <li>{@code agent:{ak}} — 路由消息到持有 Agent WS 的 Gateway 实例（{@link EventRelayService} 使用）</li>
 *   <li>{@code gw:relay:{instanceId}} — V2 GW 实例间 relay 通道（下行，SS→Agent），
 *       消息格式为 {@link RelayMessage} JSON</li>
 * </ul>
 */
@Slf4j
@Service
public class RedisMessageBroker {

    // ==================== 新增 Key 前缀（v3） ====================

    private static final String CONN_AK_KEY_PREFIX = "conn:ak:";

    /** Key prefix for GW-internal agent registry: gw:internal:agent:{ak} → instanceId */
    private static final String INTERNAL_AGENT_KEY_PREFIX = "gw:internal:agent:";

    /** Key prefix for active cloud stream owner: gw:cloud-stream:{toolSessionId} → gatewayInstanceId */
    private static final String CLOUD_STREAM_KEY_PREFIX = "gw:cloud-stream:";
    private static final String CLOUD_STREAM_OWNERS_KEY_SUFFIX = ":owners";

    // ==================== 保留的 Key/Channel 前缀 ====================

    private static final String AGENT_CHANNEL_PREFIX = "agent:";
    private static final String AGENT_USER_KEY_PREFIX = "gw:agent:user:";

    /** Channel prefix for GW-to-GW relay: gw:relay:{instanceId} */
    private static final String GW_RELAY_CHANNEL_PREFIX = "gw:relay:";

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

    // ==================== gw:pending:{ak} 下行消息缓冲队列（Phase 1.6） ====================

    /** Key prefix for per-agent downlink pending queue: gw:pending:{ak} → Redis List of GatewayMessage JSON */
    private static final String PENDING_KEY_PREFIX = "gw:pending:";

    /**
     * Lua script: atomically fetch all elements and delete the list.
     * Returns all list elements as a multi-bulk reply, then deletes the key.
     * This prevents a race where a new message is enqueued between LRANGE and DEL.
     */
    private static final DefaultRedisScript<List> DRAIN_PENDING_SCRIPT = new DefaultRedisScript<>(
            "local msgs = redis.call('LRANGE', KEYS[1], 0, -1)\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "return msgs",
            List.class);

    /**
     * Enqueues a downlink message for an offline agent.
     *
     * <p>Appends {@code message} to the Redis List at {@code gw:pending:{ak}} and refreshes
     * the list TTL. The TTL is reset on every push so it counts from the last enqueue.
     *
     * @param ak      Agent Access Key
     * @param message serialized GatewayMessage JSON string
     * @param ttl     expiry for the entire pending list
     */
    public void enqueuePending(String ak, String message, Duration ttl) {
        if (ak == null || ak.isBlank() || message == null) {
            return;
        }
        String key = pendingKey(ak);
        redisTemplate.opsForList().rightPush(key, message);
        redisTemplate.expire(key, ttl);
        log.info("[ENTRY] RedisMessageBroker.enqueuePending: ak={}, queueKey={}", ak, key);
    }

    /**
     * Atomically drains all pending messages for the given agent and clears the queue.
     *
     * <p>Uses a Lua script to LRANGE + DEL in one round-trip, preventing any concurrent
     * enqueue from being silently lost between a plain LRANGE and a subsequent DEL.
     *
     * @param ak Agent Access Key
     * @return list of GatewayMessage JSON strings in FIFO order; empty list if none
     */
    @SuppressWarnings("unchecked")
    public List<String> drainPending(String ak) {
        if (ak == null || ak.isBlank()) {
            return Collections.emptyList();
        }
        String key = pendingKey(ak);
        List<String> messages = redisTemplate.execute(DRAIN_PENDING_SCRIPT, java.util.List.of(key));
        if (messages == null) {
            return Collections.emptyList();
        }
        log.info("RedisMessageBroker.drainPending: ak={}, count={}", ak, messages.size());
        return messages;
    }

    private String pendingKey(String ak) {
        return PENDING_KEY_PREFIX + ak;
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
    public boolean conditionalRemoveConnAk(String ak, String expectedInstanceId) {
        if (ak == null || ak.isBlank() || expectedInstanceId == null) {
            return false;
        }
        Long removed = redisTemplate.execute(CONDITIONAL_DELETE_SCRIPT,
                java.util.List.of(connAkKey(ak)), expectedInstanceId);
        return removed != null && removed > 0;
    }

    // ==================== 云端流持有者路由表 ====================

    /**
     * Records the GW instance that currently owns a cloud streaming connection.
     *
     * <p>Abort control frames may arrive at a different GW instance than the one holding the
     * SSE/WebSocket connection. This short-lived key lets that receiver relay the abort to the owner.</p>
     *
     * @param toolSessionId     tool session ID of the active cloud stream
     * @param gatewayInstanceId GW instance ID holding the stream
     * @param ttl               expiry for the ownership key
     */
    public void setCloudStreamRoute(String toolSessionId, String gatewayInstanceId, Duration ttl) {
        if (toolSessionId == null || toolSessionId.isBlank()
                || gatewayInstanceId == null || gatewayInstanceId.isBlank()
                || ttl == null) {
            return;
        }
        redisTemplate.opsForValue().set(cloudStreamKey(toolSessionId), gatewayInstanceId, ttl);
        String ownersKey = cloudStreamOwnersKey(toolSessionId);
        redisTemplate.opsForSet().add(ownersKey, gatewayInstanceId);
        redisTemplate.expire(ownersKey, ttl);
        log.info("RedisMessageBroker.setCloudStreamRoute: toolSessionId={}, gatewayInstanceId={}",
                toolSessionId, gatewayInstanceId);
    }

    /**
     * Looks up the GW instance that owns the cloud streaming connection for a tool session.
     *
     * @param toolSessionId tool session ID
     * @return gatewayInstanceId, or null if absent
     */
    public String getCloudStreamRoute(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(cloudStreamKey(toolSessionId));
    }

    /**
     * Looks up all GW instances that currently own active cloud streams for a tool session.
     *
     * @param toolSessionId tool session ID
     * @return owner gateway IDs, or an empty set when absent
     */
    public Set<String> getCloudStreamOwners(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> owners = redisTemplate.opsForSet().members(cloudStreamOwnersKey(toolSessionId));
        return owners == null ? Collections.emptySet() : owners;
    }

    /**
     * Removes a cloud stream owner key only if it is still owned by the expected GW instance.
     *
     * @param toolSessionId              tool session ID
     * @param expectedGatewayInstanceId  expected owner instance ID
     */
    public void removeCloudStreamRoute(String toolSessionId, String expectedGatewayInstanceId) {
        if (toolSessionId == null || toolSessionId.isBlank()
                || expectedGatewayInstanceId == null || expectedGatewayInstanceId.isBlank()) {
            return;
        }
        redisTemplate.execute(CONDITIONAL_DELETE_SCRIPT,
                java.util.List.of(cloudStreamKey(toolSessionId)), expectedGatewayInstanceId);
        redisTemplate.opsForSet().remove(cloudStreamOwnersKey(toolSessionId), expectedGatewayInstanceId);
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

    // ==================== gw:internal:agent 内部路由注册表（Phase 1.3 新增） ====================

    /**
     * Binds an AK to the GW instance ID in the internal agent registry.
     * Called alongside {@link #bindConnAk} on successful Agent registration.
     *
     * <p>Key: {@code gw:internal:agent:{ak}} → value: instanceId string.</p>
     *
     * @param ak         Agent Access Key
     * @param instanceId GW instance ID holding the Agent WebSocket connection
     * @param ttl        TTL (same as heartbeat interval + buffer)
     */
    public void bindInternalAgent(String ak, String instanceId, Duration ttl) {
        if (ak == null || ak.isBlank() || instanceId == null || instanceId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(internalAgentKey(ak), instanceId, ttl);
        log.info("[ENTRY] RedisMessageBroker.bindInternalAgent: ak={}, instanceId={}", ak, instanceId);
    }

    /**
     * Looks up which GW instance holds the given Agent's WebSocket connection,
     * using the internal registry. Used for intra-GW routing.
     *
     * @param ak Agent Access Key
     * @return GW instance ID, or {@code null} if not found
     */
    public String getInternalAgentInstance(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        String instanceId = redisTemplate.opsForValue().get(internalAgentKey(ak));
        log.info("RedisMessageBroker.getInternalAgentInstance: ak={}, instanceId={}", ak, instanceId);
        return instanceId;
    }

    /**
     * Removes the AK entry from the internal agent registry.
     * Prefer {@link #conditionalRemoveInternalAgent(String, String)} for disconnect
     * cleanup so a late close cannot remove a newer owner.
     *
     * @param ak Agent Access Key
     */
    public void removeInternalAgent(String ak) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.delete(internalAgentKey(ak));
        log.info("RedisMessageBroker.removeInternalAgent: ak={}", ak);
    }

    public boolean conditionalRemoveInternalAgent(String ak, String expectedInstanceId) {
        if (ak == null || ak.isBlank() || expectedInstanceId == null || expectedInstanceId.isBlank()) {
            return false;
        }
        Long removed = redisTemplate.execute(CONDITIONAL_DELETE_SCRIPT,
                java.util.List.of(internalAgentKey(ak)), expectedInstanceId);
        log.info("RedisMessageBroker.conditionalRemoveInternalAgent: ak={}, expected={}, removed={}",
                ak, expectedInstanceId, removed);
        return removed != null && removed > 0;
    }

    /**
     * Refreshes the TTL of the internal agent registry entry. Called on heartbeat,
     * alongside {@link #refreshConnAkTtl}.
     *
     * @param ak  Agent Access Key
     * @param ttl new TTL
     */
    public void refreshInternalAgentTtl(String ak, Duration ttl) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.expire(internalAgentKey(ak), ttl);
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

    // ==================== GW relay pub/sub（Phase 1.4 新增） ====================

    /**
     * Subscribes this GW instance to its own relay channel {@code gw:relay:{instanceId}}.
     *
     * <p>The handler receives raw JSON strings. Messages in the new format carry a
     * {@code "type":"relay"} wrapper ({@link RelayMessage}); legacy messages are raw
     * {@link GatewayMessage} JSON without that wrapper.
     *
     * <p>Call this once during {@code @PostConstruct} initialization with the self instance ID.
     *
     * @param instanceId this GW instance's ID
     * @param handler    callback that receives the raw JSON string from Redis
     */
    public void subscribeToGwRelay(String instanceId, Consumer<String> handler) {
        String channel = GW_RELAY_CHANNEL_PREFIX + instanceId;
        unsubscribeFromGwRelay(instanceId);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody());
                handler.accept(json);
                log.debug("Received from GW relay channel {}: length={}", channel, json.length());
            } catch (Exception e) {
                log.error("Failed to process message from GW relay channel {}: {}", channel, e.getMessage(), e);
            }
        };
        activeListeners.put(channel, listener);
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
        log.info("Subscribed to GW relay channel: {}", channel);
    }

    /**
     * Publishes a raw JSON string (a serialized {@link RelayMessage}) to the target GW instance's
     * relay channel {@code gw:relay:{targetInstanceId}}.
     *
     * @param targetInstanceId the GW instance ID that owns the target Agent's WebSocket connection
     * @param message          serialized {@link RelayMessage} JSON
     */
    public void publishToGwRelay(String targetInstanceId, String message) {
        String channel = GW_RELAY_CHANNEL_PREFIX + targetInstanceId;
        try {
            redisTemplate.convertAndSend(channel, message);
            log.debug("Published to GW relay channel {}: length={}", channel, message.length());
        } catch (Exception e) {
            log.error("Failed to publish to GW relay channel {}: {}", channel, e.getMessage(), e);
        }
    }

    /**
     * Unsubscribes from this GW instance's relay channel. Called on shutdown or re-subscription.
     *
     * @param instanceId this GW instance's ID
     */
    public void unsubscribeFromGwRelay(String instanceId) {
        String channel = GW_RELAY_CHANNEL_PREFIX + instanceId;
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

    // ==================== Source 连接注册 (gw:source-conn) ====================

    /** Key prefix for source connection registry: gw:source-conn:{sourceType}:{sourceInstanceId} */
    private static final String SOURCE_CONN_KEY_PREFIX = "gw:source-conn:";

    /** TTL for source connection HASH keys (2 hours). */
    private static final Duration SOURCE_CONN_TTL = Duration.ofHours(2);

    private static final String SOURCE_L2_STREAM_KEY_PREFIX = "gw:l2:source:";
    private static final String SOURCE_L2_GROUP_PREFIX = "gw-l2-";
    private static final String SOURCE_L2_DEAD_LETTER_SUFFIX = ":dead";
    private static final Duration SOURCE_L2_MAILBOX_TTL = Duration.ofHours(2);
    private static final String SOURCE_L2_FIELD_PAYLOAD = "payload";
    private static final String SOURCE_L2_FIELD_ROUTING_KEY = "routingKey";
    private static final String SOURCE_L2_FIELD_TRACE_ID = "traceId";
    private static final String SOURCE_L2_FIELD_MESSAGE_TYPE = "messageType";
    private static final String SOURCE_L2_FIELD_TARGET_GW = "targetGw";
    private static final String SOURCE_L2_FIELD_ENQUEUED_AT = "enqueuedAt";
    private static final String SOURCE_L2_FIELD_ATTEMPT = "attempt";
    private static final String SOURCE_L2_FIELD_REQUEUED_AT = "requeuedAt";
    private static final String SOURCE_L2_FIELD_FAILED_STREAM_ID = "failedStreamId";
    private static final String SOURCE_L2_FIELD_FAILURE_REASON = "failureReason";
    private static final String SOURCE_L2_FIELD_DEAD_LETTERED_AT = "deadLetteredAt";

    public record SourceL2Work(String id, Map<String, String> fields) {
        public String payload() {
            return fields.get(SOURCE_L2_FIELD_PAYLOAD);
        }

        public String routingKey() {
            return fields.get(SOURCE_L2_FIELD_ROUTING_KEY);
        }

        public String traceId() {
            return fields.get(SOURCE_L2_FIELD_TRACE_ID);
        }

        public String messageType() {
            return fields.get(SOURCE_L2_FIELD_MESSAGE_TYPE);
        }

        public String targetGw() {
            return fields.get(SOURCE_L2_FIELD_TARGET_GW);
        }

        public int attempt() {
            String raw = fields.get(SOURCE_L2_FIELD_ATTEMPT);
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    /**
     * Registers a source connection in Redis.
     * Called when a Source WebSocket connection is established.
     *
     * <p>Key: {@code gw:source-conn:{sourceType}:{sourceInstanceId}} (HASH)
     * <br>Field: gwInstanceId, Value: epoch_seconds timestamp
     *
     * @param sourceType       source type, e.g. "skill-server"
     * @param sourceInstanceId source instance ID
     * @param gwInstanceId     this GW instance's ID
     */
    public void registerSourceConnection(String sourceType, String sourceInstanceId, String gwInstanceId) {
        if (sourceType == null || sourceInstanceId == null || gwInstanceId == null) {
            return;
        }
        String key = sourceConnKey(sourceType, sourceInstanceId);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        redisTemplate.opsForHash().put(key, gwInstanceId, timestamp);
        redisTemplate.expire(key, SOURCE_CONN_TTL);
        log.info("RedisMessageBroker.registerSourceConnection: sourceType={}, sourceInstanceId={}, gwInstanceId={}",
                sourceType, sourceInstanceId, gwInstanceId);
    }

    /**
     * Unregisters a source connection from Redis.
     * Called when a Source WebSocket connection is closed.
     *
     * @param sourceType       source type
     * @param sourceInstanceId source instance ID
     * @param gwInstanceId     this GW instance's ID
     */
    public void unregisterSourceConnection(String sourceType, String sourceInstanceId, String gwInstanceId) {
        if (sourceType == null || sourceInstanceId == null || gwInstanceId == null) {
            return;
        }
        String key = sourceConnKey(sourceType, sourceInstanceId);
        redisTemplate.opsForHash().delete(key, gwInstanceId);
        log.info("RedisMessageBroker.unregisterSourceConnection: sourceType={}, sourceInstanceId={}, gwInstanceId={}",
                sourceType, sourceInstanceId, gwInstanceId);
    }

    /**
     * Refreshes the heartbeat timestamp for a source connection.
     *
     * @param sourceType       source type
     * @param sourceInstanceId source instance ID
     * @param gwInstanceId     this GW instance's ID
     */
    public void refreshSourceConnectionHeartbeat(String sourceType, String sourceInstanceId, String gwInstanceId) {
        if (sourceType == null || sourceInstanceId == null || gwInstanceId == null) {
            return;
        }
        String key = sourceConnKey(sourceType, sourceInstanceId);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        redisTemplate.opsForHash().put(key, gwInstanceId, timestamp);
        redisTemplate.expire(key, SOURCE_CONN_TTL);
    }

    // ==================== 4-param overloads (connection-level, gwInstanceId#sessionId) ====================

    /**
     * Registers a source connection with connection-level granularity.
     * Writes compound field {@code gwInstanceId#sessionId} AND compat field {@code gwInstanceId}
     * (dual-write) so that legacy callers reading the HASH still find a value.
     *
     * @param sourceType       source type, e.g. "skill-server"
     * @param sourceInstanceId source instance ID
     * @param gwInstanceId     this GW instance's ID
     * @param sessionId        the specific WebSocket session ID for this connection
     */
    public void registerSourceConnection(String sourceType, String sourceInstanceId,
            String gwInstanceId, String sessionId) {
        if (sourceType == null || sourceInstanceId == null || gwInstanceId == null || sessionId == null) {
            return;
        }
        String key = sourceConnKey(sourceType, sourceInstanceId);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String compoundField = gwInstanceId + "#" + sessionId;
        redisTemplate.opsForHash().put(key, compoundField, timestamp);
        // dual-write compat field so legacy readers find at least one entry
        redisTemplate.opsForHash().put(key, gwInstanceId, timestamp);
        redisTemplate.expire(key, SOURCE_CONN_TTL);
        log.info("RedisMessageBroker.registerSourceConnection: sourceType={}, sourceInstanceId={}, gwInstanceId={}, sessionId={}",
                sourceType, sourceInstanceId, gwInstanceId, sessionId);
    }

    /**
     * Unregisters a source connection by its compound field {@code gwInstanceId#sessionId}.
     * The compat field {@code gwInstanceId} is intentionally NOT deleted here because other
     * concurrent connections from the same GW instance may still be alive.
     *
     * @param sourceType       source type
     * @param sourceInstanceId source instance ID
     * @param gwInstanceId     this GW instance's ID
     * @param sessionId        the specific WebSocket session ID for this connection
     */
    public void unregisterSourceConnection(String sourceType, String sourceInstanceId,
            String gwInstanceId, String sessionId) {
        if (sourceType == null || sourceInstanceId == null || gwInstanceId == null || sessionId == null) {
            return;
        }
        String key = sourceConnKey(sourceType, sourceInstanceId);
        String compoundField = gwInstanceId + "#" + sessionId;
        redisTemplate.opsForHash().delete(key, compoundField);
        log.info("RedisMessageBroker.unregisterSourceConnection: sourceType={}, sourceInstanceId={}, gwInstanceId={}, sessionId={}",
                sourceType, sourceInstanceId, gwInstanceId, sessionId);
    }

    /**
     * Refreshes the heartbeat for a specific connection (compound field) and also refreshes
     * the compat field so that legacy readers see an up-to-date timestamp.
     *
     * @param sourceType       source type
     * @param sourceInstanceId source instance ID
     * @param gwInstanceId     this GW instance's ID
     * @param sessionId        the specific WebSocket session ID for this connection
     */
    public void refreshSourceConnectionHeartbeat(String sourceType, String sourceInstanceId,
            String gwInstanceId, String sessionId) {
        if (sourceType == null || sourceInstanceId == null || gwInstanceId == null || sessionId == null) {
            return;
        }
        String key = sourceConnKey(sourceType, sourceInstanceId);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String compoundField = gwInstanceId + "#" + sessionId;
        redisTemplate.opsForHash().put(key, compoundField, timestamp);
        // dual-write compat field
        redisTemplate.opsForHash().put(key, gwInstanceId, timestamp);
        redisTemplate.expire(key, SOURCE_CONN_TTL);
    }

    /**
     * Extracts unique GW instance IDs from a source-connection map whose keys may be either
     * compound ({@code gwInstanceId#sessionId}) or plain legacy ({@code gwInstanceId}).
     *
     * @param sourceConnections map of HASH field → timestamp (as returned by
     *                          {@link #getSourceConnections})
     * @return set of unique gwInstanceIds; empty set if input is null or empty
     */
    public Set<String> extractUniqueGwInstances(Map<String, Long> sourceConnections) {
        if (sourceConnections == null || sourceConnections.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String field : sourceConnections.keySet()) {
            int sep = field.indexOf('#');
            if (sep > 0) {
                result.add(field.substring(0, sep));
            } else {
                result.add(field);
            }
        }
        return result;
    }

    /**
     * Returns all GW instances that hold connections to the specified source,
     * with lazy cleanup of stale entries (older than 30 seconds).
     *
     * @param sourceType       source type
     * @param sourceInstanceId source instance ID
     * @return map of gwInstanceId to epoch seconds timestamp
     */
    public Map<String, Long> getSourceConnections(String sourceType, String sourceInstanceId) {
        if (sourceType == null || sourceInstanceId == null) {
            return Collections.emptyMap();
        }
        String key = sourceConnKey(sourceType, sourceInstanceId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return Collections.emptyMap();
        }

        long now = Instant.now().getEpochSecond();
        Map<String, Long> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String gwId = String.valueOf(entry.getKey());
            try {
                long ts = Long.parseLong(String.valueOf(entry.getValue()));
                if (now - ts > 30) {
                    // Lazy cleanup: remove stale entry
                    redisTemplate.opsForHash().delete(key, gwId);
                    log.info("RedisMessageBroker.getSourceConnections: cleaned stale entry gwId={}, age={}s",
                            gwId, now - ts);
                } else {
                    result.put(gwId, ts);
                }
            } catch (NumberFormatException e) {
                redisTemplate.opsForHash().delete(key, gwId);
            }
        }
        return result;
    }

    /**
     * Cleans up all source connection entries belonging to this GW instance.
     * Called on GW startup to handle crash-restart scenarios.
     *
     * <p>Scans all keys matching {@code gw:source-conn:*} and removes fields
     * where the field name matches the given gwInstanceId.
     *
     * @param gwInstanceId this GW instance's ID
     */
    public void cleanupStaleSourceConnections(String gwInstanceId) {
        if (gwInstanceId == null || gwInstanceId.isBlank()) {
            return;
        }
        Set<String> keys = redisTemplate.keys(SOURCE_CONN_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        String prefix = gwInstanceId + "#";
        int cleaned = 0;
        for (String key : keys) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            for (Object fieldObj : entries.keySet()) {
                String field = String.valueOf(fieldObj);
                if (field.equals(gwInstanceId) || field.startsWith(prefix)) {
                    redisTemplate.opsForHash().delete(key, field);
                    cleaned++;
                }
            }
        }
        log.info("RedisMessageBroker.cleanupStaleSourceConnections: gwInstanceId={}, cleanedFields={}",
                gwInstanceId, cleaned);
    }

    private String sourceConnKey(String sourceType, String sourceInstanceId) {
        return SOURCE_CONN_KEY_PREFIX + sourceType + ":" + sourceInstanceId;
    }

    /**
     * Discovers all GW instance IDs that currently hold Source connections of any type.
     * Scans all {@code gw:source-conn:*} keys in Redis and collects active GW instance IDs.
     *
     * @return set of GW instance IDs (excluding stale entries older than 30s)
     */
    public Set<String> discoverAllSourceGwInstances() {
        Set<String> keys = redisTemplate.keys(SOURCE_CONN_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        long now = Instant.now().getEpochSecond();
        Set<String> gwIds = new HashSet<>();
        for (String key : keys) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                String field = String.valueOf(entry.getKey());
                try {
                    long ts = Long.parseLong(String.valueOf(entry.getValue()));
                    if (now - ts <= 30) {
                        // extract gwInstanceId: strip sessionId suffix from compound field
                        int sep = field.indexOf('#');
                        String gwId = sep > 0 ? field.substring(0, sep) : field;
                        gwIds.add(gwId);
                    }
                } catch (NumberFormatException ignored) {
                    // skip invalid entries
                }
            }
        }
        return gwIds;
    }

    /**
     * Discovers GW instance IDs that currently hold connections for one source type.
     *
     * <p>This is used by GW-to-SS L2 mailbox routing to choose exactly one target GW
     * that has a local Source WebSocket for the requested source type.</p>
     */
    public Set<String> discoverSourceGwInstances(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> keys = redisTemplate.keys(SOURCE_CONN_KEY_PREFIX + sourceType + ":*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        long now = Instant.now().getEpochSecond();
        Set<String> gwIds = new HashSet<>();
        for (String key : keys) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                String field = String.valueOf(entry.getKey());
                try {
                    long ts = Long.parseLong(String.valueOf(entry.getValue()));
                    if (now - ts <= 30) {
                        int sep = field.indexOf('#');
                        gwIds.add(sep > 0 ? field.substring(0, sep) : field);
                    } else {
                        redisTemplate.opsForHash().delete(key, field);
                    }
                } catch (NumberFormatException ignored) {
                    redisTemplate.opsForHash().delete(key, field);
                }
            }
        }
        return gwIds;
    }

    // ==================== Source L2 stream (GW-local Redis) ====================

    public String enqueueSourceL2Work(String sourceType, String targetGwId, String payload, String routingKey,
                                      String traceId, String messageType, long maxLen) {
        if (sourceType == null || sourceType.isBlank()
                || targetGwId == null || targetGwId.isBlank()
                || payload == null || payload.isBlank()) {
            return null;
        }
        String streamKey = sourceL2StreamKey(sourceType, targetGwId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(SOURCE_L2_FIELD_PAYLOAD, payload);
        fields.put(SOURCE_L2_FIELD_TARGET_GW, targetGwId);
        putIfNotBlank(fields, SOURCE_L2_FIELD_ROUTING_KEY, routingKey);
        putIfNotBlank(fields, SOURCE_L2_FIELD_TRACE_ID, traceId);
        putIfNotBlank(fields, SOURCE_L2_FIELD_MESSAGE_TYPE, messageType);
        fields.put(SOURCE_L2_FIELD_ENQUEUED_AT, String.valueOf(Instant.now().toEpochMilli()));
        fields.put(SOURCE_L2_FIELD_ATTEMPT, "0");

        try {
            RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);
            ensureSourceL2Group(sourceType, targetGwId);
            trimSourceL2Stream(streamKey, maxLen);
            redisTemplate.expire(streamKey, SOURCE_L2_MAILBOX_TTL);
            return recordId == null ? null : recordId.getValue();
        } catch (Exception e) {
            log.error("RedisMessageBroker.enqueueSourceL2Work: failed, sourceType={}, targetGw={}, type={}",
                    sourceType, targetGwId, messageType, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<SourceL2Work> readSourceL2Work(String sourceType, String targetGwId, String consumerName,
                                               int count, Duration blockTimeout) {
        if (sourceType == null || sourceType.isBlank()
                || targetGwId == null || targetGwId.isBlank()
                || consumerName == null || consumerName.isBlank()) {
            return Collections.emptyList();
        }
        ensureSourceL2Group(sourceType, targetGwId);
        String streamKey = sourceL2StreamKey(sourceType, targetGwId);
        String group = sourceL2Group(sourceType, targetGwId);
        StreamReadOptions options = StreamReadOptions.empty().count(Math.max(1, count));
        if (blockTimeout != null && !blockTimeout.isNegative() && !blockTimeout.isZero()) {
            options = options.block(blockTimeout);
        }
        try {
            List<MapRecord<String, Object, Object>> records = (List<MapRecord<String, Object, Object>>) (List<?>)
                    redisTemplate.opsForStream().read(
                            org.springframework.data.redis.connection.stream.Consumer.from(group, consumerName),
                            options,
                            StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) {
                return Collections.emptyList();
            }
            return records.stream()
                    .map(this::toSourceL2Work)
                    .toList();
        } catch (Exception e) {
            if (isMissingStreamOrGroup(e)) {
                log.debug("RedisMessageBroker.readSourceL2Work: stream/group not ready, sourceType={}, targetGw={}",
                        sourceType, targetGwId);
                return Collections.emptyList();
            }
            log.error("RedisMessageBroker.readSourceL2Work: failed, sourceType={}, targetGw={}, consumer={}",
                    sourceType, targetGwId, consumerName, e);
            return Collections.emptyList();
        }
    }

    public void ackSourceL2Work(String sourceType, String targetGwId, String streamId) {
        if (sourceType == null || sourceType.isBlank()
                || targetGwId == null || targetGwId.isBlank()
                || streamId == null || streamId.isBlank()) {
            return;
        }
        redisTemplate.opsForStream().acknowledge(
                sourceL2StreamKey(sourceType, targetGwId), sourceL2Group(sourceType, targetGwId), streamId);
    }

    public String requeueSourceL2Work(String sourceType, String targetGwId, SourceL2Work work,
                                      int nextAttempt, long maxLen) {
        if (sourceType == null || sourceType.isBlank()
                || targetGwId == null || targetGwId.isBlank()
                || work == null || work.payload() == null) {
            return null;
        }
        String streamKey = sourceL2StreamKey(sourceType, targetGwId);
        Map<String, String> fields = new LinkedHashMap<>(work.fields());
        fields.put(SOURCE_L2_FIELD_TARGET_GW, targetGwId);
        fields.put(SOURCE_L2_FIELD_ATTEMPT, String.valueOf(Math.max(0, nextAttempt)));
        fields.put(SOURCE_L2_FIELD_REQUEUED_AT, String.valueOf(Instant.now().toEpochMilli()));
        try {
            RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);
            trimSourceL2Stream(streamKey, maxLen);
            redisTemplate.expire(streamKey, SOURCE_L2_MAILBOX_TTL);
            return recordId == null ? null : recordId.getValue();
        } catch (Exception e) {
            log.error("RedisMessageBroker.requeueSourceL2Work: failed, sourceType={}, targetGw={}, streamId={}",
                    sourceType, targetGwId, work.id(), e);
            return null;
        }
    }

    public String deadLetterSourceL2Work(String sourceType, String targetGwId, SourceL2Work work, String failureReason) {
        if (sourceType == null || sourceType.isBlank()
                || targetGwId == null || targetGwId.isBlank()
                || work == null || work.payload() == null) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>(work.fields());
        fields.put(SOURCE_L2_FIELD_TARGET_GW, targetGwId);
        fields.put(SOURCE_L2_FIELD_FAILED_STREAM_ID, work.id());
        fields.put(SOURCE_L2_FIELD_FAILURE_REASON, failureReason == null ? "unknown" : failureReason);
        fields.put(SOURCE_L2_FIELD_DEAD_LETTERED_AT, String.valueOf(Instant.now().toEpochMilli()));
        try {
            RecordId recordId = redisTemplate.opsForStream().add(sourceL2DeadLetterKey(sourceType, targetGwId), fields);
            return recordId == null ? null : recordId.getValue();
        } catch (Exception e) {
            log.error("RedisMessageBroker.deadLetterSourceL2Work: failed, sourceType={}, targetGw={}, streamId={}",
                    sourceType, targetGwId, work.id(), e);
            return null;
        }
    }

    private SourceL2Work toSourceL2Work(MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((key, value) ->
                fields.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        return new SourceL2Work(record.getId().getValue(), fields);
    }

    private void ensureSourceL2Group(String sourceType, String targetGwId) {
        String streamKey = sourceL2StreamKey(sourceType, targetGwId);
        String group = sourceL2Group(sourceType, targetGwId);
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), group);
        } catch (Exception e) {
            if (isGroupAlreadyExists(e) || isMissingStreamOrGroup(e)) {
                log.debug("RedisMessageBroker.ensureSourceL2Group: ignored, sourceType={}, targetGw={}, reason={}",
                        sourceType, targetGwId, e.getMessage());
                return;
            }
            log.warn("RedisMessageBroker.ensureSourceL2Group: failed, sourceType={}, targetGw={}, reason={}",
                    sourceType, targetGwId, e.getMessage());
        }
    }

    private void trimSourceL2Stream(String streamKey, long maxLen) {
        if (maxLen <= 0) {
            return;
        }
        try {
            redisTemplate.opsForStream().trim(streamKey, maxLen, true);
        } catch (Exception e) {
            log.debug("RedisMessageBroker.trimSourceL2Stream: failed, key={}, reason={}",
                    streamKey, e.getMessage());
        }
    }

    private String sourceL2StreamKey(String sourceType, String targetGwId) {
        return SOURCE_L2_STREAM_KEY_PREFIX + sourceType + ":" + targetGwId;
    }

    private String sourceL2DeadLetterKey(String sourceType, String targetGwId) {
        return sourceL2StreamKey(sourceType, targetGwId) + SOURCE_L2_DEAD_LETTER_SUFFIX;
    }

    private String sourceL2Group(String sourceType, String targetGwId) {
        return SOURCE_L2_GROUP_PREFIX + sourceType + ":" + targetGwId;
    }

    private static void putIfNotBlank(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }

    private static boolean isGroupAlreadyExists(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("BUSYGROUP");
    }

    private static boolean isMissingStreamOrGroup(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("NOGROUP")
                || message.contains("no such key")
                || message.contains("requires the key to exist");
    }

    // ==================== Session route (gw:route) ====================

    /** Key prefix for session route: gw:route:{toolSessionId} */
    private static final String SESSION_ROUTE_KEY_PREFIX = "gw:route:";

    /** Key prefix for welink session route: gw:route:w:{welinkSessionId} */
    private static final String WELINK_SESSION_ROUTE_KEY_PREFIX = "gw:route:w:";

    /** TTL for session route keys (2 hours). */
    private static final Duration SESSION_ROUTE_TTL = Duration.ofHours(2);

    /**
     * Sets a session route mapping: toolSessionId -> sourceType:sourceInstanceId.
     * Called when an invoke message arrives, to learn the source for this session.
     *
     * @param toolSessionId    tool session ID
     * @param sourceType       source type
     * @param sourceInstanceId source instance ID
     */
    public void setSessionRoute(String toolSessionId, String sourceType, String sourceInstanceId) {
        if (toolSessionId == null || toolSessionId.isBlank() || sourceType == null || sourceInstanceId == null) {
            return;
        }
        String key = SESSION_ROUTE_KEY_PREFIX + toolSessionId;
        String value = sourceType + ":" + sourceInstanceId;
        redisTemplate.opsForValue().set(key, value, SESSION_ROUTE_TTL);
        log.info("RedisMessageBroker.setSessionRoute: toolSessionId={}, route={}", toolSessionId, value);
    }

    /**
     * Sets a welink session route mapping: welinkSessionId -> sourceType:sourceInstanceId.
     *
     * @param welinkSessionId  welink session ID
     * @param sourceType       source type
     * @param sourceInstanceId source instance ID
     */
    public void setWelinkSessionRoute(String welinkSessionId, String sourceType, String sourceInstanceId) {
        if (welinkSessionId == null || welinkSessionId.isBlank() || sourceType == null || sourceInstanceId == null) {
            return;
        }
        String key = WELINK_SESSION_ROUTE_KEY_PREFIX + welinkSessionId;
        String value = sourceType + ":" + sourceInstanceId;
        redisTemplate.opsForValue().set(key, value, SESSION_ROUTE_TTL);
        log.info("RedisMessageBroker.setWelinkSessionRoute: welinkSessionId={}, route={}", welinkSessionId, value);
    }

    /**
     * Gets the session route for a toolSessionId.
     *
     * @param toolSessionId tool session ID
     * @return "sourceType:sourceInstanceId" or null if not found
     */
    public String getSessionRoute(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(SESSION_ROUTE_KEY_PREFIX + toolSessionId);
    }

    /**
     * Gets the session route for a welinkSessionId.
     *
     * @param welinkSessionId welink session ID
     * @return "sourceType:sourceInstanceId" or null if not found
     */
    public String getWelinkSessionRoute(String welinkSessionId) {
        if (welinkSessionId == null || welinkSessionId.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(WELINK_SESSION_ROUTE_KEY_PREFIX + welinkSessionId);
    }

    // ==================== to-source relay ====================

    /**
     * Publishes a to-source relay message to the target GW instance's relay channel.
     *
     * @param targetGwId             target GW instance ID
     * @param targetSourceType       target source type
     * @param targetSourceInstanceId target source instance ID
     * @param payload                the message payload to deliver
     */
    public void publishToSourceRelay(String targetGwId, String targetSourceType,
                                     String targetSourceInstanceId, String payload) {
        if (targetGwId == null || targetSourceType == null || targetSourceInstanceId == null || payload == null) {
            return;
        }
        try {
            RelayMessage relayMessage = RelayMessage.toSource(targetSourceType, targetSourceInstanceId, payload);
            String json = objectMapper.writeValueAsString(relayMessage);
            publishToGwRelay(targetGwId, json);
            log.debug("RedisMessageBroker.publishToSourceRelay: targetGw={}, sourceType={}, sourceInstanceId={}",
                    targetGwId, targetSourceType, targetSourceInstanceId);
        } catch (Exception e) {
            log.error("RedisMessageBroker.publishToSourceRelay: failed, targetGw={}, error={}",
                    targetGwId, e.getMessage(), e);
        }
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

    private String internalAgentKey(String ak) {
        return INTERNAL_AGENT_KEY_PREFIX + ak;
    }

    private String cloudStreamKey(String toolSessionId) {
        return CLOUD_STREAM_KEY_PREFIX + toolSessionId;
    }

    private String cloudStreamOwnersKey(String toolSessionId) {
        return cloudStreamKey(toolSessionId) + CLOUD_STREAM_OWNERS_KEY_SUFFIX;
    }

    private String agentUserKey(String ak) {
        return AGENT_USER_KEY_PREFIX + ak;
    }
}
