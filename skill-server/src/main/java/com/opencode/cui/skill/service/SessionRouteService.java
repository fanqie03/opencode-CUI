package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SessionRoute;
import com.opencode.cui.skill.repository.SessionRouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Session ownership service (pure Redis).
 *
 * Manages session-to-instance ownership via Redis key:
 *   ss:internal:session:{welinkSessionId} → instanceId
 *
 * All core paths use Redis only. MySQL-based methods are retained but marked @Deprecated.
 * TTL-based expiry replaces explicit cleanup — no reverse index needed.
 */
@Slf4j
@Service
public class SessionRouteService {

    private static final String SESSION_CACHE_PREFIX = "ss:internal:session:";

    /**
     * Redis Lua CAS script: replace owner only if current value equals deadInstance.
     * KEYS[1] = session key, ARGV[1] = deadInstanceId, ARGV[2] = newInstanceId, ARGV[3] = ttl seconds
     * Returns 1 on success, 0 on conflict.
     */
    private static final String LUA_CAS_TAKEOVER =
            "local current = redis.call('GET', KEYS[1])\n" +
            "if current == ARGV[1] then\n" +
            "    redis.call('SET', KEYS[1], ARGV[2], 'EX', tonumber(ARGV[3]))\n" +
            "    return 1\n" +
            "end\n" +
            "return 0";

    private final DefaultRedisScript<Long> casScript;

    private final SessionRouteRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final String instanceId;
    private final int ownershipCacheTtlSeconds;

    public SessionRouteService(SessionRouteRepository repository,
            StringRedisTemplate redisTemplate,
            @Value("${skill.instance-id:${HOSTNAME:skill-server-local}}") String instanceId,
            @Value("${skill.session.ownership-cache-ttl-seconds:1800}") int ownershipCacheTtlSeconds) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.instanceId = instanceId;
        this.ownershipCacheTtlSeconds = ownershipCacheTtlSeconds;

