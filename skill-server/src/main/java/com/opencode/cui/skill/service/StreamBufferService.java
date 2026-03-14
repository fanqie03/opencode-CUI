package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based stream buffer for session resilience.
 *
 * Accumulates streaming Part content in Redis so that when a client
 * reconnects (e.g. after closing/switching tabs), the server can
 * provide a snapshot of in-progress content.
 *
 * Key schema (all with 1h TTL):
 * stream:{sessionId}:status → JSON { status, messageId }
 * stream:{sessionId}:part:{partId} → JSON { partType, content, ... }
 * stream:{sessionId}:parts_order → Redis List of partId strings
 */
@Slf4j
@Service
public class StreamBufferService {

    private static final long TTL_HOURS = 1;
    private static final String PREFIX = "stream:";
    private static final String SUFFIX_STATUS = ":status";
    private static final String SUFFIX_PART = ":part:";
    private static final String SUFFIX_ORDER = ":parts_order";
    private static final String SUFFIX_REGISTERED = ":registered";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public StreamBufferService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ==================== Accumulate ====================

    /**
     * Accumulate a StreamMessage into Redis buffer based on its type.
     */
    public void accumulate(String sessionId, StreamMessage msg) {
        try {
            switch (msg.getType()) {
                case StreamMessage.Types.TEXT_DELTA -> accumulateDelta(sessionId, msg, "text");
                case StreamMessage.Types.TEXT_DONE -> clearPartIfPresent(sessionId, msg);
                case StreamMessage.Types.THINKING_DELTA -> accumulateDelta(sessionId, msg, "thinking");
                case StreamMessage.Types.THINKING_DONE -> clearPartIfPresent(sessionId, msg);
                case StreamMessage.Types.TOOL_UPDATE, StreamMessage.Types.QUESTION,
                        StreamMessage.Types.FILE ->
                    setPartFull(sessionId, msg.getPartId(), msg);
                case StreamMessage.Types.PERMISSION_ASK,
                        StreamMessage.Types.PERMISSION_REPLY ->
                    accumulatePermission(sessionId, msg);
                case StreamMessage.Types.SESSION_STATUS -> handleSessionStatus(sessionId, msg);
                default -> {
                    /* step.start, agent.online/offline, error — no buffering */ }
            }
        } catch (Exception e) {
            log.error("Failed to accumulate StreamMessage to Redis for session {}: {}",
                    sessionId, e.getMessage(), e);
        }
    }

    private void accumulateDelta(String sessionId, StreamMessage msg, String partType) {
        appendContent(sessionId, msg, partType);
        setSessionStreaming(sessionId, true);
    }

    private void clearPartIfPresent(String sessionId, StreamMessage msg) {
        if (msg.getPartId() != null) {
            clearPart(sessionId, msg.getPartId());
        }
    }

    private void accumulatePermission(String sessionId, StreamMessage msg) {
        String permId = msg.getPermission() != null ? msg.getPermission().getPermissionId() : null;
        String permPartId = msg.getPartId() != null ? msg.getPartId() : permId;
        setPartFull(sessionId, permPartId, msg);
    }

    private void handleSessionStatus(String sessionId, StreamMessage msg) {
        if ("idle".equals(msg.getSessionStatus()) || "completed".equals(msg.getSessionStatus())) {
            clearSession(sessionId);
        }
    }

    // ==================== Query (for resume) ====================

