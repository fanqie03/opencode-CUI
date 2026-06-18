package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.AvailabilityResult;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.AllowedSlashCommandsResolver;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.AssistantAvailabilityService;
import com.opencode.cui.skill.service.AssistantOfflineMessageProvider;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;
import com.opencode.cui.skill.service.GatewayMessageRouter;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;
import org.mockito.ArgumentCaptor;

/**
 * SkillMessageController 单元测试（纯 Mockito，不加载 Spring 上下文）。
 */
@ExtendWith(MockitoExtension.class)
class SkillMessageControllerTest {

    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private GatewayRelayService gatewayRelayService;
    @Mock
    private GatewayApiClient gatewayApiClient;
    @Mock
    private ImMessageService imMessageService;
    @Mock
    private SessionAccessControlService accessControlService;
    @Mock
    private GatewayMessageRouter messageRouter;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private AssistantOfflineMessageProvider offlineMessageProvider;
    @Mock
    private AssistantAvailabilityService availabilityService;
    @Mock
    private AssistantAccountResolverService assistantAccountResolverService;
    @Mock
    private DefaultAssistantRuleService ruleService;
     @Mock
     private AllowedSlashCommandsResolver allowedSlashCommandsResolver;
     @Mock
     private MessagePersistenceService persistenceService;
     @Mock
     private MessageTurnLifecycle messageTurnLifecycle;

     private AssistantIdProperties assistantIdProperties;
     private SkillMessageController controller;

    @BeforeEach
    void setUp() {
        assistantIdProperties = new AssistantIdProperties();
        assistantIdProperties.setEnabled(true);
        assistantIdProperties.setTargetToolType("assistant");
        lenient().when(offlineMessageProvider.get()).thenReturn("MOCK_OFFLINE_MSG");
        // 默认 resolver 行为：开关 ON（null 放行），非 null 默认 EXISTS
        lenient().when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(true);
        lenient().when(assistantAccountResolverService.getDeletionMessage()).thenReturn("该助理已被删除");
        lenient().when(assistantAccountResolverService.check(any())).thenReturn(ExistenceStatus.EXISTS);
        // 默认 ruleService 未命中规则（PR3 老路径行为不变）
        lenient().when(ruleService.lookup(any(), any())).thenReturn(Optional.empty());
        // 默认 slash resolver 返 null（未配置）
        lenient().when(allowedSlashCommandsResolver.resolve(any(), any())).thenReturn(null);

         controller = new SkillMessageController(
                 messageService, sessionService, gatewayRelayService,
                 gatewayApiClient, assistantIdProperties, imMessageService, new ObjectMapper(),
                 accessControlService, messageRouter, assistantInfoService, scopeDispatcher,
                 offlineMessageProvider, availabilityService, assistantAccountResolverService, ruleService,
                 allowedSlashCommandsResolver, persistenceService,
                 org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                 messageTurnLifecycle);
        // 默认 scopeDispatcher 返回 personal 策略（requiresOnlineCheck=true）
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy personalStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(personalStrategy);
        lenient().when(scopeDispatcher.getStrategy(
                nullable(String.class), nullable(String.class), nullable(AssistantInfo.class)))
                .thenAnswer(invocation -> scopeDispatcher.getStrategy(
                        (AssistantInfo) invocation.getArgument(2)));
        AssistantInfo defaultPersonalInfo = new AssistantInfo();
        defaultPersonalInfo.setAssistantScope("personal");
        lenient().when(assistantInfoService.getAssistantInfo(any())).thenReturn(defaultPersonalInfo);
        // 默认 Agent 在线，离线场景在专用测试中覆盖
        lenient().when(availabilityService.resolve(any()))
                .thenReturn(AvailabilityResult.ofOnline());
        lenient().when(gatewayApiClient.getAgentByAk(any()))
                .thenReturn(AgentSummary.builder().ak("99").toolType("assistant").build());
    }

    @Test
    @DisplayName("sendMessage returns 200 and invokes AI gateway")
    void sendMessage200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(1L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (com.opencode.cui.skill.model.ApiResponse<ProtocolMessageView>) response.getBody();
        assertNotNull(body);
        assertEquals("1", body.getData().getWelinkSessionId());
        assertEquals("user", body.getData().getRole());
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("99", cmdCaptor.getValue().ak());
        assertEquals("1", cmdCaptor.getValue().userId());
        assertEquals("1", cmdCaptor.getValue().sessionId());
        assertEquals("chat", cmdCaptor.getValue().action());
    }

