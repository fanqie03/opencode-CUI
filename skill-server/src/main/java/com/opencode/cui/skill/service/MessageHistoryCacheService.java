package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MessageHistoryCacheService {

    private static final String KEY_PREFIX = "skill:history:latest:";
    private static final TypeReference<MessageHistoryResult<ProtocolMessageView>> HISTORY_TYPE =
            new TypeReference<>() {
            };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final List<Integer> warmSizes;

    public MessageHistoryCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${skill.message-history.cache-ttl-seconds:30}") long ttlSeconds,
            @Value("${skill.message-history.warm-sizes:50}") String warmSizesConfig) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(Math.max(ttlSeconds, 1));
        this.warmSizes = parseWarmSizes(warmSizesConfig);
    }

    public MessageHistoryResult<ProtocolMessageView> getLatestHistory(Long sessionId, int size) {
        String raw = redisTemplate.opsForValue().get(buildKey(sessionId, size));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, HISTORY_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize latest history cache: sessionId={}, size={}, error={}",
                    sessionId, size, e.getMessage());
            invalidateLatestHistory(sessionId);
            return null;
        }
    }

    public void putLatestHistory(Long sessionId, int size, MessageHistoryResult<ProtocolMessageView> result) {
        if (sessionId == null || result == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    buildKey(sessionId, size),
                    objectMapper.writeValueAsString(result),
                    ttl);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize latest history cache: sessionId={}, size={}, error={}",
                    sessionId, size, e.getMessage());
        }
    }

    public void invalidateLatestHistory(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        List<String> keys = warmSizes.stream()
                .map(size -> buildKey(sessionId, size))
                .collect(Collectors.toList());
        if (keys.isEmpty()) {
            return;
        }
        redisTemplate.delete(keys);
    }

    public List<Integer> getWarmSizes() {
        return warmSizes;
    }

    private String buildKey(Long sessionId, int size) {
        return KEY_PREFIX + sessionId + ":" + size;
    }

    private List<Integer> parseWarmSizes(String warmSizesConfig) {
        List<Integer> parsed = Arrays.stream(warmSizesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(this::parseWarmSize)
                .filter(size -> size > 0)
                .distinct()
                .sorted()
                .toList();
        return parsed.isEmpty() ? List.of(50) : parsed;
    }

    private int parseWarmSize(String rawSize) {
        try {
            return Integer.parseInt(rawSize);
        } catch (NumberFormatException e) {
            log.warn("Ignore invalid message history warm size: {}", rawSize);
            return -1;
        }
    }
}
