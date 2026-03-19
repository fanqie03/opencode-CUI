package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ImSessionManager {

    private static final long CREATE_LOCK_RETRY_MILLIS = 100L;
    private static final Duration CREATE_LOCK_TTL = Duration.ofSeconds(15);

    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final SessionRebuildService rebuildService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int autoCreateTimeoutSeconds;

    public ImSessionManager(
            SkillSessionService sessionService,
            GatewayRelayService gatewayRelayService,
            SessionRebuildService rebuildService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${skill.session.auto-create-timeout-seconds:30}") int autoCreateTimeoutSeconds) {
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.rebuildService = rebuildService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.autoCreateTimeoutSeconds = autoCreateTimeoutSeconds;
    }

    /**
     * Non-blocking session lookup. Returns null if no session exists.
     */
    public SkillSession findSession(String businessDomain, String sessionType,
            String sessionId, String ak) {
        SkillSession existing = sessionService.findByBusinessSession(businessDomain, sessionType, sessionId, ak);
        if (existing != null) {
            sessionService.touchSession(existing.getId());
        }
        return existing;
    }

    /**
     * Create a new DB session and send create_session to Gateway asynchronously.
     * The pending message is stored so that when Gateway replies with
     * session_created,
     * it is automatically forwarded via
     * {@link GatewayMessageRouter#retryPendingMessage}.
     */
    public void createSessionAsync(String businessDomain, String sessionType,
            String sessionId, String ak, String assistantAccount,
            String pendingMessage) {
        String lockKey = buildCreateLockKey(businessDomain, sessionType, sessionId, ak);
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;

        try {
            locked = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, CREATE_LOCK_TTL));
            if (!locked) {
                log.info("Session creation already in progress for sessionId={}, skipping", sessionId);
                return;
            }

            SkillSession existing = sessionService.findByBusinessSession(
                    businessDomain, sessionType, sessionId, ak);
            if (existing != null) {
                sessionService.touchSession(existing.getId());
                requestToolSession(existing, pendingMessage);
                return;
            }

            SkillSession created = sessionService.createSession(
                    assistantAccount,
                    ak,
                    buildTitle(businessDomain, sessionType, sessionId),
                    businessDomain,
                    sessionType,
                    sessionId,
                    assistantAccount);

            requestToolSession(created, pendingMessage);
        } finally {
            releaseCreateLock(lockKey, lockValue, locked);
        }
    }

    /**
     * Store the pending message and send create_session to Gateway.
     * When Gateway replies with session_created, the message is auto-retried.
     */
    public void requestToolSession(SkillSession session, String pendingMessage) {
        String sessionIdStr = String.valueOf(session.getId());
        gatewayRelayService.rebuildToolSession(sessionIdStr, session, pendingMessage);
        log.info("Requested tool session creation for welinkSession={}, ak={}",
                sessionIdStr, session.getAk());
    }

    /**
     * Synchronous find-or-create for backward compatibility.
     * Blocks until toolSessionId is available.
     */
    public SkillSession findOrCreateSession(String businessDomain, String sessionType,
            String sessionId, String ak, String assistantAccount) {
        SkillSession existing = sessionService.findByBusinessSession(businessDomain, sessionType, sessionId, ak);
        if (existing != null) {
            sessionService.touchSession(existing.getId());
            return ensureToolSession(existing, false);
        }

        String lockKey = buildCreateLockKey(businessDomain, sessionType, sessionId, ak);
        String lockValue = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + autoCreateTimeoutSeconds * 1000L;
        boolean locked = false;

        try {
            while (System.currentTimeMillis() < deadline) {
                locked = Boolean.TRUE.equals(
                        redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, CREATE_LOCK_TTL));
                if (locked) {
                    SkillSession latest = sessionService.findByBusinessSession(
                            businessDomain, sessionType, sessionId, ak);
                    if (latest != null) {
                        sessionService.touchSession(latest.getId());
                        return ensureToolSession(latest, false);
                    }

                    SkillSession created = sessionService.createSession(
                            assistantAccount,
                            ak,
                            buildTitle(businessDomain, sessionType, sessionId),
                            businessDomain,
                            sessionType,
                            sessionId,
                            assistantAccount);
                    return ensureToolSession(created, true);
                }

                SkillSession concurrent = sessionService.findByBusinessSession(
                        businessDomain, sessionType, sessionId, ak);
                if (concurrent != null) {
                    sessionService.touchSession(concurrent.getId());
                    return ensureToolSession(concurrent, false);
                }

                sleepQuietly(CREATE_LOCK_RETRY_MILLIS);
            }
        } finally {
            releaseCreateLock(lockKey, lockValue, locked);
        }

        throw new IllegalStateException("Timed out waiting for IM session creation lock: " + sessionId);
    }

    private SkillSession ensureToolSession(SkillSession session, boolean newlyCreated) {
        if (session.getToolSessionId() != null && !session.getToolSessionId().isBlank()) {
            return sessionService.getSession(session.getId());
        }

        if (newlyCreated) {
            gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                    session.getAk(),
                    session.getUserId(),
                    String.valueOf(session.getId()),
                    GatewayActions.CREATE_SESSION,
                    PayloadBuilder.buildPayload(objectMapper, Map.of("title", session.getTitle()))));
        } else {
            gatewayRelayService.rebuildToolSession(String.valueOf(session.getId()), session, null);
        }

        return waitForToolSession(session.getId());
    }

    private SkillSession waitForToolSession(Long sessionId) {
        long deadline = System.currentTimeMillis() + autoCreateTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            SkillSession latest = sessionService.findByIdSafe(sessionId);
            if (latest != null && latest.getToolSessionId() != null && !latest.getToolSessionId().isBlank()) {
                return latest;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for toolSessionId");
            }
        }
        throw new IllegalStateException("Timed out waiting for tool session creation: " + sessionId);
    }

    private String buildTitle(String businessDomain, String sessionType, String sessionId) {
        return "%s-%s-%s".formatted(
                businessDomain != null ? businessDomain : "im",
                sessionType != null ? sessionType : "session",
                sessionId != null ? sessionId : "unknown");
    }

    private String buildCreateLockKey(String businessDomain, String sessionType, String sessionId, String ak) {
        return "skill:im-session:create:%s:%s:%s:%s".formatted(
                businessDomain != null ? businessDomain : "im",
                sessionType != null ? sessionType : "session",
                sessionId != null ? sessionId : "unknown",
                ak != null ? ak : "unknown");
    }

    private void releaseCreateLock(String lockKey, String lockValue, boolean locked) {
        if (!locked) {
            return;
        }
        try {
            String currentValue = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(currentValue)) {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("Failed to release IM session creation lock: key={}, error={}", lockKey, e.getMessage());
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for IM session creation lock");
        }
    }
}
