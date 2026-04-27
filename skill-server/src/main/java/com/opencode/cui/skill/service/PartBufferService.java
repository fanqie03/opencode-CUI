package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessagePart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓冲服务：暂存流式 part，tool_done 时批量刷入 MySQL。
 *
 * <p>Key schema（均带 1h TTL）：</p>
 * <ul>
 *   <li>{@code ss:part-buf:{messageDbId}} — LIST，缓冲序列化的 part JSON</li>
 *   <li>{@code ss:part-seq:{messageDbId}} — STRING (counter)，原子递增的 seq</li>
 * </ul>
 */
@Slf4j
@Service
public class PartBufferService {

    private static final String BUF_PREFIX = "ss:part-buf:";
    private static final String SEQ_PREFIX = "ss:part-seq:";
    private static final long TTL_HOURS = 1;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PartBufferService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 part 序列化后 RPUSH 到 Redis LIST。
     */
    public void bufferPart(Long messageDbId, SkillMessagePart part) {
        String key = BUF_PREFIX + messageDbId;
        try {
            String json = objectMapper.writeValueAsString(part);
            redis.opsForList().rightPush(key, json);
            redis.expire(key, TTL_HOURS, TimeUnit.HOURS);
            log.debug("Buffered part to Redis: messageDbId={}, partId={}", messageDbId, part.getPartId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize part for Redis buffer: messageDbId={}, partId={}, error={}",
                    messageDbId, part.getPartId(), e.getMessage());
        }
    }

    /**
     * 原子递增 seq 计数器，返回新的 seq 值。
     */
    public int nextSeq(Long messageDbId) {
        String key = SEQ_PREFIX + messageDbId;
        Long seq = redis.opsForValue().increment(key);
        redis.expire(key, TTL_HOURS, TimeUnit.HOURS);
        return seq != null ? seq.intValue() : 1;
    }

