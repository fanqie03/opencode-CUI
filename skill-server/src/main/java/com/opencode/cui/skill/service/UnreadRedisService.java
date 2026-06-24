package com.opencode.cui.skill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.opencode.cui.skill.model.UnreadSessionItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis operations for the unread message badge feature.
 * All unread state lives in the Redis Hash {@code ss:unread:{userId}:{assistantAccount}}.
 * Two Lua scripts provide atomic update / compare-and-delete semantics.
 */
@Slf4j
@Component
public class UnreadRedisService {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> updateMaxSeqScript;
    private final DefaultRedisScript<Long> markReadScript;

    public UnreadRedisService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.updateMaxSeqScript = loadScript("lua/unread_update_max_seq.lua");
        this.markReadScript = loadScript("lua/unread_mark_read.lua");
    }

    private static DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Atomically update the maxSeq for a session.
     *
     * @return 1 if the field was created or updated (sync needed), 0 if unchanged.
     */
    public Long updateMaxSeq(String userId, String assistantAccount, String sessionId, int newSeq, int ttlSeconds) {
        String key = buildKey(userId, assistantAccount);
        try {
            return redisTemplate.execute(updateMaxSeqScript,
                    List.of(key),
                    sessionId,
                    String.valueOf(newSeq),
                    String.valueOf(ttlSeconds));
        } catch (Exception e) {
            log.error("UnreadRedisService.updateMaxSeq failed: key={}, sessionId={}, newSeq={}, error={}",
                    key, sessionId, newSeq, e.getMessage());
            return null;
        }
    }

    /**
     * Atomically compare readSeq against stored maxSeq.
     * If {@code readSeq == maxSeq}, the field is deleted (session is fully read).
     * If {@code readSeq > maxSeq}, the read position is invalid.
     *
     * @return 1 if field absent/deleted (sync needed), 0 if still partially unread, -1 if invalid.
     */
    public Long markRead(String userId, String assistantAccount, String sessionId, int readSeq) {
        String key = buildKey(userId, assistantAccount);
        try {
            return redisTemplate.execute(markReadScript,
                    List.of(key),
                    sessionId,
                    String.valueOf(readSeq));
        } catch (Exception e) {
            log.error("UnreadRedisService.markRead failed: key={}, sessionId={}, readSeq={}, error={}",
                    key, sessionId, readSeq, e.getMessage());
            return null;
        }
    }

    /**
     * Remove a single field from the unread Hash (e.g. after hard session delete).
     */
    public Long removeField(String userId, String assistantAccount, String sessionId) {
        String key = buildKey(userId, assistantAccount);
        try {
            return redisTemplate.opsForHash().delete(key, sessionId);
        } catch (Exception e) {
            log.error("UnreadRedisService.removeField failed: key={}, sessionId={}, error={}",
                    key, sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * HGET a single session field.
     */
    public String getMaxSeq(String userId, String assistantAccount, String sessionId) {
        String key = buildKey(userId, assistantAccount);
        try {
            Object value = redisTemplate.opsForHash().get(key, sessionId);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.error("UnreadRedisService.getMaxSeq failed: key={}, sessionId={}, error={}",
                    key, sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Get unread state for one or more sessions.
     * If {@code sessionIds} is null or empty, returns all fields via HGETALL.
     * Otherwise, returns requested fields via HMGET (null values are omitted).
     */
    public List<UnreadSessionItem> getUnread(String userId, String assistantAccount, List<String> sessionIds) {
        String key = buildKey(userId, assistantAccount);
        try {
            if (sessionIds == null || sessionIds.isEmpty()) {
                Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
                List<UnreadSessionItem> items = new ArrayList<>();
                for (Map.Entry<Object, Object> e : entries.entrySet()) {
                    items.add(new UnreadSessionItem(
                            e.getKey().toString(), Integer.parseInt(e.getValue().toString())));
                }
                return items;
            }
            List<Object> values = redisTemplate.opsForHash().multiGet(key, List.copyOf(sessionIds));
            List<UnreadSessionItem> items = new ArrayList<>();
            for (int i = 0; i < sessionIds.size(); i++) {
                Object val = values.get(i);
                if (val != null) {
                    items.add(new UnreadSessionItem(sessionIds.get(i), Integer.parseInt(val.toString())));
                }
            }
            return items;
        } catch (Exception e) {
            log.error("UnreadRedisService.getUnread failed: key={}, sessionIds={}, error={}",
                    key, sessionIds, e.getMessage());
            return List.of();
        }
    }

    private String buildKey(String userId, String assistantAccount) {
        return "ss:unread:" + userId + ":" + (assistantAccount != null ? assistantAccount : "");
    }
}
