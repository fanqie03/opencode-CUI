package com.opencode.cui.skill.ws;

import com.opencode.cui.skill.service.GatewayRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket handler for AI-Gateway internal connection.
 * Endpoint: /ws/internal/gateway?token={internal_token}
 *
 * Handles incoming messages from AI-Gateway:
 * - tool_event: raw OpenCode event for a session
 * - tool_done: tool execution completed with usage stats
 * - tool_error: tool execution error
 * - agent_online: PCAgent connected with tool info
 * - agent_offline: PCAgent disconnected
 * - session_created: OpenCode session created with toolSessionId
 *
 * Also provides sendToGateway() for downstream invoke messages (v1 protocol).
 */
@Slf4j
@Component
public class GatewayWSHandler extends TextWebSocketHandler {

    private final GatewayRelayService gatewayRelayService;
    private final String internalToken;

    /**
     * Current active Gateway WS session.
     * In v1, each Skill instance has at most one Gateway connection.
     */
    private final AtomicReference<WebSocketSession> gatewaySession = new AtomicReference<>();

    public GatewayWSHandler(@Lazy GatewayRelayService gatewayRelayService,
            @Value("${skill.gateway.internal-token}") String internalToken) {
        this.gatewayRelayService = gatewayRelayService;
        this.internalToken = internalToken;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Verify internal token from query parameter
        if (!verifyToken(session)) {
            log.warn("Gateway connection rejected: invalid internal token, remoteAddr={}",
                    session.getRemoteAddress());
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Invalid internal token"));
            return;
        }

        // Store as the active gateway session
        WebSocketSession oldSession = gatewaySession.getAndSet(session);
        if (oldSession != null && oldSession.isOpen()) {
            log.warn("Replacing existing Gateway connection: old={}, new={}",
                    oldSession.getId(), session.getId());
            try {
                oldSession.close(CloseStatus.NORMAL.withReason("Replaced by new connection"));
            } catch (IOException e) {
                log.warn("Failed to close old gateway session: {}", e.getMessage());
            }
        }

        log.info("Gateway WebSocket connected: sessionId={}, remoteAddr={}",
                session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Gateway message received: sessionId={}, length={}", session.getId(), payload.length());

        try {
            gatewayRelayService.handleGatewayMessage(payload);
        } catch (Exception e) {
            log.error("Error handling gateway message: sessionId={}, error={}",
                    session.getId(), e.getMessage(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        gatewaySession.compareAndSet(session, null);
        log.info("Gateway WebSocket disconnected: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Gateway WebSocket transport error: sessionId={}, error={}",
                session.getId(), exception.getMessage(), exception);
        gatewaySession.compareAndSet(session, null);
    }

    // ==================== v1 Protocol: Downstream Methods ====================

    /**
     * Send a message to Gateway via the active WS connection.
     * Used for downstream invoke commands (v1 protocol).
     *
     * @param message the JSON message string to send
     * @return true if sent successfully, false if no active connection
     */
    public boolean sendToGateway(String message) {
        WebSocketSession session = gatewaySession.get();
        if (session == null || !session.isOpen()) {
            log.debug("No active Gateway WS connection, cannot send directly");
            return false;
        }

        try {
            session.sendMessage(new TextMessage(message));
            log.debug("Sent to Gateway via WS: length={}", message.length());
            return true;
        } catch (IOException e) {
            log.error("Failed to send to Gateway via WS: {}", e.getMessage());
            gatewaySession.compareAndSet(session, null);
            return false;
        }
    }

    /**
     * Check if there is an active Gateway WS connection on this instance.
     */
    public boolean hasActiveConnection() {
        WebSocketSession session = gatewaySession.get();
        return session != null && session.isOpen();
    }

    // ==================== Internal ====================

    /**
     * Verify the internal token from the WebSocket handshake query parameter.
     */
    private boolean verifyToken(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return false;
        }

        Map<String, String> params = UriComponentsBuilder.fromUri(uri)
                .build()
                .getQueryParams()
                .toSingleValueMap();

        String token = params.get("token");
        return internalToken.equals(token);
    }
}
