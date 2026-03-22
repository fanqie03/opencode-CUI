package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.SkillRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Skill Server 内部 WebSocket 处理器。
 * 处理 Skill Server → Gateway 的 WebSocket 连接，
 * 接收 invoke 命令并转发给对应的 Agent。
 *
 * <p>
 * 握手认证：通过 Sec-WebSocket-Protocol 子协议传递 Base64 编码的 token + source。
 * </p>
 */
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
        HandshakeAuth handshakeAuth = extractAcceptedProtocol(request);
        if (handshakeAuth == null) {
            log.warn("Rejected skill handshake: invalid auth subprotocol");
            return false;
        }

        attributes.put(SkillRelayService.SOURCE_ATTR, handshakeAuth.source());
        if (handshakeAuth.instanceId() != null && !handshakeAuth.instanceId().isBlank()) {
            attributes.put(SkillRelayService.INSTANCE_ID_ATTR, handshakeAuth.instanceId());
        }
        response.getHeaders().set("Sec-WebSocket-Protocol", handshakeAuth.protocol());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        skillRelayService.registerSourceSession(session);
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

        try {
            MdcHelper.fromGatewayMessage(message);
            MdcHelper.putScenario("ws-skill-invoke");

            if (!GatewayMessage.Type.INVOKE.equals(message.getType())) {
                log.warn("Unsupported message type from skill internal link: sessionId={}, type={}",
                        session.getId(), message.getType());
                return;
            }

            log.info("[ENTRY] SkillWSHandler.handleTextMessage: sessionId={}, ak={}, action={}",
                    session.getId(), message.getAk(), message.getAction());
            skillRelayService.handleInvokeFromSkill(session, message);
            log.info("[EXIT] SkillWSHandler.handleTextMessage: sessionId={}, ak={}",
                    session.getId(), message.getAk());
        } finally {
            MdcHelper.clearAll();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        skillRelayService.removeSourceSession(session);
        log.info("Skill internal WebSocket disconnected: sessionId={}, status={}",
                session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        skillRelayService.removeSourceSession(session);
        log.error("Skill internal WebSocket transport error: sessionId={}, error={}",
                session.getId(), exception.getMessage(), exception);
    }

    /** 从请求头中提取并验证认证子协议。 */
    private HandshakeAuth extractAcceptedProtocol(ServerHttpRequest request) {
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
                HandshakeAuth handshakeAuth = verifyProtocolToken(protocol);
                if (handshakeAuth != null) {
                    return handshakeAuth;
                }
            }
        }
        return null;
    }

    /** 解码并验证 Base64 认证令牌。 */
    private HandshakeAuth verifyProtocolToken(String protocol) {
        String encodedPayload = protocol.substring(AUTH_PROTOCOL_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedPayload);
            String json = new String(decoded, StandardCharsets.UTF_8);
            var authNode = objectMapper.readTree(json);
            String token = authNode.path("token").asText(null);
            String source = authNode.path("source").asText(null);
            if (!internalToken.equals(token) || source == null || source.isBlank()) {
                return null;
            }
            String instanceId = authNode.path("instanceId").asText(null);
            return new HandshakeAuth(protocol, source, instanceId);
        } catch (Exception e) {
            log.warn("Failed to decode skill auth subprotocol: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 握手认证信息。
     *
     * @param protocol   原始子协议字符串
     * @param source     来源标识
     * @param instanceId Skill Server 实例 ID
     */
    private record HandshakeAuth(String protocol, String source, String instanceId) {
    }
}
