package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.service.ProtocolMessageMapper;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket handler for Skill miniapp streaming.
 *
 * Protocol endpoint:
 * - /ws/skill/stream : one stream per user, authenticated by Cookie userId
 *
 */
@Slf4j
@Component
public class SkillStreamHandler extends TextWebSocketHandler {

    private static final String ATTR_USER_ID = "userId";

    private final ObjectMapper objectMapper;
    private final StreamBufferService bufferService;
    private final SkillSessionService sessionService;
    private final SkillMessageService messageService;
    private final SkillMessagePartRepository partRepository;
    private final RedisMessageBroker redisMessageBroker;

    /**
     * Protocol subscriptions keyed by userId. Each socket receives events for all
     * active sessions of that user.
     */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> userSubscribers = new ConcurrentHashMap<>();

    /**
     * Cache for resolving welinkSessionId -> userId.
     */
    private final ConcurrentHashMap<String, String> sessionOwners = new ConcurrentHashMap<>();

    /**
     * Per-session transport sequence counter.
     */
    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> activeConnectionCounts = new ConcurrentHashMap<>();

    public SkillStreamHandler(ObjectMapper objectMapper,
            StreamBufferService bufferService,
            SkillSessionService sessionService,
            SkillMessageService messageService,
            SkillMessagePartRepository partRepository,
            RedisMessageBroker redisMessageBroker) {
        this.objectMapper = objectMapper;
        this.bufferService = bufferService;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.partRepository = partRepository;
        this.redisMessageBroker = redisMessageBroker;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = extractUserIdFromCookie(session);
        if (userId == null) {
            log.warn("Skill stream connection rejected: missing userId cookie, remoteAddr={}",
                    session.getRemoteAddress());
            session.close(CloseStatus.BAD_DATA.withReason("Missing userId cookie"));
            return;
        }

        registerUserSubscriber(session, userId);
        sendInitialStreamingState(session, userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String action = node.path("action").asText(null);
            if (!"resume".equals(action)) {
                log.debug("Unknown client action on stream endpoint: wsId={}, action={}",
                        session.getId(), action);
                return;
            }

            String userId = (String) session.getAttributes().get(ATTR_USER_ID);
            if (userId != null) {
                sendInitialStreamingState(session, userId);
            }
        } catch (Exception e) {
            log.warn("Failed to parse client message on stream endpoint: wsId={}, error={}",
                    session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        unregisterSubscriber(session);
        log.info("Skill stream subscriber disconnected: wsId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Skill stream transport error: wsId={}, error={}",
                session.getId(), exception.getMessage(), exception);
        unregisterSubscriber(session);
    }

    /**
     * Push a StreamMessage to subscribers of a given skill session.
     * Automatically assigns transport seq.
     */
    public void pushStreamMessage(String sessionId, StreamMessage msg) {
        Set<WebSocketSession> recipients = resolveRecipients(sessionId);
        if (recipients.isEmpty()) {
            log.debug("No subscribers for session {}, message type={} dropped", sessionId, msg.getType());
            return;
        }

        long seq = nextTransportSeq(sessionId);
        msg.setSeq(seq);

        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize StreamMessage: type={}", msg.getType(), e);
            return;
        }

        TextMessage textMessage = new TextMessage(messageText);
        for (WebSocketSession ws : recipients) {
            if (ws.isOpen()) {
                try {
                    ws.sendMessage(textMessage);
                } catch (IOException e) {
                    log.error("Failed to push StreamMessage to subscriber: sessionId={}, wsId={}, error={}",
                            sessionId, ws.getId(), e.getMessage());
                    unregisterSubscriber(ws);
                }
            } else {
                unregisterSubscriber(ws);
            }
        }
    }

    /**
     * Push a legacy ad-hoc message to subscribers of a given skill session.
     *
     * @deprecated Use {@link #pushStreamMessage(String, StreamMessage)} instead.
     */
    @Deprecated
    public void pushToSession(String sessionId, String type, String content) {
        Set<WebSocketSession> recipients = resolveRecipients(sessionId);
        if (recipients.isEmpty()) {
            log.debug("No subscribers for session {}, message type={} dropped", sessionId, type);
            return;
        }

        long seq = nextTransportSeq(sessionId);
        String message = buildMessage(sessionId, type, content, seq);
        if (message == null) {
            return;
        }

        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession ws : recipients) {
            if (ws.isOpen()) {
                try {
                    ws.sendMessage(textMessage);
                } catch (IOException e) {
                    log.error("Failed to push message to subscriber: sessionId={}, wsId={}, error={}",
                            sessionId, ws.getId(), e.getMessage());
                    unregisterSubscriber(ws);
                }
            } else {
                unregisterSubscriber(ws);
            }
        }
    }

    public int getSubscriberCount(String sessionId) {
        return resolveRecipients(sessionId).size();
    }

