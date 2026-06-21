package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.AvailabilityResult;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.telemetry.chat.ChatRequestTelemetryEvent;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnContext;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Message route flow for chat/question/permission requests.
 *
 * <p>Controllers own HTTP shape and access checks; this service owns assistant deletion checks,
 * assistant type routing, online checks, payload construction, and gateway dispatch.</p>
 */
@Slf4j
@Service
public class SkillMessageFlowService {

    private final SkillMessageService messageService;
    private final GatewayRelayService gatewayRelayService;
    private final ObjectMapper objectMapper;
    private final GatewayMessageRouter messageRouter;
    private final AssistantIdProperties assistantIdProperties;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final AssistantAvailabilityService availabilityService;
    private final AssistantAccountResolverService assistantAccountResolverService;
    private final DefaultAssistantRuleService ruleService;
    private final AllowedSlashCommandsResolver allowedSlashCommandsResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final MessagePersistenceService persistenceService;
    private final MessageTurnLifecycle messageTurnLifecycle;

    public SkillMessageFlowService(SkillMessageService messageService,
                                   GatewayRelayService gatewayRelayService,
                                   ObjectMapper objectMapper,
                                   GatewayMessageRouter messageRouter,
                                   AssistantIdProperties assistantIdProperties,
                                   AssistantInfoService assistantInfoService,
                                   AssistantScopeDispatcher scopeDispatcher,
                                   AssistantAvailabilityService availabilityService,
                                   AssistantAccountResolverService assistantAccountResolverService,
                                   DefaultAssistantRuleService ruleService,
                                   AllowedSlashCommandsResolver allowedSlashCommandsResolver,
                                   ApplicationEventPublisher eventPublisher,
                                   MessagePersistenceService persistenceService,
                                   MessageTurnLifecycle messageTurnLifecycle) {
        this.messageService = messageService;
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.assistantIdProperties = assistantIdProperties;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.availabilityService = availabilityService;
        this.assistantAccountResolverService = assistantAccountResolverService;
        this.ruleService = ruleService;
        this.allowedSlashCommandsResolver = allowedSlashCommandsResolver;
        this.eventPublisher = eventPublisher;
        this.persistenceService = persistenceService;
        this.messageTurnLifecycle = messageTurnLifecycle;
    }

    public ApiResponse<ProtocolMessageView> sendMessage(SkillSession session,
                                                         String sessionId,
                                                         Long numericSessionId,
                                                         SendMessageCommand request,
                                                         String userIdCookie) {
        if (!isDefaultAssistant(session)) {
            ApiResponse<ProtocolMessageView> deletionBlock = checkAssistantDeletion(
                    session.getAssistantAccount(), sessionId, "sendMessage");
            if (deletionBlock != null) {
                return deletionBlock;
            }
        } else {
            log.info("[SKIP] sendMessage.deletionCheck: reason=default_assistant_rule_matched, sessionId={}, domain={}, type={}",
                    sessionId, session.getBusinessSessionDomain(), session.getBusinessSessionType());
        }

        SkillMessage message = messageService.saveUserMessage(numericSessionId, request.content());
        messageRouter.broadcastStreamMessage(
                sessionId, session.getUserId(),
                StreamMessage.userMessage(
                        message.getMessageId(),
                        message.getSeq(),
                        message.getContent(),
                        sessionId));

        // Get businessTag from assistant info if available
        String brainTag = null;
        if (!isDefaultAssistant(session)) {
            AssistantInfo scopeInfo = getAssistantInfo(session);
            if (scopeInfo != null) {
                brainTag = scopeInfo.getBusinessTag();
            }
        }
        String effectiveUserId = effectiveUserId(userIdCookie, session);
        messageTurnLifecycle.onTurnStart(
                new MessageTurnContext(message.getMessageId(), brainTag, sessionId, null, effectiveUserId, brainTag)
        );

        routeToGateway(session, sessionId, numericSessionId, request, userIdCookie);
        return ApiResponse.ok(ProtocolMessageMapper.toProtocolMessage(message, List.of(), objectMapper));
    }