    /**
     * Check if a session is currently streaming.
     */
    public boolean isSessionStreaming(String sessionId) {
        String key = statusKey(sessionId);
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * Get all in-progress streaming parts for a session.
     * Returns list of StreamMessage-like objects for the frontend.
     */
    public List<StreamMessage> getStreamingParts(String sessionId) {
        String orderKey = partsOrderKey(sessionId);
        List<String> partIds = redis.opsForList().range(orderKey, 0, -1);

        if (partIds == null || partIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<StreamMessage> parts = new ArrayList<>();
        for (String partId : partIds) {
            String partKey = partKey(sessionId, partId);
            String json = redis.opsForValue().get(partKey);
            if (json != null) {
                try {
                    StreamMessage part = objectMapper.readValue(json, StreamMessage.class);
                    parts.add(part);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to deserialize streaming part: partId={}, error={}",
                            partId, e.getMessage());
                }
            }
        }
        return parts;
    }

    // ==================== Session streaming status ====================

    /**
     * Set or clear the session streaming status.
     */
    public void setSessionStreaming(String sessionId, boolean busy) {
        String key = statusKey(sessionId);
        if (busy) {
            redis.opsForValue().set(key, "{\"status\":\"busy\"}", TTL_HOURS, TimeUnit.HOURS);
        } else {
            redis.delete(key);
        }
    }

    // ==================== Part operations ====================

    private static final int MAX_APPEND_RETRIES = 3;

    /**
     * Append content to an existing part (used for text.delta / thinking.delta).
     * If part doesn't exist, creates it.
     * 使用 while 循环 + 有限重试替代递归，避免极端并发下栈溢出。
     */
    private void appendContent(String sessionId, StreamMessage msg, String partType) {
        String partId = msg.getPartId();
        String content = msg.getContent();
        if (partId == null || content == null)
            return;

        String key = partKey(sessionId, partId);

        for (int attempt = 0; attempt < MAX_APPEND_RETRIES; attempt++) {
            String existing = redis.opsForValue().get(key);
            if (existing != null) {
                try {
                    StreamMessage part = objectMapper.readValue(existing, StreamMessage.class);
                    String newContent = (part.getContent() != null ? part.getContent() : "") + content;
                    part.setContent(newContent);
                    part.setEmittedAt(msg.getEmittedAt());
                    String updated = objectMapper.writeValueAsString(part);
                    Boolean replaced = redis.opsForValue().setIfPresent(key, updated, TTL_HOURS, TimeUnit.HOURS);
                    if (Boolean.FALSE.equals(replaced)) {
                        // key 在读取后被删除，下一轮循环当作新建处理
                        continue;
                    }
                    return; // 追加成功
                } catch (JsonProcessingException e) {
                    log.warn("Failed to append to part {}: {}", partId, e.getMessage());
                    return;
                }
            } else {
                if (createNewPart(sessionId, msg, partType, key)) {
                    return; // 创建成功
                }
                // 另一个线程已创建，下一轮循环尝试追加
            }
        }
        log.warn("Failed to append content after {} retries for part {}", MAX_APPEND_RETRIES, msg.getPartId());
    }

    /**
     * 创建新的 streaming part 并注册到 parts_order 列表。
     * 使用 setIfAbsent 保证仅第一个并发线程成功创建。
     * 
     * @return true 如果成功创建，false 如果被其他线程抢占
     */
    private boolean createNewPart(String sessionId, StreamMessage msg, String partType, String key) {
        StreamMessage part = StreamMessage.builder()
                .type(partType.equals("text") ? StreamMessage.Types.TEXT_DELTA : StreamMessage.Types.THINKING_DELTA)
                .sessionId(msg.getSessionId())
                .emittedAt(msg.getEmittedAt())
                .messageId(msg.getMessageId())
                .messageSeq(msg.getMessageSeq())
                .role(msg.getRole())
                .sourceMessageId(msg.getSourceMessageId())
                .partId(msg.getPartId())
                .partSeq(msg.getPartSeq())
                .content(msg.getContent())
                .build();
        try {
            String json = objectMapper.writeValueAsString(part);
            Boolean created = redis.opsForValue().setIfAbsent(key, json, TTL_HOURS, TimeUnit.HOURS);
            if (Boolean.TRUE.equals(created)) {
                redis.opsForList().rightPush(partsOrderKey(sessionId), msg.getPartId());
                redis.expire(partsOrderKey(sessionId), TTL_HOURS, TimeUnit.HOURS);
                return true;
            }
            return false; // 被其他线程抢占
        } catch (JsonProcessingException e) {
            log.warn("Failed to create part {}: {}", msg.getPartId(), e.getMessage());
            return true; // 序列化失败不应重试
        }
    }

    /**
     * Set a part with full StreamMessage data (used for tool.update, question,
     * permission).
     * 先无条件写入 part 数据，再通过 setIfAbsent 检查 order 列表是否需要注册。
     */
    private void setPartFull(String sessionId, String partId, StreamMessage msg) {
        if (partId == null)
            return;

        String key = partKey(sessionId, partId);
        try {
            String serialized = objectMapper.writeValueAsString(msg);
            // 无条件写入最新数据
            redis.opsForValue().set(key, serialized, TTL_HOURS, TimeUnit.HOURS);
            // 尝试注册到 parts_order（如果已存在则跳过）
            String orderKey = partsOrderKey(sessionId);
            // 用一个 sentinel key 检查此 partId 是否已注册过
            Boolean isNew = redis.opsForValue().setIfAbsent(
                    key + SUFFIX_REGISTERED, "1", TTL_HOURS, TimeUnit.HOURS);
            if (Boolean.TRUE.equals(isNew)) {
                redis.opsForList().rightPush(orderKey, partId);
                redis.expire(orderKey, TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to set part {}: {}", partId, e.getMessage());
        }
    }

    /**
     * Clear a specific part from the buffer (called when part is
     * finalized/persisted).
     */
    public void clearPart(String sessionId, String partId) {
        if (partId == null)
            return;
        redis.delete(partKey(sessionId, partId));
        redis.opsForList().remove(partsOrderKey(sessionId), 0, partId);
        log.debug("Cleared buffer part: session={}, partId={}", sessionId, partId);
    }

    /**
     * Clear all streaming data for a session (called on session idle/step done).
     */
    public void clearSession(String sessionId) {
        String orderKey = partsOrderKey(sessionId);
        List<String> partIds = redis.opsForList().range(orderKey, 0, -1);

        List<String> keysToDelete = new ArrayList<>();
        keysToDelete.add(orderKey);
        keysToDelete.add(statusKey(sessionId));
        if (partIds != null) {
            for (String partId : partIds) {
                keysToDelete.add(partKey(sessionId, partId));
            }
        }
        redis.delete(keysToDelete);

        log.debug("Cleared all buffer data for session {}", sessionId);
    }

    // ==================== Key builders ====================

    private String statusKey(String sessionId) {
        return PREFIX + sessionId + SUFFIX_STATUS;
    }

    private String partKey(String sessionId, String partId) {
        return PREFIX + sessionId + SUFFIX_PART + partId;
    }

    private String partsOrderKey(String sessionId) {
        return PREFIX + sessionId + SUFFIX_ORDER;
    }
}
