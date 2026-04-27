package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IM 会话管理器。
 * 负责 SkillSession 的查找、创建和 toolSession 的就绪保障。
 *
 * 核心机制：
 * - 使用 Redis 分布式锁防止并发重复创建会话
 * - 异步创建模式（createSessionAsync）：存储待发消息，Gateway 就绪后自动重发
 * - 同步创建模式（findOrCreateSession）：阻塞等待 toolSessionId 就绪
 */
@Slf4j
@Service
public class ImSessionManager {

    private static final long CREATE_LOCK_RETRY_MILLIS = 100L; // 获取锁失败后的重试间隔
    private static final Duration CREATE_LOCK_TTL = Duration.ofSeconds(15); // 分布式锁过期时间

    private final SkillSessionService sessionService; // 会话 CRUD 服务
    private final GatewayRelayService gatewayRelayService; // Gateway 通信服务
    private final SessionRebuildService rebuildService; // toolSession 重建服务
    private final StringRedisTemplate redisTemplate; // Redis 操作模板（用于分布式锁）
    private final ObjectMapper objectMapper; // JSON 序列化
    private final AssistantInfoService assistantInfoService; // 助手信息查询服务
    private final AssistantScopeDispatcher scopeDispatcher; // 助手作用域调度器
    private final int autoCreateTimeoutSeconds; // 同步创建模式的超时秒数

    public ImSessionManager(
            SkillSessionService sessionService,
            GatewayRelayService gatewayRelayService,
            SessionRebuildService rebuildService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AssistantInfoService assistantInfoService,
            AssistantScopeDispatcher scopeDispatcher,
            @org.springframework.beans.factory.annotation.Value("${skill.session.auto-create-timeout-seconds:30}") int autoCreateTimeoutSeconds) {
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.rebuildService = rebuildService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.autoCreateTimeoutSeconds = autoCreateTimeoutSeconds;
    }

    /**
     * 非阻塞查找会话。
     * 根据业务域、会话类型、会话 ID 和 ak 查找已存在的 SkillSession。
     * 找到时同步刷新活跃时间。
     *
     * @return 已存在的 session，不存在返回 null
     */
    public SkillSession findSession(String businessDomain, String sessionType,
            String sessionId, String ak) {
        log.info("Looking up session: domain={}, sessionType={}, sessionId={}, ak={}",
                businessDomain, sessionType, sessionId, ak);
        SkillSession existing = sessionService.findByBusinessSession(businessDomain, sessionType, sessionId, ak);
        if (existing != null) {
            log.info("Session found: skillSessionId={}, toolSessionId={}",
                    existing.getId(), existing.getToolSessionId());
            sessionService.touchSession(existing.getId());
        } else {
            log.info("No session found: domain={}, sessionType={}, sessionId={}, ak={}",
                    businessDomain, sessionType, sessionId, ak);
        }
        return existing;
    }

    /**
     * 异步创建会话并请求 Gateway 建立 toolSession。
     * 步骤：
     * 1. 获取 Redis 分布式锁（防止并发创建）
     * 2. 二次检查 session 是否已存在
     * 3. 在 DB 创建 SkillSession
     * 4. 通过 rebuildService 发送 create_session 到 Gateway，并缓存待发消息
     * → Gateway 回复 session_created 后，{@link GatewayMessageRouter} 自动重发待发消息
     *
     * @param pendingMessage 待发送的用户消息（会缓存到 Redis，session 就绪后自动发送）
     */
    public void createSessionAsync(String businessDomain, String sessionType,
            String sessionId, String ak, String ownerWelinkId,
            String assistantAccount, String senderUserAccount,
            String pendingMessage,
            JsonNode businessExtParam) {
        // 构建分布式锁 key
        String lockKey = buildCreateLockKey(businessDomain, sessionType, sessionId, ak);
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;

        try {
            // 尝试获取 Redis 分布式锁
            locked = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, CREATE_LOCK_TTL));
            if (!locked) {
                log.info("Session creation already in progress for sessionId={}, skipping", sessionId);
                return; // 其他实例正在创建，直接跳过
            }
            log.info("Acquired creation lock: sessionId={}, ak={}", sessionId, ak);

            // 二次检查：在获取锁后再查一次，避免重复创建
            SkillSession existing = sessionService.findByBusinessSession(
                    businessDomain, sessionType, sessionId, ak);
            if (existing != null) {
                log.info("Session already exists during async creation: skillSessionId={}, requesting toolSession",
                        existing.getId());
                sessionService.touchSession(existing.getId());
                requestToolSession(existing, pendingMessage); // session 存在但可能缺少 toolSession
                return;
            }

            // 创建新的 SkillSession 记录
            SkillSession created = sessionService.createSession(
                    ownerWelinkId, // 用户 ID = 助手拥有者的 WeLink ID
                    ak, // 应用密钥
                    buildTitle(businessDomain, sessionType, sessionId),
                    businessDomain,
                    sessionType,
                    sessionId,
                    assistantAccount);
            log.info("Session created: skillSessionId={}, userId={}, ak={}, sessionId={}",
                    created.getId(), ownerWelinkId, ak, sessionId);

