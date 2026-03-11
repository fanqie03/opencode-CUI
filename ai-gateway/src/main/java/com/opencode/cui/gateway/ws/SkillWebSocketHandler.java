package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.SkillRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SkillWebSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final String AUTH_PROTOCOL_PREFIX = "auth.";

    private final ObjectMapper objectMapper;
    private final SkillRelayService skillRelayService;
    private final String internalToken;

    public SkillWebSocketHandler(ObjectMapper objectMapper,
            SkillRelayService skillRelayService,
            @Value("${skill.gateway.internal-token:${gateway.skill-server.internal-token:changeme}}") String internalToken) {
        this.objectMapper = objectMapper;
        this.skillRelayService = skillRelayService;
        this.internalToken = internalToken;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String selectedProtocol = extractAcceptedProtocol(request);
        if (selectedProtocol == null) {
            log.warn("Rejected skill handshake: invalid auth subprotocol");
            return false;
        }

        response.getHeaders().set("Sec-WebSocket-Protocol", selectedProtocol);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        skillRelayService.registerSkillSession(session);
        log.info("Skill internal WebSocket connected: sessionId={}, remoteAddr={}",
                session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        GatewayMessage message;
        try {
            message = objectMapper.readValue(textMessage.getPayload(), GatewayMessage.class);
        } catch (Exception e) {
            log.warn("Invalid skill internal message: sessionId={}, error={}",
                    session.getId(), e.getMessage());
            return;
        }

        if (!"invoke".equals(message.getType())) {
            log.warn("Unsupported message type from skill internal link: sessionId={}, type={}",
                    session.getId(), message.getType());
            return;
        }

        skillRelayService.handleInvokeFromSkill(session, message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        skillRelayService.removeSkillSession(session);
        log.info("Skill internal WebSocket disconnected: sessionId={}, status={}",
                session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        skillRelayService.removeSkillSession(session);
        log.error("Skill internal WebSocket transport error: sessionId={}, error={}",
                session.getId(), exception.getMessage(), exception);
    }

    private String extractAcceptedProtocol(ServerHttpRequest request) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }

        for (String protocolHeader : protocols) {
            for (String candidate : protocolHeader.split(",")) {
                String protocol = candidate.trim();
                if (!protocol.startsWith(AUTH_PROTOCOL_PREFIX)) {
                    continue;
                }
                if (verifyProtocolToken(protocol)) {
                    return protocol;
                }
            }
        }
        return null;
    }

    private boolean verifyProtocolToken(String protocol) {
        String encodedPayload = protocol.substring(AUTH_PROTOCOL_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedPayload);
            String json = new String(decoded, StandardCharsets.UTF_8);
            String token = objectMapper.readTree(json).path("token").asText(null);
            return internalToken.equals(token);
        } catch (Exception e) {
            log.warn("Failed to decode skill auth subprotocol: {}", e.getMessage());
            return false;
        }
    }
}
