package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SessionRoute;
import com.opencode.cui.skill.repository.SessionRouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 会话级路由服务。
 *
 * 管理 session_route 表的 CRUD，提供 ownership 检查用于广播降级时的过滤。
 * 包含生命周期管理（启动接管、优雅关闭）和数据清理功能。
 *
 * Redis cache layer: ss:internal:session:{welinkSessionId} → instanceId
 * Read: check Redis first, fallback to MySQL and backfill on miss.
 * Write: MySQL first, then Redis. Redis failures are non-fatal (WARN + continue).
 */
@Slf4j
@Service
public class SessionRouteService {

    private static final String SESSION_CACHE_PREFIX = "ss:internal:session:";

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
    }

    // ==================== CRUD ====================

    /**
     * 创建路由记录。会话创建时调用。
     * toolSessionId 此时为 null，等 session_created 回来后由 updateToolSessionId 回填。
     * 并发防护：重复插入时记录警告但不抛异常。
     * After successful MySQL insert, writes ownership to Redis cache.
     */
    public void createRoute(String ak, Long welinkSessionId, String sourceType, String userId) {
        SessionRoute route = SessionRoute.builder()
                .id(welinkSessionId) // 用 welinkSessionId 作为主键（Snowflake ID）
                .ak(ak)
                .welinkSessionId(welinkSessionId)
                .sourceType(sourceType)
                .sourceInstance(instanceId)
                .userId(userId)
                .status("ACTIVE")
                .build();
        try {
            repository.insert(route);
            log.info("Created session route: ak={}, welinkSessionId={}, sourceType={}, sourceInstance={}",
                    ak, welinkSessionId, sourceType, instanceId);
        } catch (DuplicateKeyException e) {
            log.warn("路由记录已存在，跳过插入: ak={}, welinkSessionId={}, sourceType={}",
                    ak, welinkSessionId, sourceType);
            return;
        }
        // Write ownership to Redis after successful MySQL insert
        writeCacheOwnership(welinkSessionId.toString(), instanceId);
    }

    /**
     * 回填 toolSessionId。session_created 事件到达时调用。
     */
    public void updateToolSessionId(Long welinkSessionId, String sourceType, String toolSessionId) {
        repository.updateToolSessionId(welinkSessionId, sourceType, toolSessionId);
        log.info("Updated toolSessionId: welinkSessionId={}, toolSessionId={}", welinkSessionId, toolSessionId);
    }

    /**
     * 关闭路由。会话关闭时调用。
     * Deletes Redis cache entry after MySQL update.
     */
    public void closeRoute(Long welinkSessionId, String sourceType) {
        repository.updateStatus(welinkSessionId, sourceType, "CLOSED");
        log.info("Closed session route: welinkSessionId={}, sourceType={}", welinkSessionId, sourceType);
        // Remove ownership from Redis after route is closed
        deleteCacheOwnership(welinkSessionId.toString());
    }

    // ==================== Ownership 检查 ====================

    /**
     * 检查指定 welinkSessionId 的会话是否属于本实例。
     * 广播降级时由 GatewayMessageRouter 调用。
     * DB 异常时降级为 true（"不确定就处理"策略）。
     */
    public boolean isMySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            Long numericId = Long.parseLong(sessionId);
            SessionRoute route = repository.findByWelinkSessionId(numericId);
            return route != null && instanceId.equals(route.getSourceInstance());
        } catch (NumberFormatException e) {
            return false;
        } catch (Exception e) {
            log.warn("isMySession 查询失败，降级为处理: sessionId={}, error={}", sessionId, e.getMessage());
            return true;
        }
    }

    /**
     * 确保路由 ownership：查路由 → 存在则判 ownership → 不存在则 auto-claim。
     * <p>
     * 用于存量会话迁移：旧会话在 session_route 中没有记录时，
     * 第一个处理到该消息的实例会自动创建路由记录并获得 ownership。
     * <p>
     * DB 异常时降级为 true（"不确定就处理"策略）。
     *
     * @param sessionId welinkSessionId（字符串形式）
     * @param ak        Agent Access Key
     * @param userId    会话所有者
     * @return true = 本实例应处理该消息；false = 不属于本实例
     */
    public boolean ensureRouteOwnership(String sessionId, String ak, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            Long numericId = Long.parseLong(sessionId);
            SessionRoute route = repository.findByWelinkSessionId(numericId);

            if (route != null) {
                // 路由存在，直接判 ownership
                return instanceId.equals(route.getSourceInstance());
            }

            // 路由不存在 → auto-claim：创建路由抢占 ownership
            log.info("路由记录不存在，auto-claim: sessionId={}, ak={}, sourceInstance={}",
                    sessionId, ak, instanceId);
            createRoute(ak, numericId, "skill-server", userId);
            // createRoute already writes Redis cache on success

            // 创建成功（无 DuplicateKey），本实例获得 ownership
            return true;
        } catch (NumberFormatException e) {
            return false;
        } catch (DuplicateKeyException e) {
            // 其他实例已抢先创建 → 重新查询判断 ownership
            try {
                Long numericId = Long.parseLong(sessionId);
                SessionRoute route = repository.findByWelinkSessionId(numericId);
                return route != null && instanceId.equals(route.getSourceInstance());
            } catch (Exception ex) {
                log.warn("auto-claim 后重查失败，降级为处理: sessionId={}, error={}",
                        sessionId, ex.getMessage());
                return true;
            }
        } catch (Exception e) {
            log.warn("ensureRouteOwnership 异常，降级为处理: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return true;
        }
    }


    /**
     * 检查指定 toolSessionId 的会话是否属于本实例。
     * DB 异常时降级为 true（"不确定就处理"策略）。
     */
    public boolean isMyToolSession(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return false;
        }
        try {
            SessionRoute route = repository.findByToolSessionId(toolSessionId);
            return route != null && instanceId.equals(route.getSourceInstance());
        } catch (Exception e) {
            log.warn("isMyToolSession 查询失败，降级为处理: toolSessionId={}, error={}", toolSessionId, e.getMessage());
            return true;
        }
    }

    // ==================== 路由发现（relay 路由使用） ====================

    /**
     * Returns the owning instance ID for the given welinkSessionId.
     * Read strategy: Redis first → MySQL fallback with cache backfill.
     * Returns null if no ACTIVE route exists.
     *
     * @param welinkSessionId the session ID (string form)
     * @return owning instanceId, or null if not found
     */
    public String getOwnerInstance(String welinkSessionId) {
        if (welinkSessionId == null || welinkSessionId.isBlank()) {
            return null;
        }
        // 1. Check Redis cache first
        try {
            String cached = redisTemplate.opsForValue().get(SESSION_CACHE_PREFIX + welinkSessionId);
            if (cached != null) {
                log.info("getOwnerInstance cache hit: welinkSessionId={}, owner={}", welinkSessionId, cached);
                return cached;
            }
        } catch (Exception e) {
            log.warn("getOwnerInstance Redis read failed, falling back to MySQL: welinkSessionId={}, error={}",
                    welinkSessionId, e.getMessage());
        }

        // 2. Redis miss → query MySQL
        try {
            Long numericId = Long.parseLong(welinkSessionId);
            SessionRoute route = repository.findByWelinkSessionId(numericId);
            if (route != null && "ACTIVE".equals(route.getStatus())) {
                String owner = route.getSourceInstance();
                log.info("getOwnerInstance MySQL hit: welinkSessionId={}, owner={}", welinkSessionId, owner);
                // 3. Backfill Redis cache
                writeCacheOwnership(welinkSessionId, owner);
                return owner;
            }
            log.info("getOwnerInstance no active route: welinkSessionId={}", welinkSessionId);
            return null;
        } catch (NumberFormatException e) {
            log.warn("getOwnerInstance invalid welinkSessionId format: {}", welinkSessionId);
            return null;
        } catch (Exception e) {
            log.warn("getOwnerInstance MySQL query failed: welinkSessionId={}, error={}",
                    welinkSessionId, e.getMessage());
            return null;
        }
    }

    // ==================== Takeover (Task 2.6) ====================

    /**
     * Attempts an optimistic-lock takeover of a session owned by a dead instance.
     * Uses MySQL CAS: UPDATE ... SET source_instance = new WHERE source_instance = dead.
     *
     * <p>On success, updates the Redis ownership cache to the new instance.
     *
     * @param welinkSessionId the session to take over (string form)
     * @param deadInstanceId  the expected current owner (will be used as CAS condition)
     * @param newInstanceId   the new owner instance ID
     * @return true if this instance won the takeover; false if another instance won first
     */
    public boolean tryTakeover(String welinkSessionId, String deadInstanceId, String newInstanceId) {
        if (welinkSessionId == null || welinkSessionId.isBlank()) {
            return false;
        }
        try {
            Long numericId = Long.parseLong(welinkSessionId);
            int affected = repository.tryTakeover(numericId, deadInstanceId, newInstanceId);
            if (affected == 1) {
                log.info("Takeover succeeded: welinkSessionId={}, from={}, to={}",
                        welinkSessionId, deadInstanceId, newInstanceId);
                // Update Redis ownership cache to reflect new owner
                writeCacheOwnership(welinkSessionId, newInstanceId);
                return true;
            } else {
                log.info("Takeover conflict: welinkSessionId={}, deadInstance={}, affected=0",
                        welinkSessionId, deadInstanceId);
                return false;
            }
        } catch (NumberFormatException e) {
            log.warn("tryTakeover invalid welinkSessionId format: {}", welinkSessionId);
            return false;
        } catch (Exception e) {
            log.error("tryTakeover failed: welinkSessionId={}, error={}", welinkSessionId, e.getMessage(), e);
            return false;
        }
    }

    // ==================== 查询 ====================

    /**
     * 根据 toolSessionId 查询路由记录。
     */
    public SessionRoute findByToolSessionId(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return null;
        }
        return repository.findByToolSessionId(toolSessionId);
    }

    /**
     * 根据 welinkSessionId 查询路由记录。
     */
    public SessionRoute findByWelinkSessionId(Long welinkSessionId) {
        if (welinkSessionId == null) {
            return null;
        }
        return repository.findByWelinkSessionId(welinkSessionId);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动接管：将指定 AK 下所有 ACTIVE 路由的 sourceInstance 更新为当前实例。
     * 解决 Pod 重启后 instanceId 变化导致 ownership 失效的问题。
     */
    public void takeoverActiveRoutes(String ak) {
        int updated = repository.takeoverByAk(ak, instanceId);
        if (updated > 0) {
            log.info("接管路由记录: ak={}, count={}, newSourceInstance={}", ak, updated, instanceId);
        }
    }

    /**
     * 优雅关闭：关闭当前实例所有 ACTIVE 路由。
     * 用于 @PreDestroy，确保缩容/滚动更新时不留孤儿记录。
     */
    public void closeAllByInstance() {
        int closed = repository.closeAllBySourceInstance(instanceId);
        log.info("优雅关闭路由记录: sourceInstance={}, count={}", instanceId, closed);
    }

    // ==================== 数据清理 ====================

    /**
     * 清理过期路由数据。
     *
     * @param activeTimeoutHours  ACTIVE 记录超过多少小时未更新视为僵尸，将被关闭
     * @param closedRetentionDays CLOSED 记录保留多少天，超期将被删除
     */
    public void cleanupStaleRoutes(int activeTimeoutHours, int closedRetentionDays) {
        LocalDateTime activeCutoff = LocalDateTime.now().minusHours(activeTimeoutHours);
        int closedZombies = repository.closeStaleActiveRoutes(activeCutoff);
        if (closedZombies > 0) {
            log.info("清理僵尸 ACTIVE 路由: count={}, cutoff={}", closedZombies, activeCutoff);
        }

        LocalDateTime closedCutoff = LocalDateTime.now().minusDays(closedRetentionDays);
        int purged = repository.purgeClosedBefore(closedCutoff);
        if (purged > 0) {
            log.info("清理历史 CLOSED 路由: count={}, cutoff={}", purged, closedCutoff);
        }
    }

    public String getInstanceId() {
        return instanceId;
    }

    // ==================== Redis cache helpers ====================

    /**
     * Writes session ownership to Redis cache with configured TTL.
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
     * Deletes session ownership from Redis cache.
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
