package com.yourapp.skill.ws;

import com.yourapp.skill.service.GatewayRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

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
 */
@Slf4j
@Component
public class GatewayWSHandler extends TextWebSocketHandler {

    private final GatewayRelayService gatewayRelayService;
    private final String internalToken;

    public GatewayWSHandler(GatewayRelayService gatewayRelayService,
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

        String sessionKey = session.getId();
        gatewayRelayService.registerGatewaySession(sessionKey, session);
        log.info("Gateway WebSocket connected: sessionId={}, remoteAddr={}",
                sessionKey, session.getRemoteAddress());
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
        String sessionKey = session.getId();
        gatewayRelayService.removeGatewaySession(sessionKey);
        log.info("Gateway WebSocket disconnected: sessionId={}, status={}", sessionKey, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Gateway WebSocket transport error: sessionId={}, error={}",
                session.getId(), exception.getMessage(), exception);
        String sessionKey = session.getId();
        gatewayRelayService.removeGatewaySession(sessionKey);
    }

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
