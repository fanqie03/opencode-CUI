package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.enums.AsyncTaskType;
import com.opencode.cui.skill.model.event.SessionDeletedEvent;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.service.scope.DefaultAssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session route flow for assistant-backed session lifecycle operations.
 */
@Slf4j
@Service
public class SkillSessionFlowService {

    private final SkillSessionService sessionService;
    private final GatewayRelayService gatewayRelayService;
    private final ObjectMapper objectMapper;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final AssistantAccountResolverService assistantAccountResolverService;
    private final DefaultAssistantRuleService ruleService;
    private final DefaultAssistantScopeStrategy defaultAssistantScopeStrategy;
    private final MessagePersistenceService persistenceService;
    private final StreamBufferService bufferService;
    private final SkillMessageRepository messageRepository;
    private final AsyncTaskService asyncTaskService;
    private final ApplicationEventPublisher eventPublisher;

    public SkillSessionFlowService(SkillSessionService sessionService,
                                   GatewayRelayService gatewayRelayService,
                                   ObjectMapper objectMapper,
                                   AssistantInfoService assistantInfoService,
                                   AssistantScopeDispatcher scopeDispatcher,
                                   AssistantAccountResolverService assistantAccountResolverService,
                                   DefaultAssistantRuleService ruleService,
                                   DefaultAssistantScopeStrategy defaultAssistantScopeStrategy,
                                   MessagePersistenceService persistenceService,
                                   StreamBufferService bufferService,
                                   SkillMessageRepository messageRepository,
                                   AsyncTaskService asyncTaskService,
                                   ApplicationEventPublisher eventPublisher) {
        this.sessionService = sessionService;
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.assistantAccountResolverService = assistantAccountResolverService;
        this.ruleService = ruleService;
        this.defaultAssistantScopeStrategy = defaultAssistantScopeStrategy;
        this.persistenceService = persistenceService;
        this.bufferService = bufferService;
        this.messageRepository = messageRepository;
        this.asyncTaskService = asyncTaskService;
        this.eventPublisher = eventPublisher;
    }

    public ApiResponse<SkillSession> createSession(String resolvedUserId,
                                                   String userIdCookie,
                                                   CreateSessionCommand request) {
        boolean hasExplicit = hasAssistantIdentity(request.ak(), request.assistantAccount());
        if (!hasExplicit) {
            Optional<DefaultAssistantRule> ruleOpt = ruleService.lookup(
                    request.businessSessionDomain(), request.businessSessionType());
            if (ruleOpt.isEmpty()) {
                log.warn("[BLOCK] createSession: reason=missing_ak_and_no_rule, domain={}, type={}, userId={}",
                        request.businessSessionDomain(), request.businessSessionType(), userIdCookie);
                return ApiResponse.error(400, "ak 和 assistantAccount 必填");
            }
            DefaultAssistantRule rule = ruleOpt.get();
            String toolSessionId = defaultAssistantScopeStrategy.generateToolSessionId();
            SkillSession injected = sessionService.createSessionWithDefaultAssistant(
                    resolvedUserId,
                    rule.ak(),
                    rule.assistantAccount(),
                    request.title(),
                    request.businessSessionDomain(),
                    request.businessSessionType(),
                    request.businessSessionId(),
                    toolSessionId);
            log.info("[INFO] createSession: rule-injected, domain={}, type={}, ak={}",
                    request.businessSessionDomain(), request.businessSessionType(), rule.ak());
            return ApiResponse.ok(injected);
        }

        ApiResponse<SkillSession> deletionBlock = checkAssistantDeletion(request.assistantAccount(), userIdCookie);
        if (deletionBlock != null) {
            return deletionBlock;
        }

        SkillSession session = sessionService.createSession(
                resolvedUserId,
                request.ak(),
                request.title(),
                request.businessSessionDomain(),
                request.businessSessionType(),
                request.businessSessionId(),
                request.assistantAccount());

        routeCreateSession(resolvedUserId, session, request);
        return ApiResponse.ok(session);
    }

    public void closeSession(SkillSession session) {
        if (shouldSendLifecycleInvoke(session)) {
            gatewayRelayService.sendInvokeToGateway(lifecycleCommand(session, GatewayActions.CLOSE_SESSION));
        }
        sessionService.closeSession(session.getId());
    }

