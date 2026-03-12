package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages communication with AI-Gateway.
 *
 * Upstream (Gateway -> Skill via WS):
 * - Receives tool_event/done/error/session_created/agent_online/offline
 * - Persists to DB, then publishes to Skill Redis user-stream:{userId} for
 * multi-instance broadcast
 * - Each Skill instance subscribes to user-stream:{userId} and pushes to local clients
 *
 * Downstream (Skill -> Gateway):
 * - Sends invoke messages through the active internal WebSocket connection
 */
@Slf4j
@Service
public class GatewayRelayService {

    public static final String SOURCE = "skill-server";

    public interface GatewayRelayTarget {
        boolean sendToGateway(String message);

        boolean hasActiveConnection();
    }

    private final ObjectMapper objectMapper;
    private final SkillStreamHandler skillStreamHandler;
    private final SkillMessageService messageService;
    private final SkillSessionService sessionService;
    private final SkillMessageRepository messageRepository;
    private final RedisMessageBroker redisMessageBroker;
    private final SequenceTracker sequenceTracker;
    private final OpenCodeEventTranslator translator;
    private final MessagePersistenceService persistenceService;
    private final StreamBufferService bufferService;
    private volatile GatewayRelayTarget gatewayRelayTarget;

    /**
     * Stores pending message text to retry after toolSession rebuild. sessionId →
     * text
     */
    private final ConcurrentHashMap<String, String> pendingRebuildMessages = new ConcurrentHashMap<>();

    public GatewayRelayService(ObjectMapper objectMapper,
            SkillStreamHandler skillStreamHandler,
            SkillMessageService messageService,
            SkillSessionService sessionService,
            SkillMessageRepository messageRepository,
            RedisMessageBroker redisMessageBroker,
            SequenceTracker sequenceTracker,
            OpenCodeEventTranslator translator,
            MessagePersistenceService persistenceService,
            StreamBufferService bufferService) {
        this.objectMapper = objectMapper;
        this.skillStreamHandler = skillStreamHandler;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.redisMessageBroker = redisMessageBroker;
        this.sequenceTracker = sequenceTracker;
        this.translator = translator;
        this.persistenceService = persistenceService;
        this.bufferService = bufferService;
    }

    public void setGatewayRelayTarget(GatewayRelayTarget gatewayRelayTarget) {
        this.gatewayRelayTarget = gatewayRelayTarget;
    }

    // ==================== Initialization ====================

    /**
     * On startup, wire circular dependency with SkillSessionService.
     */
    @PostConstruct
    public void init() {
        // Wire circular dependency: SkillSessionService needs GatewayRelayService
        // for Redis unsubscribe during IDLE cleanup
        sessionService.setGatewayRelayService(this);
        log.info("GatewayRelayService initialized");
    }

    // ==================== Downstream: Skill -> Gateway ====================

