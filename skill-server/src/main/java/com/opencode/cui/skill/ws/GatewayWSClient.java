package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.SessionRouteService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Gateway WebSocket connection pool manager.
 *
 * <p>Maintains N WebSocket connections to the Gateway ALB endpoint.
 * Messages carrying a session key are dispatched sticky by that key; messages
 * without a session key are dispatched via round-robin across available connections.
 * Each connection has independent reconnect logic with exponential backoff.</p>
 *
 * <h3>Configuration</h3>
 * <ul>
 * <li>{@code skill.gateway.ws-url} - ALB WebSocket endpoint</li>
 * <li>{@code skill.gateway.connection-count} - Number of pooled connections (default 3)</li>
 * </ul>
 */
@Slf4j
@Component
public class GatewayWSClient implements GatewayRelayService.GatewayRelayTarget {

    private static final String INVALID_INTERNAL_TOKEN_REASON = "invalid internal token";
    private static final String AUTH_PROTOCOL_PREFIX = "auth.";

    private final GatewayRelayService gatewayRelayService;
    private final ObjectMapper objectMapper;
    private final SessionRouteService sessionRouteService;
    private final MeterRegistry meterRegistry;

    private final AtomicInteger currentConnections = new AtomicInteger(0);
    private final Counter totalConnections;

    @Value("${skill.gateway.internal-token:changeme}")
    private String internalToken;

    @Value("${HOSTNAME:skill-server-local}")
    private String instanceId;

    /** ALB WebSocket endpoint */
    @Value("${skill.gateway.ws-url:ws://localhost:8081/ws/skill}")
    private String wsUrl;

    /** Number of pooled connections */
    @Value("${skill.gateway.connection-count:3}")
    private int connectionCount;

    @Value("${skill.gateway.reconnect-initial-delay-ms:1000}")
    private long reconnectInitialDelayMs;

    @Value("${skill.gateway.reconnect-max-delay-ms:30000}")
    private long reconnectMaxDelayMs;

    /** Connection pool: fixed-size array of PooledConnection (AtomicReferenceArray for thread-safe element access) */
    private volatile AtomicReferenceArray<PooledConnection> pool;

