package com.opencode.cui.skill.ws;

import com.opencode.cui.skill.service.GatewayRelayService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class GatewayWSClient implements GatewayRelayService.GatewayRelayTarget {

    private static final String INVALID_INTERNAL_TOKEN_REASON = "invalid internal token";

    private final GatewayRelayService gatewayRelayService;

    @Value("${skill.gateway.ws-url:ws://localhost:8081/ws/skill}")
    private String gatewayWsUrl;

    @Value("${skill.gateway.internal-token:changeme}")
    private String internalToken;

    @Value("${skill.gateway.reconnect-initial-delay-ms:1000}")
    private long reconnectInitialDelayMs;

    @Value("${skill.gateway.reconnect-max-delay-ms:30000}")
    private long reconnectMaxDelayMs;

    private volatile InternalWebSocketClient wsClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gateway-ws-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    public GatewayWSClient(GatewayRelayService gatewayRelayService) {
        this.gatewayRelayService = gatewayRelayService;
    }

    @PostConstruct
    public void init() {
        gatewayRelayService.setGatewayRelayTarget(this);
        running.set(true);
        connect();
    }

    @PreDestroy
    public void destroy() {
        running.set(false);
        scheduler.shutdownNow();
        if (wsClient != null) {
            try {
                wsClient.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("GatewayWSClient shut down");
    }

    @Override
    public boolean sendToGateway(String message) {
        InternalWebSocketClient client = this.wsClient;
        if (client == null || !client.isOpen()) {
            return false;
        }

        try {
            client.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to send message to gateway", e);
            return false;
        }
    }

    @Override
    public boolean hasActiveConnection() {
        InternalWebSocketClient client = this.wsClient;
        return client != null && client.isOpen();
    }

    private void connect() {
        if (!running.get()) {
            return;
        }

        try {
            URI uri = URI.create(gatewayWsUrl);
            wsClient = new InternalWebSocketClient(uri);
            wsClient.addHeader("Authorization", "Bearer " + internalToken);
            wsClient.connectBlocking(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to connect to gateway: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        long delay = Math.min(
                reconnectInitialDelayMs * (1L << Math.min(attempts - 1, 20)),
                reconnectMaxDelayMs);

        log.info("Scheduling reconnect to gateway in {}ms (attempt #{})", delay, attempts);

        scheduler.schedule(() -> {
            if (running.get()) {
                connect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void handleGatewayMessage(String rawMessage) {
        gatewayRelayService.handleGatewayMessage(rawMessage);
    }

    private class InternalWebSocketClient extends WebSocketClient {

        private InternalWebSocketClient(URI serverUri) {
            super(serverUri);
            this.setConnectionLostTimeout(30);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            reconnectAttempts.set(0);
            log.info("Connected to gateway internal WS: url={}, status={}", uri, handshake.getHttpStatus());
        }

        @Override
        public void onMessage(String message) {
            handleGatewayMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.warn("Disconnected from gateway internal WS: code={}, reason={}, remote={}",
                    code, reason, remote);
            if (running.get() && !isInvalidInternalTokenReason(reason)) {
                scheduleReconnect();
                return;
            }

            if (running.get()) {
                log.error("Stop reconnecting to gateway due to authentication failure: reason={}", reason);
            }
        }

        @Override
        public void onError(Exception ex) {
            log.error("Gateway internal WebSocket error: {}", ex.getMessage());
        }

        private boolean isInvalidInternalTokenReason(String reason) {
            return reason != null && reason.toLowerCase(Locale.ROOT).contains(INVALID_INTERNAL_TOKEN_REASON);
        }
    }
}