    public void abortSession(SkillSession session) {
        if (shouldSendAbortInvoke(session)) {
            gatewayRelayService.sendInvokeToGateway(lifecycleCommand(session, GatewayActions.ABORT_SESSION));
        }
        finalizeAbortedSession(session);
    }

    /**
     * 硬删除会话。主流程：
     * 1. 如 ACTIVE 则先 abort（持久化缓冲 → IDLE）
     * 2. 统计被删消息数量
     * 3. 物理删除 session 主表
     * 4. 创建异步清理任务（事务内，失败回滚）
     * 5. 发布 SessionDeletedEvent（WS/Gateway 通知）
     */
    @Transactional
    public void deleteSession(SkillSession session, String userId) {
        Long sessionId = session.getId();
        log.info("[ENTRY] deleteSession: sessionId={}, userId={}", sessionId, userId);

        // ACTIVE 会话先 abort（持久化流式缓冲）
        if (session.getStatus() == SkillSession.Status.ACTIVE) {
            abortSession(session);
            log.info("Aborted ACTIVE session before delete: sessionId={}", sessionId);
        }

        // 统计消息数（用于事件 payload）
        int messageCount = (int) messageRepository.countBySessionId(sessionId);

        // 物理删除主表
        sessionService.deleteSession(sessionId);

        // 创建异步清理任务（在事务内，失败则回滚）
        String cleanPayload = "{\"sessionId\":" + sessionId + ",\"messageCount\":" + messageCount + "}";
        asyncTaskService.createTask(AsyncTaskType.DELETE_SESSION_MESSAGES, cleanPayload);

        // 发布事件（WS 推送、Gateway 通知）
        eventPublisher.publishEvent(new SessionDeletedEvent(
                session, userId, Instant.now(), messageCount));

        log.info("[EXIT] deleteSession: sessionId={}", sessionId);
    }

    private void finalizeAbortedSession(SkillSession session) {
        if (session == null || session.getId() == null) {
            return;
        }
        Long sessionId = session.getId();
        String sessionIdText = sessionId.toString();
        persistBufferedAbortParts(sessionId, sessionIdText);
        StreamMessage idle = StreamMessage.sessionStatus("idle");
        persistenceService.persistIfFinal(sessionId, idle);
        boolean markedIdle = sessionService.markSessionIdle(sessionId);
        bufferService.clearSession(sessionIdText);
        log.info("[EXIT] abortSession.localFinalize: sessionId={}, markedIdle={}", sessionId, markedIdle);
    }

    private void persistBufferedAbortParts(Long sessionId, String sessionIdText) {
        List<StreamMessage> parts = bufferService.getStreamingParts(sessionIdText);
        if (parts == null || parts.isEmpty()) {
            return;
        }
        for (StreamMessage part : parts) {
            StreamMessage finalPart = toAbortFinalPart(part);
            if (finalPart != null) {
                persistenceService.persistIfFinal(sessionId, finalPart);
            }
        }
    }

    private StreamMessage toAbortFinalPart(StreamMessage part) {
        if (part == null || part.getType() == null) {
            return null;
        }
        return switch (part.getType()) {
            case StreamMessage.Types.TEXT_DELTA -> withType(part, StreamMessage.Types.TEXT_DONE);
            case StreamMessage.Types.THINKING_DELTA -> withType(part, StreamMessage.Types.THINKING_DONE);
            case StreamMessage.Types.TEXT_DONE,
                    StreamMessage.Types.THINKING_DONE,
                    StreamMessage.Types.TOOL_UPDATE,
                    StreamMessage.Types.QUESTION,
                    StreamMessage.Types.FILE,
                    StreamMessage.Types.PERMISSION_ASK,
                    StreamMessage.Types.PERMISSION_REPLY,
                    StreamMessage.Types.STEP_DONE ->
                part;
            default -> null;
        };
    }

    private StreamMessage withType(StreamMessage msg, String type) {
        msg.setType(type);
        return msg;
    }

