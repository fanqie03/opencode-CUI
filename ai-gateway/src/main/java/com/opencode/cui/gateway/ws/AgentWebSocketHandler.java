package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.AkSkAuthService;
import com.opencode.cui.gateway.service.DeviceBindingService;
import com.opencode.cui.gateway.service.EventRelayService;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket handler for PCAgent connections.
 *
 * Handshake: extracts auth params from Sec-WebSocket-Protocol subprotocol,
 * validates AK/SK signature via AkSkAuthService.
 *
 * After connection:
 * - register: validates device binding + duplicate connection + registers agent
 * - heartbeat: updates last_seen_at
 * - tool_event / tool_done / tool_error / session_created / permission_request:
 * relayed to Skill Server
 * - status_response: consumed by Gateway for REST status queries
 *
 * On close: marks agent offline, notifies Skill Server agent_offline
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_AK_ID = "akId";
    private static final String ATTR_AGENT_ID = "agentId";
    private static final String AUTH_PROTOCOL_PREFIX = "auth.";

    /** Custom WebSocket close code: duplicate connection rejected */
    private static final CloseStatus CLOSE_DUPLICATE = new CloseStatus(4409, "duplicate_connection");
    /** Custom WebSocket close code: device binding validation failed */
    private static final CloseStatus CLOSE_BINDING_FAILED = new CloseStatus(4403, "device_binding_failed");
    /** Custom WebSocket close code: registration timeout */
    private static final CloseStatus CLOSE_REGISTER_TIMEOUT = new CloseStatus(4408, "register_timeout");

    private final AkSkAuthService akSkAuthService;
    private final AgentRegistryService agentRegistryService;
    private final DeviceBindingService deviceBindingService;
    private final EventRelayService eventRelayService;
    private final ObjectMapper objectMapper;

    @Value("${gateway.agent.register-timeout-seconds:10}")
    private int registerTimeoutSeconds;

    /** wsSessionId -> ak mapping for routing cleanup on disconnect */
    private final Map<String, String> sessionAkMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "register-timeout");
        t.setDaemon(true);
        return t;
    });

    public AgentWebSocketHandler(AkSkAuthService akSkAuthService,
            AgentRegistryService agentRegistryService,
            DeviceBindingService deviceBindingService,
            EventRelayService eventRelayService,
            ObjectMapper objectMapper) {
        this.akSkAuthService = akSkAuthService;
        this.agentRegistryService = agentRegistryService;
        this.deviceBindingService = deviceBindingService;
        this.eventRelayService = eventRelayService;
        this.objectMapper = objectMapper;
    }

    // ==================== Handshake Interceptor ====================

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {

        // Extract auth from Sec-WebSocket-Protocol: "auth.{base64-json}"
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null || protocols.isEmpty()) {
            log.warn("WebSocket handshake rejected: no subprotocol provided");
            return false;
        }

        // Find the auth protocol (may be comma-separated in a single header value)
        String authPayload = null;
        for (String protocol : protocols) {
            // Handle comma-separated values within a single header
            for (String p : protocol.split(",")) {
                String trimmed = p.trim();
                if (trimmed.startsWith(AUTH_PROTOCOL_PREFIX)) {
                    authPayload = trimmed.substring(AUTH_PROTOCOL_PREFIX.length());
                    break;
                }
            }
            if (authPayload != null)
                break;
        }

        if (authPayload == null) {
            log.warn("WebSocket handshake rejected: no auth subprotocol found");
            return false;
        }

        // Decode Base64URL -> JSON -> extract ak/ts/nonce/sign
        // Uses URL-safe Base64 (RFC 4648 §5) because WebSocket subprotocol tokens
        // cannot contain '+', '/', '=' (standard Base64 chars).
        String ak, ts, nonce, sign;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(authPayload);
            String json = new String(decoded, StandardCharsets.UTF_8);
            var authNode = objectMapper.readTree(json);
            ak = authNode.path("ak").asText(null);
            ts = authNode.path("ts").asText(null);
            nonce = authNode.path("nonce").asText(null);
            sign = authNode.path("sign").asText(null);
        } catch (Exception e) {
            log.warn("WebSocket handshake rejected: failed to decode auth subprotocol: {}",
                    e.getMessage());
            return false;
        }

        // Verify AK/SK signature
        Long userId = akSkAuthService.verify(ak, ts, nonce, sign);
        if (userId == null) {
            log.warn("WebSocket handshake rejected: auth failed. ak={}", ak);
            return false;
        }

        // Store auth info in session attributes
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_AK_ID, ak);

        // Echo the selected subprotocol in response.
        // RFC 6455 requires the server to respond with one of the client's
        // offered subprotocols EXACTLY. Bun enforces this strictly and will
        // reset the connection if the value doesn't match.
        String selectedProtocol = AUTH_PROTOCOL_PREFIX + authPayload;
        response.getHeaders().set("Sec-WebSocket-Protocol", selectedProtocol);

        log.info("WebSocket handshake accepted: ak={}, userId={}", ak, userId);
        return true;
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

        // Start register timeout: if no register message within N seconds, close
        scheduler.schedule(() -> {
            if (!sessionAkMap.containsKey(session.getId()) && session.isOpen()) {
                log.warn("Register timeout: closing unregistered connection. sessionId={}, ak={}",
                        session.getId(), akId);
                try {
                    session.close(CLOSE_REGISTER_TIMEOUT);
                } catch (IOException e) {
                    log.debug("Error closing timed-out session: {}", e.getMessage());
                }
            }
        }, registerTimeoutSeconds, TimeUnit.SECONDS);
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
            case "tool_event", "tool_done", "tool_error", "session_created",
                    "permission_request" ->
                handleRelayToSkillServer(session, message);
            case "status_response" -> handleStatusResponse(session, message);
            default -> log.warn("Unknown message type from PCAgent: type={}, sessionId={}",
                    type, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String ak = sessionAkMap.remove(session.getId());
        if (ak != null) {
            // Retrieve agentId (Long) from session attributes for DB operation
            Long agentId = (Long) session.getAttributes().get(ATTR_AGENT_ID);
            log.info("PCAgent disconnected: ak={}, agentId={}, sessionId={}, status={}",
                    ak, agentId, session.getId(), status);

            // Mark agent offline in database (requires agentId Long)
            if (agentId != null) {
                agentRegistryService.markOffline(agentId);
            }

            // Remove from relay service (uses ak)
            eventRelayService.removeAgentSession(ak);

            // Notify Skill Server that agent went offline (uses ak)
            GatewayMessage offlineMsg = GatewayMessage.agentOffline(ak);
            eventRelayService.relayToSkillServer(ak, offlineMsg);
        } else {
            log.info("PCAgent WebSocket closed (not registered): sessionId={}, status={}",
                    session.getId(), status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String ak = sessionAkMap.get(session.getId());
        log.error("WebSocket transport error: ak={}, sessionId={}, error={}",
                ak, session.getId(), exception.getMessage());
    }

    // ==================== Message Handlers ====================

    private void handleRegister(WebSocketSession session, GatewayMessage message,
            Long userId, String akId) {
        String deviceName = message.getDeviceName();
        String macAddress = message.getMacAddress();
        String os = message.getOs();
        String toolType = message.getToolType() != null ? message.getToolType() : "channel";
        String toolVersion = message.getToolVersion();

        // Step 1: Validate device binding (3rd party, fail-open)
        if (!deviceBindingService.validate(akId, macAddress, toolType)) {
            log.warn("Register rejected: device binding failed. ak={}, mac={}, toolType={}",
                    akId, macAddress, toolType);
            sendAndClose(session, GatewayMessage.registerRejected("device_binding_failed"),
                    CLOSE_BINDING_FAILED);
            return;
        }

        // Step 2: Check for duplicate active connection (keep old, reject new)
        if (eventRelayService.hasAgentSession(akId)) {
            log.warn("Register rejected: duplicate connection. ak={}, toolType={}",
                    akId, toolType);
            sendAndClose(session, GatewayMessage.registerRejected("duplicate_connection"),
                    CLOSE_DUPLICATE);
            return;
        }

        // Step 3: Register in database (reuse existing record or create new)
        AgentConnection agent = agentRegistryService.register(
                userId, akId, deviceName, macAddress, os, toolType, toolVersion);

        Long agentId = agent.getId();

        // Store agentId in session attributes (for DB operations on disconnect)
        session.getAttributes().put(ATTR_AGENT_ID, agentId);
        // Store ak in session-ak map (for routing)
        sessionAkMap.put(session.getId(), akId);

        // Register WebSocket session in relay service (keyed by ak)
        eventRelayService.registerAgentSession(akId, session);

        // Send register_ok to client
        try {
            String okJson = objectMapper.writeValueAsString(GatewayMessage.registerOk());
            session.sendMessage(new TextMessage(okJson));
        } catch (IOException e) {
            log.error("Failed to send register_ok: ak={}", akId, e);
        }

        // Notify Skill Server that agent is online (keyed by ak)
        GatewayMessage onlineMsg = GatewayMessage.agentOnline(
                akId, toolType, toolVersion);
        eventRelayService.relayToSkillServer(akId, onlineMsg);

        log.info("Agent registered via WebSocket: ak={}, agentId={}, device={}, mac={}, tool={}/{}",
                akId, agentId, deviceName, macAddress, toolType, toolVersion);
    }

    private void handleHeartbeat(WebSocketSession session) {
        // Heartbeat needs agentId (Long) from session attributes for DB operation
        Long agentId = (Long) session.getAttributes().get(ATTR_AGENT_ID);
        if (agentId != null) {
            agentRegistryService.heartbeat(agentId);
        } else {
            log.warn("Heartbeat from unregistered session: sessionId={}", session.getId());
        }
    }

    private void handleRelayToSkillServer(WebSocketSession session, GatewayMessage message) {
        String ak = sessionAkMap.get(session.getId());
        if (ak == null) {
            log.warn("Relay attempt from unregistered session: sessionId={}, type={}",
                    session.getId(), message.getType());
            return;
        }

        // Trace: log sessionId from PCAgent message for upstream debugging
        log.debug("PCAgent -> Skill relay: ak={}, type={}, welinkSessionId={}, toolSessionId={}",
                ak, message.getType(), message.getWelinkSessionId(), message.getToolSessionId());

        eventRelayService.relayToSkillServer(ak, message);
    }

    private void handleStatusResponse(WebSocketSession session, GatewayMessage message) {
        String ak = sessionAkMap.get(session.getId());
        if (ak == null) {
            log.warn("status_response from unregistered session: sessionId={}", session.getId());
            return;
        }

        eventRelayService.recordStatusResponse(ak, message.getOpencodeOnline());
        log.debug("Recorded status_response: ak={}, opencodeOnline={}", ak, message.getOpencodeOnline());
    }

    // ==================== Helpers ====================

    /**
     * Send a rejection message and close the WebSocket connection.
     */
    private void sendAndClose(WebSocketSession session, GatewayMessage message, CloseStatus status) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            session.close(status);
        } catch (IOException e) {
            log.error("Failed to send rejection message: {}", e.getMessage());
            try {
                session.close(status);
            } catch (IOException ex) {
                log.debug("Error closing session after send failure: {}", ex.getMessage());
            }
        }
    }
}
