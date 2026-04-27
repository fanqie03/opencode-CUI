package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.ResolveOutcome;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 入站消息处理服务。
 * 从 ImInboundController 提取的共享处理逻辑，供 IM 和外部渠道（ExternalInboundController）复用。
 *
 * <p>支持四种处理模式：
 * <ul>
 *   <li>{@link #processChat} — 普通聊天消息</li>
 *   <li>{@link #processQuestionReply} — 交互式问答回复</li>
 *   <li>{@link #processPermissionReply} — 权限请求回复</li>
 *   <li>{@link #processRebuild} — 会话重建</li>
 * </ul>
 */
@Slf4j
@Service
public class InboundProcessingService {

    /** business 助手 toolSessionId 自愈 / 重生分布式锁：防止并发生成多个 cloud- ID 覆盖彼此 Redis mapping。 */
    private static final String BUSINESS_HEAL_LOCK_PREFIX = "skill:im-session:heal:";
    /** 锁 TTL：避免进程崩溃锁永久残留。 */
    private static final Duration BUSINESS_HEAL_LOCK_TTL = Duration.ofSeconds(10);
    /** self-heal 等待别人完成的总超时时间。 */
    private static final long BUSINESS_HEAL_LOCK_TIMEOUT_MS = 2000L;
    /** self-heal 轮询别人完成的间隔。 */
    private static final long BUSINESS_HEAL_LOCK_RETRY_MILLIS = 200L;

    private final AssistantAccountResolverService resolverService;
    private final AssistantIdProperties assistantIdProperties;
    private final GatewayApiClient gatewayApiClient;
    private final ImSessionManager sessionManager;
    private final ContextInjectionService contextInjectionService;
    private final GatewayRelayService gatewayRelayService;
    private final SkillMessageService messageService;
    private final SessionRebuildService rebuildService;
    private final ObjectMapper objectMapper;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final StreamMessageEmitter emitter;
    private final DeliveryProperties deliveryProperties;
    private final RedisMessageBroker redisMessageBroker;
    private final AssistantOfflineMessageProvider offlineMessageProvider;
    private final SkillSessionService sessionService;
    private final StringRedisTemplate redisTemplate;

    public InboundProcessingService(
            AssistantAccountResolverService resolverService,
            AssistantIdProperties assistantIdProperties,
            GatewayApiClient gatewayApiClient,
            ImSessionManager sessionManager,
            ContextInjectionService contextInjectionService,
            GatewayRelayService gatewayRelayService,
            SkillMessageService messageService,
            SessionRebuildService rebuildService,
            ObjectMapper objectMapper,
            AssistantInfoService assistantInfoService,
            AssistantScopeDispatcher scopeDispatcher,
            StreamMessageEmitter emitter,
            DeliveryProperties deliveryProperties,
            RedisMessageBroker redisMessageBroker,
            AssistantOfflineMessageProvider offlineMessageProvider,
            SkillSessionService sessionService,
            StringRedisTemplate redisTemplate) {
        this.resolverService = resolverService;
        this.assistantIdProperties = assistantIdProperties;
        this.gatewayApiClient = gatewayApiClient;
        this.sessionManager = sessionManager;
        this.contextInjectionService = contextInjectionService;
        this.gatewayRelayService = gatewayRelayService;
        this.messageService = messageService;
        this.rebuildService = rebuildService;
        this.objectMapper = objectMapper;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.emitter = emitter;
        this.deliveryProperties = deliveryProperties;
        this.redisMessageBroker = redisMessageBroker;
        this.offlineMessageProvider = offlineMessageProvider;
        this.sessionService = sessionService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 处理聊天消息。
     * 完整流程：解析助手 → 在线检查 → 上下文注入 → 查找/创建 session → 转发 Gateway。
     *
     * @param businessDomain   业务域（im / external）
     * @param sessionType      会话类型（direct / group）
     * @param sessionId        业务侧会话 ID
     * @param assistantAccount 助手账号
     * @param content          消息内容
     * @param msgType          消息类型
     * @param imageUrl         图片 URL（预留）
     * @param chatHistory      群聊历史上下文
     * @return 处理结果
     */
    public InboundResult processChat(String businessDomain, String sessionType, String sessionId,
                                      String assistantAccount, String senderUserAccount,
                                      String content, String msgType,
                                      String imageUrl, List<ImMessageRequest.ChatMessage> chatHistory,
                                      String inboundSource,
                                      JsonNode businessExtParam) {
        // 第 1 步：解析助手账号 → 三态 existence 判定
        ResolveOutcome outcome = resolverService.resolveWithStatus(assistantAccount);
        if (outcome.status() == ExistenceStatus.NOT_EXISTS) {
            // NOT_EXISTS：保留 businessSessionId 信封（ak 未知，findSession 无法定位 skillSession）
            log.info("[SKIP] processChat: reason=assistant_not_exists, decision=block, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(410, resolverService.getDeletionMessage(), sessionId, null);
        }
        if (outcome.status() == ExistenceStatus.UNKNOWN) {
            log.warn("[SKIP] processChat: reason=assistant_check_unknown, decision=block-unknown, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = outcome.ak();
        String ownerWelinkId = outcome.ownerWelinkId();
        log.info("processChat: resolved assistant={}, ak={}, ownerWelinkId={}",
                assistantAccount, ak, ownerWelinkId);

        // 第 2 步：Agent 在线检查（开关控制，业务助手跳过）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        // 第 3 步：上下文注入
        String prompt = contextInjectionService.resolvePrompt(sessionType, content, chatHistory);
        log.debug("Context injection resolved: sessionType={}, promptLength={}",
                sessionType, prompt != null ? prompt.length() : 0);

        // 第 4 步：查找已有 session
        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);

        // 情况 A：session 不存在 → 异步创建（welinkSessionId 异步生成，暂不返回）
        if (session == null) {
            log.info("No existing session found, creating async: domain={}, sessionType={}, sessionId={}, ak={}",
                    businessDomain, sessionType, sessionId, ak);
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, senderUserAccount, prompt,
                    businessExtParam);
            // 异步创建后重新查询获取 session ID
            SkillSession created = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
            writeInvokeSource(created, inboundSource);
            return InboundResult.ok(sessionId,
                    created != null ? String.valueOf(created.getId()) : null);
        }

        // 情况 B：session 存在但 toolSessionId 尚未就绪
        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
                    assistantInfoService.getCachedScope(ak));
            String generated = strategy.generateToolSessionId();
            if (generated != null) {
                // business 自愈：加锁 + 二次检查，失败则降级到 rebuild 路径避免丢消息
                if (!tryHealBusinessToolSessionId(session, strategy)) {
                    log.warn("[WARN] business self-heal timeout, falling back to rebuild path: welinkSessionId={}, ak={}",
                            session.getId(), ak);
                    sessionManager.requestToolSession(session, prompt);
                    writeInvokeSource(session, inboundSource);
                    return InboundResult.ok(sessionId, String.valueOf(session.getId()));
                }
                log.warn("[WARN] business toolSessionId missing, self-healing: welinkSessionId={}, ak={}, assistantAccount={}, newToolSessionId={}, inboundSource={}",
                        session.getId(), ak, assistantAccount, session.getToolSessionId(), inboundSource);

                // 重放 pending list 中的历史消息（可能由 handleSessionNotFound / handleContextOverflow 路径遗留）
                // business 路径发送不再 appendToPending，避免并发下 peer 把本请求的 prompt 误当 legacy 重放。
                List<String> legacyPending = rebuildService.consumePendingMessages(String.valueOf(session.getId()));
                if (!legacyPending.isEmpty()) {
                    log.warn("[WARN] business self-heal: replaying pending messages, welinkSessionId={}, count={}",
                            session.getId(), legacyPending.size());
                    for (String legacyMsg : legacyPending) {
                        if (legacyMsg == null || legacyMsg.isBlank() || legacyMsg.equals(prompt)) {
                            continue;
                        }
                        // D17: pending 队列仅含纯文本，无原消息绑定的 businessExtParam，
                        // 显式传 null 避免把当前请求的 ext 错绑到 legacy 消息上；
                        // 云端报文 extParameters.businessExtParam 由 BusinessScopeStrategy 兜底为 {}
                        dispatchChatToGateway(session, legacyMsg, ak, ownerWelinkId, assistantAccount,
                                senderUserAccount, sessionType, sessionId, inboundSource, legacyMsg, false,
                                null);
                    }
                }
                return dispatchChatToGateway(session, prompt, ak, ownerWelinkId, assistantAccount,
                        senderUserAccount, sessionType, sessionId, inboundSource, content, false,
                        businessExtParam);
            }
            // personal / scope 识别降级：保持现行 rebuild 路径
            log.info("Session exists but toolSessionId not ready, requesting rebuild: skillSessionId={}",
                    session.getId());
            sessionManager.requestToolSession(session, prompt);
            writeInvokeSource(session, inboundSource);
            return InboundResult.ok(sessionId, String.valueOf(session.getId()));
        }

        // 情况 C：session 就绪，转发消息到 AI Gateway
        // append pending 仅对 personal 有意义（rebuild 链路消费者），business 不写避免并发重放放大
        AssistantScopeStrategy caseCStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(ak));
        boolean appendToPending = caseCStrategy.generateToolSessionId() == null;
        return dispatchChatToGateway(session, prompt, ak, ownerWelinkId, assistantAccount,
                senderUserAccount, sessionType, sessionId, inboundSource, content, appendToPending,
                businessExtParam);
    }

    /**
     * business 助手 toolSessionId 缺失自愈（带分布式锁）。
     *
     * <p>流程：
     * <ol>
     *   <li>申请 Redis 锁 {@code skill:im-session:heal:{sessionId}}</li>
     *   <li>拿到锁 → 二次检查 DB：若已被别人补齐则复用；否则生成新 cloud- ID 持久化</li>
     *   <li>拿不到锁 → 轮询 DB 等别人完成，读到就绪状态即复用</li>
     *   <li>超时仍未就绪 → 返回 false，由调用方降级到 rebuild 路径保留消息</li>
     * </ol>
     *
     * <p>成功时刷新 {@code session.toolSessionId} 并返回 true；失败返回 false。
     */
    private boolean tryHealBusinessToolSessionId(SkillSession session, AssistantScopeStrategy strategy) {
        String lockKey = BUSINESS_HEAL_LOCK_PREFIX + session.getId();
        String lockValue = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + BUSINESS_HEAL_LOCK_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, BUSINESS_HEAL_LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    SkillSession latest = sessionService.findByIdSafe(session.getId());
                    if (latest != null && latest.getToolSessionId() != null
                            && !latest.getToolSessionId().isBlank()) {
                        session.setToolSessionId(latest.getToolSessionId());
                        return true;
                    }
                    String newId = strategy.generateToolSessionId();
                    sessionService.updateToolSessionId(session.getId(), newId);
                    session.setToolSessionId(newId);
                    return true;
                } finally {
                    releaseBusinessHealLock(lockKey, lockValue);
                }
            }

            // 锁被别人持有：先查 DB 看对方是否已完成
            SkillSession latest = sessionService.findByIdSafe(session.getId());
            if (latest != null && latest.getToolSessionId() != null
                    && !latest.getToolSessionId().isBlank()) {
                session.setToolSessionId(latest.getToolSessionId());
                return true;
            }

            try {
                Thread.sleep(BUSINESS_HEAL_LOCK_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // 超时后最后一次查 DB
        SkillSession latest = sessionService.findByIdSafe(session.getId());
        if (latest != null && latest.getToolSessionId() != null
                && !latest.getToolSessionId().isBlank()) {
            session.setToolSessionId(latest.getToolSessionId());
            return true;
        }
        return false;
    }

    /** 释放 business heal 锁：仅删除自己持有的锁（CAS 语义由 value 比对保证）。 */
    private void releaseBusinessHealLock(String lockKey, String lockValue) {
        try {
            String current = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(current)) {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("Failed to release business heal lock: key={}, error={}", lockKey, e.getMessage());
        }
    }

    /**
     * session 就绪后的统一 chat 投递入口。
     * 负责：单聊消息持久化 → pending 追加（可选） → payload 构建 → Gateway 转发 → invoke source 记录。
     *
     * @param appendToPending 是否追加当前 prompt 到 {@code ss:pending-rebuild:*} 列表。
     *     该列表的消费者是 {@code GatewayMessageRouter#handleSessionCreated} → {@code retryPendingMessages}，
     *     仅在 personal 助手的 create_session → session_created 回调链路上真正被消费。
     *     business 助手不走该链路，写入只会积累无人消费，并在 self-heal consume 时被当作 legacy 误重放——
     *     因此 business 路径必须传 false。
     */
    private InboundResult dispatchChatToGateway(SkillSession session, String prompt,
            String ak, String ownerWelinkId, String assistantAccount,
            String senderUserAccount, String sessionType, String sessionId,
            String inboundSource, String content, boolean appendToPending,
            JsonNode businessExtParam) {
        log.info("Session ready, forwarding to gateway: skillSessionId={}, toolSessionId={}, sessionType={}",
                session.getId(), session.getToolSessionId(), sessionType);

        if (session.isImDirectSession()) {
            messageService.saveUserMessage(session.getId(), content);
        }
        if (appendToPending) {
            rebuildService.appendPendingMessage(String.valueOf(session.getId()), prompt);
        }

        Map<String, Object> payloadFields = new LinkedHashMap<>();
        payloadFields.put("text", prompt);
        payloadFields.put("toolSessionId", session.getToolSessionId());
        payloadFields.put("assistantAccount", assistantAccount);
        // 群聊：用实际发送者；单聊：用助手创建人（即对话人）
        String effectiveSender = "group".equals(sessionType)
                && senderUserAccount != null && !senderUserAccount.isBlank()
                ? senderUserAccount : ownerWelinkId;
        payloadFields.put("sendUserAccount", effectiveSender);
        payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : null);
        payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
        payloadFields.put("businessExtParam", businessExtParam);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.CHAT,
                PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields)));

        writeInvokeSource(session, inboundSource);
        return InboundResult.ok(sessionId, String.valueOf(session.getId()));
    }

    /**
     * 处理交互式问答回复。
     *
     * @param businessDomain    业务域
     * @param sessionType       会话类型
     * @param sessionId         业务侧会话 ID
     * @param assistantAccount  助手账号
     * @param senderUserAccount 发送者账号（信封层必填）
     * @param content           回复内容
     * @param toolCallId        工具调用 ID
     * @param subagentSessionId 子代理会话 ID（可为 null）
     * @return 处理结果
     */
    public InboundResult processQuestionReply(String businessDomain, String sessionType,
                                               String sessionId, String assistantAccount,
                                               String senderUserAccount,
                                               String content, String toolCallId,
                                               String subagentSessionId, String inboundSource,
                                               JsonNode businessExtParam) {
        ResolveOutcome outcome = resolverService.resolveWithStatus(assistantAccount);
        if (outcome.status() == ExistenceStatus.NOT_EXISTS) {
            log.info("[SKIP] processQuestionReply: reason=assistant_not_exists, decision=block, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(410, resolverService.getDeletionMessage(), sessionId, null);
        }
        if (outcome.status() == ExistenceStatus.UNKNOWN) {
            log.warn("[SKIP] processQuestionReply: reason=assistant_check_unknown, decision=block-unknown, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = outcome.ak();
        String ownerWelinkId = outcome.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready", sessionId,
                    session != null ? String.valueOf(session.getId()) : null);
        }

        // 在线检查（404 后置：保留 session 不存在 → 404 语义；session 存在 + 离线 → 503）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, Object> payloadFields = new LinkedHashMap<>();
        payloadFields.put("answer", content);
        payloadFields.put("toolCallId", toolCallId);
        payloadFields.put("toolSessionId", targetToolSessionId);
        payloadFields.put("sendUserAccount", senderUserAccount);
        payloadFields.put("businessExtParam", businessExtParam);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.QUESTION_REPLY,
                PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields)));

        writeInvokeSource(session, inboundSource);
        return InboundResult.ok(sessionId, String.valueOf(session.getId()));
    }

    /**
     * 处理权限请求回复。
     *
     * @param businessDomain    业务域
     * @param sessionType       会话类型
     * @param sessionId         业务侧会话 ID
     * @param assistantAccount  助手账号
     * @param senderUserAccount 发送者账号（信封层必填）
     * @param permissionId      权限请求 ID
     * @param response          用户应答（once / always / reject）
     * @param subagentSessionId 子代理会话 ID（可为 null）
     * @return 处理结果
     */
    public InboundResult processPermissionReply(String businessDomain, String sessionType,
                                                 String sessionId, String assistantAccount,
                                                 String senderUserAccount,
                                                 String permissionId, String response,
                                                 String subagentSessionId, String inboundSource,
                                                 JsonNode businessExtParam) {
        ResolveOutcome outcome = resolverService.resolveWithStatus(assistantAccount);
        if (outcome.status() == ExistenceStatus.NOT_EXISTS) {
            log.info("[SKIP] processPermissionReply: reason=assistant_not_exists, decision=block, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(410, resolverService.getDeletionMessage(), sessionId, null);
        }
        if (outcome.status() == ExistenceStatus.UNKNOWN) {
            log.warn("[SKIP] processPermissionReply: reason=assistant_check_unknown, decision=block-unknown, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = outcome.ak();
        String ownerWelinkId = outcome.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready", sessionId,
                    session != null ? String.valueOf(session.getId()) : null);
        }

        // 在线检查（404 后置：同 processQuestionReply）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, Object> payloadFields = new LinkedHashMap<>();
        payloadFields.put("permissionId", permissionId);
        payloadFields.put("response", response);
        payloadFields.put("toolSessionId", targetToolSessionId);
        payloadFields.put("sendUserAccount", senderUserAccount);
        payloadFields.put("businessExtParam", businessExtParam);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.PERMISSION_REPLY,
                PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields)));

        // 广播权限回复消息到前端
        StreamMessage replyMsg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .role("assistant")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId(permissionId)
                        .response(response)
                        .build())
                .subagentSessionId(subagentSessionId)
                .build();
        gatewayRelayService.publishProtocolMessage(String.valueOf(session.getId()), replyMsg);

        writeInvokeSource(session, inboundSource);
        return InboundResult.ok(sessionId, String.valueOf(session.getId()));
    }

    /**
     * 处理会话重建请求。
     * session 存在时请求新的 toolSession，不存在时异步创建。
     *
     * @param businessDomain    业务域
     * @param sessionType       会话类型
     * @param sessionId         业务侧会话 ID
     * @param assistantAccount  助手账号
     * @param senderUserAccount 发送者账号（信封层必填）
     * @return 处理结果
     */
    public InboundResult processRebuild(String businessDomain, String sessionType,
                                         String sessionId, String assistantAccount,
                                         String senderUserAccount) {
        ResolveOutcome outcome = resolverService.resolveWithStatus(assistantAccount);
        if (outcome.status() == ExistenceStatus.NOT_EXISTS) {
            log.info("[SKIP] processRebuild: reason=assistant_not_exists, decision=block, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(410, resolverService.getDeletionMessage(), sessionId, null);
        }
        if (outcome.status() == ExistenceStatus.UNKNOWN) {
            log.warn("[SKIP] processRebuild: reason=assistant_check_unknown, decision=block-unknown, assistantAccount={}, domain={}, sessionType={}, sessionId={}",
                    assistantAccount, businessDomain, sessionType, sessionId);
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = outcome.ak();
        String ownerWelinkId = outcome.ownerWelinkId();

        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
                    assistantInfoService.getCachedScope(ak));
            String generated = strategy.generateToolSessionId();
            if (generated != null) {
                // business rebuild：加锁防止并发覆盖 Redis mapping；锁未拿到视为"合并重复请求"直接返回 ok
                String lockKey = BUSINESS_HEAL_LOCK_PREFIX + session.getId();
                String lockValue = UUID.randomUUID().toString();
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, lockValue, BUSINESS_HEAL_LOCK_TTL);
                if (!Boolean.TRUE.equals(acquired)) {
                    log.info("business rebuild merged with concurrent heal/rebuild: welinkSessionId={}, ak={}",
                            session.getId(), ak);
                    return InboundResult.ok(sessionId, String.valueOf(session.getId()));
                }
                try {
                    String oldToolSessionId = session.getToolSessionId();
                    sessionService.updateToolSessionId(session.getId(), generated);
                    session.setToolSessionId(generated);
                    log.warn("[WARN] business rebuild: regenerating toolSessionId, welinkSessionId={}, ak={}, oldToolSessionId={}, newToolSessionId={}",
                            session.getId(), ak, oldToolSessionId, generated);
                    return InboundResult.ok(sessionId, String.valueOf(session.getId()));
                } finally {
                    releaseBusinessHealLock(lockKey, lockValue);
                }
            }
            // personal / scope 识别降级：保持现行 rebuild 路径
            sessionManager.requestToolSession(session, null);
            return InboundResult.ok(sessionId, String.valueOf(session.getId()));
        } else {
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, senderUserAccount, null,
                    null);
            SkillSession created = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
            return InboundResult.ok(sessionId,
                    created != null ? String.valueOf(created.getId()) : null);
        }
    }

    private void writeInvokeSource(SkillSession session, String inboundSource) {
        if (session != null && inboundSource != null && deliveryProperties.isWsMode()) {
            redisMessageBroker.setInvokeSource(
                    String.valueOf(session.getId()), inboundSource,
                    deliveryProperties.getInvokeSourceTtlSeconds());
        }
    }

    /**
     * 返回 null 表示在线（或跳过检查），调用方继续主流程。
     * 返回非 null 表示离线，调用方直接 return 该结果
     * （已带好 code=503、errormsg、session IDs，并已执行 handleAgentOffline 副作用）。
     */
    private InboundResult checkAgentOnline(String businessDomain, String sessionType,
                                           String sessionId, String ak,
                                           String assistantAccount) {
        if (!assistantIdProperties.isEnabled()) return null;
        AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(ak));
        if (!scopeStrategy.requiresOnlineCheck()) return null;
        if (gatewayApiClient.getAgentByAk(ak) != null) return null;

        log.warn("[SKIP] checkAgentOnline: reason=agent_offline, ak={}, domain={}, sessionType={}, sessionId={}",
                ak, businessDomain, sessionType, sessionId);
        SkillSession existing = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        handleAgentOffline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        return InboundResult.error(
                503,
                offlineMessageProvider.get(),
                sessionId,
                existing != null ? String.valueOf(existing.getId()) : null);
    }

    /**
     * Agent 离线处理：通过 StreamMessageEmitter 发送离线提示（enrich + deliver），
     * 并在单聊 session 中持久化系统消息。
     */
    void handleAgentOffline(String businessDomain, String sessionType,
                                     String sessionId, String ak, String assistantAccount) {
        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            String offlineMessage = offlineMessageProvider.get();
            StreamMessage offlineMsg = StreamMessage.builder()
                    .type(StreamMessage.Types.ERROR)
                    .error(offlineMessage)
                    .build();
            emitter.emitToSession(session,
                    String.valueOf(session.getId()), null, offlineMsg);
            if (session.isImDirectSession()) {
                try {
                    messageService.saveSystemMessage(session.getId(), offlineMessage);
                } catch (Exception e) {
                    log.error("Failed to persist agent_offline message: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 入站处理结果。
     *
     * @param success 是否成功
     * @param code    错误码（成功为 0）
     * @param message 错误消息（成功为 null）
     */
    /**
     * 入站处理结果。
     *
     * @param success           是否成功
     * @param code              错误码（成功为 0）
     * @param message           错误消息（成功为 null）
     * @param businessSessionId 业务侧会话 ID（原样返回）
     * @param welinkSessionId   Skill Server 内部会话 ID（WS 推送消息中的 sessionId）
     */
    public record InboundResult(boolean success, int code, String message,
                                 String businessSessionId, String welinkSessionId) {
        public static InboundResult ok(String businessSessionId, String welinkSessionId) {
            return new InboundResult(true, 0, null, businessSessionId, welinkSessionId);
        }

        public static InboundResult ok() {
            return new InboundResult(true, 0, null, null, null);
        }

        public static InboundResult error(int code, String message) {
            return new InboundResult(false, code, message, null, null);
        }

        public static InboundResult error(int code, String message,
                                           String businessSessionId, String welinkSessionId) {
            return new InboundResult(false, code, message, businessSessionId, welinkSessionId);
        }
    }
}
