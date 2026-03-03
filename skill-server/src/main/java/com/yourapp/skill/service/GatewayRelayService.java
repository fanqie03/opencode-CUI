package com.yourapp.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.skill.ws.SkillStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages communication with AI-Gateway via WebSocket.
 * Routes invoke commands to gateway. Receives tool events and dispatches to SkillStreamHandler.
 *
 * Uses Redis pub/sub for multi-instance coordination:
 * - Subscribes to session:{sessionId} channels on WebSocket connect
 * - Publishes to agent:{agentId} channels for routing across instances
 * - Validates sequence numbers on incoming messages
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

    /**
     * Active gateway WebSocket sessions keyed by session ID (local connections only).
     */
    private final ConcurrentHashMap<String, WebSocketSession> gatewaySessions = new ConcurrentHashMap<>();

    public GatewayRelayService(ObjectMapper objectMapper,
                               SkillStreamHandler skillStreamHandler,
                               SkillMessageService messageService,
                               SkillSessionService sessionService,
                               RedisMessageBroker redisMessageBroker,
                               SequenceTracker sequenceTracker) {
        this.objectMapper = objectMapper;
        this.skillStreamHandler = skillStreamHandler;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.redisMessageBroker = redisMessageBroker;
        this.sequenceTracker = sequenceTracker;
    }

    /**
     * Register a gateway WebSocket session.
     * Note: This is for the internal gateway connection, not per-session subscriptions.
     */
    public void registerGatewaySession(String sessionKey, WebSocketSession session) {
        gatewaySessions.put(sessionKey, session);
        log.info("Gateway session registered: {}", sessionKey);
    }

    /**
     * Remove a gateway WebSocket session.
     */
    public void removeGatewaySession(String sessionKey) {
        gatewaySessions.remove(sessionKey);
        log.info("Gateway session removed: {}", sessionKey);
    }

    /**
     * Subscribe to Redis channel for a specific session.
     * Called when a skill session is created.
     */
    public void subscribeToSession(String sessionId) {
        redisMessageBroker.subscribeToSession(sessionId, message -> {
            handleRedisMessage(sessionId, message);
        });
        log.info("Subscribed to session channel: {}", sessionId);
    }

    /**
     * Unsubscribe from Redis channel for a specific session.
     * Called when a skill session is closed.
     */
    public void unsubscribeFromSession(String sessionId) {
        redisMessageBroker.unsubscribeFromSession(sessionId);
        sequenceTracker.resetSession(sessionId);
        log.info("Unsubscribed from session channel: {}", sessionId);
    }

    /**
     * Handle a message received from Redis pub/sub.
     * Validates sequence numbers and routes to appropriate handler.
     */
    private void handleRedisMessage(String sessionId, String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);

            // Validate sequence number
            Long sequenceNumber = node.has("sequenceNumber") ? node.get("sequenceNumber").asLong() : null;
            String action = sequenceTracker.validateSequence(sessionId, sequenceNumber);

            switch (action) {
                case "reconnect":
                    log.error("Triggering reconnect for session {} due to large sequence gap", sessionId);
                    // TODO: Implement reconnect logic
                    break;
                case "request_recovery":
                    log.warn("Requesting recovery for session {} due to medium sequence gap", sessionId);
                    // TODO: Implement recovery request logic
                    break;
                case "continue":
                default:
                    // Process message normally
                    handleGatewayMessage(rawMessage);
                    break;
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Redis message for session {}: {}", sessionId, rawMessage, e);
        }
    }

    /**
     * Send an invoke command to AI-Gateway for routing to a PCAgent.
     * Publishes to Redis channel - any gateway instance with this agent connected will receive it.
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

        // Publish to Redis channel instead of direct WebSocket send
        redisMessageBroker.publishToAgent(agentId, messageText);
        log.debug("Published invoke to agent channel: agentId={}, action={}, sessionId={}",
                agentId, action, sessionId);
    }

    /**
     * Handle an incoming message from AI-Gateway.
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
                    // Continue processing but log the gap
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
            default -> log.warn("Unknown gateway message type: {}", type);
        }
    }

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

        // Push delta to Skill miniapp subscribers
        skillStreamHandler.pushToSession(sessionId, "delta", rawEvent);
    }

    private void handleToolDone(String sessionId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_done missing sessionId");
            return;
        }

        JsonNode usage = node.get("usage");
        String usageMeta = usage != null ? usage.toString() : null;

        // Push done event to Skill miniapp subscribers
        skillStreamHandler.pushToSession(sessionId, "done", usageMeta);

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
            skillStreamHandler.pushToSession(sessionId, "error", error);
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
                sessionService.findByAgentId(Long.valueOf(agentId)).forEach(session ->
                        skillStreamHandler.pushToSession(session.getId().toString(), "agent_online", null));
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
                sessionService.findByAgentId(Long.valueOf(agentId)).forEach(session ->
                        skillStreamHandler.pushToSession(session.getId().toString(), "agent_offline", null));
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
}
