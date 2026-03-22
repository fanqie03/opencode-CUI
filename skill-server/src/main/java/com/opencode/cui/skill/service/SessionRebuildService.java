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

/**
 * 会话重建服务。
 * 当工具会话（toolSession）过期、上下文溢出或找不到时，
 * 自动触发重建流程：通知前端重试状态 → 清除旧会话 → 向 Gateway 发送 create_session 命令。
 *
 * <p>
 * 使用 Caffeine 缓存暂存待重建的用户消息，重建完成后自动重试发送。
 * </p>
 */
@Slf4j
@Service
public class SessionRebuildService {

    private final ObjectMapper objectMapper;
    private final SkillSessionService sessionService;
    private final SkillMessageRepository messageRepository;
    /** 待重建消息缓存：sessionId → 用户消息文本，5 分钟过期 */
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

    /** 处理工具会话不存在的情况，触发从存储消息重建。 */
    public void handleSessionNotFound(String sessionId, String userId, RebuildCallback callback) {
        log.warn("Tool session not found for welinkSession={}, initiating rebuild", sessionId);
        rebuildFromStoredUserMessage(sessionId, callback);
    }

    /** 处理上下文溢出的情况，清除旧会话并触发重建。 */
    public void handleContextOverflow(String sessionId, String userId, RebuildCallback callback) {
        log.warn("Context overflow for welinkSession={}, initiating rebuild", sessionId);
        rebuildFromStoredUserMessage(sessionId, callback);
    }

    /**
     * 执行工具会话重建核心流程。
     * <ol>
     * <li>缓存待重试的用户消息</li>
     * <li>广播 retry 状态到前端</li>
     * <li>向 Gateway 发送 create_session 命令</li>
     * </ol>
     */
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

    /** 消费并返回待重建的用户消息，消费后从缓存中移除。 */
    public String consumePendingMessage(String sessionId) {
        String pendingText = pendingRebuildMessages.getIfPresent(sessionId);
        pendingRebuildMessages.invalidate(sessionId);
        return pendingText;
    }

    /** 清除会话的待重建消息缓存。 */
    public void clearPendingMessage(String sessionId) {
        pendingRebuildMessages.invalidate(sessionId);
    }

    /** 从数据库中获取最近的用户消息并触发重建。 */
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

    /**
     * 重建回调接口。
     * 由调用方实现，用于消息广播和命令发送。
     */
    public interface RebuildCallback {
        /** 向前端广播流式消息。 */
        void broadcast(String sessionId, String userId, StreamMessage msg);

        /** 向 Gateway 发送 invoke 命令。 */
        void sendInvoke(InvokeCommand command);
    }
}
