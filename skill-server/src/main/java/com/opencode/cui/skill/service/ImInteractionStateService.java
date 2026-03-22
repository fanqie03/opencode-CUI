package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * IM 交互状态管理服务。
 * 使用 Redis 记录会话中待处理的交互状态（如提问、权限请求），
 * 用于在收到用户回复时匹配并清除对应的待处理状态。
 *
 * <p>
 * Redis Key 模式：{@code skill:im:interaction:{sessionId}}
 * </p>
 */
@Slf4j
@Service
public class ImInteractionStateService {

    /** 交互类型常量：提问 */
    public static final String TYPE_QUESTION = "question";
    /** 交互类型常量：权限请求 */
    public static final String TYPE_PERMISSION = "permission";
    /** Redis Key 前缀 */
    private static final String KEY_PREFIX = "skill:im:interaction:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    /** 交互状态的 TTL，超时后自动清理 */
    private final Duration ttl;

    public ImInteractionStateService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${skill.im.reply-state-ttl-minutes:30}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** 标记会话有待处理的提问。 */
    public void markQuestion(Long sessionId, String toolCallId) {
        save(sessionId, new PendingInteraction(TYPE_QUESTION, toolCallId));
    }

    /** 标记会话有待处理的权限请求。 */
    public void markPermission(Long sessionId, String permissionId) {
        save(sessionId, new PendingInteraction(TYPE_PERMISSION, permissionId));
    }

    /**
     * 获取会话的待处理交互状态。
     *
     * @return 待处理交互信息，不存在或反序列化失败返回 null
     */
    public PendingInteraction getPending(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        String raw = redisTemplate.opsForValue().get(buildKey(sessionId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, PendingInteraction.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize IM interaction state: sessionId={}, error={}", sessionId, e.getMessage());
            clear(sessionId);
            return null;
        }
    }

    /** 清除会话的待处理交互状态。 */
    public void clear(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        redisTemplate.delete(buildKey(sessionId));
    }

    /**
     * 条件清除：仅当类型和回复 ID 匹配时才清除待处理状态。
     * 防止误清除不匹配的交互状态。
     */
    public void clearIfMatches(Long sessionId, String type, String replyId) {
        PendingInteraction pending = getPending(sessionId);
        if (pending == null) {
            return;
        }
        if (type != null && !type.equals(pending.type())) {
            return;
        }
        if (replyId != null && !replyId.isBlank() && !replyId.equals(pending.replyId())) {
            return;
        }
        clear(sessionId);
    }

    /**
     * 持久化交互状态到 Redis。
     * 设置 TTL 防止遗留数据占用内存。
     */
    private void save(Long sessionId, PendingInteraction pending) {
        if (sessionId == null || pending == null || pending.replyId() == null || pending.replyId().isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    buildKey(sessionId),
                    objectMapper.writeValueAsString(pending),
                    ttl);
        } catch (Exception e) {
            log.warn("Failed to persist IM interaction state: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /** 构建 Redis Key。 */
    private String buildKey(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }

    /**
     * 待处理交互记录。
     *
     * @param type    交互类型（question / permission）
     * @param replyId 关联的回复标识（toolCallId 或 permissionId）
     */
    public record PendingInteraction(String type, String replyId) {
    }
}