        this.casScript = new DefaultRedisScript<>(LUA_CAS_TAKEOVER, Long.class);
    }

    // ==================== Core (Redis-only) ====================

    /**
     * Creates ownership for a session. Uses SETNX semantics — if the key already exists,
     * ownership is NOT overwritten (another instance claimed it first).
     *
     * @param ak              Agent Access Key
     * @param welinkSessionId session ID
     * @param sourceType      origin type (e.g. "skill-server")
     * @param userId          session owner user
     */
    public void createRoute(String ak, Long welinkSessionId, String sourceType, String userId) {
        String key = SESSION_CACHE_PREFIX + welinkSessionId;
        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(
                    key, instanceId, Duration.ofSeconds(ownershipCacheTtlSeconds));
            if (Boolean.TRUE.equals(created)) {
                log.info("Created session ownership: ak={}, welinkSessionId={}, sourceType={}, owner={}",
                        ak, welinkSessionId, sourceType, instanceId);
            } else {
                String existingOwner = redisTemplate.opsForValue().get(key);
                log.info("Session ownership already exists: welinkSessionId={}, existingOwner={}, thisInstance={}",
                        welinkSessionId, existingOwner, instanceId);
            }
        } catch (Exception e) {
            log.warn("Failed to create session ownership in Redis: welinkSessionId={}, error={}",
                    welinkSessionId, e.getMessage());
        }
    }

    /**
     * @deprecated MySQL-based toolSessionId backfill is no longer needed in pure Redis mode.
     *             toolSessionId mapping is handled by skill_sessions table via SkillSessionService.
     */
    @Deprecated
    public void updateToolSessionId(Long welinkSessionId, String sourceType, String toolSessionId) {
        repository.updateToolSessionId(welinkSessionId, sourceType, toolSessionId);
        log.info("(deprecated) Updated toolSessionId: welinkSessionId={}, toolSessionId={}",
                welinkSessionId, toolSessionId);
    }

    /**
     * Removes session ownership from Redis.
     */
    public void closeRoute(Long welinkSessionId, String sourceType) {
        deleteCacheOwnership(welinkSessionId.toString());
        log.info("Closed session ownership: welinkSessionId={}, sourceType={}", welinkSessionId, sourceType);
    }

    // ==================== Ownership check ====================

    /**
     * Checks whether the given welinkSessionId is owned by this instance.
     * Pure Redis lookup. Returns false if not found (no degradation to true).
     */
    public boolean isMySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            String owner = redisTemplate.opsForValue().get(SESSION_CACHE_PREFIX + sessionId);
            return instanceId.equals(owner);
        } catch (Exception e) {
            log.warn("isMySession Redis query failed, degrading to true: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return true;
        }
    }

    /**
     * Ensures ownership for a session using Redis SETNX.
     * If key does not exist: SETNX succeeds → this instance is the owner.
     * If key exists: GET and compare to check if this instance owns it.
     *
     * @param sessionId welinkSessionId (string form)
     * @param ak        Agent Access Key
     * @param userId    session owner
     * @return true if this instance should process the message
     */
    public boolean ensureRouteOwnership(String sessionId, String ak, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        String key = SESSION_CACHE_PREFIX + sessionId;
        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(
                    key, instanceId, Duration.ofSeconds(ownershipCacheTtlSeconds));
            if (Boolean.TRUE.equals(created)) {
                log.info("ensureRouteOwnership auto-claim succeeded: sessionId={}, ak={}, owner={}",
                        sessionId, ak, instanceId);
                return true;
            }
            // Key exists — check if we are the owner
            String existingOwner = redisTemplate.opsForValue().get(key);
            boolean isMine = instanceId.equals(existingOwner);
            log.info("ensureRouteOwnership existing owner: sessionId={}, owner={}, isMine={}",
                    sessionId, existingOwner, isMine);
            return isMine;
        } catch (Exception e) {
            log.warn("ensureRouteOwnership Redis error, degrading to true: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return true;
        }
    }

    /**
     * Checks whether the given toolSessionId is owned by this instance.
     * Pure Redis cannot look up by toolSessionId (no reverse index).
     * Callers should use SkillSessionService.findByToolSessionId() + isMySession() instead.
     *
     * @deprecated Use SkillSessionService.findByToolSessionId() to get welinkSessionId, then isMySession().
     */
    @Deprecated
    public boolean isMyToolSession(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return false;
        }
        try {
            SessionRoute route = repository.findByToolSessionId(toolSessionId);
            return route != null && instanceId.equals(route.getSourceInstance());
        } catch (Exception e) {
            log.warn("isMyToolSession query failed, degrading to true: toolSessionId={}, error={}",
                    toolSessionId, e.getMessage());
            return true;
        }
    }

    // ==================== Ownership discovery (relay routing) ====================

    /**
     * Returns the owning instance ID for the given welinkSessionId.
     * Pure Redis lookup. Returns null if no ownership exists.
     *
     * @param welinkSessionId the session ID (string form)
     * @return owning instanceId, or null if not found
     */
    public String getOwnerInstance(String welinkSessionId) {
        if (welinkSessionId == null || welinkSessionId.isBlank()) {
            return null;
        }
        try {
            String owner = redisTemplate.opsForValue().get(SESSION_CACHE_PREFIX + welinkSessionId);
            if (owner != null) {
                log.info("getOwnerInstance hit: welinkSessionId={}, owner={}", welinkSessionId, owner);
            } else {
                log.info("getOwnerInstance miss: welinkSessionId={}", welinkSessionId);
            }
            return owner;
        } catch (Exception e) {
            log.warn("getOwnerInstance Redis query failed: welinkSessionId={}, error={}",
                    welinkSessionId, e.getMessage());
            return null;
        }
    }

    // ==================== Takeover (Redis Lua CAS) ====================

    /**
     * Attempts a CAS takeover of a session owned by a dead instance.
     * Uses Redis Lua script: only replaces owner if current value == deadInstanceId.
     *
     * @param welinkSessionId the session to take over (string form)
     * @param deadInstanceId  the expected current owner (CAS condition)
     * @param newInstanceId   the new owner instance ID
     * @return true if this instance won the takeover; false if another instance won first
     */
    public boolean tryTakeover(String welinkSessionId, String deadInstanceId, String newInstanceId) {
        if (welinkSessionId == null || welinkSessionId.isBlank()) {
            return false;
        }
        try {
            String key = SESSION_CACHE_PREFIX + welinkSessionId;
            Long result = redisTemplate.execute(
                    casScript,
                    List.of(key),
                    deadInstanceId,
                    newInstanceId,
                    String.valueOf(ownershipCacheTtlSeconds));
            if (result != null && result == 1L) {
                log.info("Takeover succeeded: welinkSessionId={}, from={}, to={}",
                        welinkSessionId, deadInstanceId, newInstanceId);
                return true;
            } else {
                log.info("Takeover conflict: welinkSessionId={}, deadInstance={}, result={}",
                        welinkSessionId, deadInstanceId, result);
                return false;
            }
        } catch (Exception e) {
            log.error("tryTakeover Redis Lua CAS failed: welinkSessionId={}, error={}",
                    welinkSessionId, e.getMessage(), e);
            return false;
        }
    }

    // ==================== Query (deprecated MySQL-based) ====================

    /**
     * @deprecated Use SkillSessionService.findByToolSessionId() instead.
     */
    @Deprecated
    public SessionRoute findByToolSessionId(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return null;
        }
        return repository.findByToolSessionId(toolSessionId);
    }

    /**
     * @deprecated No longer needed in pure Redis mode. Ownership is in Redis.
     */
    @Deprecated
    public SessionRoute findByWelinkSessionId(Long welinkSessionId) {
        if (welinkSessionId == null) {
            return null;
        }
        return repository.findByWelinkSessionId(welinkSessionId);
    }

    // ==================== Lifecycle (deprecated MySQL-based) ====================

    /**
     * @deprecated No longer needed. Replaced by message-driven lazy takeover.
     */
    @Deprecated
    public void takeoverActiveRoutes(String ak) {
        log.info("(deprecated) takeoverActiveRoutes called but skipped: ak={}", ak);
    }

    /**
     * @deprecated No longer needed. Ownership relies on TTL expiry.
     *             Cannot scan Redis for "all keys owned by this instance" without reverse index.
     */
    @Deprecated
    public void closeAllByInstance() {
        log.info("(deprecated) closeAllByInstance called but skipped: instanceId={}. Ownership relies on TTL expiry.",
                instanceId);
    }

    // ==================== Cleanup (deprecated MySQL-based) ====================

    /**
     * @deprecated No longer needed. Redis TTL handles ownership expiry automatically.
     */
    @Deprecated
    public void cleanupStaleRoutes(int activeTimeoutHours, int closedRetentionDays) {
        log.info("(deprecated) cleanupStaleRoutes called but skipped. Redis TTL handles expiry.");
    }

    public String getInstanceId() {
        return instanceId;
    }

    // ==================== Redis helpers ====================

    /**
     * Writes session ownership to Redis with configured TTL.
     * Failures are non-fatal: logs WARN and continues.
     */
    private void writeCacheOwnership(String welinkSessionId, String owner) {
        try {
            redisTemplate.opsForValue().set(
                    SESSION_CACHE_PREFIX + welinkSessionId,
                    owner,
                    Duration.ofSeconds(ownershipCacheTtlSeconds));
            log.info("Cached session ownership: welinkSessionId={}, owner={}, ttl={}s",
                    welinkSessionId, owner, ownershipCacheTtlSeconds);
        } catch (Exception e) {
            log.warn("Failed to write session ownership cache: welinkSessionId={}, error={}",
                    welinkSessionId, e.getMessage());
        }
    }

    /**
     * Deletes session ownership from Redis.
     * Failures are non-fatal: logs WARN and continues.
     */
    private void deleteCacheOwnership(String welinkSessionId) {
        try {
            redisTemplate.delete(SESSION_CACHE_PREFIX + welinkSessionId);
            log.info("Deleted session ownership cache: welinkSessionId={}", welinkSessionId);
        } catch (Exception e) {
            log.warn("Failed to delete session ownership cache: welinkSessionId={}, error={}",
                    welinkSessionId, e.getMessage());
        }
    }
}