    @Test
    @DisplayName("sendMessage supports remote assistant session without AK")
    void sendMessageRemoteAssistantWithoutAkRoutesByAssistantAccount() {
        SkillSession session = new SkillSession();
        session.setId(10L);
        session.setAk(null);
        session.setAssistantAccount("bot-001");
        session.setUserId("1");
        session.setToolSessionId("tool-remote");
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("dm");
        session.setBusinessSessionId("dm-001");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(10L, "1")).thenReturn(session);

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("remote-tag");
        when(assistantInfoService.getAssistantInfo(null, "bot-001")).thenReturn(info);

        SkillMessage msg = SkillMessage.builder()
                .id(10L).sessionId(10L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(10L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "10", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertNull(cmdCaptor.getValue().ak());
        assertEquals("bot-001", cmdCaptor.getValue().assistantAccount());
        assertEquals("bot-001", cmdCaptor.getValue().partnerAccount());
        verify(availabilityService, never()).resolve(any());
    }

    @Test
    @DisplayName("sendMessage with toolCallId routes to question_reply")
    void sendMessageWithToolCallIdSendsQuestionReply() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(2L).sessionId(1L).role(SkillMessage.Role.USER).content("yes").build();
        when(messageService.saveUserMessage(eq(1L), eq("yes"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("yes");
        request.setToolCallId("tc-001");
        request.setQuestionId("q-001");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("question_reply", cmdCaptor.getValue().action());
        verify(persistenceService).recordQuestionReply(1L, "tc-001", "yes", "q-001");
    }

    @Test
    @DisplayName("sendMessage returns 400 for empty content")
    void sendMessageEmptyContent400() {
        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getCode());
    }

    @Test
    @DisplayName("sendMessage returns 409 for closed session")
    void sendMessageClosedSession409() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.CLOSED);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(409, ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getCode());
    }

    @Test
    @DisplayName("getMessageHistory returns 200")
    void getMessages200() {
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(new SkillSession());
        ProtocolMessageView view = new ProtocolMessageView();
        view.setWelinkSessionId("1");
        view.setRole("assistant");
        view.setContentType("markdown");
        view.setParts(List.of());
        when(messageService.getMessageHistoryWithParts(1L, 0, 50))
                .thenReturn(new PageResult<>(List.of(view), 1, 0, 50));

        var response = controller.getMessages("1", "1", 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("1", response.getBody().getData().getContent().get(0).getWelinkSessionId());
        assertEquals("assistant", response.getBody().getData().getContent().get(0).getRole());
        assertEquals("markdown", response.getBody().getData().getContent().get(0).getContentType());
    }

    @Test
    @DisplayName("getCursorMessageHistory returns 200")
    void getCursorMessages200() {
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(new SkillSession());
        ProtocolMessageView view = new ProtocolMessageView();
        view.setWelinkSessionId("1");
        view.setRole("assistant");
        view.setContentType("markdown");
        view.setParts(List.of());
        when(messageService.getCursorMessageHistoryWithParts(1L, null, 50))
                .thenReturn(MessageHistoryResult.<ProtocolMessageView>builder()
                        .content(List.of(view))
                        .size(50)
                        .hasMore(false)
                        .build());

        var response = controller.getCursorMessages("1", "1", null, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("1", response.getBody().getData().getContent().get(0).getWelinkSessionId());
        assertEquals("assistant", response.getBody().getData().getContent().get(0).getRole());
        assertEquals("markdown", response.getBody().getData().getContent().get(0).getContentType());
    }

    @Test
    @DisplayName("getMessageHistory returns 400 when size exceeds limit")
    void getMessagesRejectsOversizedRequest() {
        var response = controller.getMessages("1", "1", 0, 201);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getCode());
        verifyNoInteractions(accessControlService, messageService);
    }

    @Test
    @DisplayName("getCursorMessageHistory returns 400 when size exceeds limit")
    void getCursorMessagesRejectsOversizedRequest() {
        var response = controller.getCursorMessages("1", "1", null, 201);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getCode());
        verifyNoInteractions(accessControlService, messageService);
    }

