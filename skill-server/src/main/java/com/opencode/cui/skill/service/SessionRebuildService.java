package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class SessionRebuildService {

    private final ObjectMapper objectMapper;
    private final SkillSessionService sessionService;
    private final SkillMessageRepository messageRepository;
    private final Cache<String, String> pendingRebuildMessages = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1_000)
            .build();

    public SessionRebuildService(ObjectMapper objectMapper,
            SkillSessionService sessionService,
            SkillMessageRepository messageRepository) {
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
    }

    public void handleSessionNotFound(String sessionId, String userId, RebuildCallback callback) {
        log.warn("Tool session not found for welinkSession={}, initiating rebuild", sessionId);
        rebuildFromStoredUserMessage(sessionId, callback);
    }

    public void handleContextOverflow(String sessionId, String userId, RebuildCallback callback) {
        log.warn("Context overflow for welinkSession={}, initiating rebuild", sessionId);
        rebuildFromStoredUserMessage(sessionId, callback);
    }

    public void rebuildToolSession(String sessionId, SkillSession session,
            String pendingMessage, RebuildCallback callback) {
        log.info("Rebuilding toolSession for welinkSession={}", sessionId);

        if (pendingMessage != null && !pendingMessage.isBlank()) {
            pendingRebuildMessages.put(sessionId, pendingMessage);
            log.info("Stored pending retry message for session {}: '{}'",
                    sessionId,
                    pendingMessage.substring(0, Math.min(50, pendingMessage.length())));
        }

        callback.broadcast(sessionId, session.getUserId(), StreamMessage.sessionStatus("retry"));

        if (session.getAk() == null || session.getAk().isBlank()) {
            log.error("Cannot rebuild session {}: no ak associated", sessionId);
            pendingRebuildMessages.invalidate(sessionId);
            callback.broadcast(sessionId, session.getUserId(),
                    StreamMessage.error("AI session expired and cannot be rebuilt"));
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", session.getTitle() != null ? session.getTitle() : "");
        String payloadStr;
        try {
            payloadStr = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            payloadStr = "{}";
        }

        callback.sendInvoke(new InvokeCommand(
                session.getAk(),
                session.getUserId(),
                sessionId,
                GatewayActions.CREATE_SESSION,
                payloadStr));
        log.info("Rebuild create_session sent for welinkSession={}, ak={}", sessionId, session.getAk());
    }

    public String consumePendingMessage(String sessionId) {
        String pendingText = pendingRebuildMessages.getIfPresent(sessionId);
        pendingRebuildMessages.invalidate(sessionId);
        return pendingText;
    }

    public void clearPendingMessage(String sessionId) {
        pendingRebuildMessages.invalidate(sessionId);
    }

    private void rebuildFromStoredUserMessage(String sessionId, RebuildCallback callback) {
        Long sessionIdLong = ProtocolUtils.parseSessionId(sessionId);
        if (sessionIdLong == null) {
            log.error("Failed to rebuild session {}: invalid sessionId", sessionId);
            return;
        }

        try {
            SkillSession session = sessionService.getSession(sessionIdLong);
            sessionService.clearToolSessionId(sessionIdLong);

            String pendingMessage = null;
            SkillMessage lastUserMsg = messageRepository.findLastUserMessage(sessionIdLong);
            if (lastUserMsg != null && lastUserMsg.getContent() != null) {
                pendingMessage = lastUserMsg.getContent();
            }

            rebuildToolSession(sessionId, session, pendingMessage, callback);
        } catch (Exception e) {
            log.error("Failed to rebuild session {}: {}", sessionId, e.getMessage(), e);
            pendingRebuildMessages.invalidate(sessionId);
        }
    }

    public interface RebuildCallback {
        void broadcast(String sessionId, String userId, StreamMessage msg);

        void sendInvoke(InvokeCommand command);
    }
}
