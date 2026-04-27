package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SessionListQuery;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${skill.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    public SkillSessionService(SkillSessionRepository sessionRepository,
            SnowflakeIdGenerator snowflakeIdGenerator,
            SessionRouteService sessionRouteService,
            RedisMessageBroker redisMessageBroker) {
        this.sessionRepository = sessionRepository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.sessionRouteService = sessionRouteService;
        this.redisMessageBroker = redisMessageBroker;
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

    @Transactional(readOnly = true)
    public SkillSession findByIdSafe(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionRepository.findById(sessionId);
    }

    @Transactional(readOnly = true)
    public SkillSession findByBusinessSession(String businessDomain, String sessionType, String sessionId, String ak) {
        if (businessDomain == null || businessDomain.isBlank()
                || sessionType == null || sessionType.isBlank()
                || sessionId == null || sessionId.isBlank()
                || ak == null || ak.isBlank()) {
            return null;
        }
        return sessionRepository.findByBusinessSession(businessDomain, sessionType, sessionId, ak);
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

    /** 分页查询用户的会话列表，支持可选筛选条件。 */
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
        List<SkillSession> content = sessionRepository.findByUserId(query.userId(), offset, query.size());
        long total = sessionRepository.countByUserId(query.userId());
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

    /** 当会话标题发生变化时，更新为 AI 生成的新标题。 */
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
}