    /**
     * Send an invoke command to AI-Gateway over the active internal WebSocket.
     *
     * @param ak        the target agent key
     * @param sessionId the skill session ID
     * @param action    the action to invoke (e.g., "chat", "create_session")
     * @param payload   the action payload as a JSON string
     */
    public void sendInvokeToGateway(String ak, String userId, String sessionId, String action, String payload) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "invoke");
        message.put("ak", ak);
        message.put("source", SOURCE);
        if (userId != null && !userId.isBlank()) {
            message.put("userId", userId);
        }
        if ("create_session".equals(action) && sessionId != null && !sessionId.isBlank()) {
            // 使用字符串传输 welinkSessionId，防止 JavaScript IEEE 754 大数精度丢失
            message.put("welinkSessionId", sessionId);
        }
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

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("Gateway WS connection not available, invoke dropped: ak={}, userId={}, action={}",
                    ak, userId, action);
            return;
        }

        boolean sent = relayTarget.sendToGateway(messageText);
        if (!sent) {
            log.warn("Failed to send invoke through Gateway WS: ak={}, userId={}, action={}",
                    ak, userId, action);
            return;
        }

        log.debug("Invoke sent via Gateway WS: ak={}, userId={}, action={}", ak, userId, action);
    }

    // ==================== Upstream: Gateway -> Skill (via WS) ====================

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
        String ak = node.path("ak").asText(null);
        String userId = node.path("userId").asText(null);
        if (ak == null || ak.isBlank()) {
            ak = node.path("agentId").asText(null);
        }

        // Resolve welinkSessionId: prefer explicit sessionId, fallback to toolSessionId
        // DB lookup
        String sessionId = requiresSessionAffinity(type) ? resolveSessionId(type, node) : null;

        // Trace: log key fields for full-chain debugging
        log.debug("Gateway message dispatch: type={}, sessionId={}, ak={}, userId={}",
                type, sessionId, ak, userId);

        switch (type) {
            case "tool_event" -> handleToolEvent(sessionId, userId, node);
            case "tool_done" -> handleToolDone(sessionId, userId, node);
            case "tool_error" -> handleToolError(sessionId, userId, node);
            case "agent_online" -> handleAgentOnline(ak, userId, node);
            case "agent_offline" -> handleAgentOffline(ak, userId);
            case "session_created" -> handleSessionCreated(ak, userId, node);
            case "permission_request" -> handlePermissionRequest(sessionId, userId, node);
            default -> log.warn("Unknown gateway message type: {}", type);
        }
    }

    /**
     * Resolve the welink session ID from a gateway message.
     * Accepts protocol fields only: explicit welinkSessionId, otherwise
     * resolve via toolSessionId -> DB lookup.
     */
    private String resolveSessionId(String messageType, JsonNode node) {
        String sessionId = node.path("welinkSessionId").asText(null);
        if (sessionId != null) {
            return sessionId;
        }

        // Fallback: resolve via toolSessionId → DB lookup
        String toolSessionId = node.path("toolSessionId").asText(null);
        if (toolSessionId != null) {
            try {
                SkillSession session = sessionService.findByToolSessionId(toolSessionId);
                if (session != null) {
                    log.debug("Resolved session affinity: type={}, toolSessionId={} -> welinkSessionId={}",
                            messageType, toolSessionId, session.getId());
                    return session.getId().toString();
                } else {
                    log.warn("Dropping upstream message: type={}, toolSessionId={}, reason=session_not_found",
                            messageType, toolSessionId);
                }
            } catch (Exception e) {
                log.error("Failed to resolve upstream session affinity: type={}, toolSessionId={}, reason={}",
                        messageType, toolSessionId, e.getMessage());
            }
        } else {
            log.warn("Dropping upstream message: type={}, toolSessionId=<missing>, reason=no_session_identifier, keys={}",
                    messageType, fieldNames(node));
        }

        return null;
    }

    private boolean requiresSessionAffinity(String messageType) {
        return switch (messageType) {
            case "tool_event", "tool_done", "tool_error", "permission_request" -> true;
            default -> false;
        };
    }

    // ==================== Message Handlers ====================

    private void handleToolEvent(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_event missing sessionId, agentId={}, raw keys={}",
                    node.path("agentId").asText(null), node.fieldNames());
            return;
        }

        // Ensure Redis subscription is active for this session (lazy subscribe)

        // Activate session if currently IDLE (IDLE → ACTIVE on first successful event)
        try {
            boolean activated = sessionService.activateSession(Long.parseLong(sessionId));
            if (activated) {
                // Notify frontend that session is now active
                StreamMessage activeMsg = StreamMessage.builder()
                        .type(StreamMessage.Types.SESSION_STATUS)
                        .sessionStatus("busy")
                        .build();
                broadcastStreamMessage(sessionId, userId, activeMsg);
            }
        } catch (NumberFormatException e) {
            // ignore non-numeric sessionId
        }

        log.debug("handleToolEvent: sessionId={}, broadcasting to subscribers", sessionId);

        // Extract the OpenCode event
        JsonNode event = node.get("event");

        // Translate OpenCode event to semantic StreamMessage
        StreamMessage msg = translator.translate(event);
        if (msg == null) {
            log.debug("Event ignored by translator for session {}", sessionId);
            return;
        }

        // Broadcast to all subscribers via Skill Redis
        broadcastStreamMessage(sessionId, userId, msg);

        // Accumulate to Redis buffer (for resume)
        bufferService.accumulate(sessionId, msg);

        // Persist final states
        try {
            persistenceService.persistIfFinal(Long.parseLong(sessionId), msg);
        } catch (NumberFormatException e) {
            log.warn("Cannot persist: sessionId is not a valid Long: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to persist StreamMessage for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    private void handleToolDone(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("tool_done missing sessionId, agentId={}", node.path("agentId").asText(null));
            return;
        }

        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS)
                .sessionStatus("idle")
                .build();
        broadcastStreamMessage(sessionId, userId, msg);

        // Accumulate to Redis buffer (clears session on idle)
        bufferService.accumulate(sessionId, msg);

        // Persist session idle status
        try {
            persistenceService.persistIfFinal(Long.parseLong(sessionId), msg);
        } catch (NumberFormatException e) {
            log.warn("Cannot persist tool_done: sessionId is not a valid Long: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to persist tool_done for session {}: {}", sessionId, e.getMessage(), e);
        }

        log.info("Tool done for session {}", sessionId);
    }

    private void handleToolError(String sessionId, String userId, JsonNode node) {
        String error = node.path("error").asText("Unknown error");
        String reason = node.path("reason").asText("");

        if (sessionId != null) {
            // Check if this is a session_not_found error → trigger toolSession rebuild
            // Primary: explicit reason from PCAgent
            // Fallback: error message pattern matching for known session-invalid indicators
            if ("session_not_found".equals(reason) || isSessionInvalidError(error)) {
                handleSessionNotFound(sessionId, userId);
                return;
            }

            try {
                messageService.saveSystemMessage(Long.valueOf(sessionId), "Error: " + error);
            } catch (Exception e) {
                log.error("Failed to persist tool_error for session {}: {}", sessionId, e.getMessage());
            }
            StreamMessage msg = StreamMessage.builder()
                    .type(StreamMessage.Types.ERROR)
                    .error(error)
                    .build();
            broadcastStreamMessage(sessionId, userId, msg);
            try {
                persistenceService.finalizeActiveAssistantTurn(Long.valueOf(sessionId));
            } catch (NumberFormatException e) {
                log.warn("Cannot finalize active message after tool_error: invalid sessionId={}", sessionId);
            }
        }

        log.error("Tool error for session {}: {}", sessionId, error);
    }

    /**
     * Check if an error message indicates the tool session is no longer valid.
     * Fallback detection when PCAgent doesn't explicitly set
     * reason=session_not_found.
     */
    private boolean isSessionInvalidError(String error) {
        if (error == null)
            return false;
        String lower = error.toLowerCase();
        return lower.contains("not found")
                || lower.contains("session_not_found")
                || lower.contains("json parse error")
                || lower.contains("unexpected eof");
    }

    /**
     * Handle session_not_found error: clear invalid toolSessionId,
     * store pending message for retry, and auto-rebuild by sending
     * create_session to Gateway.
     */
    private void handleSessionNotFound(String sessionId, String userId) {
        log.warn("Tool session not found for welinkSession={}, initiating rebuild", sessionId);

        try {
            Long sessionIdLong = Long.valueOf(sessionId);
            SkillSession session = sessionService.getSession(sessionIdLong);

            // Clear invalid toolSessionId
            sessionService.clearToolSessionId(sessionIdLong);

            // Store the last user message text for retry after rebuild
            SkillMessage lastUserMsg = messageRepository.findLastUserMessage(sessionIdLong);
            if (lastUserMsg != null && lastUserMsg.getContent() != null) {
                pendingRebuildMessages.put(sessionId, lastUserMsg.getContent());
                log.info("Stored pending retry message for session {}: '{}'",
                        sessionId,
                        lastUserMsg.getContent().substring(0, Math.min(50, lastUserMsg.getContent().length())));
            }

            // Notify frontend that session is reconnecting
            StreamMessage reconnecting = StreamMessage.builder()
                    .type(StreamMessage.Types.SESSION_STATUS)
                    .sessionStatus("retry")
                    .build();
            broadcastStreamMessage(sessionId, userId, reconnecting);

            // Send create_session to Gateway to rebuild toolSession
            if (session.getAk() != null) {
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("title", session.getTitle() != null ? session.getTitle() : "");
                String payloadStr;
                try {
                    payloadStr = objectMapper.writeValueAsString(payload);
                } catch (JsonProcessingException e) {
                    payloadStr = "{}";
                }
                sendInvokeToGateway(session.getAk(), session.getUserId(), sessionId, "create_session", payloadStr);
                log.info("Rebuild create_session sent for welinkSession={}, ak={}", sessionId, session.getAk());
            } else {
                log.error("Cannot rebuild session {}: no ak associated", sessionId);
                pendingRebuildMessages.remove(sessionId);
                StreamMessage errorMsg = StreamMessage.builder()
                        .type(StreamMessage.Types.ERROR)
                        .error("AI session expired and cannot be rebuilt")
                        .build();
                broadcastStreamMessage(sessionId, userId, errorMsg);
            }
        } catch (Exception e) {
            log.error("Failed to rebuild session {}: {}", sessionId, e.getMessage(), e);
            pendingRebuildMessages.remove(sessionId);
        }
    }

    private void handleAgentOnline(String ak, String userId, JsonNode node) {
        String toolType = node.path("toolType").asText("UNKNOWN");
        String toolVersion = node.path("toolVersion").asText("UNKNOWN");
        log.info("Agent online: ak={}, toolType={}, toolVersion={}", ak, toolType, toolVersion);

        if (ak != null) {
            StreamMessage msg = StreamMessage.builder()
                    .type(StreamMessage.Types.AGENT_ONLINE)
                    .build();
            sessionService.findByAk(ak).forEach(
                    session -> broadcastStreamMessage(session.getId().toString(),
                            userId != null && !userId.isBlank() ? userId : session.getUserId(),
                            msg));
        }
    }

    private void handleAgentOffline(String ak, String userId) {
        log.warn("Agent offline: ak={}", ak);

        if (ak != null) {
            StreamMessage msg = StreamMessage.builder()
                    .type(StreamMessage.Types.AGENT_OFFLINE)
                    .build();
            sessionService.findByAk(ak).forEach(
                    session -> broadcastStreamMessage(session.getId().toString(),
                            userId != null && !userId.isBlank() ? userId : session.getUserId(),
                            msg));
        }
    }

    private void handleSessionCreated(String ak, String userId, JsonNode node) {
        String toolSessionId = node.path("toolSessionId").asText(null);
        String sessionId = node.path("welinkSessionId").asText(null);

        if (sessionId == null || toolSessionId == null) {
            log.warn("session_created missing fields: sessionId={}, toolSessionId={}, ak={}, raw={}",
                    sessionId, toolSessionId, ak, node.toString());
            return;
        }

        try {
            sessionService.updateToolSessionId(Long.valueOf(sessionId), toolSessionId);
            log.info("Tool session created: sessionId={}, toolSessionId={}", sessionId, toolSessionId);

            // Check for pending message retry after rebuild
            String pendingText = pendingRebuildMessages.remove(sessionId);
            if (pendingText != null) {
                log.info("Retrying pending message after rebuild: sessionId={}, text='{}'",
                        sessionId, pendingText.substring(0, Math.min(50, pendingText.length())));

                // Build chat payload with new toolSessionId and re-send
                ObjectNode chatPayload = objectMapper.createObjectNode();
                chatPayload.put("text", pendingText);
                chatPayload.put("toolSessionId", toolSessionId);
                String payloadStr;
                try {
                    payloadStr = objectMapper.writeValueAsString(chatPayload);
                } catch (JsonProcessingException e) {
                    payloadStr = "{}";
                }
                sendInvokeToGateway(ak, userId, sessionId, "chat", payloadStr);
                log.info("Pending message re-sent after rebuild: sessionId={}", sessionId);

                // Notify frontend that session is active again
                StreamMessage activeMsg = StreamMessage.builder()
                        .type(StreamMessage.Types.SESSION_STATUS)
                        .sessionStatus("busy")
                        .build();
                broadcastStreamMessage(sessionId, userId, activeMsg);
            }
        } catch (Exception e) {
            log.error("Failed to update tool session ID: sessionId={}, error={}", sessionId, e.getMessage());
            pendingRebuildMessages.remove(sessionId);
        }
    }

    /**
     * Handle a permission request from OpenCode via AI-Gateway.
     * Pushes the request to connected Skill miniapp clients for user approval.
     */
    private void handlePermissionRequest(String sessionId, String userId, JsonNode node) {
        if (sessionId == null) {
            log.warn("permission_request missing sessionId");
            return;
        }

        StreamMessage msg = translator.translatePermissionFromGateway(node);
        broadcastStreamMessage(sessionId, userId, msg);

        log.info("Permission request broadcast to session {}: permId={}",
                sessionId, msg.getPermissionId());
    }

    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        broadcastStreamMessage(sessionId, null, msg);
        bufferService.accumulate(sessionId, msg);
    }

    // ==================== Internal Helpers ====================

    /**
     * Broadcast a StreamMessage to all skill instances that currently hold the
     * user's stream connection via the user-stream Redis channel.
     */
    private void broadcastStreamMessage(String sessionId, String userIdHint, StreamMessage msg) {
        try {
            enrichStreamMessage(sessionId, msg);
            String userId = resolveUserId(sessionId, userIdHint);
            if (userId == null || userId.isBlank()) {
                log.warn("Failed to broadcast StreamMessage without userId: sessionId={}, type={}",
                        sessionId, msg.getType());
                return;
            }
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("sessionId", sessionId);
            envelope.put("userId", userId);
            envelope.set("message", objectMapper.valueToTree(msg));

            String messageText = objectMapper.writeValueAsString(envelope);
            redisMessageBroker.publishToUser(userId, messageText);
        } catch (Exception e) {
            log.error("Failed to broadcast StreamMessage to session {}: type={}, error={}",
                    sessionId, msg.getType(), e.getMessage());
        }
    }

    private String resolveUserId(String sessionId, String userIdHint) {
        if (userIdHint != null && !userIdHint.isBlank()) {
            return userIdHint;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessionService.getSession(Long.valueOf(sessionId)).getUserId();
        } catch (Exception e) {
            log.warn("Failed to resolve userId for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void enrichStreamMessage(String sessionId, StreamMessage msg) {
        // Frontend WS routing expects the skill session id here, not the OpenCode tool
        // session id.
        msg.setSessionId(sessionId);
        msg.setWelinkSessionId(sessionId);
        if (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank()) {
            msg.setEmittedAt(Instant.now().toString());
        }
        try {
            persistenceService.prepareMessageContext(Long.valueOf(sessionId), msg);
        } catch (NumberFormatException e) {
            log.warn("Cannot prepare stream message context: invalid sessionId={}", sessionId);
        }
    }

    /**
     * Request recovery of missing messages for a session.
     */
    private void requestRecovery(String sessionId, long fromSequence) {
        try {
            var session = sessionService.getSession(Long.valueOf(sessionId));
            if (session.getAk() != null) {
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
                        session.getAk(),
                        session.getUserId(),
                        sessionId,
                        "request_recovery",
                        payloadStr);
                log.info("Recovery requested for session {}: fromSeq={}", sessionId, fromSequence);
            }
        } catch (Exception e) {
            log.error("Failed to request recovery for session {}: {}", sessionId, e.getMessage());
        }
    }




    private String fieldNames(JsonNode node) {
        StringBuilder names = new StringBuilder();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            if (!names.isEmpty()) {
                names.append(',');
            }
            names.append(iterator.next());
        }
        return names.toString();
    }
}
