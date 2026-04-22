package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            AssistantOfflineMessageProvider offlineMessageProvider) {
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
                                      String inboundSource) {
        // 第 1 步：解析助手账号 → 获取 ak 和 ownerWelinkId
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            log.warn("[SKIP] processChat: reason=resolve_failed, assistantAccount={}", assistantAccount);
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();
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
                    ak, ownerWelinkId, assistantAccount, senderUserAccount, prompt);
            // 异步创建后重新查询获取 session ID
            SkillSession created = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
            writeInvokeSource(created, inboundSource);
            return InboundResult.ok(sessionId,
                    created != null ? String.valueOf(created.getId()) : null);
        }

        // 情况 B：session 存在但 toolSessionId 尚未就绪
        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            log.info("Session exists but toolSessionId not ready, requesting rebuild: skillSessionId={}",
                    session.getId());
            sessionManager.requestToolSession(session, prompt);
            writeInvokeSource(session, inboundSource);
            return InboundResult.ok(sessionId, String.valueOf(session.getId()));
        }

        // 情况 C：session 就绪，转发消息到 AI Gateway
        log.info("Session ready, forwarding to gateway: skillSessionId={}, toolSessionId={}, sessionType={}",
                session.getId(), session.getToolSessionId(), sessionType);

        if (session.isImDirectSession()) {
            messageService.saveUserMessage(session.getId(), content);
        }
        rebuildService.appendPendingMessage(String.valueOf(session.getId()), prompt);

        Map<String, String> payloadFields = new LinkedHashMap<>();
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
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.CHAT,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

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
                                               String subagentSessionId, String inboundSource) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready", sessionId,
                    session != null ? String.valueOf(session.getId()) : null);
        }

        // 在线检查（404 后置：保留 session 不存在 → 404 语义；session 存在 + 离线 → 503）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("answer", content);
        payloadFields.put("toolCallId", toolCallId);
        payloadFields.put("toolSessionId", targetToolSessionId);
        payloadFields.put("sendUserAccount", senderUserAccount);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.QUESTION_REPLY,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

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
                                                 String subagentSessionId, String inboundSource) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready", sessionId,
                    session != null ? String.valueOf(session.getId()) : null);
        }

        // 在线检查（404 后置：同 processQuestionReply）
        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("permissionId", permissionId);
        payloadFields.put("response", response);
        payloadFields.put("toolSessionId", targetToolSessionId);
        payloadFields.put("sendUserAccount", senderUserAccount);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.PERMISSION_REPLY,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

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
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
        if (offline != null) return offline;

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            sessionManager.requestToolSession(session, null);
            return InboundResult.ok(sessionId, String.valueOf(session.getId()));
        } else {
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, senderUserAccount, null);
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
