package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.SkillRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SkillWebSocketHandler extends TextWebSocketHandler {

    private static final String INVALID_INTERNAL_TOKEN_REASON = "Invalid internal token";

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
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!verifyToken(session)) {
            log.warn("Rejected skill internal connection: sessionId={}, remoteAddr={}",
                    session.getId(), session.getRemoteAddress());
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason(INVALID_INTERNAL_TOKEN_REASON));
            return;
        }

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

    private boolean verifyToken(WebSocketSession session) {
        // 1. Try Authorization header first (preferred, avoids token in URL)
        List<String> authHeaders = session.getHandshakeHeaders().get("Authorization");
        if (authHeaders != null) {
            for (String header : authHeaders) {
                if (header.startsWith("Bearer ")) {
                    String headerToken = header.substring(7);
                    if (internalToken.equals(headerToken)) {
                        return true;
                    }
                }
            }
        }

        // 2. Fallback: query parameter (backward compatibility)
        URI uri = session.getUri();
        if (uri == null) {
            return false;
        }

        Map<String, String> params = UriComponentsBuilder.fromUri(uri)
                .build()
                .getQueryParams()
                .toSingleValueMap();

        return internalToken.equals(params.get("token"));
    }
}