            // 根据助手类型决定 toolSession 创建方式
            String scope = assistantInfoService.getCachedScope(ak);
            AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(scope);
            String generatedToolSessionId = strategy.generateToolSessionId();
            if (generatedToolSessionId != null) {
                // 业务助手：本地预生成 toolSessionId，跳过 Gateway create_session
                sessionService.updateToolSessionId(created.getId(), generatedToolSessionId);
                log.info("Business assistant: toolSessionId pre-generated locally, skillSessionId={}, toolSessionId={}, ak={}",
                        created.getId(), generatedToolSessionId, ak);
                // 会话直接就绪，立即发送待发消息（不经过 session_created 回调）
                if (pendingMessage != null && !pendingMessage.isBlank()) {
                    Map<String, Object> payloadFields = new LinkedHashMap<>();
                    payloadFields.put("text", pendingMessage);
                    payloadFields.put("toolSessionId", generatedToolSessionId);
                    payloadFields.put("assistantAccount", assistantAccount);
                    String effectiveSender = "group".equals(sessionType)
                            && senderUserAccount != null && !senderUserAccount.isBlank()
                            ? senderUserAccount : ownerWelinkId;
                    payloadFields.put("sendUserAccount", effectiveSender);
                    payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : null);
                    payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
                    payloadFields.put("businessExtParam", businessExtParam);
                    gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                            ak,
                            ownerWelinkId,
                            String.valueOf(created.getId()),
                            GatewayActions.CHAT,
                            PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields)));
                    log.info("Business assistant: chat invoke sent immediately, skillSessionId={}, ak={}",
                            created.getId(), ak);
                }
            } else {
                // 个人助手：向 Gateway 请求创建 toolSession，等待 session_created 回调
                requestToolSession(created, pendingMessage);
            }
        } finally {
            releaseCreateLock(lockKey, lockValue, locked);
        }
    }

    /**
     * 缓存待发消息并请求 Gateway 创建 toolSession。
     * 当 Gateway 返回 session_created 时，{@link GatewayMessageRouter} 会自动重发待发消息。
     */
    public void requestToolSession(SkillSession session, String pendingMessage) {
        String sessionIdStr = String.valueOf(session.getId());
        gatewayRelayService.rebuildToolSession(sessionIdStr, session, pendingMessage);
        log.info("Requested tool session creation for welinkSession={}, ak={}",
                sessionIdStr, session.getAk());
    }

    /**
     * 同步查找或创建会话（向后兼容）。
     * 阻塞当前线程直到 toolSessionId 就绪或超时。
     * 主要被非 IM 入站场景使用。
     */
    public SkillSession findOrCreateSession(String businessDomain, String sessionType,
            String sessionId, String ak, String ownerWelinkId, String assistantAccount) {
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
                            ownerWelinkId,
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

    /**
     * 确保 session 拥有可用的 toolSessionId。
     * - 已有 toolSessionId → 直接返回
     * - 新建 session → 发送 create_session 到 Gateway
     * - 旧 session 缺 toolSession → 触发重建
     * 最终阻塞等待 toolSessionId 就绪。
     */
    private SkillSession ensureToolSession(SkillSession session, boolean newlyCreated) {
        if (session.getToolSessionId() != null && !session.getToolSessionId().isBlank()) {
            log.debug("Tool session already exists: skillSessionId={}, toolSessionId={}",
                    session.getId(), session.getToolSessionId());
            return sessionService.getSession(session.getId());
        }

        if (newlyCreated) {
            // 新创建的 session：发送 create_session 指令到 Gateway
            log.info("Sending create_session to gateway: skillSessionId={}, ak={}",
                    session.getId(), session.getAk());
            gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                    session.getAk(),
                    session.getUserId(),
                    String.valueOf(session.getId()),
                    GatewayActions.CREATE_SESSION,
                    PayloadBuilder.buildPayload(objectMapper, Map.of("title", session.getTitle()))));
        } else {
            // 已存在但无 toolSession：触发重建
            log.info("Rebuilding tool session: skillSessionId={}, ak={}",
                    session.getId(), session.getAk());
            gatewayRelayService.rebuildToolSession(String.valueOf(session.getId()), session, null);
        }

        return waitForToolSession(session.getId()); // 阻塞等待 Gateway 回填 toolSessionId
    }

    /**
     * 轮询等待 toolSessionId 就绪。
     * 每 200ms 查询一次数据库，超时则抛出异常。
     */
    private SkillSession waitForToolSession(Long sessionId) {
        log.info("Waiting for toolSessionId: skillSessionId={}, timeout={}s", sessionId, autoCreateTimeoutSeconds);
        long deadline = System.currentTimeMillis() + autoCreateTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            SkillSession latest = sessionService.findByIdSafe(sessionId);
            if (latest != null && latest.getToolSessionId() != null && !latest.getToolSessionId().isBlank()) {
                log.info("ToolSessionId ready: skillSessionId={}, toolSessionId={}",
                        sessionId, latest.getToolSessionId());
                return latest;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for toolSessionId");
            }
        }
        log.error("Timed out waiting for toolSessionId: skillSessionId={}, timeout={}s",
                sessionId, autoCreateTimeoutSeconds);
        throw new IllegalStateException("Timed out waiting for tool session creation: " + sessionId);
    }

    /** 构建会话标题：domain-sessionType-sessionId */
    private String buildTitle(String businessDomain, String sessionType, String sessionId) {
        return "%s-%s-%s".formatted(
                businessDomain != null ? businessDomain : "im",
                sessionType != null ? sessionType : "session",
                sessionId != null ? sessionId : "unknown");
    }

    /** 构建分布式锁的 Redis key */
    private String buildCreateLockKey(String businessDomain, String sessionType, String sessionId, String ak) {
        return "skill:im-session:create:%s:%s:%s:%s".formatted(
                businessDomain != null ? businessDomain : "im",
                sessionType != null ? sessionType : "session",
                sessionId != null ? sessionId : "unknown",
                ak != null ? ak : "unknown");
    }

    /** 安全释放 Redis 分布式锁（仅释放自己持有的锁） */
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

    /** 静默休眠，中断时转为 IllegalStateException */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for IM session creation lock");
        }
    }
}
