package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.logging.StreamEventLogHelper;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import com.opencode.cui.skill.telemetry.metrics.WsConnectionMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ExternalStreamHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final String AUTH_PROTOCOL_PREFIX = "auth.";
    private static final String SOURCE_ATTR = "source";
    private static final String INSTANCE_ID_ATTR = "instanceId";
    private static final String CHANNEL_PREFIX = "stream:";

    private final ObjectMapper objectMapper;
    private final RedisMessageBroker redisMessageBroker;
    private final String inboundToken;
    private final ExternalWsRegistry wsRegistry;
    private final SkillInstanceRegistry instanceRegistry;
    private final DeliveryProperties deliveryProperties;
    private final WsConnectionMetrics wsConnectionMetrics;

    private static final String WS_URL = "/ws/external/stream";
    private static final String WS_BUSINESS = "external-module";
    private static final String WS_DIRECTION = "inbound";

    private final ConnectionPool connectionPool = new ConnectionPool();
    /** wsSessionId → last activity time */
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    public ExternalStreamHandler(ObjectMapper objectMapper,
                                  RedisMessageBroker redisMessageBroker,
                                  @Value("${skill.im.inbound-token:changeme}") String inboundToken,
                                  ExternalWsRegistry wsRegistry,
                                  SkillInstanceRegistry instanceRegistry,
                                  DeliveryProperties deliveryProperties,
                                  WsConnectionMetrics wsConnectionMetrics) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.inboundToken = inboundToken;
        this.wsRegistry = wsRegistry;
        this.instanceRegistry = instanceRegistry;
        this.deliveryProperties = deliveryProperties;
        this.wsConnectionMetrics = wsConnectionMetrics;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        HandshakeAuth auth = extractAuth(request);
        if (auth == null) {
            log.warn("Rejected external handshake: invalid auth subprotocol");
            return false;
        }
        attributes.put(SOURCE_ATTR, auth.source());
        attributes.put(INSTANCE_ID_ATTR, auth.instanceId());
        response.getHeaders().set("Sec-WebSocket-Protocol", auth.protocol());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String source = (String) session.getAttributes().get(SOURCE_ATTR);
        String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);

        connectionPool.add(source, instanceId, session);
        lastActivity.put(session.getId(), Instant.now());
        wsConnectionMetrics.connectionOpened(WS_URL, WS_BUSINESS, WS_DIRECTION);

        String channel = CHANNEL_PREFIX + source;
        if (!redisMessageBroker.isChannelSubscribed(channel)) {
            redisMessageBroker.subscribeToChannel(channel, msg -> handleRedisMessage(source, msg));
        }
        log.info("External WS connected: source={}, instanceId={}, sessionId={}", source, instanceId, session.getId());
        wsRegistry.register(source, connectionPool.countBySource(source));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        lastActivity.put(session.getId(), Instant.now());
        try {
            var node = objectMapper.readTree(textMessage.getPayload());
            String action = node.path("action").asText("");
            if ("ping".equals(action)) {
                session.sendMessage(new TextMessage("{\"action\":\"pong\"}"));
            }
        } catch (Exception e) {
            log.warn("Invalid message from external WS: sessionId={}, error={}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String source = (String) session.getAttributes().get(SOURCE_ATTR);
        String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);

        int remaining = connectionPool.remove(source, instanceId, session);
        lastActivity.remove(session.getId());
        wsConnectionMetrics.connectionClosed(WS_URL, WS_BUSINESS, WS_DIRECTION);

        if (remaining == 0) {
            redisMessageBroker.unsubscribeFromChannel(CHANNEL_PREFIX + source);
            wsRegistry.unregister(source);
        } else {
            wsRegistry.register(source, remaining);
        }
        log.info("External WS disconnected: source={}, instanceId={}, sessionId={}, status={}, remaining={}",
                source, instanceId, session.getId(), status, remaining);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("External WS transport error: sessionId={}, error={}", session.getId(), exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    public boolean hasActiveConnections(String source) {
        return connectionPool.hasActiveConnections(source);
    }

    public void pushToSource(String source, String message) {
        if (!connectionPool.hasActiveConnections(source)) {
            log.warn("No active connections for source: {}", source);
            return;
        }
        TextMessage textMessage = new TextMessage(message);
        connectionPool.forEach(source, (key, session) -> {
            if (session.isOpen()) {
                try {
                    synchronized (session) { session.sendMessage(textMessage); }
                    StreamEventLogHelper.outbound(log, "ss.external_ws", "sent", message);
                } catch (Exception e) {
                    log.error("Failed to push to external WS: source={}, key={}, error={}",
                            source, key, e.getMessage());
                }
            }
        });
    }

    /**
     * 精确投递：选择一条活跃 WS 连接推送消息。
     * 发送失败时自动移除该连接并重试下一条，最多 3 次。
     *
     * @return true 如果成功推送，false 如果无可用连接
     */
    public boolean pushToOne(String source, String message) {
        TextMessage textMessage = new TextMessage(message);
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            WebSocketSession session = connectionPool.pickOne(source);
            if (session == null) return false;
            try {
                synchronized (session) { session.sendMessage(textMessage); }
                StreamEventLogHelper.outbound(log, "ss.external_ws", "sent", message);
                return true;
            } catch (Exception e) {
                log.warn("pushToOne failed, removing session and retrying: source={}, sessionId={}, attempt={}/{}",
                        source, session.getId(), i + 1, maxRetries);
                connectionPool.removeBySessionId(source,
                        (String) session.getAttributes().get(INSTANCE_ID_ATTR),
                        session.getId());
            }
        }
        return false;
    }

    /**
     * 启动后订阅本实例的 external relay channel，接收其他实例中转过来的 external WS 消息。
     *
     * <p>必须使用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：
     * Spring 的 {@code RedisMessageListenerContainer} 是 {@code SmartLifecycle}（默认
     * {@code autoStartup=true}），会在所有 {@code @PostConstruct} 方法执行完毕之后才
     * {@code start()}。在 {@code @PostConstruct} 阶段调 {@code addMessageListener} 时
     * container 尚未 running，listener 仅被加进 {@code channelMapping} 但**不会真实**
     * 把 SUBSCRIBE 命令发到 Redis（spring-data-redis 3.4.6 + Lettuce 6.4.2 实测）。
     * 监听 {@code ApplicationReadyEvent} 时 container 已 start，
     * {@code addMessageListener} 会同步触发 SUBSCRIBE。详见 PRD D1。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeRelayChannel(ApplicationReadyEvent event) {
        String instanceId = instanceRegistry.getInstanceId();
        redisMessageBroker.subscribeToExternalRelay(instanceId, this::handleExternalRelayMessage);
        log.info("Subscribed to external relay channel: ss:external-relay:{}", instanceId);
    }

    private void handleExternalRelayMessage(String message) {
        try {
            var node = objectMapper.readTree(message);
            MdcHelper.fromJsonNode(node);
            MdcHelper.ensureTraceId();
            MdcHelper.putScenario("external-relay-rx");
            String domain = node.path("domain").asText(null);
            String payload = node.path("payload").asText(null);
            if (domain != null && payload != null) {
                pushToOne(domain, payload);
            }
        } catch (Exception e) {
            log.error("Failed to handle external relay message: {}", e.getMessage());
        }
    }

    /**
     * 周期任务：清理 idle 连接 + 整批同步本实例 held-by hash。
     *
     * <p>周期 10s 与 {@link SkillInstanceRegistry} 心跳一致；TTL 30s = 3× 心跳，
     * 给 GC pause / 网络抖动留余量，避免误过期。</p>
     *
     * <p>实现要点（owner-only writes）：
     * <ul>
     *   <li>取本地 {@code connectionPool.snapshotCountsBySource()}</li>
     *   <li>空 snapshot → {@code DEL external-ws:held-by:{selfId}}（避免残留过时字段）</li>
     *   <li>非空 → {@code HSET ... putAll(snapshot)} + {@code EXPIRE key 30s}</li>
     * </ul>
     */
    @Scheduled(fixedRate = 10_000)
    public void checkHeartbeatTimeouts() {
        Instant timeout = Instant.now().minusSeconds(60);
        connectionPool.forEachAll(session -> {
            Instant last = lastActivity.get(session.getId());
            if (last != null && last.isBefore(timeout) && session.isOpen()) {
                try {
                    log.warn("Closing external WS due to heartbeat timeout: sessionId={}", session.getId());
                    session.close(CloseStatus.GOING_AWAY);
                } catch (Exception e) {
                    log.error("Failed to close timed-out session: {}", e.getMessage());
                }
            }
        });
        Map<String, Integer> snapshot = connectionPool.snapshotCountsBySource();
        wsRegistry.heartbeatBatch(snapshot);
    }

    /**
     * 服务关闭时主动删除本实例 held-by key，加速其他实例 L2 投递跳过本实例（graceful 路径）。
     */
    @PreDestroy
    public void cleanupHeldBy() {
        try {
            wsRegistry.clearOnShutdown();
            log.info("[CLEANUP] ExternalStreamHandler.cleanupHeldBy: instanceId={}",
                    instanceRegistry.getInstanceId());
        } catch (Exception e) {
            log.warn("[CLEANUP] ExternalStreamHandler.cleanupHeldBy failed: error={}", e.getMessage());
        }
    }

    private void handleRedisMessage(String source, String message) { pushToSource(source, message); }

    private HandshakeAuth extractAuth(ServerHttpRequest request) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null || protocols.isEmpty()) return null;
        for (String protocolHeader : protocols) {
            for (String candidate : protocolHeader.split(",")) {
                String protocol = candidate.trim();
                if (protocol.startsWith(AUTH_PROTOCOL_PREFIX)) {
                    HandshakeAuth auth = verifyToken(protocol);
                    if (auth != null) return auth;
                }
            }
        }
        return null;
    }

    private HandshakeAuth verifyToken(String protocol) {
        String encoded = protocol.substring(AUTH_PROTOCOL_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String json = new String(decoded, StandardCharsets.UTF_8);
            var authNode = objectMapper.readTree(json);
            String token = authNode.path("token").asText(null);
            String source = authNode.path("source").asText(null);
            String instanceId = authNode.path("instanceId").asText(null);
            if (!inboundToken.equals(token) || source == null || source.isBlank()
                    || instanceId == null || instanceId.isBlank()) return null;
            return new HandshakeAuth(protocol, source, instanceId);
        } catch (Exception e) {
            log.warn("Failed to decode external auth subprotocol: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 多连接池：source → { instanceId → { wsSessionId → WebSocketSession } }。
     * 封装并发安全的连接管理，对外暴露简洁 API。
     */
    static class ConnectionPool {
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>> pool
                = new ConcurrentHashMap<>();

        void add(String source, String instanceId, WebSocketSession session) {
            pool.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), session);
        }

        int remove(String source, String instanceId, WebSocketSession session) {
            var instances = pool.get(source);
            if (instances == null) return 0;
            var sessions = instances.get(instanceId);
            if (sessions != null) {
                sessions.remove(session.getId(), session);
                if (sessions.isEmpty()) instances.remove(instanceId);
            }
            if (instances.isEmpty()) pool.remove(source);
            return countBySource(source);
        }

        int removeBySessionId(String source, String instanceId, String wsSessionId) {
            var instances = pool.get(source);
            if (instances == null) return 0;
            var sessions = instances.get(instanceId);
            if (sessions != null) {
                sessions.remove(wsSessionId);
                if (sessions.isEmpty()) instances.remove(instanceId);
            }
            if (instances.isEmpty()) pool.remove(source);
            return countBySource(source);
        }

        WebSocketSession pickOne(String source) {
            var instances = pool.get(source);
            if (instances == null) return null;
            for (var sessions : instances.values()) {
                for (WebSocketSession session : sessions.values()) {
                    if (session.isOpen()) return session;
                }
            }
            return null;
        }

        int countBySource(String source) {
            var instances = pool.get(source);
            if (instances == null) return 0;
            int count = 0;
            for (var sessions : instances.values()) {
                count += sessions.size();
            }
            return count;
        }

        boolean hasActiveConnections(String source) {
            return pickOne(source) != null;
        }

        java.util.Set<String> sources() {
            return pool.keySet();
        }

        /**
         * 整体 snapshot 本实例持有的 {@code {source → totalCount}}。
         * 用于 owner 周期心跳整批同步 Redis {@code held-by:{selfId}} hash，
         * 一次 putAll + EXPIRE 完成续命，避免逐 domain 多次 RTT。
         */
        Map<String, Integer> snapshotCountsBySource() {
            Map<String, Integer> snap = new HashMap<>();
            for (var entry : pool.entrySet()) {
                int total = 0;
                for (var sessions : entry.getValue().values()) {
                    total += sessions.size();
                }
                if (total > 0) {
                    snap.put(entry.getKey(), total);
                }
            }
            return snap;
        }

        void forEach(String source, java.util.function.BiConsumer<String, WebSocketSession> action) {
            var instances = pool.get(source);
            if (instances == null) return;
            for (var entry : instances.entrySet()) {
                String instanceId = entry.getKey();
                for (var sessionEntry : entry.getValue().entrySet()) {
                    action.accept(instanceId + ":" + sessionEntry.getKey(), sessionEntry.getValue());
                }
            }
        }

        void forEachAll(java.util.function.Consumer<WebSocketSession> action) {
            for (var instances : pool.values()) {
                for (var sessions : instances.values()) {
                    for (WebSocketSession session : sessions.values()) {
                        action.accept(session);
                    }
                }
            }
        }
    }

    private record HandshakeAuth(String protocol, String source, String instanceId) {}
}
