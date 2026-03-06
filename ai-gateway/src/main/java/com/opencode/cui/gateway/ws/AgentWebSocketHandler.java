package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.AkSkAuthService;
import com.opencode.cui.gateway.service.EventRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for PCAgent connections.
 *
 * Handshake: extracts ak/ts/nonce/sign from query params, validates via
 * AkSkAuthService.
 * After connection:
 * - register: registers agent in AgentRegistryService, notifies Skill Server
 * agent_online
 * - heartbeat: updates last_seen_at
 * - tool_event / tool_done / tool_error / session_created: relayed to Skill
 * Server
 * On close: marks agent offline, notifies Skill Server agent_offline
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_AK_ID = "akId";
    private static final String ATTR_AGENT_ID = "agentId";

    private final AkSkAuthService akSkAuthService;
    private final AgentRegistryService agentRegistryService;
    private final EventRelayService eventRelayService;
    private final ObjectMapper objectMapper;

    /** sessionId -> agentId mapping for cleanup on disconnect */
    private final Map<String, String> sessionAgentMap = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(AkSkAuthService akSkAuthService,
            AgentRegistryService agentRegistryService,
            EventRelayService eventRelayService,
            ObjectMapper objectMapper) {
        this.akSkAuthService = akSkAuthService;
        this.agentRegistryService = agentRegistryService;
        this.eventRelayService = eventRelayService;
        this.objectMapper = objectMapper;
    }

    // ==================== Handshake Interceptor ====================

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String ak = servletRequest.getServletRequest().getParameter("ak");
            String ts = servletRequest.getServletRequest().getParameter("ts");
            String nonce = servletRequest.getServletRequest().getParameter("nonce");
            String sign = servletRequest.getServletRequest().getParameter("sign");

            Long userId = akSkAuthService.verify(ak, ts, nonce, sign);
            if (userId == null) {
                log.warn("WebSocket handshake rejected: auth failed. ak={}", ak);
                return false;
            }

            // Store auth info in session attributes
            attributes.put(ATTR_USER_ID, userId);
            attributes.put(ATTR_AK_ID, ak);
            log.info("WebSocket handshake accepted: ak={}, userId={}", ak, userId);
            return true;
        }

        log.warn("WebSocket handshake rejected: not a servlet request");
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    // ==================== WebSocket Handler ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        String akId = (String) session.getAttributes().get(ATTR_AK_ID);
        log.info("PCAgent WebSocket connected: sessionId={}, userId={}, ak={}",
                session.getId(), userId, akId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        String payload = textMessage.getPayload();
        GatewayMessage message;
        try {
            message = objectMapper.readValue(payload, GatewayMessage.class);
        } catch (Exception e) {
            log.warn("Failed to parse message from PCAgent: sessionId={}, error={}",
                    session.getId(), e.getMessage());
            return;
        }

        String type = message.getType();
        if (type == null) {
            log.warn("Message without type from PCAgent: sessionId={}", session.getId());
            return;
        }

        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        String akId = (String) session.getAttributes().get(ATTR_AK_ID);

        switch (type) {
            case "register" -> handleRegister(session, message, userId, akId);
            case "heartbeat" -> handleHeartbeat(session);
            case "tool_event", "tool_done", "tool_error", "session_created", "status_response" ->
                handleRelayToSkillServer(session, message);
            default -> log.warn("Unknown message type from PCAgent: type={}, sessionId={}",
                    type, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String agentId = sessionAgentMap.remove(session.getId());
        if (agentId != null) {
            log.info("PCAgent disconnected: agentId={}, sessionId={}, status={}",
                    agentId, session.getId(), status);

            // Mark agent offline in database
            agentRegistryService.markOffline(Long.parseLong(agentId));

            // Remove from relay service
            eventRelayService.removeAgentSession(agentId);

            // Notify Skill Server that agent went offline
            GatewayMessage offlineMsg = GatewayMessage.agentOffline(agentId);
            eventRelayService.relayToSkillServer(agentId, offlineMsg);
        } else {
            log.info("PCAgent WebSocket closed (not registered): sessionId={}, status={}",
                    session.getId(), status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String agentId = sessionAgentMap.get(session.getId());
        log.error("WebSocket transport error: agentId={}, sessionId={}, error={}",
                agentId, session.getId(), exception.getMessage());
    }

    // ==================== Message Handlers ====================

    private void handleRegister(WebSocketSession session, GatewayMessage message,
            Long userId, String akId) {
        String deviceName = message.getDeviceName();
        String os = message.getOs();
        String toolType = message.getToolType() != null ? message.getToolType() : "OPENCODE";
        String toolVersion = message.getToolVersion();

        // Register in database (this also kicks old connections with same AK +
        // toolType)
        AgentConnection agent = agentRegistryService.register(
                userId, akId, deviceName, os, toolType, toolVersion);

        Long agentId = agent.getId();
        String agentIdStr = String.valueOf(agentId);

        // Store agentId in session attributes and mapping
        session.getAttributes().put(ATTR_AGENT_ID, agentId);
        sessionAgentMap.put(session.getId(), agentIdStr);

        // Register WebSocket session in relay service
        eventRelayService.registerAgentSession(agentIdStr, session);

        // Notify Skill Server that agent is online
        GatewayMessage onlineMsg = GatewayMessage.agentOnline(
                agentIdStr, toolType, toolVersion);
        eventRelayService.relayToSkillServer(agentIdStr, onlineMsg);

        log.info("Agent registered via WebSocket: agentId={}, device={}, os={}, tool={}/{}",
                agentId, deviceName, os, toolType, toolVersion);
    }

    private void handleHeartbeat(WebSocketSession session) {
        String agentId = sessionAgentMap.get(session.getId());
        if (agentId != null) {
            agentRegistryService.heartbeat(Long.parseLong(agentId));
        } else {
            log.warn("Heartbeat from unregistered session: sessionId={}", session.getId());
        }
    }

    private void handleRelayToSkillServer(WebSocketSession session, GatewayMessage message) {
        String agentId = sessionAgentMap.get(session.getId());
        if (agentId == null) {
            log.warn("Relay attempt from unregistered session: sessionId={}, type={}",
                    session.getId(), message.getType());
            return;
        }

        eventRelayService.relayToSkillServer(agentId, message);
    }
}
