package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.ConsistentHashRing;
import com.opencode.cui.skill.service.GatewayDiscoveryService;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.SessionRouteService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway WebSocket 连接池管理。
 *
 * <h3>v3 重构</h3>
 * <ul>
 * <li>从连单个 GW 改为连接所有 GW 实例（全连接网格）</li>
 * <li>通过 {@link GatewayDiscoveryService} 动态发现 GW 实例变化</li>
 * <li>支持按 instanceId 精确发送和广播到所有 GW</li>
 * <li>每个 GW 连接独立重连逻辑</li>
 * </ul>
 *
 * <h3>兼容旧版 Gateway</h3>
 * <p>
 * 通过 {@code skill.gateway.ws-url} 配置种子 URL，旧版 GW 不注册
 * {@code gw:instance:*} key 时仍可通过种子 URL 直连。
 * 种子连接和 discovery 动态连接并存。
 * </p>
 */
@Slf4j
@Component
public class GatewayWSClient implements GatewayRelayService.GatewayRelayTarget,
        GatewayDiscoveryService.Listener {

    private static final String INVALID_INTERNAL_TOKEN_REASON = "invalid internal token";
    private static final String AUTH_PROTOCOL_PREFIX = "auth.";

    private final GatewayRelayService gatewayRelayService;
    private final ObjectMapper objectMapper;
    private final GatewayDiscoveryService discoveryService;
    private final SessionRouteService sessionRouteService;

    @Value("${skill.gateway.internal-token:changeme}")
    private String internalToken;

    @Value("${skill.instance-id:${HOSTNAME:skill-server-local}}")
    private String instanceId;

    /** 种子 URL：兼容旧版 GW（不注册 gw:instance:* 的 GW） */
    @Value("${skill.gateway.ws-url:}")
    private String seedWsUrl;

    @Value("${skill.gateway.reconnect-initial-delay-ms:1000}")
    private long reconnectInitialDelayMs;

    @Value("${skill.gateway.reconnect-max-delay-ms:30000}")
    private long reconnectMaxDelayMs;

    @Value("${skill.hash-ring.virtual-nodes:150}")
    private int virtualNodes;

    /** gwInstanceId → 连接信息 */
    private final Map<String, GwConnection> gwConnections = new ConcurrentHashMap<>();

    /** Consistent hash ring for GW connection selection (gwInstanceId → GwConnection). */
    private final ConsistentHashRing<GwConnection> hashRing = new ConsistentHashRing<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gw-ws-pool");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GatewayWSClient(GatewayRelayService gatewayRelayService,
            ObjectMapper objectMapper,
            GatewayDiscoveryService discoveryService,
            SessionRouteService sessionRouteService) {
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
        this.discoveryService = discoveryService;
        this.sessionRouteService = sessionRouteService;
    }

    @PostConstruct
    public void init() {
        if ("changeme".equals(internalToken)) {
            log.warn("skill.gateway.internal-token is using the default value 'changeme'. "
                    + "This is insecure for production.");
        }
        gatewayRelayService.setGatewayRelayTarget(this);
        running.set(true);

        // 注册为 DiscoveryService 的监听者
        if (discoveryService != null) {
            discoveryService.addListener(this);
        }

        // 种子连接：兼容旧版 GW（不注册 gw:instance:* 的 GW）
        if (seedWsUrl != null && !seedWsUrl.isBlank()) {
            String seedId = "seed-" + extractHostPort(seedWsUrl);
            log.info("Connecting to seed Gateway: id={}, url={}", seedId, seedWsUrl);
            connectToGateway(seedId, seedWsUrl);
        }

        log.info("GatewayWSClient initialized: instanceId={}, seedUrl={}", instanceId,
                seedWsUrl != null && !seedWsUrl.isBlank() ? seedWsUrl : "none");
    }

    /**
     * 定期触发 GW 实例发现。
     */
    @Scheduled(fixedDelayString = "${skill.gateway.discovery-interval-ms:10000}")
    public void triggerDiscovery() {
        if (running.get() && discoveryService != null) {
            try {
                discoveryService.discover();
            } catch (Exception e) {
                log.error("Gateway discovery failed: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void destroy() {
        running.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (GwConnection conn : gwConnections.values()) {
            closeQuietly(conn.client);
        }
        gwConnections.clear();

        // 优雅关闭：关闭本实例所有 ACTIVE 路由记录
        try {
            sessionRouteService.closeAllByInstance();
        } catch (Exception e) {
            log.warn("优雅关闭路由记录失败: {}", e.getMessage());
        }

        log.info("GatewayWSClient shut down: all connections closed");
    }

    // ==================== GatewayRelayTarget 实现 ====================

    @Override
    public boolean sendToGateway(String message) {
        // 兼容旧接口：发给任意一个活跃连接
        for (GwConnection conn : gwConnections.values()) {
            if (conn.client != null && conn.client.isOpen()) {
                return sendViaClient(conn.client, message);
            }
        }
        return false;
    }

    @Override
    public boolean sendToGateway(String gwInstanceId, String message) {
        GwConnection conn = gwConnections.get(gwInstanceId);
        if (conn == null || conn.client == null || !conn.client.isOpen()) {
            log.debug("Cannot send to GW instance {}: not connected", gwInstanceId);
            return false;
        }
        return sendViaClient(conn.client, message);
    }

    @Override
    public boolean broadcastToAllGateways(String message) {
        boolean anySent = false;
        for (Map.Entry<String, GwConnection> entry : gwConnections.entrySet()) {
            GwConnection conn = entry.getValue();
            if (conn.client != null && conn.client.isOpen()) {
                if (sendViaClient(conn.client, message)) {
                    anySent = true;
                }
            }
        }
        return anySent;
    }

    @Override
    public boolean hasActiveConnection() {
        return gwConnections.values().stream()
                .anyMatch(conn -> conn.client != null && conn.client.isOpen());
    }

    /**
     * Sends a message by selecting a GW connection via the consistent hash ring.
     *
     * @param hashKey hash key for node selection (typically ak)
     * @param message serialized message payload
     * @return true if the message was sent successfully; false if ring is empty or connection is down
     */
    @Override
    public boolean sendViaHash(String hashKey, String message) {
        GwConnection conn = hashRing.getNode(hashKey);
        if (conn != null && conn.client != null && conn.client.isOpen()) {
            log.info("[EXIT->GW] sendViaHash: hashKey={}, gwInstanceId={}", hashKey, conn.gwInstanceId);
            return sendViaClient(conn.client, message);
        }
        log.info("sendViaHash: no suitable connection found for hashKey={}", hashKey);
        return false;
    }

    // ==================== GatewayDiscoveryService.Listener 实现 ====================

    @Override
    public void onGatewayAdded(String gwInstanceId, String wsUrl) {
        if (gwConnections.containsKey(gwInstanceId)) {
            log.info("GW instance already connected: {}", gwInstanceId);
            return;
        }

        // 去重：如果已有 seed 连接指向相同 URL
        String duplicateSeedId = findSeedConnectionByUrl(wsUrl);
        if (duplicateSeedId != null) {
            GwConnection seedConn = gwConnections.remove(duplicateSeedId);
            if (seedConn != null) {
                if (seedConn.client != null && seedConn.client.isOpen()) {
                    // seed 连接存活 → 仅重映射 key，不断开连接，避免 GW 侧路由缓存清除
                    gwConnections.put(gwInstanceId, seedConn);
                    // Remove old seed key from ring and add under the canonical discovery key
                    hashRing.removeNode(duplicateSeedId);
                    hashRing.addNode(gwInstanceId, seedConn);
                    log.info("Promoted seed connection to discovery: seed={} -> discovery={}, url={}",
                            duplicateSeedId, gwInstanceId, wsUrl);
                    return;
                }
                // seed 连接已断开（如 SS 先于 GW 启动）-> 关闭死连接，用 discovery ID 新建
                hashRing.removeNode(duplicateSeedId);
                closeQuietly(seedConn.client);
                log.info("Seed connection dead, replacing with discovery: seed={} -> discovery={}, url={}",
                        duplicateSeedId, gwInstanceId, wsUrl);
                connectToGateway(gwInstanceId, wsUrl);
                return;
            }
        }

        log.info("Connecting to new GW instance: id={}, url={}", gwInstanceId, wsUrl);
        connectToGateway(gwInstanceId, wsUrl);
    }

    /**
     * 查找与指定 URL 匹配的 seed 连接 ID。
     */
    private String findSeedConnectionByUrl(String wsUrl) {
        for (Map.Entry<String, GwConnection> entry : gwConnections.entrySet()) {
            if (entry.getKey().startsWith("seed-") && wsUrl.equals(entry.getValue().wsUrl)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public void onGatewayRemoved(String gwInstanceId) {
        GwConnection conn = gwConnections.remove(gwInstanceId);
        if (conn != null) {
            hashRing.removeNode(gwInstanceId);
            closeQuietly(conn.client);
            log.info("Disconnected from removed GW instance: {}", gwInstanceId);
        }
    }

    // ==================== 连接管理 ====================

    public void connectToGateway(String gwInstanceId, String wsUrl) {
        if (!running.get()) {
            return;
        }

        try {
            URI uri = URI.create(wsUrl);
            String authProtocol = buildAuthProtocol();
            InternalWebSocketClient client = new InternalWebSocketClient(
                    gwInstanceId, uri, authProtocol);

            GwConnection conn = new GwConnection(gwInstanceId, wsUrl, client);
            gwConnections.put(gwInstanceId, conn);
            // Register in hash ring immediately; sendViaHash guards against closed connections
            hashRing.addNode(gwInstanceId, conn);

            client.connectBlocking(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to connect to GW {}: {}", gwInstanceId, e.getMessage());
            scheduleReconnect(gwInstanceId);
        }
    }

    public void disconnectFromGateway(String gwInstanceId) {
        onGatewayRemoved(gwInstanceId);
    }

    public Set<String> getConnectedInstanceIds() {
        return Set.copyOf(gwConnections.keySet());
    }

    // ==================== 内部方法 ====================

    private void scheduleReconnect(String gwInstanceId) {
        if (!running.get()) {
            return;
        }

        GwConnection conn = gwConnections.get(gwInstanceId);
        if (conn == null) {
            return; // 已被移除，不重连
        }

        int attempts = conn.reconnectAttempts.incrementAndGet();
        long delay = Math.min(
                reconnectInitialDelayMs * (1L << Math.min(attempts - 1, 20)),
                reconnectMaxDelayMs);

        log.info("Scheduling reconnect to GW {} in {}ms (attempt #{})", gwInstanceId, delay, attempts);

        scheduler.schedule(() -> {
            if (!running.get()) {
                return;
            }
            // 使用 computeIfPresent 保证检查+修改的原子性，防止与 onGatewayRemoved 并发竞态
            gwConnections.computeIfPresent(gwInstanceId, (id, existing) -> {
                try {
                    URI uri = URI.create(existing.wsUrl);
                    String authProtocol = buildAuthProtocol();
                    InternalWebSocketClient newClient = new InternalWebSocketClient(
                            id, uri, authProtocol);
                    existing.client = newClient;
                    newClient.connectBlocking(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("Reconnect to GW {} failed: {}", id, e.getMessage());
                    scheduleReconnect(id);
                }
                return existing; // 保留条目以支持后续重连
            });
        }, delay, TimeUnit.MILLISECONDS);
    }

    String buildAuthProtocol() {
        try {
            var payload = new java.util.LinkedHashMap<String, String>();
            payload.put("token", internalToken);
            payload.put("source", GatewayRelayService.SOURCE);
            if (instanceId != null && !instanceId.isBlank()) {
                payload.put("instanceId", instanceId);
            }
            String json = objectMapper.writeValueAsString(payload);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return AUTH_PROTOCOL_PREFIX + encoded;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode gateway auth subprotocol", e);
        }
    }

    /**
     * 从 gwConnections 中查找指定 client 对应的当前 gwInstanceId。
     * seed 提升为 discovery 后，gwConnections 的 key 会变，但 client 不变。
     */
    private String resolveCurrentGwId(WebSocketClient client) {
        for (Map.Entry<String, GwConnection> entry : gwConnections.entrySet()) {
            if (entry.getValue().client == client) {
                return entry.getKey();
            }
        }
        // 未找到说明已被移除（onGatewayRemoved），返回原始 ID 以保持日志可读
        return client instanceof InternalWebSocketClient
                ? ((InternalWebSocketClient) client).gwInstanceId
                : "unknown";
    }

    private boolean sendViaClient(WebSocketClient client, String message) {
        try {
            client.send(message);
            log.info("[EXIT->GW] WS message sent: gwInstanceId={}, length={}",
                    resolveCurrentGwId(client), message.length());
            return true;
        } catch (Exception e) {
            log.error("[EXIT->GW] Failed to send via GW WS: gwInstanceId={}, error={}",
                    resolveCurrentGwId(client), e.getMessage());
            return false;
        }
    }

    private void closeQuietly(WebSocketClient client) {
        if (client != null) {
            try {
                client.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Error closing WS client: {}", e.getMessage());
            }
        }
    }

    /**
     * 从 WebSocket URL 提取 host:port 作为种子连接 ID。
     */
    private String extractHostPort(String wsUrl) {
        try {
            URI uri = URI.create(wsUrl);
            int port = uri.getPort();
            return uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return wsUrl.replaceAll("[^a-zA-Z0-9.:-]", "");
        }
    }

    // ==================== 内部类 ====================

    /**
     * 单个 GW 实例的连接信息。
     */
    private static class GwConnection {
        final String gwInstanceId;
        final String wsUrl;
        volatile WebSocketClient client;
        final AtomicInteger reconnectAttempts = new AtomicInteger(0);

        GwConnection(String gwInstanceId, String wsUrl, WebSocketClient client) {
            this.gwInstanceId = gwInstanceId;
            this.wsUrl = wsUrl;
            this.client = client;
        }
    }

    /**
     * 内部 WebSocket 客户端，绑定到特定 GW 实例。
     */
    private class InternalWebSocketClient extends WebSocketClient {

        private final String gwInstanceId;

        InternalWebSocketClient(String gwInstanceId, URI serverUri, String authProtocol) {
            super(serverUri, new Draft_6455(List.of(), List.of(new Protocol(authProtocol))));
            this.gwInstanceId = gwInstanceId;
            this.setConnectionLostTimeout(30);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            GwConnection conn = gwConnections.get(gwInstanceId);
            if (conn != null) {
                conn.reconnectAttempts.set(0);
            }
            log.info("Connected to GW {}: url={}, status={}", gwInstanceId, uri, handshake.getHttpStatus());
        }

        @Override
        public void onMessage(String message) {
            gatewayRelayService.handleGatewayMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            // 动态查找当前 gwInstanceId：seed 可能已被提升为 discovery ID
            String currentGwId = resolveCurrentGwId(this);
            log.warn("Disconnected from GW {}: code={}, reason={}, remote={}",
                    currentGwId, code, reason, remote);
            if (running.get() && !isInvalidTokenReason(reason) && gwConnections.containsKey(currentGwId)) {
                scheduleReconnect(currentGwId);
            } else if (running.get() && isInvalidTokenReason(reason)) {
                log.error("Stop reconnecting to GW {}: authentication failure", currentGwId);
                gwConnections.remove(currentGwId);
            }
        }

        @Override
        public void onError(Exception ex) {
            log.error("GW {} WebSocket error: {}", gwInstanceId, ex.getMessage());
        }

        private boolean isInvalidTokenReason(String reason) {
            return reason != null && reason.toLowerCase(Locale.ROOT).contains(INVALID_INTERNAL_TOKEN_REASON);
        }
    }
}
