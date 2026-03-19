package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class ImInteractionStateService {

    public static final String TYPE_QUESTION = "question";
    public static final String TYPE_PERMISSION = "permission";
    private static final String KEY_PREFIX = "skill:im:interaction:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public ImInteractionStateService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${skill.im.reply-state-ttl-minutes:30}") int ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public void markQuestion(Long sessionId, String toolCallId) {
        save(sessionId, new PendingInteraction(TYPE_QUESTION, toolCallId));
    }

    public void markPermission(Long sessionId, String permissionId) {
        save(sessionId, new PendingInteraction(TYPE_PERMISSION, permissionId));
    }

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

    public void clear(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        redisTemplate.delete(buildKey(sessionId));
    }

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

    private String buildKey(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }

    public record PendingInteraction(String type, String replyId) {
    }
}
