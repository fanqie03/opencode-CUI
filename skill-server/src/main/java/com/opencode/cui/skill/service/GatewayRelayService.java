package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.ws.GatewayWSHandler;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages communication with AI-Gateway (v1 protocol - æ–¹æ¡ˆ5).
 *
 * Upstream (Gateway â†?Skill via WS):
 * - Receives tool_event/done/error/session_created/agent_online/offline from
 * GatewayWSHandler
 * - Persists to DB, then publishes to Skill Redis session:{id} for
 * multi-instance broadcast
 * - Each Skill instance subscribes to session:{id} and pushes to local Clients
 *
 * Downstream (Skill â†?Gateway):
 * - If this instance has Gateway WS â†?sendToGateway() directly [fast path]
 * - If no Gateway WS â†?publishInvokeRelay() â†?another instance relays [relay
 * path]
 */
@Slf4j
@Service
public class GatewayRelayService {

    private final ObjectMapper objectMapper;
    private final SkillStreamHandler skillStreamHandler;
    private final SkillMessageService messageService;
    private final SkillSessionService sessionService;
    private final RedisMessageBroker redisMessageBroker;
    private final SequenceTracker sequenceTracker;
    private final GatewayWSHandler gatewayWSHandler;

    public GatewayRelayService(ObjectMapper objectMapper,
            SkillStreamHandler skillStreamHandler,
            SkillMessageService messageService,
            SkillSessionService sessionService,
            RedisMessageBroker redisMessageBroker,
            SequenceTracker sequenceTracker,
            GatewayWSHandler gatewayWSHandler) {
        this.objectMapper = objectMapper;
        this.skillStreamHandler = skillStreamHandler;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.redisMessageBroker = redisMessageBroker;
        this.sequenceTracker = sequenceTracker;
        this.gatewayWSHandler = gatewayWSHandler;
    }

    // ==================== Initialization ====================

    /**
     * On startup, subscribe to Skill Redis channels for all ACTIVE sessions
     * and set up invoke_relay listener if we have a Gateway WS.
     */
    @PostConstruct
    public void init() {
        // Subscribe to Redis session channels for all active sessions
        try {
            List<SkillSession> activeSessions = sessionService.findByStatus(SkillSession.Status.ACTIVE);
            for (SkillSession session : activeSessions) {
                String sessionId = session.getId().toString();
                subscribeToSessionBroadcast(sessionId);
            }
            log.info("Startup: subscribed to {} active session Redis channels", activeSessions.size());
        } catch (Exception e) {
            log.error("Failed to subscribe to active sessions on startup: {}", e.getMessage(), e);
        }
    }

    // ==================== Redis Session Broadcast ====================

    /**
     * Subscribe to Skill Redis session:{id} channel.
     * When a message is received, push it to local Client subscribers.
     */
    public void subscribeToSessionBroadcast(String sessionId) {
        redisMessageBroker.subscribeToSession(sessionId, message -> {
            handleSessionBroadcast(sessionId, message);
        });
        log.info("Subscribed to session broadcast channel: session:{}", sessionId);
    }

    /**
     * Unsubscribe from Skill Redis session channel.
     */
    public void unsubscribeFromSession(String sessionId) {
        redisMessageBroker.unsubscribeFromSession(sessionId);
        sequenceTracker.resetSession(sessionId);
        log.info("Unsubscribed from session channel: {}", sessionId);
    }

    /**
     * Handle a message received from Skill Redis session:{id} broadcast.
     * This is called on EVERY Skill instance. Each instance pushes to its local
     * Clients.
     */
    private void handleSessionBroadcast(String sessionId, String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);
            String type = node.path("type").asText("delta");
            String content = node.has("content") ? node.get("content").toString() : null;

