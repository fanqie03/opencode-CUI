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
import java.util.Map;
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
                case StreamMessage.Types.TEXT_DELTA:
                    appendContent(sessionId, msg.getPartId(), "text", msg.getContent());
                    setSessionStreaming(sessionId, true);
                    break;

                case StreamMessage.Types.TEXT_DONE:
                    // Final text: set full content then clear (will be persisted to DB)
                    if (msg.getPartId() != null) {
                        clearPart(sessionId, msg.getPartId());
                    }
                    break;

                case StreamMessage.Types.THINKING_DELTA:
                    appendContent(sessionId, msg.getPartId(), "thinking", msg.getContent());
                    setSessionStreaming(sessionId, true);
                    break;

                case StreamMessage.Types.THINKING_DONE:
                    if (msg.getPartId() != null) {
                        clearPart(sessionId, msg.getPartId());
                    }
                    break;

                case StreamMessage.Types.TOOL_UPDATE:
                    // Tool state: overwrite with latest status
                    setPartFull(sessionId, msg.getPartId(), msg);
                    break;

                case StreamMessage.Types.QUESTION:
                    setPartFull(sessionId, msg.getPartId(), msg);
                    break;

                case StreamMessage.Types.PERMISSION_ASK:
                    String permPartId = msg.getPartId() != null ? msg.getPartId() : msg.getPermissionId();
                    setPartFull(sessionId, permPartId, msg);
                    break;

                case StreamMessage.Types.STEP_DONE:
                    // Step completed: clear all parts for this session
                    clearSession(sessionId);
                    break;

                case StreamMessage.Types.SESSION_STATUS:
                    if ("idle".equals(msg.getSessionStatus()) || "completed".equals(msg.getSessionStatus())) {
                        clearSession(sessionId);
                    }
                    break;

                default:
                    // step.start, agent.online/offline, error, etc. — no buffering needed
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to accumulate StreamMessage to Redis for session {}: {}",
                    sessionId, e.getMessage(), e);
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

    /**
     * Append content to an existing part (used for text.delta / thinking.delta).
     * If part doesn't exist, creates it.
     */
    private void appendContent(String sessionId, String partId, String partType, String content) {
        if (partId == null || content == null)
            return;

        String key = partKey(sessionId, partId);

        // Check if this part already exists
        Boolean exists = redis.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            // Part exists: read, append content, write back
            String existing = redis.opsForValue().get(key);
            if (existing != null) {
                try {
                    StreamMessage part = objectMapper.readValue(existing, StreamMessage.class);
                    String newContent = (part.getContent() != null ? part.getContent() : "") + content;
                    part.setContent(newContent);
                    redis.opsForValue().set(key, objectMapper.writeValueAsString(part),
                            TTL_HOURS, TimeUnit.HOURS);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to append to part {}: {}", partId, e.getMessage());
                }
            }
        } else {
            // New part: create with initial content
            StreamMessage part = StreamMessage.builder()
                    .type(partType.equals("text") ? StreamMessage.Types.TEXT_DELTA : StreamMessage.Types.THINKING_DELTA)
                    .partId(partId)
                    .content(content)
                    .build();
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(part),
                        TTL_HOURS, TimeUnit.HOURS);
                // Track part order
                redis.opsForList().rightPush(partsOrderKey(sessionId), partId);
                redis.expire(partsOrderKey(sessionId), TTL_HOURS, TimeUnit.HOURS);
            } catch (JsonProcessingException e) {
                log.warn("Failed to create part {}: {}", partId, e.getMessage());
            }
        }
    }

    /**
     * Set a part with full StreamMessage data (used for tool.update, question,
     * permission).
     */
    private void setPartFull(String sessionId, String partId, StreamMessage msg) {
        if (partId == null)
            return;

        String key = partKey(sessionId, partId);
        try {
            Boolean exists = redis.hasKey(key);
            redis.opsForValue().set(key, objectMapper.writeValueAsString(msg),
                    TTL_HOURS, TimeUnit.HOURS);
            if (!Boolean.TRUE.equals(exists)) {
                redis.opsForList().rightPush(partsOrderKey(sessionId), partId);
                redis.expire(partsOrderKey(sessionId), TTL_HOURS, TimeUnit.HOURS);
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
        // Get all part IDs to delete their keys
        String orderKey = partsOrderKey(sessionId);
        List<String> partIds = redis.opsForList().range(orderKey, 0, -1);
        if (partIds != null) {
            for (String partId : partIds) {
                redis.delete(partKey(sessionId, partId));
            }
        }
        redis.delete(orderKey);
        redis.delete(statusKey(sessionId));

        log.debug("Cleared all buffer data for session {}", sessionId);
    }

    // ==================== Key builders ====================

    private String statusKey(String sessionId) {
        return PREFIX + sessionId + ":status";
    }

    private String partKey(String sessionId, String partId) {
        return PREFIX + sessionId + ":part:" + partId;
    }

    private String partsOrderKey(String sessionId) {
        return PREFIX + sessionId + ":parts_order";
    }
}