    @Test
    @DisplayName("replyPermission returns 200 with once response")
    void permissionReplyOnce200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("1", response.getBody().getData().get("welinkSessionId"));
        assertEquals("p-abc", response.getBody().getData().get("permissionId"));
        assertEquals("once", response.getBody().getData().get("response"));
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("permission_reply", cmdCaptor.getValue().action());
        verify(gatewayRelayService).publishProtocolMessage(eq("1"), any());
        verify(persistenceService).recordPermissionReply(1L, "p-abc", "once");
    }

    @Test
    @DisplayName("replyPermission returns 400 when response is null")
    void permissionReplyMissingResponse400() {
        var request = new SkillMessageController.PermissionReplyRequest();
        // response is null

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
    }

    @Test
    @DisplayName("replyPermission returns 400 for invalid response value")
    void permissionReplyInvalidResponse400() {
        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("invalid");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
    }

    @Test
    @DisplayName("replyPermission returns 409 for closed session")
    void permissionReplyClosedSession409() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.CLOSED);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(409, response.getBody().getCode());
    }

    @Test
    @DisplayName("replyPermission returns 503 when personal agent is offline")
    void permissionReplyAgentOffline503() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(availabilityService.resolve("99")).thenReturn(
                AvailabilityResult.ofOfflineDefault("MOCK_OFFLINE_MSG", null)); // Agent 离线

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(503, response.getBody().getCode());
        assertEquals("MOCK_OFFLINE_MSG", response.getBody().getErrormsg());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("replyPermission skips online check for business assistant (always online)")
    void permissionReplyBusinessAssistantSkipsOnlineCheck() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("biz-ak");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        // 设置 business scope 策略（requiresOnlineCheck=false）
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy businessStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("biz-ak")).thenReturn(bizInfo);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        // 不应调用 availabilityService — 云端助手跳过在线检查
        verify(availabilityService, never()).resolve("biz-ak");
        verify(gatewayRelayService).sendInvokeToGateway(any());
    }

    // ==================== send-to-im ====================

    @Test
    @DisplayName("sendToIm: group_<g>_<u> + cookie=<u> → 200, IM body 含 targetType=group/targetId/senderAccount")
    void sendToImGroup200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g123_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(imMessageService.sendMessage("group", "g123", "u456", "Hello IM")).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertEquals(true, response.getBody().getData().get("success"));
        verify(imMessageService).sendMessage("group", "g123", "u456", "Hello IM");
    }

    @Test
    @DisplayName("sendToIm: direct_<t>_<u> + cookie=<u> → 200, IM body 含 targetType=direct")
    void sendToImDirect200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("direct_t789_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(imMessageService.sendMessage("direct", "t789", "u456", "hi")).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        verify(imMessageService).sendMessage("direct", "t789", "u456", "hi");
    }

    @Test
    @DisplayName("sendToIm: businessSessionId 前缀非法 → 业务码 400 'Invalid businessSessionId format'")
    void sendToImInvalidPrefix400() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("chat-123");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertEquals("Invalid businessSessionId format", response.getBody().getErrormsg());
        verify(imMessageService, never()).sendMessage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendToIm: businessSessionId 段数 ≠ 3 → 业务码 400")
    void sendToImWrongSegmentCount400() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g_1_2");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertEquals("Invalid businessSessionId format", response.getBody().getErrormsg());
    }

    @Test
    @DisplayName("sendToIm: cookie userId ≠ businessSessionId 末段 senderAccount → 业务码 403 'Sender mismatch'")
    void sendToImSenderMismatch403() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setUserId("attacker");
        session.setBusinessSessionId("group_g123_victim");
        when(accessControlService.requireSessionAccess(1L, "attacker")).thenReturn(session);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        var response = controller.sendToIm("attacker", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(403, response.getBody().getCode());
        assertEquals("Sender mismatch", response.getBody().getErrormsg());
        verify(imMessageService, never()).sendMessage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendToIm: cookie userId 缺失 → ProtocolException 400 'userId is required'（由 requireSessionAccess 抛）")
    void sendToImMissingCookie400() {
        when(accessControlService.requireSessionAccess(1L, null))
                .thenThrow(new com.opencode.cui.skill.service.ProtocolException(400, "userId is required"));

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        com.opencode.cui.skill.service.ProtocolException ex = assertThrows(
                com.opencode.cui.skill.service.ProtocolException.class,
                () -> controller.sendToIm(null, "1", request));
        assertEquals(400, ex.getCode());
        assertEquals("userId is required", ex.getMessage());
        verify(imMessageService, never()).sendMessage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendToIm: content blank → 业务码 400 'Content is required'")
    void sendToImBlankContent400() {
        var request = new SkillMessageController.SendToImRequest();
        request.setContent("");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertEquals("Content is required", response.getBody().getErrormsg());
        verifyNoInteractions(accessControlService, imMessageService);
    }

    @Test
    @DisplayName("sendToIm: content.length > 4000 → 业务码 400 'Content too long'")
    void sendToImContentTooLong400() {
        String big = "x".repeat(4001);
        var request = new SkillMessageController.SendToImRequest();
        request.setContent(big);

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertTrue(response.getBody().getErrormsg().contains("Content too long"));
        verifyNoInteractions(accessControlService, imMessageService);
    }

    @Test
    @DisplayName("sendToIm: IM 下游返回 false → 业务码 500 'Failed to send message to IM'")
    void sendToImDownstreamFails500() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g123_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(imMessageService.sendMessage("group", "g123", "u456", "hi")).thenReturn(false);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(500, response.getBody().getCode());
        assertEquals("Failed to send message to IM", response.getBody().getErrormsg());
    }

    // ==================== 助理删除校验 ====================

    @Test
    @DisplayName("sendMessage: null assistantAccount + 开关 ON → 放行 200")
    void sendMessageNullAssistantAccountSkipOnAllows() {
        when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(true);
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        // assistantAccount 为 null
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(1L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getCode());
        verify(messageService).saveUserMessage(eq(1L), eq("Hello"));
    }

    @Test
    @DisplayName("sendMessage: null assistantAccount + 开关 OFF → 400 'assistantAccount is required'")
    void sendMessageNullAssistantAccountSkipOffReturns400() {
        when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(false);
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        // assistantAccount 为 null
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getCode());
        verify(messageService, never()).saveUserMessage(anyLong(), anyString());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("sendMessage: NOT_EXISTS → 410（不 saveUserMessage / 不广播 / 不 routeToGateway）")
    void sendMessageAssistantNotExistsReturns410() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setAssistantAccount("deleted-acc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(assistantAccountResolverService.check("deleted-acc")).thenReturn(ExistenceStatus.NOT_EXISTS);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(410, ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getCode());
        assertEquals("该助理已被删除",
                ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getErrormsg());
        verify(messageService, never()).saveUserMessage(anyLong(), anyString());
        verify(messageRouter, never()).broadcastStreamMessage(anyString(), anyString(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("sendMessage: UNKNOWN → 放行 200（best-effort 阻断）")
    void sendMessageAssistantUnknownAllows() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setAssistantAccount("unknown-acc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(assistantAccountResolverService.check("unknown-acc")).thenReturn(ExistenceStatus.UNKNOWN);
        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(1L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getCode());
        verify(messageService).saveUserMessage(eq(1L), eq("Hello"));
    }

    @Test
    @DisplayName("sendMessage: EXISTS → 放行 200（happy path）")
    void sendMessageAssistantExistsAllows() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setAssistantAccount("exists-acc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(assistantAccountResolverService.check("exists-acc")).thenReturn(ExistenceStatus.EXISTS);
        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(1L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, ((com.opencode.cui.skill.model.ApiResponse<?>) response.getBody()).getCode());
    }

    @Test
    @DisplayName("replyPermission: null assistantAccount + 开关 ON → 放行 200")
    void replyPermissionNullAssistantAccountSkipOnAllows() {
        when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(true);
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
    }

    @Test
    @DisplayName("replyPermission: null assistantAccount + 开关 OFF → 400")
    void replyPermissionNullAssistantAccountSkipOffReturns400() {
        when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(false);
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("replyPermission: NOT_EXISTS → 410（不发 invoke / 不广播）")
    void replyPermissionAssistantNotExistsReturns410() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setAssistantAccount("deleted-acc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(assistantAccountResolverService.check("deleted-acc")).thenReturn(ExistenceStatus.NOT_EXISTS);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(410, response.getBody().getCode());
        assertEquals("该助理已被删除", response.getBody().getErrormsg());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        verify(gatewayRelayService, never()).publishProtocolMessage(anyString(), any());
    }

    @Test
    @DisplayName("replyPermission: NOT_EXISTS 优先于 online check（agent 离线也返 410 而非 503）")
    void replyPermissionNotExistsBeatsAgentOffline() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setAssistantAccount("deleted-acc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(assistantAccountResolverService.check("deleted-acc")).thenReturn(ExistenceStatus.NOT_EXISTS);
        lenient().when(gatewayApiClient.getAgentByAk("99")).thenReturn(null); // 离线（但永远不被查）

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(410, response.getBody().getCode());
        verify(gatewayApiClient, never()).getAgentByAk(anyString());
    }

    @Test
    @DisplayName("replyPermission: UNKNOWN → 放行")
    void replyPermissionAssistantUnknownAllows() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setAssistantAccount("unknown-acc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(assistantAccountResolverService.check("unknown-acc")).thenReturn(ExistenceStatus.UNKNOWN);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
    }

    @Test
    @DisplayName("sendMessage broadcasts error via WebSocket and saves system message when agent is offline")
    void sendMessageAgentOfflineBroadcastsError() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(availabilityService.resolve("99")).thenReturn(
                AvailabilityResult.ofOfflineDefault("MOCK_OFFLINE_MSG", null)); // Agent 离线

        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(1L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        // 验证保存了系统错误消息
        verify(messageService).saveSystemMessage(eq(1L), eq("MOCK_OFFLINE_MSG"));
        // 验证通过 WebSocket 广播了错误
        ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(gatewayRelayService).publishProtocolMessage(eq("1"), msgCaptor.capture());
        assertEquals(StreamMessage.Types.ERROR, msgCaptor.getValue().getType());
        assertEquals("MOCK_OFFLINE_MSG", msgCaptor.getValue().getError());
        // 验证没有调用 Gateway 发送 invoke
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    // ==================== businessExtParam 透传 ====================

    @Test
    @DisplayName("T-6: sendMessage chat 分支透传 businessExtParam")
    void sendMessageChatPassesBusinessExtParam() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode bep = om.readTree("{\"k\":\"v\"}");

        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("hi").build();
        when(messageService.saveUserMessage(eq(1L), eq("hi"))).thenReturn(msg);

        SkillMessageController.SendMessageRequest request = new SkillMessageController.SendMessageRequest();
        request.setContent("hi");
        request.setBusinessExtParam(bep);

        controller.sendMessage("1", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(capt.getValue().payload());
        assertNotNull(payload.get("businessExtParam"));
        assertEquals("v", payload.get("businessExtParam").get("k").asText());
    }

    @Test
    @DisplayName("T-7: sendMessage question_reply 分支透传 businessExtParam")
    void sendMessageQuestionReplyPassesBusinessExtParam() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode bep = om.readTree("{\"q\":\"x\"}");

        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(2L).sessionId(1L).role(SkillMessage.Role.USER).content("reply").build();
        when(messageService.saveUserMessage(eq(1L), eq("reply"))).thenReturn(msg);

        SkillMessageController.SendMessageRequest request = new SkillMessageController.SendMessageRequest();
        request.setContent("reply");
        request.setToolCallId("tc-1");
        request.setBusinessExtParam(bep);

        controller.sendMessage("1", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(capt.getValue().payload());
        assertNotNull(payload.get("businessExtParam"));
        assertEquals("x", payload.get("businessExtParam").get("q").asText());
    }

    @Test
    @DisplayName("T-8: replyPermission 透传 businessExtParam")
    void replyPermissionPassesBusinessExtParam() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode bep = om.readTree("{\"p\":true}");

        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        SkillMessageController.PermissionReplyRequest request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");
        request.setBusinessExtParam(bep);

        controller.replyPermission("1", "1", "perm-1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(capt.getValue().payload());
        assertNotNull(payload.get("businessExtParam"));
        assertTrue(payload.get("businessExtParam").get("p").asBoolean());
    }

    // ==================== PR3 D8: payload 补 assistantAccount + sendUserAccount ====================

    @Test
    @DisplayName("PR3 D8: sendMessage chat 分支 → payload 含 assistantAccount + sendUserAccount，InvokeCommand 带 domain/domainType")
    void sendMessageChatPayloadHasAssistantAccountAndSendUser() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK_V");
        session.setUserId("u-1");
        session.setAssistantAccount("ACC_V");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "cookie-u-1")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(1L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        controller.sendMessage("cookie-u-1", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        InvokeCommand cmd = capt.getValue();
        assertEquals("chat", cmd.action());
        // domain/domainType 塞入
        assertEquals("helpdesk", cmd.domain());
        assertEquals("direct", cmd.domainType());
        // payload 含 assistantAccount + sendUserAccount（cookie 优先）
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(cmd.payload());
        assertEquals("ACC_V", payload.get("assistantAccount").asText());
        assertEquals("cookie-u-1", payload.get("sendUserAccount").asText());
    }

    @Test
    @DisplayName("PR3 D8: sendMessage question_reply 分支 → payload 补 assistantAccount + sendUserAccount (修复)")
    void sendMessageQuestionReplyPayloadHasAssistantAccountAndSendUser() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK_V");
        session.setUserId("u-1");
        session.setAssistantAccount("ACC_V");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "cookie-u-1")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(2L).sessionId(1L).role(SkillMessage.Role.USER).content("yes").build();
        when(messageService.saveUserMessage(eq(1L), eq("yes"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("yes");
        request.setToolCallId("tc-1");

        controller.sendMessage("cookie-u-1", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        InvokeCommand cmd = capt.getValue();
        assertEquals("question_reply", cmd.action());
        assertEquals("helpdesk", cmd.domain());
        assertEquals("direct", cmd.domainType());
        // D8 修复：payload 补 assistantAccount + sendUserAccount
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(cmd.payload());
        assertEquals("ACC_V", payload.get("assistantAccount").asText());
        assertEquals("cookie-u-1", payload.get("sendUserAccount").asText());
    }

    @Test
    @DisplayName("sendMessage question_reply: questionId 非空 → payload 含 questionId（personal scope 快路径）")
    void sendMessageQuestionReplyPayloadHasRequestIdWhenProvided() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK");
        session.setUserId("u");
        session.setAssistantAccount("ACC");
        session.setToolSessionId("ts");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "u")).thenReturn(session);
        SkillMessage msg = SkillMessage.builder().id(2L).sessionId(1L).role(SkillMessage.Role.USER).content("a").build();
        when(messageService.saveUserMessage(eq(1L), eq("a"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("a");
        request.setToolCallId("tc-1");
        request.setQuestionId("req-uuid-1");
        controller.sendMessage("u", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(capt.getValue().payload());
        assertEquals("req-uuid-1", payload.get("questionId").asText());
    }

    @Test
    @DisplayName("sendMessage question_reply: questionId 为 null → payload 无 questionId key（D8）")
    void sendMessageQuestionReplyPayloadOmitsRequestIdWhenAbsent() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK");
        session.setUserId("u");
        session.setAssistantAccount("ACC");
        session.setToolSessionId("ts");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "u")).thenReturn(session);
        SkillMessage msg = SkillMessage.builder().id(2L).sessionId(1L).role(SkillMessage.Role.USER).content("a").build();
        when(messageService.saveUserMessage(eq(1L), eq("a"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("a");
        request.setToolCallId("tc-1");
        // questionId 不设置（null）
        controller.sendMessage("u", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(capt.getValue().payload());
        org.junit.jupiter.api.Assertions.assertFalse(payload.has("questionId"), "payload 不应含 questionId key");
    }

    @Test
    @DisplayName("sendMessage question_reply: questionId 为空白 → payload 无 questionId key（D8）")
    void sendMessageQuestionReplyPayloadOmitsRequestIdWhenBlank() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK");
        session.setUserId("u");
        session.setAssistantAccount("ACC");
        session.setToolSessionId("ts");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "u")).thenReturn(session);
        SkillMessage msg = SkillMessage.builder().id(2L).sessionId(1L).role(SkillMessage.Role.USER).content("a").build();
        when(messageService.saveUserMessage(eq(1L), eq("a"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("a");
        request.setToolCallId("tc-1");
        request.setQuestionId("   "); // blank
        controller.sendMessage("u", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(capt.getValue().payload());
        org.junit.jupiter.api.Assertions.assertFalse(payload.has("questionId"), "blank questionId 应被视为缺失");
    }

    @Test
    @DisplayName("PR3 D8: replyPermission → payload 补 assistantAccount + sendUserAccount，InvokeCommand 带 domain/domainType")
    void replyPermissionPayloadHasAssistantAccountAndSendUser() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK_V");
        session.setUserId("u-1");
        session.setAssistantAccount("ACC_V");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "cookie-u-1")).thenReturn(session);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        controller.replyPermission("cookie-u-1", "1", "perm-1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        InvokeCommand cmd = capt.getValue();
        assertEquals("permission_reply", cmd.action());
        assertEquals("helpdesk", cmd.domain());
        assertEquals("direct", cmd.domainType());
        com.fasterxml.jackson.databind.JsonNode payload = om.readTree(cmd.payload());
        assertEquals("ACC_V", payload.get("assistantAccount").asText());
        assertEquals("cookie-u-1", payload.get("sendUserAccount").asText());
    }

    // ==================== PR3 Req 12: deletion check 短路 ====================

    @Test
    @DisplayName("PR3 Req 12: sendMessage 命中默认助手规则 → 跳过 deletion check (resolver.check 不被调)")
    void sendMessageDefaultAssistantSkipsDeletionCheck() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK_V");
        session.setUserId("u-1");
        session.setAssistantAccount("ACC_V");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "u-1")).thenReturn(session);
        // 命中规则
        when(ruleService.lookup("helpdesk", "direct"))
                .thenReturn(Optional.of(new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square")));
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy defaultStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        when(defaultStrategy.requiresOnlineCheck()).thenReturn(false);
        when(defaultStrategy.generateToolSessionId()).thenReturn("cloud-pre-gen");
        when(scopeDispatcher.getStrategy(eq("helpdesk"), eq("direct"), isNull()))
                .thenReturn(defaultStrategy);

        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("hi").build();
        when(messageService.saveUserMessage(eq(1L), eq("hi"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("hi");

        controller.sendMessage("u-1", "1", request);
        // resolver.check 完全不被调用
        verify(assistantAccountResolverService, never()).check(any());
        verify(assistantAccountResolverService, never()).isSkipOnNullAssistantAccount();
        verify(assistantInfoService, never()).getAssistantInfo("AK_V");
        verify(availabilityService, never()).resolve("AK_V");
        // 消息仍被保存
        verify(messageService).saveUserMessage(eq(1L), eq("hi"));
    }

    @Test
    @DisplayName("PR3 Req 12: sendMessage 未命中规则 → 走老 deletion check (resolver.check 被调)")
    void sendMessageLegacyStillRunsDeletionCheck() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("u-1");
        session.setAssistantAccount("real-acc");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("miniapp");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "u-1")).thenReturn(session);
        when(ruleService.lookup(any(), any())).thenReturn(Optional.empty());

        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(1L).role(SkillMessage.Role.USER).content("hi").build();
        when(messageService.saveUserMessage(eq(1L), eq("hi"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("hi");

        controller.sendMessage("u-1", "1", request);
        // resolver.check 被调
        verify(assistantAccountResolverService).check("real-acc");
    }

    @Test
    @DisplayName("PR3 Req 12: replyPermission 命中默认助手规则 → 跳过 deletion check")
    void replyPermissionDefaultAssistantSkipsDeletionCheck() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("AK_V");
        session.setUserId("u-1");
        session.setAssistantAccount("ACC_V");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "u-1")).thenReturn(session);
        when(ruleService.lookup("helpdesk", "direct"))
                .thenReturn(Optional.of(new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square")));
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy defaultStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        when(defaultStrategy.requiresOnlineCheck()).thenReturn(false);
        when(scopeDispatcher.getStrategy(eq("helpdesk"), eq("direct"), isNull()))
                .thenReturn(defaultStrategy);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("u-1", "1", "perm-1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        // resolver.check 完全不被调用
        verify(assistantAccountResolverService, never()).check(any());
        verify(assistantInfoService, never()).getAssistantInfo("AK_V");
        verify(availabilityService, never()).resolve("AK_V");
    }

    // ==================== v3 allowed-slash-commands: routeToGateway action guard + scope gating ====================

    @Test
    @DisplayName("v3 AC1: routeToGateway / personal scope CHAT + sysconfig 命中 → InvokeCommand 含 allowedSlashCommands")
    void routeToGateway_personalScopeChatWithConfig_invokeContainsList() {
        SkillSession session = new SkillSession();
        session.setId(9001L);
        session.setAk("ak-v3-1");
        session.setUserId("u-v3-1");
        session.setToolSessionId("tool-v3-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("group");
        session.setBusinessSessionId("biz-v3-1");
        when(accessControlService.requireSessionAccess(9001L, "u-v3-1")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(1L).sessionId(9001L).role(SkillMessage.Role.USER).content("hello").build();
        when(messageService.saveUserMessage(eq(9001L), eq("hello"))).thenReturn(msg);

        // sysconfig 命中
        when(allowedSlashCommandsResolver.resolve("im", "group"))
                .thenReturn(java.util.List.of("plan", "ask"));

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("hello");
        controller.sendMessage("u-v3-1", "9001", request);

        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        InvokeCommand cmd = captor.getValue();
        assertEquals("chat", cmd.action());
        assertNotNull(cmd.allowedSlashCommands());
        assertEquals(2, cmd.allowedSlashCommands().size());
        assertEquals("plan", cmd.allowedSlashCommands().get(0));
    }

    @Test
    @DisplayName("v3 AC3: routeToGateway / personal scope CHAT + sysconfig 未配置 → InvokeCommand allowedSlashCommands=null")
    void routeToGateway_personalScopeChatNoConfig_invokeListNull() {
        SkillSession session = new SkillSession();
        session.setId(9002L);
        session.setAk("ak-v3-2");
        session.setUserId("u-v3-2");
        session.setToolSessionId("tool-v3-2");
        session.setStatus(SkillSession.Status.ACTIVE);
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("direct");
        session.setBusinessSessionId("biz-v3-2");
        when(accessControlService.requireSessionAccess(9002L, "u-v3-2")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(2L).sessionId(9002L).role(SkillMessage.Role.USER).content("hello").build();
        when(messageService.saveUserMessage(eq(9002L), eq("hello"))).thenReturn(msg);

        // resolver 默认返 null

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("hello");
        controller.sendMessage("u-v3-2", "9002", request);

        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals("chat", captor.getValue().action());
        assertNull(captor.getValue().allowedSlashCommands());
    }

    @Test
    @DisplayName("v3 AC11: routeToGateway / question_reply 分支 → resolver 不被 invoke + allowedSlashCommands=null（action guard）")
    void routeToGateway_questionReplyAction_skipsResolverAndListNull() {
        SkillSession session = new SkillSession();
        session.setId(9003L);
        session.setAk("ak-v3-3");
        session.setUserId("u-v3-3");
        session.setToolSessionId("tool-v3-3");
        session.setStatus(SkillSession.Status.ACTIVE);
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("group");
        session.setBusinessSessionId("biz-v3-3");
        when(accessControlService.requireSessionAccess(9003L, "u-v3-3")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(3L).sessionId(9003L).role(SkillMessage.Role.USER).content("yes").build();
        when(messageService.saveUserMessage(eq(9003L), eq("yes"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("yes");
        request.setToolCallId("tc-v3");
        controller.sendMessage("u-v3-3", "9003", request);

        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals("question_reply", captor.getValue().action());
        // AC11: action guard 命中——非 CHAT 路径不调 resolver
        assertNull(captor.getValue().allowedSlashCommands());
        verify(allowedSlashCommandsResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("v3 AC14: routeToGateway / business scope CHAT（strategy.generateToolSessionId 非 null）→ resolver 不被 invoke + list=null")
    void routeToGateway_businessScopeChat_skipsResolver() {
        SkillSession session = new SkillSession();
        session.setId(9004L);
        session.setAk("ak-v3-4");
        session.setUserId("u-v3-4");
        session.setToolSessionId("tool-v3-4");
        session.setStatus(SkillSession.Status.ACTIVE);
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("group");
        session.setBusinessSessionId("biz-v3-4");
        when(accessControlService.requireSessionAccess(9004L, "u-v3-4")).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(4L).sessionId(9004L).role(SkillMessage.Role.USER).content("hi").build();
        when(messageService.saveUserMessage(eq(9004L), eq("hi"))).thenReturn(msg);

        // business scope strategy：generateToolSessionId 返非 null
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy businessStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        lenient().when(businessStrategy.generateToolSessionId()).thenReturn("cloud-pre-gen");
        when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(businessStrategy);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("hi");
        controller.sendMessage("u-v3-4", "9004", request);

        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals("chat", captor.getValue().action());
        // business scope: caller null + 下游 BusinessScopeStrategy 4 参 builder 也不写
        assertNull(captor.getValue().allowedSlashCommands());
        verify(allowedSlashCommandsResolver, never()).resolve(any(), any());
    }
}
