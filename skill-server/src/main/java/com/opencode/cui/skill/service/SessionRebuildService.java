package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 会话重建服务。
 * 当工具会话（toolSession）过期、上下文溢出或找不到时，
 * 自动触发重建流程：通知前端重试状态 → 清除旧会话 → 向 Gateway 发送 create_session 命令。
 *
 * <p>
 * 使用 Redis 暂存待重建的用户消息（支持多实例），重建完成后自动重试发送。
 * </p>
 */
@Slf4j
@Service
public class SessionRebuildService {

    /** 待重建消息 Redis key 前缀：ss:pending-rebuild:{sessionId} → 用户消息文本 */
    private static final String PENDING_MSG_PREFIX = "ss:pending-rebuild:";
    /** 重建计数器 Redis key 前缀：ss:rebuild-counter:{sessionId} → 已重建次数 */
    private static final String REBUILD_COUNTER_PREFIX = "ss:rebuild-counter:";
    /** 待重建消息 TTL */
    private static final Duration PENDING_MSG_TTL = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;
    private final SkillSessionService sessionService;
    private final SkillMessageRepository messageRepository;
    private final StringRedisTemplate redisTemplate;
    private final int maxRebuildAttempts;
    private final int rebuildCooldownSeconds;

    public SessionRebuildService(ObjectMapper objectMapper,
            SkillSessionService sessionService,
            SkillMessageRepository messageRepository,
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Value("${skill.session.rebuild-max-attempts:3}") int maxRebuildAttempts,
            @org.springframework.beans.factory.annotation.Value("${skill.session.rebuild-cooldown-seconds:30}") int rebuildCooldownSeconds) {
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.redisTemplate = redisTemplate;
        this.maxRebuildAttempts = maxRebuildAttempts;
        this.rebuildCooldownSeconds = rebuildCooldownSeconds;
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
     * <li>检查重建计数器是否超限</li>
     * <li>缓存待重试的用户消息到 Redis</li>
     * <li>广播 retry 状态到前端</li>
     * <li>向 Gateway 发送 create_session 命令</li>
     * </ol>
     */
    public void rebuildToolSession(String sessionId, SkillSession session,
            String pendingMessage, RebuildCallback callback) {
        // --- 重建计数器检查（Redis 全局共享，多实例一致） ---
        int attempts = incrementRebuildCounter(sessionId);

        if (attempts > maxRebuildAttempts) {
            log.warn("Rebuild exhausted: session={}, attempts={}, cooldownSeconds={}",
                    sessionId, attempts, rebuildCooldownSeconds);
            clearPendingMessage(sessionId);
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                sessionService.clearToolSessionId(numericId);
            }
            callback.broadcast(sessionId, session.getUserId(),
                    StreamMessage.error("会话连接异常（重建已达上限），请等待 "
                            + rebuildCooldownSeconds + " 秒后重试"));
            return;
        }

        log.info("Rebuild attempt {}/{} for session={}", attempts, maxRebuildAttempts, sessionId);

        // --- 缓存待重试消息到 Redis ---
        if (pendingMessage != null && !pendingMessage.isBlank()) {
            storePendingMessage(sessionId, pendingMessage);
            log.info("Stored pending retry message for session {}: '{}'",
                    sessionId,
                    pendingMessage.substring(0, Math.min(50, pendingMessage.length())));
        }

        callback.broadcast(sessionId, session.getUserId(), StreamMessage.sessionStatus("retry"));

        if (session.getAk() == null || session.getAk().isBlank()) {
            log.error("Cannot rebuild session {}: no ak associated", sessionId);
            clearPendingMessage(sessionId);
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

    /** 消费并返回待重建的用户消息，消费后从 Redis 中移除（原子操作）。 */
    public String consumePendingMessage(String sessionId) {
        String key = PENDING_MSG_PREFIX + sessionId;
        try {
            String pendingText = redisTemplate.opsForValue().getAndDelete(key);
            return pendingText;
        } catch (Exception e) {
            log.error("[ERROR] consumePendingMessage: Redis 操作失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /** 清除会话的待重建消息缓存。 */
    public void clearPendingMessage(String sessionId) {
        String key = PENDING_MSG_PREFIX + sessionId;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[WARN] clearPendingMessage: Redis 操作失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
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
            clearPendingMessage(sessionId);
        }
    }

    // ==================== Redis helpers ====================

    /**
     * 原子递增重建计数器并刷新 TTL（等效原 Caffeine expireAfterAccess 语义）。
     * Redis 失败时降级放行（返回 1），避免因 Redis 故障阻塞重建。
     */
    private int incrementRebuildCounter(String sessionId) {
        String key = REBUILD_COUNTER_PREFIX + sessionId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, Duration.ofSeconds(rebuildCooldownSeconds));
            return count != null ? count.intValue() : 1;
        } catch (Exception e) {
            log.warn("[WARN] incrementRebuildCounter: Redis 操作失败, 降级放行, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return 1;
        }
    }

    /** 存储待重建消息到 Redis，带 TTL。 */
    private void storePendingMessage(String sessionId, String message) {
        String key = PENDING_MSG_PREFIX + sessionId;
        try {
            redisTemplate.opsForValue().set(key, message, PENDING_MSG_TTL);
        } catch (Exception e) {
            log.error("[ERROR] storePendingMessage: Redis 操作失败, sessionId={}, error={}",
                    sessionId, e.getMessage());
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