    private void routeCreateSession(String resolvedUserId, SkillSession session, CreateSessionCommand request) {
        if (!hasAssistantIdentity(request.ak(), request.assistantAccount())) {
            return;
        }
        AssistantInfo info = getAssistantInfo(request.ak(), request.assistantAccount());
        AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
                session.getBusinessSessionDomain(), session.getBusinessSessionType(), info);
        String generatedToolSessionId = strategy.generateToolSessionId();
        if (generatedToolSessionId != null) {
            sessionService.updateToolSessionId(session.getId(), generatedToolSessionId);
            log.info("Business assistant: toolSessionId pre-generated, sessionId={}, toolSessionId={}",
                    session.getId(), generatedToolSessionId);
            return;
        }
        gatewayRelayService.sendInvokeToGateway(
                new InvokeCommand(request.ak(),
                        resolvedUserId,
                        session.getId().toString(),
                        GatewayActions.CREATE_SESSION,
                        PayloadBuilder.buildPayload(objectMapper,
                                request.title() != null && !request.title().isBlank()
                                        ? Map.of("title", request.title())
                                        : Map.of()),
                        null,
                        session.getBusinessSessionDomain(),
                        session.getBusinessSessionType(),
                        session.getBusinessSessionId(),
                        null,
                        session.getAssistantAccount(),
                        session.getAssistantAccount()));
    }

    private ApiResponse<SkillSession> checkAssistantDeletion(String assistantAccount, String userIdCookie) {
        if (assistantAccount == null || assistantAccount.isBlank()) {
            if (!assistantAccountResolverService.isSkipOnNullAssistantAccount()) {
                log.warn("[BLOCK] createSession: reason=no_assistant_account, decision=block, userId={}",
                        userIdCookie);
                return ApiResponse.error(400, "assistantAccount is required");
            }
            log.info("[SKIP] createSession: reason=no_assistant_account, decision=allow, userId={}", userIdCookie);
            return null;
        }
        ExistenceStatus status = assistantAccountResolverService.check(assistantAccount);
        if (status == ExistenceStatus.NOT_EXISTS) {
            log.info("[SKIP] createSession: reason=assistant_not_exists, decision=block, assistantAccount={}, userId={}",
                    assistantAccount, userIdCookie);
            return ApiResponse.error(410, assistantAccountResolverService.getDeletionMessage());
        }
        if (status == ExistenceStatus.UNKNOWN) {
            log.warn("[WARN] createSession: reason=assistant_check_unknown, decision=allow-unknown, assistantAccount={}, userId={}",
                    assistantAccount, userIdCookie);
        }
        return null;
    }

    private boolean shouldSendLifecycleInvoke(SkillSession session) {
        boolean isDefaultAssistant = ruleService.lookup(
                session.getBusinessSessionDomain(), session.getBusinessSessionType()).isPresent();
        return !isDefaultAssistant
                && hasAssistantIdentity(session.getAk(), session.getAssistantAccount())
                && session.getToolSessionId() != null;
    }

    private boolean shouldSendAbortInvoke(SkillSession session) {
        return hasAssistantIdentity(session.getAk(), session.getAssistantAccount())
                && session.getToolSessionId() != null;
    }

    private InvokeCommand lifecycleCommand(SkillSession session, String action) {
        return new InvokeCommand(session.getAk(),
                session.getUserId(),
                session.getId().toString(),
                action,
                PayloadBuilder.buildPayload(objectMapper, Map.of("toolSessionId", session.getToolSessionId())),
                null,
                session.getBusinessSessionDomain(),
                session.getBusinessSessionType(),
                session.getBusinessSessionId(),
                null,
                session.getAssistantAccount(),
                session.getAssistantAccount());
    }

    private boolean hasAssistantIdentity(String ak, String assistantAccount) {
        return (ak != null && !ak.isBlank())
                || (assistantAccount != null && !assistantAccount.isBlank());
    }

    private AssistantInfo getAssistantInfo(String ak, String assistantAccount) {
        if (assistantAccount != null && !assistantAccount.isBlank()) {
            return assistantInfoService.getAssistantInfo(ak, assistantAccount);
        }
        return assistantInfoService.getAssistantInfo(ak);
    }

    public record CreateSessionCommand(String ak,
                                       String title,
                                       String businessSessionDomain,
                                       String businessSessionType,
                                       String businessSessionId,
                                       String assistantAccount) {
    }
}