    /**
     * 读取所有缓冲 part 并清理 Redis key。
     *
     * <p>WARNING: destructive — buffer is gone before MySQL write succeeds. Use
     * {@link #prepareFlush(Long)} + {@link #commitFlush(FlushBatch)} /
     * {@link #rollbackFlush(FlushBatch)} for finalize paths that must survive
     * MySQL exceptions.</p>
     *
     * @return 反序列化后的 part 列表，如果缓冲为空返回空列表
     */
    public List<SkillMessagePart> flushParts(Long messageDbId) {
        String bufKey = BUF_PREFIX + messageDbId;
        String seqKey = SEQ_PREFIX + messageDbId;

        // Atomically rename to temp key to prevent race with concurrent bufferPart()
        String tempKey = bufKey + ":flush:" + System.nanoTime();
        List<String> jsonList = null;
        try {
            redis.rename(bufKey, tempKey);
            jsonList = redis.opsForList().range(tempKey, 0, -1);
            redis.delete(tempKey);
        } catch (Exception e) {
            // rename fails if key doesn't exist — try direct read as fallback
            jsonList = redis.opsForList().range(bufKey, 0, -1);
            redis.delete(bufKey);
        }
        redis.delete(seqKey);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<SkillMessagePart> parts = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            try {
                parts.add(objectMapper.readValue(json, SkillMessagePart.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize buffered part: {}", e.getMessage());
            }
        }
        log.debug("Flushed {} parts from Redis buffer: messageDbId={}", parts.size(), messageDbId);
        return parts;
    }

    /**
     * Non-destructive snapshot of the current buffer for a finalize attempt.
     *
     * <p>Atomically RENAMEs the buffer LIST onto a temp key so concurrent
     * {@link #bufferPart} can keep writing into a fresh buffer without races,
     * then reads the snapshot. The temp key and the seq counter are NOT touched
     * here — they live until {@link #commitFlush(FlushBatch)} (success path) or
     * {@link #rollbackFlush(FlushBatch)} (failure path) is called.</p>
     *
     * <p>The seq key is intentionally left in place during prepare — only commit
     * removes it. This way a rollback after a failed MySQL write still allows
     * subsequent buffered parts to keep monotonic seqs.</p>
     *
     * <p><b>Fail-closed semantics:</b>
     * <ul>
     *   <li>RENAME failing because the live key doesn't exist is a normal empty
     *       buffer → returns an empty batch with {@code tempKey == null}.</li>
     *   <li>RENAME succeeding but a subsequent operation (RANGE / EXPIRE) failing
     *       throws {@link FlushPrepareException}. The snapshot is preserved on the
     *       temp key; the caller MUST treat this as a finalize failure so the
     *       active ref is restored and the snapshot can be retrieved later.</li>
     * </ul>
     * </p>
     */
    public FlushBatch prepareFlush(Long messageDbId) {
        String bufKey = BUF_PREFIX + messageDbId;
        String tempKey = bufKey + ":flush:" + System.nanoTime();

        try {
            redis.rename(bufKey, tempKey);
        } catch (Exception e) {
            if (isNoSuchKeyError(e)) {
                // Normal "no buffer" case — nothing to flush.
                return new FlushBatch(messageDbId, null, Collections.emptyList());
            }
            // Real Redis failure (network, auth, timeout, …): do NOT silently drop.
            // The buffer key is still on the live side untouched; surface the error so the
            // caller rolls back and a retry can pick up the same buffer next time.
            log.error("prepareFlush: RENAME failed for {} with non-'no such key' error — will fail-closed: {}",
                    bufKey, e.getMessage());
            throw new FlushPrepareException(messageDbId, null, e);
        }

        List<String> jsonList;
        try {
            jsonList = redis.opsForList().range(tempKey, 0, -1);
            redis.expire(tempKey, TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            // Snapshot is on the temp key but unreadable. Do NOT silently drop it —
            // surface the error so the caller rolls back and a retry can recover.
            log.error("prepareFlush: snapshot RANGE failed after RENAME succeeded — temp key {} preserved for retry: {}",
                    tempKey, e.getMessage());
            throw new FlushPrepareException(messageDbId, tempKey, e);
        }

        List<SkillMessagePart> parts;
        if (jsonList == null || jsonList.isEmpty()) {
            parts = Collections.emptyList();
        } else {
            parts = new ArrayList<>(jsonList.size());
            for (String json : jsonList) {
                try {
                    parts.add(objectMapper.readValue(json, SkillMessagePart.class));
                } catch (JsonProcessingException e) {
                    log.error("Failed to deserialize buffered part: {}", e.getMessage());
                }
            }
        }
        return new FlushBatch(messageDbId, tempKey, parts);
    }

    /**
     * Confirm a successful finalize: drop the snapshot and the seq counter.
     */
    public void commitFlush(FlushBatch batch) {
        if (batch == null) {
            return;
        }
        if (batch.tempKey() != null) {
            redis.delete(batch.tempKey());
        }
        redis.delete(SEQ_PREFIX + batch.messageDbId());
        log.debug("Committed flush: messageDbId={}, parts={}", batch.messageDbId(), batch.parts().size());
    }

    /**
     * Restore the snapshot back into the live buffer when finalize failed.
     *
     * <p>Late writes that landed during the prepare→commit window stay at the
     * head of the live buffer; the snapshot is prepended (LPUSH in original
     * order) so historical parts come first on retry. Seq monotonicity is
     * preserved because the seq counter was never touched by prepare.</p>
     *
     * <p><b>Fail-closed semantics:</b> if the LPUSH back to the live buffer fails,
     * the temp key is <i>not</i> deleted — it stays as the only durable copy of the
     * snapshot for offline / human recovery. The exception is logged but swallowed
     * because rollback is itself usually invoked from a failure path (catch block
     * or {@code afterCompletion}); rethrowing would mask the original cause.</p>
     */
    public void rollbackFlush(FlushBatch batch) {
        if (batch == null || batch.tempKey() == null) {
            return;
        }
        if (batch.parts().isEmpty()) {
            // Empty snapshots are safe to drop — no data to restore.
            redis.delete(batch.tempKey());
            return;
        }
        String bufKey = BUF_PREFIX + batch.messageDbId();
        boolean restored = false;
        try {
            List<String> snapshotJsons = redis.opsForList().range(batch.tempKey(), 0, -1);
            if (snapshotJsons != null && !snapshotJsons.isEmpty()) {
                // LPUSH preserves original order when args are reversed
                String[] reversed = new String[snapshotJsons.size()];
                for (int i = 0; i < snapshotJsons.size(); i++) {
                    reversed[i] = snapshotJsons.get(snapshotJsons.size() - 1 - i);
                }
                redis.opsForList().leftPushAll(bufKey, reversed);
                redis.expire(bufKey, TTL_HOURS, TimeUnit.HOURS);
                restored = true;
            } else {
                // Nothing to restore (snapshot already gone) — temp key cleanup is fine.
                restored = true;
            }
        } catch (Exception e) {
            log.error("rollbackFlush: failed to LPUSH snapshot back to live buffer — temp key {} preserved for manual recovery: {}",
                    batch.tempKey(), e.getMessage(), e);
        }
        if (restored) {
            redis.delete(batch.tempKey());
        }
        log.warn("Rolled back flush: messageDbId={}, restoredParts={}, restored={}",
                batch.messageDbId(), batch.parts().size(), restored);
    }

    /**
     * Distinguish a benign "RENAME on missing key" from a real Redis failure.
     * Spring/Lettuce wraps the Redis error message in the exception chain.
     */
    private static boolean isNoSuchKeyError(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase().contains("no such key")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Snapshot returned by {@link #prepareFlush(Long)}. Holds the deserialized
     * parts plus the Redis temp key so {@link #commitFlush(FlushBatch)} or
     * {@link #rollbackFlush(FlushBatch)} can finalize cleanup.
     *
     * <p>{@code tempKey == null} ⇒ the buffer was empty / didn't exist; commit
     * and rollback are still safe (they early-return).</p>
     */
    public record FlushBatch(Long messageDbId, String tempKey, List<SkillMessagePart> parts) {
    }

    /**
     * Thrown by {@link #prepareFlush(Long)} when the RENAME succeeded but the
     * snapshot could not be read. The temp key is preserved on Redis so the
     * caller (or a manual recovery job) can retry.
     */
    public static class FlushPrepareException extends RuntimeException {
        private final Long messageDbId;
        private final String tempKey;

        public FlushPrepareException(Long messageDbId, String tempKey, Throwable cause) {
            super("prepareFlush snapshot read failed for messageDbId=" + messageDbId
                    + ", tempKey=" + tempKey, cause);
            this.messageDbId = messageDbId;
            this.tempKey = tempKey;
        }

        public Long getMessageDbId() { return messageDbId; }
        public String getTempKey() { return tempKey; }
    }

    /**
     * 在 Redis 缓冲中查找并更新 permission part 的 reply。
     * 扫描 buffer，找到 toolCallId 匹配的 permission part，更新 toolOutput 和 toolStatus，
     * 然后用 LSET 原地替换。
     *
     * @return true if a matching permission part was found and updated
     */
    public boolean updatePermissionReply(Long messageDbId, String permissionId, String status, String response) {
        String bufKey = BUF_PREFIX + messageDbId;
        List<String> jsonList = redis.opsForList().range(bufKey, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return false;
        }

        for (int i = 0; i < jsonList.size(); i++) {
            try {
                SkillMessagePart part = objectMapper.readValue(jsonList.get(i), SkillMessagePart.class);
                if ("permission".equals(part.getPartType())
                        && permissionId.equals(part.getToolCallId())) {
                    part.setToolStatus(status);
                    part.setToolOutput(response);
                    String updatedJson = objectMapper.writeValueAsString(part);
                    redis.opsForList().set(bufKey, i, updatedJson);
                    log.info("Updated permission reply in Redis buffer: messageDbId={}, permissionId={}, response={}",
                            messageDbId, permissionId, response);
                    return true;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to process buffered part during permission update: {}", e.getMessage());
            }
        }
        return false;
    }

    /**
     * 从 Redis 缓冲中反向查找最新的 pending permission part。
     * pending 定义：partType=permission 且 toolOutput 为 null 或空。
     *
     * @return 找到的 pending permission part，未找到返回 null
     */
    public SkillMessagePart findLatestPendingPermission(Long messageDbId) {
        String bufKey = BUF_PREFIX + messageDbId;
        List<String> jsonList = redis.opsForList().range(bufKey, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return null;
        }

        // 反向遍历，找最新的 pending permission
        for (int i = jsonList.size() - 1; i >= 0; i--) {
            try {
                SkillMessagePart part = objectMapper.readValue(jsonList.get(i), SkillMessagePart.class);
                if ("permission".equals(part.getPartType())
                        && (part.getToolOutput() == null || part.getToolOutput().isEmpty())) {
                    return part;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize buffered part during permission scan: {}", e.getMessage());
            }
        }
        return null;
    }
}
