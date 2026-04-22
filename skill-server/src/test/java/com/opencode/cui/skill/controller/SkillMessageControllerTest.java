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
import com.opencode.cui.skill.model.StreamMessage;
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

    private AssistantIdProperties assistantIdProperties;
    private SkillMessageController controller;

    @BeforeEach
    void setUp() {
        assistantIdProperties = new AssistantIdProperties();
        assistantIdProperties.setEnabled(true);
        assistantIdProperties.setTargetToolType("assistant");
        lenient().when(offlineMessageProvider.get()).thenReturn("MOCK_OFFLINE_MSG");
        controller = new SkillMessageController(
                messageService, sessionService, gatewayRelayService,
                gatewayApiClient, assistantIdProperties, imMessageService, new ObjectMapper(),
                accessControlService, messageRouter, assistantInfoService, scopeDispatcher,
                offlineMessageProvider);
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
}