    private void registerUserSubscriber(WebSocketSession session, String userId) {
        session.getAttributes().put(ATTR_USER_ID, userId);
        userSubscribers.computeIfAbsent(userId, key -> new CopyOnWriteArraySet<>()).add(session);
        int connections = activeConnectionCounts
                .computeIfAbsent(userId, key -> new AtomicInteger(0))
                .incrementAndGet();
        if (connections == 1) {
            subscribeToUserStream(userId);
        }
        preloadActiveSessionOwners(userId);
        log.info("Skill protocol stream subscriber connected: userId={}, wsId={}, remoteAddr={}",
                userId, session.getId(), session.getRemoteAddress());
    }

    private void unregisterSubscriber(WebSocketSession session) {
        // Both transport error and close callbacks may fire for the same socket.
        // Remove the marker eagerly so cleanup stays idempotent.
        String userId = (String) session.getAttributes().remove(ATTR_USER_ID);
        if (userId != null) {
            Set<WebSocketSession> sessions = userSubscribers.get(userId);
            boolean removed = false;
            if (sessions != null) {
                removed = sessions.remove(session);
                if (sessions.isEmpty()) {
                    userSubscribers.remove(userId);
                }
            }

            if (!removed) {
                return;
            }

            AtomicInteger counter = activeConnectionCounts.get(userId);
            if (counter != null) {
                int remaining = counter.decrementAndGet();
                if (remaining <= 0) {
                    activeConnectionCounts.remove(userId);
                    unsubscribeFromUserStream(userId);
                }
            }
        }
    }

    private void subscribeToUserStream(String userId) {
        if (redisMessageBroker.isUserSubscribed(userId)) {
            return;
        }
        redisMessageBroker.subscribeToUser(userId, message -> handleUserBroadcast(userId, message));
        log.info("Subscribed to user stream: userId={}", userId);
    }

    private void unsubscribeFromUserStream(String userId) {
        redisMessageBroker.unsubscribeFromUser(userId);
        log.info("Unsubscribed from user stream: userId={}", userId);
    }

    private void handleUserBroadcast(String userId, String rawMessage) {
        try {
            JsonNode node = objectMapper.readTree(rawMessage);
            if (node.has("message")) {
                StreamMessage msg = objectMapper.treeToValue(node.get("message"), StreamMessage.class);
                String sessionId = node.path("sessionId").asText(null);
                if (sessionId != null && !sessionId.isBlank()) {
                    msg.setSessionId(sessionId);
                    if (msg.getWelinkSessionId() == null) {
                        msg.setWelinkSessionId(sessionId);
                    }
                }
                pushStreamMessageToUser(userId, msg);
                return;
            }

            String sessionId = node.path("sessionId").asText(null);
            String type = node.path("type").asText("delta");
            String content = node.has("content") ? node.get("content").toString() : null;
            if (sessionId != null) {
                pushToSession(sessionId, type, content);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse user broadcast for user {}: {}", userId, e.getMessage());
        }
    }

    public void pushStreamMessageToUser(String userId, StreamMessage msg) {
        Set<WebSocketSession> recipients = new CopyOnWriteArraySet<>(userSubscribers.getOrDefault(userId, Set.of()));
        if (recipients.isEmpty()) {
            log.debug("No subscribers for user {}, message type={} dropped", userId, msg.getType());
            return;
        }

        String sessionId = msg.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            msg.setSeq(nextTransportSeq(sessionId));
        }

        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize user stream message: userId={}, type={}", userId, msg.getType(), e);
            return;
        }

