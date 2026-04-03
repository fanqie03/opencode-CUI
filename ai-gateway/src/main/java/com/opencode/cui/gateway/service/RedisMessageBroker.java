package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
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
import java.util.LinkedHashSet;
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
 *   <li>{@code gw:route:{toolSessionId}} — Session 路由映射（sourceType:sourceInstanceId）</li>
 *   <li>{@code gw:route:w:{welinkSessionId}} — WeLink Session 路由映射（sourceType:sourceInstanceId）</li>
 *   <li>{@code gw:agent:user:{ak}} — AK→userId 绑定（保留）</li>
 *   <li>{@code auth:nonce:{nonce}} — 认证防重放（保留，由 AkSkAuthService 管理）</li>
 * </ul>
 *
 * <h3>Channel 模式</h3>
 * <ul>
 *   <li>{@code agent:{ak}} — 路由 invoke 命令到持有 Agent WS 的 Gateway 实例（保留，Phase 2 后可废弃）</li>
 *   <li>{@code gw:relay:{instanceId}} — V2 GW 实例间 relay 通道（下行，SS→Agent），
 *       消息格式为 {@link RelayMessage} JSON</li>
 *   <li>{@code gw:legacy-relay:{instanceId}} — Legacy GW 实例间 relay 通道（上行，Agent→SS），
 *       消息格式为裸 {@link GatewayMessage} JSON</li>
 * </ul>
 */
@Slf4j
@Service
public class RedisMessageBroker {

    // ==================== 新增 Key 前缀（v3） ====================

    private static final String CONN_AK_KEY_PREFIX = "conn:ak:";

    /** Key prefix for GW-internal agent registry: gw:internal:agent:{ak} → instanceId */
    private static final String INTERNAL_AGENT_KEY_PREFIX = "gw:internal:agent:";

    // ==================== 保留的 Key/Channel 前缀 ====================

    private static final String AGENT_CHANNEL_PREFIX = "agent:";
    private static final String AGENT_USER_KEY_PREFIX = "gw:agent:user:";

    /** Channel prefix for GW-to-GW relay: gw:relay:{instanceId} */
    private static final String GW_RELAY_CHANNEL_PREFIX = "gw:relay:";

    /** Channel prefix for Legacy GW-to-GW relay: gw:legacy-relay:{instanceId} */
    private static final String GW_LEGACY_RELAY_CHANNEL_PREFIX = "gw:legacy-relay:";

    // ==================== 废弃的 Key/Channel 前缀（Phase 1.3 重写后移除） ====================
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
     * Removes the AK entry from the internal agent registry on Agent disconnect.
     * Uses unconditional delete (paired with {@link #conditionalRemoveConnAk} for the
     * external {@code conn:ak} key; for simplicity, the internal key mirrors the same
     * lifecycle — callers ensure correctness by only removing on the owning instance).
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
                log.info("Received from GW relay channel {}: length={}", channel, json.length());
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
            log.info("Published to GW relay channel {}: length={}", channel, message.length());
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

    // ==================== Legacy GW relay pub/sub ====================

    /**
     * 将 {@link GatewayMessage} 发布到目标 GW 实例的 legacy relay channel
     * {@code gw:legacy-relay:{targetInstanceId}}。
     *
     * <p>供 {@link LegacySkillRelayStrategy} 处理上行（Agent→SS）跨 GW 路由时使用。
     *
     * @param instanceId 目标 GW 实例 ID
     * @param message    需要中转的 GatewayMessage
     */
    public void publishToLegacyRelay(String instanceId, GatewayMessage message) {
        publishMessage(legacyRelayChannel(instanceId), message);
    }

    /**
     * 订阅 legacy relay channel {@code gw:legacy-relay:{instanceId}}。
     *
     * @param instanceId 本 GW 实例 ID
     * @param handler    接收反序列化后 {@link GatewayMessage} 的回调
     */
    public void subscribeToLegacyRelay(String instanceId, Consumer<GatewayMessage> handler) {
        subscribe(legacyRelayChannel(instanceId), handler);
    }

    /**
     * 取消订阅 legacy relay channel {@code gw:legacy-relay:{instanceId}}。
     *
     * @param instanceId 本 GW 实例 ID
     */
    public void unsubscribeFromLegacyRelay(String instanceId) {
        unsubscribe(legacyRelayChannel(instanceId));
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

    // ==================== Session 路由映射 (gw:route) ====================

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
            log.info("RedisMessageBroker.publishToSourceRelay: targetGw={}, sourceType={}, sourceInstanceId={}",
                    targetGwId, targetSourceType, targetSourceInstanceId);
        } catch (Exception e) {
            log.error("RedisMessageBroker.publishToSourceRelay: failed, targetGw={}, error={}",
                    targetGwId, e.getMessage(), e);
        }
    }

    // ==================== 废弃方法（Phase 1.3 SkillRelayService 重写后删除） ====================

    @Deprecated
    public void publishToRelay(String instanceId, GatewayMessage message) {
        publishToLegacyRelay(instanceId, message);
    }

    @Deprecated
    public void subscribeToRelay(String instanceId, Consumer<GatewayMessage> handler) {
        subscribeToLegacyRelay(instanceId, handler);
    }

    @Deprecated
    public void unsubscribeFromRelay(String instanceId) {
        unsubscribeFromLegacyRelay(instanceId);
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

    private String internalAgentKey(String ak) {
        return INTERNAL_AGENT_KEY_PREFIX + ak;
    }

    private String agentUserKey(String ak) {
        return AGENT_USER_KEY_PREFIX + ak;
    }

    private String legacyRelayChannel(String instanceId) {
        return GW_LEGACY_RELAY_CHANNEL_PREFIX + instanceId;
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
