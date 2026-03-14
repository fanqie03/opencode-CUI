package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;

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
    private final RedisMessageBroker redisMessageBroker;
    private final OpenCodeEventTranslator translator;
    private final MessagePersistenceService persistenceService;
    private final StreamBufferService bufferService;
    private final SessionRebuildService rebuildService;
    private volatile GatewayRelayTarget gatewayRelayTarget;

    /**
     * 记录已完成（tool_done）的 sessionId，用于抑制 tool_done 后到达的乱序 tool_event。
     * TTL 5 秒后自动过期，不影响同一 session 的后续正常对话。
     */
    private final Cache<String, Instant> completedSessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(1_000)
            .build();

    public GatewayRelayService(ObjectMapper objectMapper,
            SkillStreamHandler skillStreamHandler,
            SkillMessageService messageService,
            SkillSessionService sessionService,
            RedisMessageBroker redisMessageBroker,
            OpenCodeEventTranslator translator,
            MessagePersistenceService persistenceService,
            StreamBufferService bufferService,
            SessionRebuildService rebuildService) {
        this.objectMapper = objectMapper;
        this.skillStreamHandler = skillStreamHandler;
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.redisMessageBroker = redisMessageBroker;
        this.translator = translator;
        this.persistenceService = persistenceService;
        this.bufferService = bufferService;
        this.rebuildService = rebuildService;
    }

    public void setGatewayRelayTarget(GatewayRelayTarget gatewayRelayTarget) {
        this.gatewayRelayTarget = gatewayRelayTarget;
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
        // 发送新消息时清除已完成标记，防止新一轮对话的 tool_event 被误拦截
        if ("chat".equals(action) && sessionId != null) {
            completedSessions.invalidate(sessionId);
        }
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

        // 抑制 tool_done 后到达的乱序 tool_event，
        // 防止前端 isStreaming 在被 idle 重置后又被设回 true
        if (completedSessions.getIfPresent(sessionId) != null) {
            log.debug("Suppressing stale tool_event after tool_done: sessionId={}", sessionId);
            return;
        }

        // Ensure Redis subscription is active for this session (lazy subscribe)

        // Activate session if currently IDLE (IDLE → ACTIVE on first successful event)
        try {
            boolean activated = sessionService.activateSession(Long.parseLong(sessionId));
            if (activated) {
                // Notify frontend that session is now active
                StreamMessage activeMsg = StreamMessage.sessionStatus("busy");
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

        // User 消息处理：只关注 TEXT_DONE（包含最终文本内容）
        // 跳过 TEXT_DELTA / STEP_DONE / THINKING_DONE 等中间态或元数据事件，
        // 避免产生空白气泡或干扰前端状态机
        if ("user".equals(msg.getRole())) {
            if (!StreamMessage.Types.TEXT_DONE.equals(msg.getType())) {
                log.debug("Skipping non-text user event for session {}: type={}", sessionId, msg.getType());
                return;
            }
            String content = msg.getContent() != null ? msg.getContent() : "";
            if (content.isBlank()) {
                log.debug("Skipping blank user TEXT_DONE for session {}", sessionId);
                return;
            }
            // 去重：miniapp echo vs opencode CLI
            try {
                Long numericSessionId = Long.parseLong(sessionId);
                if (persistenceService.consumePendingUserMessage(numericSessionId)) {
                    log.debug("Skipping miniapp user message echo for session {}", sessionId);
                    return;
                }
                // opencode CLI 发出的 user 消息 → 持久化
                messageService.saveUserMessage(numericSessionId, content);
                log.info("Persisted user message from opencode CLI: sessionId={}, len={}",
                        sessionId, content.length());
            } catch (NumberFormatException e) {
                log.warn("Cannot process user message: sessionId is not a valid Long: {}", sessionId);
            } catch (Exception e) {
                log.error("Failed to persist CLI user message for session {}: {}",
                        sessionId, e.getMessage(), e);
            }
            // 广播到 miniapp
            broadcastStreamMessage(sessionId, userId, msg);
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

        // 标记 session 已完成，抑制后续乱序到达的 tool_event
        completedSessions.put(sessionId, Instant.now());

        StreamMessage msg = StreamMessage.sessionStatus("idle");
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
        rebuildService.handleSessionNotFound(sessionId, userId, rebuildCallback());
    }

    /**
     * Trigger toolSession rebuild: store pending message, notify frontend retry,
     * send create_session to Gateway.
     * Can be called by Controller when toolSessionId is null.
     *
     * @param sessionId      welinkSessionId (string)
     * @param session        the SkillSession entity
     * @param pendingMessage user message to auto-retry after rebuild (nullable)
     */
    public void rebuildToolSession(String sessionId, SkillSession session, String pendingMessage) {
        rebuildService.rebuildToolSession(sessionId, session, pendingMessage, rebuildCallback());
    }


    private void handleAgentOnline(String ak, String userId, JsonNode node) {
        String toolType = node.path("toolType").asText("UNKNOWN");
        String toolVersion = node.path("toolVersion").asText("UNKNOWN");
        log.info("Agent online: ak={}, toolType={}, toolVersion={}", ak, toolType, toolVersion);

        if (ak != null) {
            StreamMessage msg = StreamMessage.agentOnline();
            sessionService.findByAk(ak).forEach(
                    session -> broadcastStreamMessage(session.getId().toString(),
                            userId != null && !userId.isBlank() ? userId : session.getUserId(),
                            msg));
        }
    }

    private void handleAgentOffline(String ak, String userId) {
        log.warn("Agent offline: ak={}", ak);

        if (ak != null) {
            StreamMessage msg = StreamMessage.agentOffline();
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

            // 检查是否有 rebuild 期间的待重发消息
            String pendingText = rebuildService.consumePendingMessage(sessionId);
            if (pendingText != null) {
                log.info("Retrying pending message after rebuild: sessionId={}, text='{}'",
                        sessionId, pendingText.substring(0, Math.min(50, pendingText.length())));

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

                StreamMessage activeMsg = StreamMessage.sessionStatus("busy");
                broadcastStreamMessage(sessionId, userId, activeMsg);
            }
        } catch (Exception e) {
            log.error("Failed to update tool session ID: sessionId={}, error={}", sessionId, e.getMessage());
            rebuildService.clearPendingMessage(sessionId);
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

    /**
     * 创建回调实例，将广播和发送 invoke 委托回本服务。
     */
    private SessionRebuildService.RebuildCallback rebuildCallback() {
        return new SessionRebuildService.RebuildCallback() {
            @Override
            public void broadcast(String sessionId, String userId, StreamMessage msg) {
                broadcastStreamMessage(sessionId, userId, msg);
            }

            @Override
            public void sendInvoke(String ak, String userId, String sessionId, String action, String payload) {
                sendInvokeToGateway(ak, userId, sessionId, action, payload);
            }
        };
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
        // 协议规定 permission.reply, agent.online, agent.offline, error 不含 emittedAt
        if (!isEmittedAtExcluded(msg.getType())
                && (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank())) {
            msg.setEmittedAt(Instant.now().toString());
        }
        // User 消息已通过 saveUserMessage 独立持久化，
        // 不应走 streaming 持久化管道，否则 resolveActiveMessage 会创建一条空内容的重复记录
        if (!"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            try {
                persistenceService.prepareMessageContext(Long.valueOf(sessionId), msg);
            } catch (NumberFormatException e) {
                log.warn("Cannot prepare stream message context: invalid sessionId={}", sessionId);
            }
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




    /**
     * 协议规定以下事件类型不含 emittedAt 字段。
     */
    private static final java.util.Set<String> EMITTED_AT_EXCLUDED_TYPES = java.util.Set.of(
            StreamMessage.Types.PERMISSION_REPLY,
            StreamMessage.Types.AGENT_ONLINE,
            StreamMessage.Types.AGENT_OFFLINE,
            StreamMessage.Types.ERROR);

    private boolean isEmittedAtExcluded(String type) {
        return type != null && EMITTED_AT_EXCLUDED_TYPES.contains(type);
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
