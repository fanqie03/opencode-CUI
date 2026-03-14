package com.opencode.cui.skill.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Session-scoped caching for part types, sequences, message roles, and text
 * content.
 * Extracted from OpenCodeEventTranslator to improve testability and reduce
 * class size.
 */
@Component
public class TranslatorSessionCache {

    private static final Duration LONG_TTL = Duration.ofHours(2);
    private static final Duration SHORT_TTL = Duration.ofMinutes(10);

    private final Cache<String, String> partTypes = Caffeine.newBuilder()
            .expireAfterAccess(LONG_TTL).maximumSize(50_000).build();
    private final Cache<String, Integer> partSequences = Caffeine.newBuilder()
            .expireAfterAccess(LONG_TTL).maximumSize(50_000).build();
    private final Cache<String, Integer> nextPartSequences = Caffeine.newBuilder()
            .expireAfterAccess(LONG_TTL).maximumSize(10_000).build();
    private final Cache<String, String> messageRoles = Caffeine.newBuilder()
            .expireAfterAccess(LONG_TTL).maximumSize(10_000).build();
    private final Cache<String, String> messageTexts = Caffeine.newBuilder()
            .expireAfterAccess(SHORT_TTL).maximumSize(10_000).build();
    /** Maps sessionId:callId → question partId (from question.asked event) */
    private final Cache<String, String> questionPartIds = Caffeine.newBuilder()
            .expireAfterAccess(LONG_TTL).maximumSize(10_000).build();

    public void rememberPartType(String sessionId, String partId, String partType) {
        if (isBlank(sessionId) || isBlank(partId) || isBlank(partType))
            return;
        partTypes.put(key(sessionId, partId), partType);
    }

    public String getPartType(String sessionId, String partId) {
        if (isBlank(sessionId) || isBlank(partId))
            return null;
        return partTypes.getIfPresent(key(sessionId, partId));
    }

    public Integer rememberPartSeq(String sessionId, String messageId, String partId) {
        if (isBlank(sessionId) || isBlank(messageId) || isBlank(partId))
            return null;

        String partKey = key(sessionId, partId);
        Integer existing = partSequences.getIfPresent(partKey);
        if (existing != null)
            return existing;

        String msgKey = key(sessionId, messageId);
        Integer nextSeq = nextPartSequences.asMap().compute(msgKey, (k, v) -> v == null ? 1 : v + 1);
        Integer prior = partSequences.asMap().putIfAbsent(partKey, nextSeq);
        return prior != null ? prior : nextSeq;
    }

    public void evictPart(String sessionId, String messageId, String partId) {
        if (isBlank(sessionId) || isBlank(partId))
            return;
        String partKey = key(sessionId, partId);
        partTypes.invalidate(partKey);
        partSequences.invalidate(partKey);
        if (!isBlank(messageId)) {
            String msgKey = key(sessionId, messageId);
            if (partSequences.asMap().keySet().stream().noneMatch(k -> k.startsWith(sessionId + ":"))) {
                nextPartSequences.invalidate(msgKey);
            }
        }
    }

    public void rememberMessageRole(String sessionId, String messageId, String role) {
        if (isBlank(sessionId) || isBlank(messageId) || isBlank(role))
            return;
        messageRoles.put(key(sessionId, messageId), role);
    }

    public String resolveMessageRole(String sessionId, String messageId) {
        if (isBlank(sessionId) || isBlank(messageId))
            return "assistant";
        return ProtocolUtils.normalizeRole(messageRoles.getIfPresent(key(sessionId, messageId)));
    }

    public void rememberMessageText(String sessionId, String messageId, String text) {
        if (isBlank(sessionId) || isBlank(messageId) || isBlank(text))
            return;
        messageTexts.put(key(sessionId, messageId), text);
    }

    public String getMessageText(String sessionId, String messageId) {
        if (isBlank(sessionId) || isBlank(messageId))
            return null;
        return messageTexts.getIfPresent(key(sessionId, messageId));
    }

    public void invalidateMessageText(String sessionId, String messageId) {
        if (isBlank(sessionId) || isBlank(messageId))
            return;
        messageTexts.invalidate(key(sessionId, messageId));
    }

    /**
     * Cache the question partId (from question.asked) for a given callId,
     * so that when the tool completed event arrives we can reuse the same partId.
     */
    public void rememberQuestionPartId(String sessionId, String callId, String partId) {
        if (isBlank(sessionId) || isBlank(callId) || isBlank(partId))
            return;
        questionPartIds.put(key(sessionId, callId), partId);
    }

    public String getQuestionPartId(String sessionId, String callId) {
        if (isBlank(sessionId) || isBlank(callId))
            return null;
        return questionPartIds.getIfPresent(key(sessionId, callId));
    }

    public void clearSession(String sessionId) {
        if (isBlank(sessionId))
            return;
        String prefix = sessionId + ":";
        partTypes.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        partSequences.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        nextPartSequences.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        messageRoles.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        questionPartIds.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static String key(String a, String b) {
        return a + ":" + b;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