    public ApiResponse<Map<String, Object>> replyPermission(SkillSession session,
                                                            String sessionId,
                                                            String permId,
                                                            PermissionReplyCommand request,
                                                            String userIdCookie) {
        if (!hasAssistantIdentity(session)) {
            return ApiResponse.error(400, "No agent associated with this session");
        }

        if (!isDefaultAssistant(session)) {
            ApiResponse<Map<String, Object>> deletionBlock = checkAssistantDeletionForMap(
                    session.getAssistantAccount(), sessionId, "replyPermission");
            if (deletionBlock != null) {
                return deletionBlock;
            }
        } else {
            log.info("[SKIP] replyPermission.deletionCheck: reason=default_assistant_rule_matched, sessionId={}, domain={}, type={}",
                    sessionId, session.getBusinessSessionDomain(), session.getBusinessSessionType());
        }

        AssistantScopeStrategy scopeStrategy = resolveScopeStrategy(session);
        if (assistantIdProperties.isEnabled() && session.getAk() != null && scopeStrategy.requiresOnlineCheck()) {
            AvailabilityResult r = availabilityService.resolve(session.getAk());
            if (!r.online()) {
                log.warn("[SKIP] replyPermission: reason=agent_offline, sessionId={}, ak={}, source={}",
                        sessionId, session.getAk(), r.source());
                return ApiResponse.error(503, r.message());
            }
        }

        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return ApiResponse.error(500, "No toolSessionId available");
        }

        String targetToolSessionId = request.subagentSessionId() != null
                ? request.subagentSessionId()
                : session.getToolSessionId();
        String effectiveUserId = effectiveUserId(userIdCookie, session);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("permissionId", permId);
        pr.put("response", request.response());
        pr.put("toolSessionId", targetToolSessionId);
        pr.put("assistantAccount", session.getAssistantAccount());
        pr.put("sendUserAccount", effectiveUserId);
        pr.put("businessExtParam", request.businessExtParam());

        gatewayRelayService.sendInvokeToGateway(
                new InvokeCommand(session.getAk(),
                        effectiveUserId,
                        sessionId,
                        GatewayActions.PERMISSION_REPLY,
                        PayloadBuilder.buildPayloadWithObjects(objectMapper, pr),
                        null,
                        session.getBusinessSessionDomain(),
                        session.getBusinessSessionType(),
                        session.getBusinessSessionId(),
                        null,
                        session.getAssistantAccount(),
                        session.getAssistantAccount()));

