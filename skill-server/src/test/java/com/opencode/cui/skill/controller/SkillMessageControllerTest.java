package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.AssistantOfflineMessageProvider;
import com.opencode.cui.skill.service.GatewayApiClient;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;
import com.opencode.cui.skill.service.GatewayMessageRouter;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

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
    private AssistantAccountResolverService assistantAccountResolverService;

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

        controller = new SkillMessageController(
                messageService, sessionService, gatewayRelayService,
                gatewayApiClient, assistantIdProperties, imMessageService, new ObjectMapper(),
                accessControlService, messageRouter, assistantInfoService, scopeDispatcher,
                offlineMessageProvider, assistantAccountResolverService);
        // 默认 scopeDispatcher 返回 personal 策略（requiresOnlineCheck=true）
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy personalStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        lenient().when(scopeDispatcher.getStrategy(any())).thenReturn(personalStrategy);
        lenient().when(assistantInfoService.getCachedScope(any())).thenReturn("personal");
        // 默认 Agent 在线，离线场景在专用测试中覆盖
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

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("question_reply", cmdCaptor.getValue().action());
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
        when(gatewayApiClient.getAgentByAk("99")).thenReturn(null); // Agent 离线

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
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("biz-ak")).thenReturn("business");

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        // 不应调用 getAgentByAk — 云端助手跳过在线检查
        verify(gatewayApiClient, never()).getAgentByAk("biz-ak");
        verify(gatewayRelayService).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("sendToIm returns 200 with success")
    void sendToIm200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("chat-123");
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(imMessageService.sendMessage("chat-123", "Hello IM")).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        var response = controller.sendToIm("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().getData().get("success"));
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
        when(gatewayApiClient.getAgentByAk("99")).thenReturn(null); // Agent 离线

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
}
