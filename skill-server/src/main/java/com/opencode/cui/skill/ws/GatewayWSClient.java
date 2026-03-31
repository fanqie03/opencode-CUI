package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway WebSocket connection pool manager.
 *
 * <p>Maintains N WebSocket connections to the Gateway ALB endpoint.
 * Messages are dispatched via round-robin across available connections.
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

    /** Connection pool: fixed-size array of PooledConnection */
    private volatile PooledConnection[] pool;

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
            SessionRouteService sessionRouteService) {
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
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

        // Initialize connection pool
        int count = Math.max(1, connectionCount);
        pool = new PooledConnection[count];
        for (int i = 0; i < count; i++) {
            pool[i] = new PooledConnection(i);
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

        PooledConnection[] snapshot = pool;
        if (snapshot != null) {
            for (PooledConnection conn : snapshot) {
                closeQuietly(conn.client);
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
        PooledConnection[] snapshot = pool;
        if (snapshot == null || snapshot.length == 0) {
            return false;
        }

        int count = snapshot.length;
        int start = (roundRobinCounter.getAndIncrement() & Integer.MAX_VALUE) % count;

        // Try round-robin starting from the selected slot, wrapping around
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % count;
            PooledConnection conn = snapshot[idx];
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
        PooledConnection[] snapshot = pool;
        if (snapshot == null) {
            return false;
        }
        for (PooledConnection conn : snapshot) {
            if (conn.client != null && conn.client.isOpen()) {
                return true;
            }
        }
        return false;
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

            pool[slotIndex].client = client;
            pool[slotIndex].reconnectAttempts.set(0);

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

        PooledConnection[] snapshot = pool;
        if (snapshot == null || slotIndex >= snapshot.length) {
            return;
        }

        int attempts = snapshot[slotIndex].reconnectAttempts.incrementAndGet();
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
                pool[slotIndex].client = newClient;
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
            PooledConnection[] snapshot = pool;
            if (snapshot != null && slotIndex < snapshot.length) {
                snapshot[slotIndex].reconnectAttempts.set(0);
            }
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