        StreamMessage replyMessage = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .role("assistant")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId(permId)
                        .response(request.response())
                        .build())
                .subagentSessionId(request.subagentSessionId())
                .build();
        gatewayRelayService.publishProtocolMessage(sessionId, replyMessage);
        recordPermissionReplyForHistory(session.getId(), permId, request.response());

        return ApiResponse.ok(Map.of(
                "welinkSessionId", sessionId,
                "permissionId", permId,
                "response", request.response()));
    }

    private void routeToGateway(SkillSession session, String sessionId,
                                Long numericSessionId, SendMessageCommand request, String userIdCookie) {
        if (!hasAssistantIdentity(session)) {
            log.warn("[SKIP] SkillMessageFlowService.routeToGateway: reason=no_agent, sessionId={}", sessionId);
            return;
        }

        AssistantScopeStrategy scopeStrategy = resolveScopeStrategy(session);
        AssistantInfo scopeInfo = isDefaultAssistant(session) ? null : getAssistantInfo(session);
        if (assistantIdProperties.isEnabled() && session.getAk() != null && scopeStrategy.requiresOnlineCheck()) {
            AvailabilityResult r = availabilityService.resolve(session.getAk());
            if (!r.online()) {
                log.warn("[SKIP] SkillMessageFlowService.routeToGateway: reason=agent_offline, sessionId={}, ak={}, source={}",
                        sessionId, session.getAk(), r.source());
                try {
                    messageService.saveSystemMessage(numericSessionId, r.message());
                } catch (Exception e) {
                    log.error("Failed to persist agent_offline message for session {}: {}", sessionId, e.getMessage());
                }
                gatewayRelayService.publishProtocolMessage(sessionId, StreamMessage.builder()
                        .type(StreamMessage.Types.ERROR)
                        .error(r.message())
                        .build());
                return;
            }
        }

        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            log.info("[SKIP] SkillMessageFlowService.routeToGateway: reason=no_toolSessionId, sessionId={}, triggering rebuild",
                    sessionId);
            gatewayRelayService.rebuildToolSession(sessionId, session, request.content());
            return;
        }

        String action;
        String payload;
        String effectiveUserId = effectiveUserId(userIdCookie, session);
        if (request.toolCallId() != null && !request.toolCallId().isBlank()) {
            action = GatewayActions.QUESTION_REPLY;
            String targetToolSessionId = request.subagentSessionId() != null
                    ? request.subagentSessionId()
                    : session.getToolSessionId();
            Map<String, Object> qr = new LinkedHashMap<>();
            qr.put("answer", request.content());
            qr.put("toolCallId", request.toolCallId());
            qr.put("toolSessionId", targetToolSessionId);
            if (request.questionId() != null && !request.questionId().isBlank()) {
                qr.put("questionId", request.questionId());
            }
            qr.put("assistantAccount", session.getAssistantAccount());
            qr.put("sendUserAccount", effectiveUserId);
            qr.put("businessExtParam", request.businessExtParam());
            payload = PayloadBuilder.buildPayloadWithObjects(objectMapper, qr);
        } else {
            action = GatewayActions.CHAT;
            Map<String, Object> payloadFields = new LinkedHashMap<>();
            payloadFields.put("text", request.content());
            payloadFields.put("toolSessionId", session.getToolSessionId());
            payloadFields.put("sendUserAccount", effectiveUserId);
            payloadFields.put("assistantAccount", session.getAssistantAccount());
            payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
            payloadFields.put("businessExtParam", request.businessExtParam());
            payload = PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields);
        }

        boolean isChat = GatewayActions.CHAT.equals(action);
        boolean isPersonalScope = scopeStrategy.generateToolSessionId() == null;
        List<String> allowedSlashCommands = (isChat && isPersonalScope)
                ? allowedSlashCommandsResolver.resolve(
                        session.getBusinessSessionDomain(),
                        session.getBusinessSessionType())
                : null;
        gatewayRelayService.sendInvokeToGateway(
                new InvokeCommand(session.getAk(), effectiveUserId, sessionId, action, payload,
                        null,
                        session.getBusinessSessionDomain(),
                        session.getBusinessSessionType(),
                        session.getBusinessSessionId(),
                        allowedSlashCommands,
                        session.getAssistantAccount(),
                        session.getAssistantAccount()));
        if (GatewayActions.QUESTION_REPLY.equals(action)) {
            recordQuestionReplyForHistory(numericSessionId, request);
        }

        try {
            eventPublisher.publishEvent(new ChatRequestTelemetryEvent(
                    session,
                    effectiveUserId,
                    scopeInfo == null ? null : scopeInfo.getBusinessTag(),
                    scopeInfo == null ? null : scopeInfo.getId()));
        } catch (Throwable t) {
            log.warn("[WelinkTelemetry] publish ChatRequestTelemetryEvent failed: sessionId={}, error={}",
                    sessionId, t.getMessage());
        }
    }

    private void recordQuestionReplyForHistory(Long sessionId, SendMessageCommand request) {
        try {
            persistenceService.recordQuestionReply(
                    sessionId, request.toolCallId(), request.content(), request.questionId());
        } catch (Exception e) {
            log.warn("Failed to record question reply for history: sessionId={}, toolCallId={}, error={}",
                    sessionId, request.toolCallId(), e.getMessage());
        }
    }

    private void recordPermissionReplyForHistory(Long sessionId, String permissionId, String response) {
        try {
            persistenceService.recordPermissionReply(sessionId, permissionId, response);
        } catch (Exception e) {
            log.warn("Failed to record permission reply for history: sessionId={}, permissionId={}, error={}",
                    sessionId, permissionId, e.getMessage());
        }
    }

    private AssistantScopeStrategy resolveScopeStrategy(SkillSession session) {
        AssistantInfo info = isDefaultAssistant(session) ? null : getAssistantInfo(session);
        return scopeDispatcher.getStrategy(
                session.getBusinessSessionDomain(), session.getBusinessSessionType(), info);
    }

    private boolean isDefaultAssistant(SkillSession session) {
        return ruleService.lookup(session.getBusinessSessionDomain(), session.getBusinessSessionType()).isPresent();
    }

    private ApiResponse<ProtocolMessageView> checkAssistantDeletion(
            String assistantAccount, String sessionId, String action) {
        if (assistantAccount == null || assistantAccount.isBlank()) {
            if (!assistantAccountResolverService.isSkipOnNullAssistantAccount()) {
                log.warn("[BLOCK] {}: reason=no_assistant_account, decision=block, sessionId={}", action, sessionId);
                return ApiResponse.error(400, "assistantAccount is required");
            }
            log.info("[SKIP] {}: reason=no_assistant_account, decision=allow, sessionId={}", action, sessionId);
            return null;
        }
        ExistenceStatus status = assistantAccountResolverService.check(assistantAccount);
        if (status == ExistenceStatus.NOT_EXISTS) {
            log.info("[SKIP] {}: reason=assistant_not_exists, decision=block, sessionId={}, assistantAccount={}",
                    action, sessionId, assistantAccount);
            return ApiResponse.error(410, assistantAccountResolverService.getDeletionMessage());
        }
        if (status == ExistenceStatus.UNKNOWN) {
            log.warn("[WARN] {}: reason=assistant_check_unknown, decision=allow-unknown, sessionId={}, assistantAccount={}",
                    action, sessionId, assistantAccount);
        }
        return null;
    }

    private ApiResponse<Map<String, Object>> checkAssistantDeletionForMap(
            String assistantAccount, String sessionId, String action) {
        if (assistantAccount == null || assistantAccount.isBlank()) {
            if (!assistantAccountResolverService.isSkipOnNullAssistantAccount()) {
                log.warn("[BLOCK] {}: reason=no_assistant_account, decision=block, sessionId={}", action, sessionId);
                return ApiResponse.error(400, "assistantAccount is required");
            }
            log.info("[SKIP] {}: reason=no_assistant_account, decision=allow, sessionId={}", action, sessionId);
            return null;
        }
        ExistenceStatus status = assistantAccountResolverService.check(assistantAccount);
        if (status == ExistenceStatus.NOT_EXISTS) {
            log.info("[SKIP] {}: reason=assistant_not_exists, decision=block, sessionId={}, assistantAccount={}",
                    action, sessionId, assistantAccount);
            return ApiResponse.error(410, assistantAccountResolverService.getDeletionMessage());
        }
        if (status == ExistenceStatus.UNKNOWN) {
            log.warn("[WARN] {}: reason=assistant_check_unknown, decision=allow-unknown, sessionId={}, assistantAccount={}",
                    action, sessionId, assistantAccount);
        }
        return null;
    }

    private boolean hasAssistantIdentity(SkillSession session) {
        return (session.getAk() != null && !session.getAk().isBlank())
                || (session.getAssistantAccount() != null && !session.getAssistantAccount().isBlank());
    }

    private AssistantInfo getAssistantInfo(SkillSession session) {
        if (session.getAssistantAccount() != null && !session.getAssistantAccount().isBlank()) {
            return assistantInfoService.getAssistantInfo(session.getAk(), session.getAssistantAccount());
        }
        return assistantInfoService.getAssistantInfo(session.getAk());
    }

    private String effectiveUserId(String userIdCookie, SkillSession session) {
        return userIdCookie != null && !userIdCookie.isBlank() ? userIdCookie : session.getUserId();
    }

    public record SendMessageCommand(String content,
                                     String toolCallId,
                                     String subagentSessionId,
                                     String questionId,
                                     JsonNode businessExtParam) {
    }

    public record PermissionReplyCommand(String response,
                                         String subagentSessionId,
                                         JsonNode businessExtParam) {
    }
}
