package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SessionListQuery;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.UnreadSessionItem;
import com.opencode.cui.skill.model.event.ReadReportedEvent;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理服务。
 * 提供会话的创建、查询、关闭、激活、状态转换等操作，
 * 支持分页查询、备用多条件筛选、及定时空闲清理。
 *
 * <p>
 * 会话状态转换：ACTIVE → IDLE（超时）→ CLOSED（手动）。
 * </p>
 */
@Slf4j
@Service
public class SkillSessionService {

    private final SkillSessionRepository sessionRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final SessionRouteService sessionRouteService;
    private final RedisMessageBroker redisMessageBroker;
    private final UnreadRedisService unreadRedisService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Value("${skill.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    public SkillSessionService(SkillSessionRepository sessionRepository,
            SnowflakeIdGenerator snowflakeIdGenerator,
            SessionRouteService sessionRouteService,
            RedisMessageBroker redisMessageBroker,
            UnreadRedisService unreadRedisService,
            ApplicationEventPublisher applicationEventPublisher) {
        this.sessionRepository = sessionRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.sessionRouteService = sessionRouteService;
        this.redisMessageBroker = redisMessageBroker;
        this.unreadRedisService = unreadRedisService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /** 创建新的 Skill 会话。 */
    @Transactional
    public SkillSession createSession(String userId, String ak,
            String title,
            String businessDomain,
            String sessionType,
            String sessionId,
            String assistantAccount) {
        SkillSession session = SkillSession.builder()
                .id(snowflakeIdGenerator.nextId())
                .userId(userId)
                .ak(ak)
                .title(title)
                .businessSessionDomain(
                        businessDomain != null && !businessDomain.isBlank()
                                ? businessDomain
                                : SkillSession.DOMAIN_MINIAPP)
                .businessSessionType(sessionType)
                .businessSessionId(sessionId)
                .assistantAccount(assistantAccount)
                .status(SkillSession.Status.ACTIVE)
                .build();

        sessionRepository.insert(session);

        // 同步写入路由表
        if (ak != null) {
            sessionRouteService.createRoute(ak, session.getId(), "skill-server", userId);
        }

        log.info("Created skill session: id={}, userId={}, ak={}, domain={}, bizSessionId={}",
                session.getId(), userId, ak, session.getBusinessSessionDomain(), session.getBusinessSessionId());
        return session;
    }

    /**
     * 默认助手单事务创建会话（PR3 收口；用于 D1 优先级矩阵中"规则命中"分支）。
     *
     * <p>与 {@link #createSession} + {@link #updateToolSessionId} 两次调用相比，
     * 本方法在<b>同一个事务内</b>完成 3 件事：
     * <ol>
     *   <li>INSERT skill_session（ak / assistantAccount / toolSessionId / 业务字段 / status=ACTIVE）</li>
     *   <li>createRoute 写 session ownership（与 {@link #createSession} 同款副作用）</li>
     *   <li>Redis 写 {@code toolSessionId → sessionId} 映射（复用 {@code setToolSessionMapping}）</li>
     * </ol>
     * 避免老的两步路径中存在的"session 有 ak 但无 toolSessionId（且无 Redis 映射）"中间窗口。
     *
     * <p><b>Redis 失败宽松策略（PRD D6 拍板）</b>：Redis 写抛异常 → log WARN，
     * MySQL 事务<b>仍提交</b>，方法仍返回 session。极小窗口内 GW 上行事件
     * 按 toolSessionId 反查 sessionId 可能找不到映射 → 这条消息被丢弃；
     * 但 DB session 已就绪，后续请求走 controller 入口的规则反查仍能恢复。
     *
     * <p>老 {@code createSession + updateToolSessionId} 两步路径保留不动，
     * 给 personal scope / 显式 business ak 使用。
     *
     * @return 从 DB 重读的最新 session（避免依赖 ORM 一级缓存返回旧版本）
     */
    @Transactional
    public SkillSession createSessionWithDefaultAssistant(String userId, String ak, String assistantAccount,
            String title,
            String businessDomain,
            String businessType,
            String businessSessionId,
            String toolSessionId) {
        SkillSession session = SkillSession.builder()
                .id(snowflakeIdGenerator.nextId())
                .userId(userId)
                .ak(ak)
                .assistantAccount(assistantAccount)
                .toolSessionId(toolSessionId)
                .title(title)
                .businessSessionDomain(
                        businessDomain != null && !businessDomain.isBlank()
                                ? businessDomain
                                : SkillSession.DOMAIN_MINIAPP)
                .businessSessionType(businessType)
                .businessSessionId(businessSessionId)
                .status(SkillSession.Status.ACTIVE)
                .build();

        sessionRepository.insert(session);

        // 同步写入路由表（与现有 createSession 同款副作用）
        if (ak != null) {
            sessionRouteService.createRoute(ak, session.getId(), "skill-server", userId);
        }

        // Redis 写 toolSessionId → sessionId 映射；失败宽松：log WARN，事务仍提交
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            try {
                redisMessageBroker.setToolSessionMapping(toolSessionId, session.getId().toString());
            } catch (Exception e) {
                log.warn("[createSession] Redis mapping failed for toolSessionId={}, sessionId={}: {}",
                        toolSessionId, session.getId(), e.getMessage());
            }
        }

        log.info("Created default-assistant skill session: id={}, userId={}, ak={}, assistantAccount={}, "
                + "domain={}, type={}, toolSessionId={}",
                session.getId(), userId, ak, assistantAccount,
                session.getBusinessSessionDomain(), session.getBusinessSessionType(), toolSessionId);

        // from-DB 重读，避免 ORM 二级缓存返回旧版本
        return sessionRepository.findById(session.getId());
    }