        TextMessage textMessage = new TextMessage(messageText);
        for (WebSocketSession ws : recipients) {
            if (ws.isOpen()) {
                try {
                    ws.sendMessage(textMessage);
                } catch (IOException e) {
                    log.error("Failed to push user stream message: userId={}, wsId={}, error={}",
                            userId, ws.getId(), e.getMessage());
                    unregisterSubscriber(ws);
                }
            } else {
                unregisterSubscriber(ws);
            }
        }
    }

    private void sendInitialStreamingState(WebSocketSession session, String userId) {
        List<SkillSession> activeSessions = sessionService.findActiveByUserId(userId);
        for (SkillSession skillSession : activeSessions) {
            String sessionId = String.valueOf(skillSession.getId());
            sessionOwners.put(sessionId, skillSession.getUserId());
            sendSnapshot(session, sessionId);
            sendStreamingState(session, sessionId);
        }
    }

    private void sendSnapshot(WebSocketSession session, String sessionId) {
        if (!session.isOpen()) {
            return;
        }
        try {
            List<Object> messages = buildSnapshotMessages(sessionId);
            StreamMessage snapshotMsg = StreamMessage.builder()
                    .type(StreamMessage.Types.SNAPSHOT)
                    .seq(nextTransportSeq(sessionId))
                    .sessionId(sessionId)
                    .emittedAt(Instant.now().toString())
                    .messages(messages)
                    .build();

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(snapshotMsg)));
            log.info("Snapshot sent: sessionId={}, messages={}", sessionId, messages.size());
        } catch (Exception e) {
            log.error("Failed to send snapshot for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    private void sendStreamingState(WebSocketSession session, String sessionId) {
        if (!session.isOpen()) {
            return;
        }
        try {
            boolean isStreaming = bufferService.isSessionStreaming(sessionId);
            List<StreamMessage> parts = bufferService.getStreamingParts(sessionId);
            List<Object> aggregatedParts = parts.stream()
                    .map(part -> ProtocolMessageMapper.toProtocolStreamingPart(part, objectMapper))
                    .filter(Objects::nonNull)
                    .map(part -> (Object) part)
                    .toList();

            StreamMessage streamingMsg = StreamMessage.builder()
                    .type(StreamMessage.Types.STREAMING)
                    .seq(nextTransportSeq(sessionId))
                    .sessionId(sessionId)
                    .emittedAt(Instant.now().toString())
                    .sessionStatus(isStreaming ? "busy" : "idle")
                    .parts(aggregatedParts)
                    .build();
            if (!parts.isEmpty()) {
                StreamMessage firstPart = parts.get(0);
                streamingMsg.setMessageId(firstPart.getMessageId());
                streamingMsg.setMessageSeq(firstPart.getMessageSeq());
                streamingMsg.setRole(firstPart.getRole());
            }

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(streamingMsg)));
            log.info("Streaming state sent: sessionId={}, streaming={}, parts={}",
                    sessionId, isStreaming, parts.size());
        } catch (Exception e) {
            log.error("Failed to send streaming state for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    private List<Object> buildSnapshotMessages(String sessionId) {
        Long numericSessionId;
        try {
            numericSessionId = Long.valueOf(sessionId);
        } catch (NumberFormatException e) {
            log.warn("Cannot build snapshot: invalid sessionId={}", sessionId);
            return List.of();
        }

        return messageService.getAllMessages(numericSessionId).stream()
                .map(this::toSnapshotMessage)
                .map(node -> (Object) node)
                .toList();
    }

    private ObjectNode toSnapshotMessage(SkillMessage message) {
        ProtocolMessageView messageView = ProtocolMessageMapper.toProtocolMessage(
                message,
                partRepository.findByMessageId(message.getId()),
                objectMapper);
        return objectMapper.valueToTree(messageView);
    }

    private Set<WebSocketSession> resolveRecipients(String sessionId) {
        Set<WebSocketSession> recipients = new CopyOnWriteArraySet<>();

        String ownerUserId = sessionOwners.computeIfAbsent(sessionId, this::resolveOwnerUserId);
        if (ownerUserId != null) {
            Set<WebSocketSession> userLevel = userSubscribers.get(ownerUserId);
            if (userLevel != null) {
                recipients.addAll(userLevel);
            }
        }

        return recipients;
    }

    private String resolveOwnerUserId(String sessionId) {
        try {
            SkillSession session = sessionService.getSession(Long.valueOf(sessionId));
            return session.getUserId();
        } catch (Exception e) {
            log.debug("Failed to resolve owner for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void cacheSessionOwner(String sessionId) {
        String userId = resolveOwnerUserId(sessionId);
        if (userId != null) {
            sessionOwners.put(sessionId, userId);
        }
    }

    private void preloadActiveSessionOwners(String userId) {
        for (SkillSession session : sessionService.findActiveByUserId(userId)) {
            if (session.getId() != null && session.getUserId() != null) {
                sessionOwners.put(String.valueOf(session.getId()), session.getUserId());
            }
        }
    }

    private String buildMessage(String sessionId, String type, String content, long seq) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put("seq", seq);
        putWelinkSessionId(node, sessionId);

        if (content != null) {
            try {
                node.set("content", objectMapper.readTree(content));
            } catch (JsonProcessingException e) {
                node.put("content", content);
            }
        }

        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize stream message: type={}", type, e);
            return null;
        }
    }

    private void putWelinkSessionId(ObjectNode node, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            node.putNull("welinkSessionId");
            return;
        }
        // 以字符串传输，防止 JavaScript IEEE 754 大数精度丢失
        node.put("welinkSessionId", sessionId);
    }

    private long nextTransportSeq(String sessionId) {
        return seqCounters.computeIfAbsent(sessionId, key -> new AtomicLong(0)).incrementAndGet();
    }

    private String extractUserIdFromCookie(WebSocketSession session) {
        List<String> cookieHeaders = session.getHandshakeHeaders().get("Cookie");
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            return null;
        }

        return cookieHeaders.stream()
                .map(this::parseUserIdCookie)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String parseUserIdCookie(String cookieHeader) {
        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith("userId=") && trimmed.length() > "userId=".length()) {
                return trimmed.substring("userId=".length());
            }
        }
        return null;
    }

}
