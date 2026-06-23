package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.event.SessionDeletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based stream buffer for session resilience.
 *
 * Accumulates live streaming parts so a reconnect can recover the current
 * in-progress response. Durable history is MySQL's job; this cache is only the
 * live replay layer and is cleared when the session becomes idle.
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

    /**
     * Accumulate a StreamMessage into Redis buffer based on its type.
     */
    public void accumulate(String sessionId, StreamMessage msg) {
        try {
            switch (msg.getType()) {
                case StreamMessage.Types.TEXT_DELTA -> accumulateDelta(sessionId, msg, "text");
                case StreamMessage.Types.TEXT_DONE -> completeAccumulatedPart(sessionId, msg);
                case StreamMessage.Types.THINKING_DELTA -> accumulateDelta(sessionId, msg, "thinking");
                case StreamMessage.Types.THINKING_DONE -> completeAccumulatedPart(sessionId, msg);
                case StreamMessage.Types.TOOL_UPDATE, StreamMessage.Types.QUESTION,
                        StreamMessage.Types.FILE ->
                    setPartFull(sessionId, msg.getPartId(), msg);
                case StreamMessage.Types.PERMISSION_ASK,
                        StreamMessage.Types.PERMISSION_REPLY ->
                    accumulatePermission(sessionId, msg);
                case StreamMessage.Types.SESSION_STATUS -> handleSessionStatus(sessionId, msg);
                case StreamMessage.Types.ERROR, StreamMessage.Types.SESSION_ERROR -> clearSession(sessionId);
                default -> {
                    /* step.start, agent.online/offline: no live replay state */
                }
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

    private void accumulatePermission(String sessionId, StreamMessage msg) {
        String permId = msg.getPermission() != null ? msg.getPermission().getPermissionId() : null;
        String permPartId = msg.getPartId() != null ? msg.getPartId() : permId;
        setPartFull(sessionId, permPartId, msg);
    }

    private void handleSessionStatus(String sessionId, StreamMessage msg) {
        String status = msg.getSessionStatus();
        if ("idle".equals(status) || "completed".equals(status)) {
            clearSession(sessionId);
            return;
        }
        if ("busy".equals(status) || "retry".equals(status)) {
            setSessionStreaming(sessionId, true);
        }
    }

    /**
     * Check if a session is currently streaming.
     */
    public boolean isSessionStreaming(String sessionId) {
        String key = statusKey(sessionId);
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * Get all in-progress streaming parts for a session.
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

    private static final int MAX_APPEND_RETRIES = 3;

    private void appendContent(String sessionId, StreamMessage msg, String partType) {
        String partId = msg.getPartId();
        String content = msg.getContent();
        if (partId == null || content == null) {
            return;
        }

        String key = partKey(sessionId, partId);

        for (int attempt = 0; attempt < MAX_APPEND_RETRIES; attempt++) {
            String existing = redis.opsForValue().get(key);
            if (existing != null) {
                try {
                    StreamMessage part = objectMapper.readValue(existing, StreamMessage.class);
                    String newContent = (part.getContent() != null ? part.getContent() : "") + content;
                    part.setContent(newContent);
                    part.setEmittedAt(msg.getEmittedAt());
                    mergeMessageContext(sessionId, part, msg);
                    String updated = objectMapper.writeValueAsString(part);
                    Boolean replaced = redis.opsForValue().setIfPresent(key, updated, TTL_HOURS, TimeUnit.HOURS);
                    if (Boolean.FALSE.equals(replaced)) {
                        continue;
                    }
                    return;
                } catch (JsonProcessingException e) {
                    log.warn("Failed to append to part {}: {}", partId, e.getMessage());
                    return;
                }
            } else {
                if (createNewPart(sessionId, msg, partType, key)) {
                    return;
                }
            }
        }
        log.warn("Failed to append content after {} retries for part {}", MAX_APPEND_RETRIES, msg.getPartId());
    }

    private void mergeMessageContext(String sessionId, StreamMessage target, StreamMessage incoming) {
        if (incoming.getMessageId() != null && !incoming.getMessageId().isBlank()
                && shouldReplaceMessageId(sessionId, target, incoming)) {
            target.setMessageId(incoming.getMessageId());
        }
        if (incoming.getSourceMessageId() != null && !incoming.getSourceMessageId().isBlank()
                && shouldReplaceSourceMessageId(sessionId, target, incoming)) {
            target.setSourceMessageId(incoming.getSourceMessageId());
        }
        if (incoming.getMessageSeq() != null) {
            target.setMessageSeq(incoming.getMessageSeq());
        }
        if (incoming.getRole() != null && !incoming.getRole().isBlank()) {
            target.setRole(incoming.getRole());
        }
        if (incoming.getPartSeq() != null) {
            target.setPartSeq(incoming.getPartSeq());
        }
        if (target.getSessionId() == null) {
            target.setSessionId(incoming.getSessionId() != null ? incoming.getSessionId() : sessionId);
        }
        if (target.getWelinkSessionId() == null) {
            target.setWelinkSessionId(incoming.getWelinkSessionId());
        }
        if (target.getSubagentSessionId() == null) {
            target.setSubagentSessionId(incoming.getSubagentSessionId());
        }
        if (target.getSubagentName() == null) {
            target.setSubagentName(incoming.getSubagentName());
        }
    }

    private boolean shouldReplaceMessageId(String sessionId, StreamMessage target, StreamMessage incoming) {
        String current = target.getMessageId();
        if (current == null || current.isBlank() || current.equals(incoming.getMessageId())) {
            return true;
        }
        return ProtocolUtils.isGeneratedMessageId(sessionId, target.getMessageSeq(), current)
                && !ProtocolUtils.isGeneratedMessageId(sessionId, incoming.getMessageSeq(), incoming.getMessageId());
    }

    private boolean shouldReplaceSourceMessageId(String sessionId, StreamMessage target, StreamMessage incoming) {
        String current = target.getSourceMessageId();
        if (current == null || current.isBlank() || current.equals(incoming.getSourceMessageId())) {
            return true;
        }
        return ProtocolUtils.isGeneratedMessageId(sessionId, target.getMessageSeq(), current)
                && !ProtocolUtils.isGeneratedMessageId(sessionId, incoming.getMessageSeq(), incoming.getSourceMessageId());
    }

    private boolean createNewPart(String sessionId, StreamMessage msg, String partType, String key) {
        StreamMessage part = StreamMessage.builder()
                .type(partType.equals("text") ? StreamMessage.Types.TEXT_DELTA : StreamMessage.Types.THINKING_DELTA)
                .sessionId(msg.getSessionId() != null ? msg.getSessionId() : sessionId)
                .welinkSessionId(msg.getWelinkSessionId())
                .emittedAt(msg.getEmittedAt())
                .messageId(msg.getMessageId())
                .messageSeq(msg.getMessageSeq())
                .role(msg.getRole())
                .sourceMessageId(msg.getSourceMessageId())
                .partId(msg.getPartId())
                .partSeq(msg.getPartSeq())
                .content(msg.getContent())
                .subagentSessionId(msg.getSubagentSessionId())
                .subagentName(msg.getSubagentName())
                .build();
        try {
            String json = objectMapper.writeValueAsString(part);
            Boolean created = redis.opsForValue().setIfAbsent(key, json, TTL_HOURS, TimeUnit.HOURS);
            if (Boolean.TRUE.equals(created)) {
                registerPartIfNeeded(sessionId, msg.getPartId(), key, true);
                return true;
            }
            return false;
        } catch (JsonProcessingException e) {
            log.warn("Failed to create part {}: {}", msg.getPartId(), e.getMessage());
            return true;
        }
    }

    /**
     * Store a full part. Used for tool/question/permission/file events.
     */
    private void setPartFull(String sessionId, String partId, StreamMessage msg) {
        if (partId == null) {
            return;
        }

        String key = partKey(sessionId, partId);
        boolean alreadyBuffered = Boolean.TRUE.equals(redis.hasKey(key));
        if (msg.getSessionId() == null) {
            msg.setSessionId(sessionId);
        }
        try {
            String serialized = objectMapper.writeValueAsString(msg);
            redis.opsForValue().set(key, serialized, TTL_HOURS, TimeUnit.HOURS);
            registerPartIfNeeded(sessionId, partId, key, !alreadyBuffered);
        } catch (JsonProcessingException e) {
            log.warn("Failed to set part {}: {}", partId, e.getMessage());
        }
    }

    /**
     * text.done/thinking.done must keep the completed content in Redis until the
     * idle event clears the live state. Otherwise a refresh between done and idle
     * observes no part and the response appears to vanish.
     */
    private void completeAccumulatedPart(String sessionId, StreamMessage msg) {
        String partId = msg.getPartId();
        if (partId == null) {
            return;
        }

        String key = partKey(sessionId, partId);
        boolean alreadyBuffered = Boolean.TRUE.equals(redis.hasKey(key));
        if (msg.getSessionId() == null) {
            msg.setSessionId(sessionId);
        }
        fillMissingFieldsFromBufferedPart(key, msg);
        try {
            String serialized = objectMapper.writeValueAsString(msg);
            redis.opsForValue().set(key, serialized, TTL_HOURS, TimeUnit.HOURS);
            if (!alreadyBuffered) {
                registerPartIfNeeded(sessionId, partId, key, true);
            }
            setSessionStreaming(sessionId, true);
        } catch (JsonProcessingException e) {
            log.warn("Failed to complete part {}: {}", partId, e.getMessage());
        }
    }

    private void fillMissingFieldsFromBufferedPart(String key, StreamMessage msg) {
        if (msg.getContent() != null && msg.getMessageId() != null && msg.getRole() != null) {
            return;
        }
        String existing = redis.opsForValue().get(key);
        if (existing == null) {
            return;
        }
        try {
            StreamMessage buffered = objectMapper.readValue(existing, StreamMessage.class);
            if (msg.getContent() == null) {
                msg.setContent(buffered.getContent());
            }
            if (msg.getMessageId() == null) {
                msg.setMessageId(buffered.getMessageId());
            }
            if (msg.getMessageSeq() == null) {
                msg.setMessageSeq(buffered.getMessageSeq());
            }
            if (msg.getRole() == null) {
                msg.setRole(buffered.getRole());
            }
            if (msg.getSourceMessageId() == null) {
                msg.setSourceMessageId(buffered.getSourceMessageId());
            }
            if (msg.getPartSeq() == null) {
                msg.setPartSeq(buffered.getPartSeq());
            }
            if (msg.getSubagentSessionId() == null) {
                msg.setSubagentSessionId(buffered.getSubagentSessionId());
            }
            if (msg.getSubagentName() == null) {
                msg.setSubagentName(buffered.getSubagentName());
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to merge buffered part fields: {}", e.getMessage());
        }
    }

    private void registerPartIfNeeded(String sessionId, String partId, String key, boolean partWasAbsent) {
        String registeredKey = key + SUFFIX_REGISTERED;
        String orderKey = partsOrderKey(sessionId);
        Boolean isNew = redis.opsForValue().setIfAbsent(
                registeredKey, "1", TTL_HOURS, TimeUnit.HOURS);
        if (Boolean.TRUE.equals(isNew)) {
            redis.opsForList().rightPush(orderKey, partId);
            redis.expire(orderKey, TTL_HOURS, TimeUnit.HOURS);
            return;
        }
        redis.expire(registeredKey, TTL_HOURS, TimeUnit.HOURS);
        if (partWasAbsent) {
            redis.opsForList().remove(orderKey, 0, partId);
            redis.opsForList().rightPush(orderKey, partId);
            redis.expire(orderKey, TTL_HOURS, TimeUnit.HOURS);
        }
    }

    /**
     * Clear a specific part from the buffer.
     */
    public void clearPart(String sessionId, String partId) {
        if (partId == null) {
            return;
        }
        String key = partKey(sessionId, partId);
        redis.delete(List.of(key, key + SUFFIX_REGISTERED));
        redis.opsForList().remove(partsOrderKey(sessionId), 0, partId);
        log.debug("Cleared buffer part: session={}, partId={}", sessionId, partId);
    }

    /**
     * Clear all streaming data for a session.
     */
    public void clearSession(String sessionId) {
        String orderKey = partsOrderKey(sessionId);
        List<String> partIds = redis.opsForList().range(orderKey, 0, -1);

        List<String> keysToDelete = new ArrayList<>();
        keysToDelete.add(orderKey);
        keysToDelete.add(statusKey(sessionId));
        if (partIds != null) {
            for (String partId : partIds) {
                String key = partKey(sessionId, partId);
                keysToDelete.add(key);
                keysToDelete.add(key + SUFFIX_REGISTERED);
            }
        }
        redis.delete(keysToDelete);

        log.debug("Cleared all buffer data for session {}", sessionId);
    }

    private String statusKey(String sessionId) {
        return PREFIX + sessionId + SUFFIX_STATUS;
    }

    private String partKey(String sessionId, String partId) {
        return PREFIX + sessionId + SUFFIX_PART + partId;
    }

    private String partsOrderKey(String sessionId) {
        return PREFIX + sessionId + SUFFIX_ORDER;
    }

    /**
     * 会话删除后同步清理本服务管理的 stream buffer 缓存。
     * 缓存的 owner 自行管理清理，便于统一维护。
     */
    @EventListener
    public void onSessionDeleted(SessionDeletedEvent event) {
        if (event.session() == null) {
            return;
        }
        try {
            clearSession(String.valueOf(event.session().getId()));
            log.debug("Cleared stream buffer for deleted session: sessionId={}", event.session().getId());
        } catch (Exception e) {
            log.warn("Failed to clear stream buffer for deleted session: sessionId={}, error={}",
                    event.session().getId(), e.getMessage());
        }
    }
}