    @Transactional(readOnly = true)
    public SkillSession findByIdSafe(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionRepository.findById(sessionId);
    }

    @Transactional(readOnly = true)
    public SkillSession findByBusinessSession(String businessDomain, String sessionType, String sessionId, String ak) {
        return findByBusinessSession(businessDomain, sessionType, sessionId, ak, null);
    }

    @Transactional(readOnly = true)
    public SkillSession findByBusinessSession(String businessDomain, String sessionType, String sessionId,
                                              String ak, String assistantAccount) {
        if (businessDomain == null || businessDomain.isBlank()
                || sessionType == null || sessionType.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (ak != null && !ak.isBlank()
                && assistantAccount != null && !assistantAccount.isBlank()) {
            return sessionRepository.findByBusinessSessionAndAkAndAssistantAccount(
                    businessDomain, sessionType, sessionId, ak, assistantAccount);
        }
        if (ak != null && !ak.isBlank()) {
            return sessionRepository.findByBusinessSession(businessDomain, sessionType, sessionId, ak);
        }
        if (assistantAccount != null && !assistantAccount.isBlank()) {
            return sessionRepository.findByBusinessSessionAndAssistantAccount(
                    businessDomain, sessionType, sessionId, assistantAccount);
        }
        return null;
    }

    @Transactional
    public SkillSession createSessionSafely(String userId, String ak,
            String title,
            String businessDomain,
            String sessionType,
            String sessionId,
            String assistantAccount) {
        SkillSession existing = findByBusinessSession(businessDomain, sessionType, sessionId, ak);
        if (existing != null) {
            return existing;
        }
        return createSession(userId, ak, title, businessDomain, sessionType, sessionId, assistantAccount);
    }

    /** 分页查询用户的会话列表，支持可选筛选条件。无状态筛选时默认排除 CLOSED 会话。 */
    @Transactional(readOnly = true)
    public PageResult<SkillSession> listSessions(SessionListQuery query) {
        int offset = query.page() * query.size();
        List<String> statusNames = null;
        if (query.status() != null && !query.status().isBlank()) {
            statusNames = List.of(query.status());
        }
        boolean hasFilters = (query.ak() != null && !query.ak().isBlank())
                || (query.businessSessionDomain() != null && !query.businessSessionDomain().isBlank())
                || (query.businessSessionType() != null && !query.businessSessionType().isBlank())
                || (query.businessSessionId() != null && !query.businessSessionId().isBlank())
                || (query.assistantAccount() != null && !query.assistantAccount().isBlank())
                || statusNames != null;
        if (hasFilters) {
            List<SkillSession> content = sessionRepository.findByUserIdFiltered(
                    query.userId(), query.ak(),
                    query.businessSessionDomain(), query.businessSessionType(),
                    query.businessSessionId(), query.assistantAccount(),
                    statusNames, offset, query.size());
            long total = sessionRepository.countByUserIdFiltered(
                    query.userId(), query.ak(),
                    query.businessSessionDomain(), query.businessSessionType(),
                    query.businessSessionId(), query.assistantAccount(),
                    statusNames);
            return new PageResult<>(content, total, query.page(), query.size());
        }
        // 无任何筛选条件时，默认排除 CLOSED 会话
        List<String> defaultStatuses = List.of(
                SkillSession.Status.ACTIVE.name(), SkillSession.Status.IDLE.name());
        List<SkillSession> content = sessionRepository.findByUserIdAndStatusIn(
                query.userId(), defaultStatuses, offset, query.size());
        long total = sessionRepository.countByUserIdAndStatusIn(query.userId(), defaultStatuses);
        return new PageResult<>(content, total, query.page(), query.size());
    }

    /** 按 ID 获取单个会话，不存在则抛异常。 */
    @Transactional(readOnly = true)
    public SkillSession getSession(Long sessionId) {
        SkillSession session = findByIdSafe(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return session;
    }

    /**
     * 查询用户的所有 ACTIVE 会话。
     * 用于协议层 stream 端点连接时恢复所有活跃会话。
     */
    @Transactional(readOnly = true)
    public List<SkillSession> findActiveByUserId(String userId) {
        return sessionRepository.findActiveByUserId(userId);
    }

    /** 关闭会话（设置状态为 CLOSED）。 */
    @Transactional
    public SkillSession closeSession(Long sessionId) {
        sessionRepository.updateStatus(sessionId, SkillSession.Status.CLOSED.name());
        SkillSession session = getSession(sessionId);

        // 同步关闭路由记录
        sessionRouteService.closeRoute(sessionId, "skill-server");

        log.info("Closed skill session: id={}", sessionId);
        return session;
    }

    /** Mark an open session as IDLE after its current assistant turn is terminal. */
    @Transactional
    public boolean markSessionIdle(Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        int updated = sessionRepository.markIdle(sessionId);
        if (updated > 0) {
            log.info("Marked session IDLE: id={}", sessionId);
            return true;
        }
        return false;
    }

    @Transactional
    public boolean updateTitle(Long sessionId, String title) {
        if (sessionId == null || title == null || title.isBlank()) {
            return false;
        }
        int updated = sessionRepository.updateTitle(sessionId, title);
        if (updated > 0) {
            log.info("Updated session title: id={}, title={}", sessionId, title);
            return true;
        }
        return false;
    }

    /**
     * 激活 IDLE 会话（IDLE → ACTIVE）。
     * 当 IDLE 会话收到成功的 tool_event 时调用。
     */
    @Transactional
    public boolean activateSession(Long sessionId) {
        int updated = sessionRepository.activateSession(sessionId);
        if (updated > 0) {
            log.info("Activated session: id={} (IDLE -> ACTIVE)", sessionId);
            return true;
        }
        return false;
    }

    /** 清除会话的 toolSessionId（工具会话失效时调用）。 */
    @Transactional
    public void clearToolSessionId(Long sessionId) {
        sessionRepository.clearToolSessionId(sessionId);
        log.info("Cleared toolSessionId for session: id={}", sessionId);
    }

    /** 刷新会话的 last_active_at 时间戳。 */
    @Transactional
    public void touchSession(Long sessionId) {
        sessionRepository.updateLastActiveAt(sessionId, LocalDateTime.now());
    }

    /**
     * 更新会话的 tool_session_id（OpenCode 会话创建时设置）。
     *
     * <p>Remap 时同步失效旧的 Redis 反查缓存（{@code ss:tool-session:*}，TTL 24h），
     * 并写入新映射，避免旧 toolSessionId 在 TTL 窗口内被复用时错误路由。</p>
     */
    @Transactional
    public SkillSession updateToolSessionId(Long sessionId, String toolSessionId) {
        SkillSession existing = sessionRepository.findById(sessionId);
        String oldToolSessionId = existing != null ? existing.getToolSessionId() : null;

        sessionRepository.updateToolSessionId(sessionId, toolSessionId, LocalDateTime.now());

        if (oldToolSessionId != null && !oldToolSessionId.equals(toolSessionId)) {
            log.info("toolSessionId remap detected: sessionId={}, old={}, new={}, invalidating Redis cache",
                    sessionId, oldToolSessionId, toolSessionId);
            redisMessageBroker.deleteToolSessionMapping(oldToolSessionId);
        }
        if (toolSessionId != null) {
            redisMessageBroker.setToolSessionMapping(toolSessionId, sessionId.toString());
        }

        return getSession(sessionId);
    }

    /** 按 AK 查询所有会话。 */
    @Transactional(readOnly = true)
    public List<SkillSession> findByAk(String ak) {
        return sessionRepository.findByAk(ak);
    }

    /** 按 AK 查询活跃会话（ACTIVE 或 IDLE）。用于 agent_online/offline 广播。 */
    @Transactional(readOnly = true)
    public List<SkillSession> findActiveByAk(String ak) {
        return sessionRepository.findActiveByAk(ak);
    }

    /**
     * 按 OpenCode 工具会话 ID 查询会话。
     * 用于将 Gateway 上行消息路由到正确的 welink 会话。
     */
    @Transactional(readOnly = true)
    public SkillSession findByToolSessionId(String toolSessionId) {
        return sessionRepository.findByToolSessionId(toolSessionId);
    }

    /** 按状态查询会话（启动时用于 Redis 频道恢复）。 */
    @Transactional(readOnly = true)
    public List<SkillSession> findByStatus(SkillSession.Status status) {
        return sessionRepository.findByStatus(status.name());
    }

    /**
     * 定时清理：将超过空闲超时时间的 ACTIVE 会话标记为 IDLE。
     */
    @Scheduled(fixedDelayString = "${skill.session.cleanup-interval-minutes:10}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupIdleSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);

        // 查找将要标记为 IDLE 的会话
        List<Long> idleSessionIds = sessionRepository.findIdleSessionIds(cutoff);

        if (idleSessionIds.isEmpty()) {
            return;
        }

        // 在数据库中标记为 IDLE
        int count = sessionRepository.markIdleSessions(SkillSession.Status.IDLE.name(), cutoff);
        log.info("Marked {} sessions as IDLE (inactive since before {})", count, cutoff);

        // Note: session_route MySQL cleanup removed — ownership now uses Redis TTL expiry.
    }

    /**
     * Get unread state for one or more sessions from the Redis Hash.
     * If {@code sessionIds} is null or empty, returns all unread sessions via HGETALL.
     * Otherwise, returns requested sessions via HMGET (only fields with values are included).
     */
    public List<UnreadSessionItem> getUnreadSessions(String userId, String assistantAccount,
            List<String> sessionIds) {
        return unreadRedisService.getUnread(userId, assistantAccount, sessionIds);
    }

    /**
     * Process a read report from the frontend.
     * Atomically compares {@code readSeq} against the stored maxSeq via Lua.
     * If the session is fully read (or was never tracked), publishes a
     * {@code ReadReportedEvent} so other devices can clear the badge.
     * Domain-whitelist gating is handled in {@code UnreadManageListener.onToolDone},
     * not here — this method is a pure delegation layer.
     */
    public void reportRead(Long sessionId, int readSeq, String userId) {
        SkillSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            log.warn("reportRead: session not found sessionId={}", sessionId);
            return;
        }

        String assistantAccount = session.getAssistantAccount();
        Long result = unreadRedisService.markRead(userId, assistantAccount,
                String.valueOf(sessionId), readSeq);

        if (result != null && result == -1) {
            throw new ProtocolException(400,
                    "readSeq " + readSeq + " exceeds maxSeq for sessionId=" + sessionId);
        }

        if (result != null && result == 1) {
            applicationEventPublisher.publishEvent(
                    new ReadReportedEvent(sessionId, readSeq, userId, assistantAccount));
            log.debug("reportRead: markRead=1, published ReadReportedEvent sessionId={} readSeq={}",
                    sessionId, readSeq);
        }
    }

    /**
     * 硬删除会话主表记录（主流程同步部分）。
     * 关联的 message/part 数据由异步任务后续清理。
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        sessionRepository.deleteById(sessionId);
        sessionRouteService.closeRoute(sessionId, "skill-server");
        log.info("Deleted skill session: id={}", sessionId);
    }
}
