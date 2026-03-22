package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.SnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Skill MiniApp 流式推送 WebSocket 处理器。
 *
 * <p>
 * 协议端点：/ws/skill/stream，每个用户一条流，通过 Cookie userId 认证。
 * </p>
 */
@Slf4j
@Component
public class SkillStreamHandler extends TextWebSocketHandler {

    private static final String ATTR_USER_ID = "userId";

    private final ObjectMapper objectMapper;
    private final SkillSessionService sessionService;
    private final SnapshotService snapshotService;
    private final RedisMessageBroker redisMessageBroker;

    /**
     * 按 userId 分组的协议订阅者。
     * 每个 Socket 接收该用户所有活跃会话的事件。
     */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> userSubscribers = new ConcurrentHashMap<>();

    /**
     * welinkSessionId → userId 的解析缓存。
     * 使用 Caffeine 防止无限增长，1 小时无访问自动过期。
     */
    private final Cache<String, String> sessionOwners = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1)).maximumSize(10_000).build();

    /**
     * 每会话的传输层序列号计数器。
     * 使用 Caffeine 防止已关闭 session 的计数器永久驻留。
     */
    private final Cache<String, AtomicLong> seqCounters = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1)).maximumSize(10_000).build();
    private final ConcurrentHashMap<String, AtomicInteger> activeConnectionCounts = new ConcurrentHashMap<>();

    public SkillStreamHandler(ObjectMapper objectMapper,
            SkillSessionService sessionService,
            SnapshotService snapshotService,
            RedisMessageBroker redisMessageBroker) {
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.snapshotService = snapshotService;
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
     * 向指定会话的订阅者推送 StreamMessage。
     * 自动分配传输层序列号。
     */
    public void pushStreamMessage(String sessionId, StreamMessage msg) {
        Set<WebSocketSession> recipients = resolveRecipients(sessionId);
        if (recipients.isEmpty()) {
            log.debug("No subscribers for session {}, message type={} dropped", sessionId, msg.getType());
            return;
        }

        msg.setSeq(nextTransportSeq(sessionId));
        serializeAndBroadcast(msg, recipients, sessionId);
    }

    /** 获取指定会话的当前订阅者数量。 */
    public int getSubscriberCount(String sessionId) {
        return resolveRecipients(sessionId).size();
    }

    /** 注册用户订阅、预加载会话归属并发送初始状态。 */
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

    /** 注销订阅者（幂等操作，传输错误和关闭回调都可能触发）。 */
    private void unregisterSubscriber(WebSocketSession session) {
        // 传输错误和关闭回调可能重复触发，提前移除标记以保证幂等性
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

    /** 订阅用户级别的 Redis 消息流。 */
    private void subscribeToUserStream(String userId) {
        if (redisMessageBroker.isUserSubscribed(userId)) {
            return;
        }
        redisMessageBroker.subscribeToUser(userId, message -> handleUserBroadcast(userId, message));
        log.info("Subscribed to user stream: userId={}", userId);
    }

    /** 取消用户级别的 Redis 消息流订阅。 */
    private void unsubscribeFromUserStream(String userId) {
        redisMessageBroker.unsubscribeFromUser(userId);
        log.info("Unsubscribed from user stream: userId={}", userId);
    }

    /** 处理通过 Redis 接收到的用户级别广播消息。 */
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
                StreamMessage adHocMsg = StreamMessage.builder()
                        .type(type)
                        .sessionId(sessionId)
                        .content(content)
                        .build();
                pushStreamMessage(sessionId, adHocMsg);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse user broadcast for user {}: {}", userId, e.getMessage());
        }
    }

    /** 向指定用户的所有 WebSocket 订阅者推送 StreamMessage。 */
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

        serializeAndBroadcast(msg, recipients, "user:" + userId);
    }

    /** 发送初始流式状态（快照 + 当前状态）给新连接的订阅者。 */
    private void sendInitialStreamingState(WebSocketSession session, String userId) {
        List<SkillSession> activeSessions = snapshotService.getActiveSessionsForUser(userId);
        for (SkillSession skillSession : activeSessions) {
            String sessionId = String.valueOf(skillSession.getId());
            sessionOwners.put(sessionId, skillSession.getUserId());
            sendSnapshot(session, sessionId);
            sendStreamingState(session, sessionId);
        }
    }

    /** 发送会话快照。 */
    private void sendSnapshot(WebSocketSession session, String sessionId) {
        sendToSession(session, sessionId,
                () -> snapshotService.buildSnapshot(sessionId, nextTransportSeq(sessionId)),
                msg -> "Snapshot sent: sessionId=" + sessionId + ", messages="
                        + (msg.getMessages() != null ? msg.getMessages().size() : 0));
    }

    /** 发送会话当前流式状态。 */
    private void sendStreamingState(WebSocketSession session, String sessionId) {
        sendToSession(session, sessionId,
                () -> snapshotService.buildStreamingState(sessionId, nextTransportSeq(sessionId)),
                msg -> "Streaming state sent: sessionId=" + sessionId + ", status=" + msg.getSessionStatus());
    }

    /** 根据 sessionId 解析目标接收者（查找会话所属用户的 WebSocket 连接）。 */
    private Set<WebSocketSession> resolveRecipients(String sessionId) {
        String ownerUserId = sessionOwners.get(sessionId, this::resolveOwnerUserId);
        if (ownerUserId != null) {
            Set<WebSocketSession> userLevel = userSubscribers.get(ownerUserId);
            if (userLevel != null) {
                return userLevel;
            }
        }
        return Set.of();
    }

    /** 从数据库反查会话所属用户。 */
    private String resolveOwnerUserId(String sessionId) {
        try {
            SkillSession session = sessionService.getSession(Long.valueOf(sessionId));
            return session.getUserId();
        } catch (Exception e) {
            log.debug("Failed to resolve owner for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 预加载用户所有活跃会话的归属关系到缓存。 */
    private void preloadActiveSessionOwners(String userId) {
        for (SkillSession session : sessionService.findActiveByUserId(userId)) {
            if (session.getId() != null && session.getUserId() != null) {
                sessionOwners.put(String.valueOf(session.getId()), session.getUserId());
            }
        }
    }

    // ==================== 公共辅助方法 ====================

    /**
     * 序列化 StreamMessage 并广播到一组 WebSocket 会话。
     * 统一了 pushStreamMessage 和 pushStreamMessageToUser 中的重复逻辑。
     */
    private void serializeAndBroadcast(StreamMessage msg, Set<WebSocketSession> recipients, String context) {
        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize StreamMessage: type={}, context={}", msg.getType(), context, e);
            return;
        }
        broadcastText(new TextMessage(messageText), recipients, context);
    }

    /**
     * 在 synchronized 会话上安全发送单条 StreamMessage。
     * 统一了 sendSnapshot 和 sendStreamingState 中的重复逻辑。
     */
    private void sendToSession(WebSocketSession session, String sessionId,
            Supplier<StreamMessage> messageSupplier, Function<StreamMessage, String> logFormatter) {
        synchronized (session) {
            if (!session.isOpen()) {
                return;
            }
            try {
                StreamMessage msg = messageSupplier.get();
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
                log.info(logFormatter.apply(msg));
            } catch (Exception e) {
                log.error("Failed to send message for session {}: {}", sessionId, e.getMessage(), e);
            }
        }
    }

    /**
     * 广播预序列化的 TextMessage 到一组 WebSocket 会话。
     * 处理同步发送，自动注销已关闭/失败的会话。
     */
    private void broadcastText(TextMessage textMessage, Set<WebSocketSession> recipients, String context) {
        for (WebSocketSession ws : recipients) {
            synchronized (ws) {
                if (ws.isOpen()) {
                    try {
                        ws.sendMessage(textMessage);
                    } catch (IOException e) {
                        log.error("Failed to push message: context={}, wsId={}, error={}",
                                context, ws.getId(), e.getMessage());
                        unregisterSubscriber(ws);
                    }
                } else {
                    unregisterSubscriber(ws);
                }
            }
        }
    }

    /** 获取下一个传输层序列号。 */
    private long nextTransportSeq(String sessionId) {
        return seqCounters.get(sessionId, key -> new AtomicLong(0)).incrementAndGet();
    }

    /** 从 Cookie 中提取 userId。 */
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

    /** 解析 Cookie header 中的 userId 值。 */
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