            // Push to local Client subscribers
            skillStreamHandler.pushToSession(sessionId, type, content);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse session broadcast for session {}: {}", sessionId, e.getMessage());
        }
    }

    // ==================== Downstream: Skill â†?Gateway ====================

    /**
     * Send an invoke command to AI-Gateway.
     * v1 protocol: WS direct path with invoke_relay fallback.
     *
     * @param agentId   the target agent ID
     * @param sessionId the skill session ID
     * @param action    the action to invoke (e.g., "chat", "create_session")
     * @param payload   the action payload as a JSON string
     */
    public void sendInvokeToGateway(String agentId, String sessionId, String action, String payload) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "invoke");
        message.put("agentId", agentId);
        message.put("sessionId", sessionId);
        message.put("action", action);

        try {
            if (payload != null) {
                JsonNode payloadNode = objectMapper.readTree(payload);
                message.set("payload", payloadNode);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse invoke payload as JSON, sending as string: {}", e.getMessage());
            message.put("payload", payload);
        }

        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize invoke message", e);
            return;
        }

        // v1 protocol: try WS direct path first, fall back to invoke_relay
        if (gatewayWSHandler.hasActiveConnection()) {
            boolean sent = gatewayWSHandler.sendToGateway(messageText);
            if (sent) {
                log.debug("Invoke sent via WS direct path: agentId={}, action={}", agentId, action);
                return;
            }
        }

        // Fallback: publish to Skill Redis invoke_relay channel
        redisMessageBroker.publishInvokeRelay(agentId, messageText);
        log.debug("Invoke sent via invoke_relay: agentId={}, action={}", agentId, action);
    }

    // ==================== invoke_relay Listener ====================

    /**
     * Subscribe to invoke_relay:{agentId} channel.
     * Called when this instance has a Gateway WS and can relay invoke for other
     * instances.
     */
    public void subscribeToInvokeRelay(String agentId) {
        redisMessageBroker.subscribeInvokeRelay(agentId, message -> {
            handleInvokeRelay(agentId, message);
        });
        log.info("Subscribed to invoke_relay:{}", agentId);
    }

    /**
     * Handle an invoke_relay message: forward to Gateway via WS.
     */
    private void handleInvokeRelay(String agentId, String message) {
        if (gatewayWSHandler.hasActiveConnection()) {
            boolean sent = gatewayWSHandler.sendToGateway(message);
            if (sent) {
                log.debug("Relayed invoke to Gateway via WS: agentId={}", agentId);
            } else {
                log.warn("Failed to relay invoke to Gateway: agentId={}", agentId);
            }
        } else {
            log.warn("Received invoke_relay but no Gateway WS on this instance: agentId={}", agentId);
        }
    }

    // ==================== Upstream: Gateway â†?Skill (via WS) ====================

    /**
     * Handle an incoming message from AI-Gateway (received by GatewayWSHandler).
     * Dispatches to appropriate handler based on message type.
     */
    public void handleGatewayMessage(String rawMessage) {
        JsonNode node;
        try {
            node = objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse gateway message: {}", rawMessage, e);
            return;
        }

        String type = node.path("type").asText("");
        String sessionId = node.path("sessionId").asText(null);
        String agentId = node.path("agentId").asText(null);

        // Extract envelope metadata if present
        JsonNode envelopeNode = node.path("envelope");
        if (!envelopeNode.isMissingNode()) {
            Long sequenceNumber = envelopeNode.path("sequenceNumber").asLong(0L);
            String messageId = envelopeNode.path("messageId").asText(null);
            String source = envelopeNode.path("source").asText("UNKNOWN");
            String envelopeSessionId = envelopeNode.path("sessionId").asText(null);

            log.debug("Received enveloped message: type={}, sessionId={}, messageId={}, seq={}, source={}",
                    type, sessionId, messageId, sequenceNumber, source);

            // Pass sequence number to SequenceTracker for gap detection
            String effectiveSessionId = envelopeSessionId != null ? envelopeSessionId : sessionId;
            if (effectiveSessionId != null && sequenceNumber > 0) {
                String action = sequenceTracker.validateSequence(effectiveSessionId, sequenceNumber);
                if ("reconnect".equals(action)) {
                    log.error("Large sequence gap detected for session {}, seq={}", effectiveSessionId, sequenceNumber);
                } else if ("request_recovery".equals(action)) {
                    log.warn("Medium sequence gap detected for session {}, seq={}", effectiveSessionId, sequenceNumber);
                }
            }
        }

        switch (type) {
            case "tool_event" -> handleToolEvent(sessionId, node);
            case "tool_done" -> handleToolDone(sessionId, node);
            case "tool_error" -> handleToolError(sessionId, node);
            case "agent_online" -> handleAgentOnline(agentId, node);
            case "agent_offline" -> handleAgentOffline(agentId);
            case "session_created" -> handleSessionCreated(agentId, node);
            case "permission_request" -> handlePermissionRequest(sessionId, node);
            default -> log.warn("Unknown gateway message type: {}", type);
        }
    }

    // ==================== Message Handlers ====================

    private void handleToolEvent(String sessionId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_event missing sessionId");
            return;
        }

        // Extract raw event and persist as assistant message
        JsonNode event = node.get("event");
        String rawEvent = event != null ? event.toString() : node.toString();

        try {
            messageService.saveAssistantMessage(Long.valueOf(sessionId), rawEvent, null);
        } catch (Exception e) {
            log.error("Failed to persist tool_event for session {}: {}", sessionId, e.getMessage());
        }

        // v1: Publish to Skill Redis for multi-instance broadcast (replaces direct
        // pushToSession)
        broadcastToSession(sessionId, "delta", rawEvent);
    }

    private void handleToolDone(String sessionId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_done missing sessionId");
            return;
        }

        JsonNode usage = node.get("usage");
        String usageMeta = usage != null ? usage.toString() : null;

        // v1: Broadcast via Skill Redis
        broadcastToSession(sessionId, "done", usageMeta);

        log.info("Tool done for session {}: usage={}", sessionId, usageMeta);
    }

    private void handleToolError(String sessionId, JsonNode node) {
        String error = node.path("error").asText("Unknown error");

        if (sessionId != null) {
            try {
                messageService.saveSystemMessage(Long.valueOf(sessionId), "Error: " + error);
            } catch (Exception e) {
                log.error("Failed to persist tool_error for session {}: {}", sessionId, e.getMessage());
            }
            // v1: Broadcast via Skill Redis
            broadcastToSession(sessionId, "error", error);
        }

        log.error("Tool error for session {}: {}", sessionId, error);
    }

    private void handleAgentOnline(String agentId, JsonNode node) {
        String toolType = node.path("toolType").asText("UNKNOWN");
        String toolVersion = node.path("toolVersion").asText("UNKNOWN");
        log.info("Agent online: agentId={}, toolType={}, toolVersion={}", agentId, toolType, toolVersion);

        // Notify all subscribers of sessions associated with this agent
        if (agentId != null) {
            try {
                sessionService.findByAgentId(Long.valueOf(agentId)).forEach(
                        session -> broadcastToSession(session.getId().toString(), "agent_online", null));
            } catch (NumberFormatException e) {
                log.warn("Invalid agentId for online event: {}", agentId);
            }
        }
    }

    private void handleAgentOffline(String agentId) {
        log.warn("Agent offline: agentId={}", agentId);

        // Notify all subscribers of sessions associated with this agent
        if (agentId != null) {
            try {
                sessionService.findByAgentId(Long.valueOf(agentId)).forEach(
                        session -> broadcastToSession(session.getId().toString(), "agent_offline", null));
            } catch (NumberFormatException e) {
                log.warn("Invalid agentId for offline event: {}", agentId);
            }
        }
    }

    private void handleSessionCreated(String agentId, JsonNode node) {
        String toolSessionId = node.path("toolSessionId").asText(null);
        String sessionId = node.path("sessionId").asText(null);

        if (sessionId != null && toolSessionId != null) {
            try {
                sessionService.updateToolSessionId(Long.valueOf(sessionId), toolSessionId);
                log.info("Tool session created: sessionId={}, toolSessionId={}", sessionId, toolSessionId);
            } catch (Exception e) {
                log.error("Failed to update tool session ID: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Handle a permission request from OpenCode via AI-Gateway.
     * Pushes the request to connected Skill miniapp clients for user approval.
     */
    private void handlePermissionRequest(String sessionId, JsonNode node) {
        if (sessionId == null) {
            log.warn("permission_request missing sessionId");
            return;
        }

        String permissionId = node.path("permissionId").asText(null);
        String command = node.path("command").asText(null);
        String workingDirectory = node.path("workingDirectory").asText(null);

        // Build permission request content for the client
        ObjectNode content = objectMapper.createObjectNode();
        if (permissionId != null)
            content.put("permissionId", permissionId);
        if (command != null)
            content.put("command", command);
        if (workingDirectory != null)
            content.put("workingDirectory", workingDirectory);

        // Copy any additional fields from the original message
        JsonNode event = node.get("event");
        if (event != null) {
            content.set("event", event);
        }

        String contentStr = content.toString();

        // v1: Broadcast via Skill Redis
        broadcastToSession(sessionId, "permission_request", contentStr);

        log.info("Permission request broadcast to session {}: permId={}, command={}",
                sessionId, permissionId, command);
    }

    // ==================== Internal Helpers ====================

    /**
     * Broadcast a message to all Skill instances via Skill Redis session:{id}
     * channel.
     * Each instance's handleSessionBroadcast() will push to local Client
     * subscribers.
     */
    private void broadcastToSession(String sessionId, String type, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", type);
        message.put("sessionId", sessionId);
        if (content != null) {
            try {
                message.set("content", objectMapper.readTree(content));
            } catch (JsonProcessingException e) {
                message.put("content", content);
            }
        }

        try {
            String messageText = objectMapper.writeValueAsString(message);
            redisMessageBroker.publishToSession(sessionId, messageText);
        } catch (JsonProcessingException e) {
            log.error("Failed to broadcast to session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Request recovery of missing messages for a session.
     */
    private void requestRecovery(String sessionId, long fromSequence) {
        try {
            var session = sessionService.getSession(Long.valueOf(sessionId));
            if (session.getAgentId() != null) {
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("fromSequence", fromSequence);
                payload.put("sessionId", sessionId);

                String payloadStr;
                try {
                    payloadStr = objectMapper.writeValueAsString(payload);
                } catch (JsonProcessingException e) {
                    payloadStr = "{}";
                }

                sendInvokeToGateway(
                        session.getAgentId().toString(),
                        sessionId,
                        "request_recovery",
                        payloadStr);
                log.info("Recovery requested for session {}: fromSeq={}", sessionId, fromSequence);
            }
        } catch (Exception e) {
            log.error("Failed to request recovery for session {}: {}", sessionId, e.getMessage());
        }
    }
}
