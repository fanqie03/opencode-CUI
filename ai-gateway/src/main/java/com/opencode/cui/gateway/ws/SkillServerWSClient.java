package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.EventRelayService;
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

/**
 * WebSocket client connecting to Skill Server for bidirectional event relay (v1
 * protocol - 方案5).
 *
 * Connects to: ws://{skill-host}/ws/internal/gateway?token={internal_token}
 * Reconnects with exponential backoff on disconnect (1s -> 2s -> 4s -> ... ->
 * 30s max).
 *
 * Upstream: Forwards PCAgent events
 * (tool_event/done/error/session_created/agent_online/offline)
 * to Skill Server via WS, replacing the old shared Redis approach.
 *
 * Downstream: Receives invoke commands from Skill Server via WS and routes them
 * to the correct PCAgent via EventRelayService �?Gateway Redis agent:{agentId}.
 */
@Slf4j
@Component
public class SkillServerWSClient implements EventRelayService.SkillServerRelayTarget {

    private static final String INVALID_INTERNAL_TOKEN_REASON = "invalid internal token";

    private final EventRelayService eventRelayService;
    private final ObjectMapper objectMapper;

    @Value("${gateway.skill-server.ws-url:ws://localhost:8082/ws/internal/gateway}")
    private String skillServerWsUrl;

    @Value("${gateway.skill-server.internal-token:changeme}")
    private String internalToken;

    @Value("${gateway.skill-server.reconnect-initial-delay-ms:1000}")
    private long reconnectInitialDelayMs;

    @Value("${gateway.skill-server.reconnect-max-delay-ms:30000}")
    private long reconnectMaxDelayMs;

    private volatile InternalWebSocketClient wsClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-server-ws-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    public SkillServerWSClient(EventRelayService eventRelayService,
            ObjectMapper objectMapper) {
        this.eventRelayService = eventRelayService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // Register this as the relay target in EventRelayService
        eventRelayService.setSkillServerRelay(this);
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
        log.info("SkillServerWSClient shut down");
    }

    /**
     * Send a message to Skill Server via the WebSocket connection.
     * Called by EventRelayService when PCAgent events need to be forwarded.
     */
    @Override
    public void sendToSkillServer(GatewayMessage message) {
        InternalWebSocketClient client = this.wsClient;
        if (client == null || !client.isOpen()) {
            log.warn("Cannot send to Skill Server: connection not open. type={}", message.getType());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            client.send(json);
            log.debug("Sent to Skill Server: type={}, agentId={}", message.getType(), message.getAgentId());
        } catch (Exception e) {
            log.error("Failed to send to Skill Server: type={}", message.getType(), e);
        }
    }

    /**
     * Establish connection to Skill Server.
     */
    private void connect() {
        if (!running.get()) {
            return;
        }

        try {
            String url = skillServerWsUrl + "?token=" + internalToken;
            URI uri = URI.create(url);

            wsClient = new InternalWebSocketClient(uri);
            wsClient.connectBlocking(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to connect to Skill Server: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * Schedule a reconnection with exponential backoff.
     */
    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        long delay = Math.min(
                reconnectInitialDelayMs * (1L << Math.min(attempts - 1, 20)),
                reconnectMaxDelayMs);

        log.info("Scheduling reconnect to Skill Server in {}ms (attempt #{})", delay, attempts);

        scheduler.schedule(() -> {
            if (running.get()) {
                connect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Handle a message received from Skill Server.
     * Routes invoke messages to the appropriate PCAgent via EventRelayService.
     */
    private void handleSkillServerMessage(String rawMessage) {
        try {
            GatewayMessage message = objectMapper.readValue(rawMessage, GatewayMessage.class);
            String type = message.getType();

            if (type == null) {
                log.warn("Received message from Skill Server without type");
                return;
            }

            switch (type) {
                case "invoke" -> {
                    // Route to specific PCAgent
                    String agentIdStr = message.getAgentId();
                    if (agentIdStr == null) {
                        log.warn("Invoke message from Skill Server without agentId");
                        return;
                    }
                    eventRelayService.relayToAgent(agentIdStr, message);
                    log.debug("Routed invoke to agent: agentId={}, action={}", agentIdStr, message.getAction());
                }
                default -> log.warn("Unknown message type from Skill Server: type={}", type);
            }

        } catch (Exception e) {
            log.error("Failed to handle Skill Server message: {}", e.getMessage(), e);
        }
    }

    // ==================== Inner WebSocket Client ====================

    private class InternalWebSocketClient extends WebSocketClient {

        public InternalWebSocketClient(URI serverUri) {
            super(serverUri);
            this.setConnectionLostTimeout(30);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            reconnectAttempts.set(0);
            log.info("Connected to Skill Server: url={}, status={}", uri, handshake.getHttpStatus());
        }

        @Override
        public void onMessage(String message) {
            handleSkillServerMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.warn("Disconnected from Skill Server: code={}, reason={}, remote={}", code, reason, remote);
            if (running.get() && !isInvalidInternalTokenReason(reason)) {
                scheduleReconnect();
                return;
            }

            if (running.get()) {
                log.error("Stop reconnecting to Skill Server due to authentication failure: reason={}", reason);
            }
        }

        @Override
        public void onError(Exception ex) {
            log.error("Skill Server WebSocket error: {}", ex.getMessage());
        }

        private boolean isInvalidInternalTokenReason(String reason) {
            return reason != null && reason.toLowerCase(Locale.ROOT).contains(INVALID_INTERNAL_TOKEN_REASON);
        }
    }
}
