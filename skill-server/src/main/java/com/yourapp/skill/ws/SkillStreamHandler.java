package com.yourapp.skill.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket handler for Skill miniapp streaming.
 * Endpoint: /ws/skill/stream/{sessionId}
 *
 * Pushes streaming messages to connected Skill miniapp clients:
 * - delta: incremental content update
 * - done: tool execution completed with usage stats
 * - error: error message
 * - agent_offline: associated PCAgent went offline
 * - agent_online: associated PCAgent came back online
 */
@Slf4j
@Component
public class SkillStreamHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;

    /**
     * Map of sessionId -> set of subscribed WebSocket sessions.
     * Multiple clients can subscribe to the same skill session.
     */
    private final ConcurrentHashMap<String, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    /**
     * Per-session sequence counter for ordering stream messages.
     */
    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    public SkillStreamHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId == null) {
            log.warn("Skill stream connection rejected: missing sessionId, remoteAddr={}",
                    session.getRemoteAddress());
            session.close(CloseStatus.BAD_DATA.withReason("Missing sessionId in path"));
            return;
        }

        subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("Skill stream subscriber connected: sessionId={}, wsId={}, remoteAddr={}",
                sessionId, session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Client-to-server messages on the stream endpoint are currently not expected.
        // Could be extended for client heartbeats or control messages.
        log.debug("Received client message on stream endpoint: wsId={}, payload={}",
                session.getId(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId != null) {
            Set<WebSocketSession> sessions = subscribers.get(sessionId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    subscribers.remove(sessionId);
                    seqCounters.remove(sessionId);
                }
            }
        }
        log.info("Skill stream subscriber disconnected: sessionId={}, wsId={}, status={}",
                sessionId, session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Skill stream transport error: wsId={}, error={}",
                session.getId(), exception.getMessage(), exception);
        String sessionId = extractSessionId(session);
        if (sessionId != null) {
            Set<WebSocketSession> sessions = subscribers.get(sessionId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    subscribers.remove(sessionId);
                    seqCounters.remove(sessionId);
                }
            }
        }
    }

    /**
     * Push a message to all subscribers of a given skill session.
     *
     * @param sessionId the skill session ID
     * @param type      message type: delta, done, error, agent_offline, agent_online
     * @param content   message content (may be null for status-only messages)
     */
    public void pushToSession(String sessionId, String type, String content) {
        Set<WebSocketSession> sessions = subscribers.get(sessionId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("No subscribers for session {}, message type={} dropped", sessionId, type);
            return;
        }

        long seq = seqCounters.computeIfAbsent(sessionId, k -> new AtomicLong(0)).incrementAndGet();
        String message = buildMessage(type, content, seq);
        if (message == null) {
            return;
        }

        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession ws : sessions) {
            if (ws.isOpen()) {
                try {
                    ws.sendMessage(textMessage);
                } catch (IOException e) {
                    log.error("Failed to push message to subscriber: sessionId={}, wsId={}, error={}",
                            sessionId, ws.getId(), e.getMessage());
                    sessions.remove(ws);
                }
            } else {
                sessions.remove(ws);
            }
        }
    }

    /**
     * Get the number of active subscribers for a session.
     */
    public int getSubscriberCount(String sessionId) {
        Set<WebSocketSession> sessions = subscribers.get(sessionId);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Build a JSON message string for pushing to clients.
     */
    private String buildMessage(String type, String content, long seq) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put("seq", seq);

        if (content != null) {
            // Try to parse content as JSON; if it fails, treat as plain string
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

    /**
     * Extract the sessionId from the WebSocket URI path.
     * Expected path: /ws/skill/stream/{sessionId}
     */
    private String extractSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }

        String path = uri.getPath();
        // Path format: /ws/skill/stream/{sessionId}
        String prefix = "/ws/skill/stream/";
        if (path != null && path.startsWith(prefix) && path.length() > prefix.length()) {
            return path.substring(prefix.length());
        }
        return null;
    }
}
