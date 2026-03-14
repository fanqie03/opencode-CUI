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
 * 负责 toolSession 重建逻辑与待重发消息管理。
 * 从 GatewayRelayService 中拆出，保持单一职责。
 */
@Slf4j
@Service
public class SessionRebuildService {

    private final ObjectMapper objectMapper;
    private final SkillSessionService sessionService;
    private final SkillMessageRepository messageRepository;

    /**
     * 存储 rebuild 期间待重发的用户消息。
     * 5 分钟过期防止 rebuild 失败导致内存泄漏。
     */
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

    /**
     * 处理 session_not_found 错误：清除无效 toolSessionId，存储待重发消息，
     * 然后通过回调触发 create_session 重建。
     */
    public void handleSessionNotFound(String sessionId, String userId, RebuildCallback callback) {
        log.warn("Tool session not found for welinkSession={}, initiating rebuild", sessionId);

        Long sessionIdLong = ProtocolUtils.parseSessionId(sessionId);
        if (sessionIdLong == null) {
            log.error("Failed to rebuild session {}: invalid sessionId", sessionId);
            return;
        }

        try {
            SkillSession session = sessionService.getSession(sessionIdLong);

            // 清除无效的 toolSessionId
            sessionService.clearToolSessionId(sessionIdLong);

            // 取最后一条用户消息作为待重发内容
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
     * 触发 toolSession 重建：存储待重发消息，通知前端 retry，发送 create_session。
     * 同时被 Controller 在 toolSessionId 为 null 时直接调用。
     */
    public void rebuildToolSession(String sessionId, SkillSession session,
            String pendingMessage, RebuildCallback callback) {
        log.info("Rebuilding toolSession for welinkSession={}", sessionId);

        // 存储待重发消息
        if (pendingMessage != null && !pendingMessage.isBlank()) {
            pendingRebuildMessages.put(sessionId, pendingMessage);
            log.info("Stored pending retry message for session {}: '{}'",
                    sessionId,
                    pendingMessage.substring(0, Math.min(50, pendingMessage.length())));
        }

        // 通知前端 retry 状态
        StreamMessage reconnecting = StreamMessage.sessionStatus("retry");
        callback.broadcast(sessionId, session.getUserId(), reconnecting);

        // 发送 create_session 到 Gateway 重建 toolSession
        if (session.getAk() != null) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("title", session.getTitle() != null ? session.getTitle() : "");
            String payloadStr;
            try {
                payloadStr = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                payloadStr = "{}";
            }
            callback.sendInvoke(new InvokeCommand(session.getAk(), session.getUserId(), sessionId,
                    GatewayActions.CREATE_SESSION, payloadStr));
            log.info("Rebuild create_session sent for welinkSession={}, ak={}", sessionId, session.getAk());
        } else {
            log.error("Cannot rebuild session {}: no ak associated", sessionId);
            pendingRebuildMessages.invalidate(sessionId);
            StreamMessage errorMsg = StreamMessage.error("AI session expired and cannot be rebuilt");
            callback.broadcast(sessionId, session.getUserId(), errorMsg);
        }
    }

    /**
     * session_created 后处理：检查是否有待重发消息，有则自动重试。
     *
     * @return 待重发消息文本，如果没有则返回 null
     */
    public String consumePendingMessage(String sessionId) {
        String pendingText = pendingRebuildMessages.getIfPresent(sessionId);
        pendingRebuildMessages.invalidate(sessionId);
        return pendingText;
    }

    /**
     * 清除指定 session 的待重发消息（rebuild 失败时调用）。
     */
    public void clearPendingMessage(String sessionId) {
        pendingRebuildMessages.invalidate(sessionId);
    }

    /**
     * 回调接口，避免 SessionRebuildService 反向依赖 GatewayRelayService。
     */
    public interface RebuildCallback {
        void broadcast(String sessionId, String userId, StreamMessage msg);

        void sendInvoke(InvokeCommand command);
    }
}