    /** Round-robin counter for connection selection */
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gw-ws-pool");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GatewayWSClient(GatewayRelayService gatewayRelayService,
            ObjectMapper objectMapper,
            SessionRouteService sessionRouteService,
            MeterRegistry meterRegistry) {
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
        this.sessionRouteService = sessionRouteService;
        this.meterRegistry = meterRegistry;

        this.totalConnections = meterRegistry.counter("gateway_ws_total_connections");
        meterRegistry.gauge("gateway_ws_current_connections", currentConnections);
    }

    @PostConstruct
    public void init() {
        if ("changeme".equals(internalToken)) {
            log.warn("skill.gateway.internal-token is using the default value 'changeme'. "
                    + "This is insecure for production.");
        }
        gatewayRelayService.setGatewayRelayTarget(this);
        running.set(true);

        // Initialize connection pool
        int count = Math.max(1, connectionCount);
        AtomicReferenceArray<PooledConnection> newPool = new AtomicReferenceArray<>(count);
        for (int i = 0; i < count; i++) {
            newPool.set(i, new PooledConnection(i));
        }
        pool = newPool;
        for (int i = 0; i < count; i++) {
            connectPoolSlot(i);
        }

        log.info("GatewayWSClient initialized: instanceId={}, wsUrl={}, connectionCount={}",
                instanceId, wsUrl, count);
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

        AtomicReferenceArray<PooledConnection> snapshot = pool;
        if (snapshot != null) {
            for (int i = 0; i < snapshot.length(); i++) {
                PooledConnection conn = snapshot.get(i);
                if (conn != null) {
                    closeQuietly(conn.client);
                }
            }
        }

        // Graceful shutdown: close all ACTIVE route records for this instance
        try {
            sessionRouteService.closeAllByInstance();
        } catch (Exception e) {
            log.warn("Failed to close route records on shutdown: {}", e.getMessage());
        }

        log.info("GatewayWSClient shut down: all connections closed");
    }

    // ==================== GatewayRelayTarget implementation ====================

    @Override
    public boolean sendToGateway(String message) {
        AtomicReferenceArray<PooledConnection> snapshot = pool;
        if (snapshot == null || snapshot.length() == 0) {
            return false;
        }

        int count = snapshot.length();
        int start = startIndexForMessage(message, count);

        // Try round-robin starting from the selected slot, wrapping around
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % count;
            PooledConnection conn = snapshot.get(idx);
            if (conn == null) {
                continue;
            }
            WebSocketClient client = conn.client;
            if (client != null && client.isOpen()) {
                return sendViaClient(idx, client, message);
            }
        }

        log.warn("[SKIP] sendToGateway: no available connections in pool (size={})", count);
        return false;
    }

    @Override
    public boolean hasActiveConnection() {
        AtomicReferenceArray<PooledConnection> snapshot = pool;
        if (snapshot == null) {
            return false;
        }
        for (int i = 0; i < snapshot.length(); i++) {
            PooledConnection conn = snapshot.get(i);
            if (conn != null && conn.client != null && conn.client.isOpen()) {
                return true;
            }
        }
        return false;
    }

    int startIndexForMessage(String message, int count) {
        String routeKey = extractRouteKey(message);
        if (routeKey != null) {
            return Math.floorMod(routeKey.hashCode(), count);
        }
        return (roundRobinCounter.getAndIncrement() & Integer.MAX_VALUE) % count;
    }

    private String extractRouteKey(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(message);
            JsonNode payload = node.path("payload");
            return firstNonBlank(
                    textAt(payload, "toolSessionId"),
                    textAt(node, "toolSessionId"),
                    textAt(node, "welinkSessionId"),
                    textAt(payload, "welinkSessionId"));
        } catch (Exception e) {
            log.debug("Failed to parse gateway route key: {}", e.getMessage());
            return null;
        }
    }

    private static String textAt(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // ==================== Connection management ====================

    private void connectPoolSlot(int slotIndex) {
        if (!running.get()) {
            return;
        }

        try {
            URI uri = URI.create(wsUrl);
            String authProtocol = buildAuthProtocol();
            InternalWebSocketClient client = new InternalWebSocketClient(
                    slotIndex, uri, authProtocol);

            PooledConnection conn = pool.get(slotIndex);
            conn.client = client;
            conn.reconnectAttempts.set(0);

            client.connectBlocking(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to connect pool slot {}: {}", slotIndex, e.getMessage());
            scheduleReconnect(slotIndex);
        }
    }

    private void scheduleReconnect(int slotIndex) {
        if (!running.get()) {
            return;
        }

        AtomicReferenceArray<PooledConnection> snapshot = pool;
        if (snapshot == null || slotIndex >= snapshot.length()) {
            return;
        }

        PooledConnection conn = snapshot.get(slotIndex);
        if (conn == null) {
            return;
        }

        int attempts = conn.reconnectAttempts.incrementAndGet();
        long delay = Math.min(
                reconnectInitialDelayMs * (1L << Math.min(attempts - 1, 20)),
                reconnectMaxDelayMs);

        log.info("Scheduling reconnect for pool slot {} in {}ms (attempt #{})", slotIndex, delay, attempts);

        scheduler.schedule(() -> {
            if (!running.get()) {
                return;
            }
            try {
                URI uri = URI.create(wsUrl);
                String authProtocol = buildAuthProtocol();
                InternalWebSocketClient newClient = new InternalWebSocketClient(
                        slotIndex, uri, authProtocol);
                pool.get(slotIndex).client = newClient;
                newClient.connectBlocking(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Reconnect for pool slot {} failed: {}", slotIndex, e.getMessage());
                scheduleReconnect(slotIndex);
            }
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

    // ==================== Internal methods ====================

    private boolean sendViaClient(int slotIndex, WebSocketClient client, String message) {
        try {
            client.send(message);
            log.info("[EXIT->GW] WS message sent: poolSlot={}, length={}", slotIndex, message.length());
            return true;
        } catch (Exception e) {
            log.error("[EXIT->GW] Failed to send via GW WS: poolSlot={}, error={}",
                    slotIndex, e.getMessage());
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

    // ==================== Inner classes ====================

    /**
     * Represents a single connection slot in the pool.
     */
    private static class PooledConnection {
        final int slotIndex;
        volatile WebSocketClient client;
        final AtomicInteger reconnectAttempts = new AtomicInteger(0);

        PooledConnection(int slotIndex) {
            this.slotIndex = slotIndex;
        }
    }

    /**
     * Internal WebSocket client bound to a specific pool slot.
     */
    private class InternalWebSocketClient extends WebSocketClient {

        private final int slotIndex;

        InternalWebSocketClient(int slotIndex, URI serverUri, String authProtocol) {
            super(serverUri, new Draft_6455(List.of(), List.of(new Protocol(authProtocol))));
            this.slotIndex = slotIndex;
            this.setConnectionLostTimeout(30);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            AtomicReferenceArray<PooledConnection> snapshot = pool;
            if (snapshot != null && slotIndex < snapshot.length()) {
                PooledConnection conn = snapshot.get(slotIndex);
                if (conn != null) {
                    conn.reconnectAttempts.set(0);
                }
            }
            currentConnections.incrementAndGet();
            totalConnections.increment();
            log.info("Connected to GW via pool slot {}: url={}, status={}", slotIndex, uri, handshake.getHttpStatus());
        }

        @Override
        public void onMessage(String message) {
            gatewayRelayService.handleGatewayMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.warn("Disconnected from GW pool slot {}: code={}, reason={}, remote={}",
                    slotIndex, code, reason, remote);
            currentConnections.decrementAndGet();
            if (running.get() && !isInvalidTokenReason(reason)) {
                scheduleReconnect(slotIndex);
            } else if (running.get() && isInvalidTokenReason(reason)) {
                log.error("Stop reconnecting pool slot {}: authentication failure", slotIndex);
            }
        }

        @Override
        public void onError(Exception ex) {
            log.error("GW pool slot {} WebSocket error: {}", slotIndex, ex.getMessage());
        }

        private boolean isInvalidTokenReason(String reason) {
            return reason != null && reason.toLowerCase(Locale.ROOT).contains(INVALID_INTERNAL_TOKEN_REASON);
        }
    }
}
