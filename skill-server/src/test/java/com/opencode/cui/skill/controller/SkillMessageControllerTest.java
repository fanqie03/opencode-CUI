package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.ImMessageService;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SysConfigService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillMessageFlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillMessageControllerTest {

    @Mock
    private SkillMessageService messageService;
    @Mock
    private ImMessageService imMessageService;
    @Mock
    private SessionAccessControlService accessControlService;
    @Mock
    private SkillMessageFlowService flowService;
    @Mock
    private SysConfigService sysConfigService;

    private SkillMessageController controller;

    @BeforeEach
    void setUp() {
        lenient().when(sysConfigService.getValue("msg_ext", "enabled")).thenReturn("0");
        controller = new SkillMessageController(
                messageService, imMessageService, new ObjectMapper(),
                accessControlService, flowService, sysConfigService);
    }

    // ==================== sendMessage ====================

    @Test
    @DisplayName("sendMessage returns 200 and delegates to flowService")
    void sendMessage200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        ProtocolMessageView view = new ProtocolMessageView();
        view.setWelinkSessionId("1");
        view.setRole("user");
        when(flowService.sendMessage(any(), any(), anyLong(), any(), any()))
                .thenReturn(ApiResponse.ok(view));

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (ApiResponse<ProtocolMessageView>) response.getBody();
        assertNotNull(body);
        assertEquals("1", body.getData().getWelinkSessionId());
        assertEquals("user", body.getData().getRole());
        verify(flowService).sendMessage(eq(session), eq("1"), eq(1L), any(), eq("1"));
    }

    @Test
    @DisplayName("sendMessage returns 400 for empty content")
    void sendMessageEmptyContent400() {
        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, ((ApiResponse<?>) response.getBody()).getCode());
        verifyNoInteractions(flowService);
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
        assertEquals(409, ((ApiResponse<?>) response.getBody()).getCode());
        verifyNoInteractions(flowService);
    }

    @Test
    @DisplayName("sendMessage passes SendMessageCommand to flowService")
    void sendMessagePassesCommandToFlowService() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(flowService.sendMessage(any(), any(), anyLong(), any(), any()))
                .thenReturn(ApiResponse.ok(new ProtocolMessageView()));

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");
        request.setToolCallId("tc-1");
        request.setQuestionId("q-1");
        request.setSubagentSessionId("sub-1");

        controller.sendMessage("1", "1", request);

        verify(flowService).sendMessage(eq(session), eq("1"), eq(1L),
                argThat(cmd -> "Hello".equals(cmd.content())
                        && "tc-1".equals(cmd.toolCallId())
                        && "q-1".equals(cmd.questionId())
                        && "sub-1".equals(cmd.subagentSessionId())),
                eq("1"));
    }

    @Test
    @DisplayName("sendMessage: flowService returns error (e.g. assistant deleted) → pass-through")
    void sendMessageFlowServiceErrorPassthrough() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(flowService.sendMessage(any(), any(), anyLong(), any(), any()))
                .thenReturn(ApiResponse.error(410, "该助理已被删除"));

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(410, ((ApiResponse<?>) response.getBody()).getCode());
        assertEquals("该助理已被删除", ((ApiResponse<?>) response.getBody()).getErrormsg());
    }

    // ==================== replyPermission ====================

    @Test
    @DisplayName("replyPermission returns 200 and delegates to flowService")
    void permissionReplyOnce200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(flowService.replyPermission(any(), any(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(Map.of("welinkSessionId", "1", "permissionId", "p-abc", "response", "once")));

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("1", response.getBody().getData().get("welinkSessionId"));
        assertEquals("p-abc", response.getBody().getData().get("permissionId"));
        assertEquals("once", response.getBody().getData().get("response"));
        verify(flowService).replyPermission(eq(session), eq("1"), eq("p-abc"), any(), eq("1"));
    }

    @Test
    @DisplayName("replyPermission returns 400 when response is null")
    void permissionReplyMissingResponse400() {
        var request = new SkillMessageController.PermissionReplyRequest();
        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        verifyNoInteractions(flowService);
    }

    @Test
    @DisplayName("replyPermission returns 400 for invalid response value")
    void permissionReplyInvalidResponse400() {
        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("invalid");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        verifyNoInteractions(flowService);
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
        verifyNoInteractions(flowService);
    }

    @Test
    @DisplayName("replyPermission: flowService returns error → pass-through")
    void replyPermissionFlowServiceErrorPassthrough() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(flowService.replyPermission(any(), any(), any(), any(), any()))
                .thenReturn(ApiResponse.error(410, "该助理已被删除"));

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(410, response.getBody().getCode());
        assertEquals("该助理已被删除", response.getBody().getErrormsg());
    }

    // ==================== getMessages ====================

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
        assertEquals(400, response.getBody().getCode());
        verifyNoInteractions(accessControlService, messageService);
    }

    @Test
    @DisplayName("getCursorMessageHistory returns 400 when size exceeds limit")
    void getCursorMessagesRejectsOversizedRequest() {
        var response = controller.getCursorMessages("1", "1", null, 201);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        verifyNoInteractions(accessControlService, messageService);
    }

    // ==================== send-to-im ====================

    @Test
    @DisplayName("sendToIm: group_<g>_<u> + cookie=<u> → 200")
    void sendToImGroup200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g123_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(imMessageService.sendMessage("group", "g123", "u456", "Hello IM", null)).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertEquals(true, response.getBody().getData().get("success"));
        verify(imMessageService).sendMessage("group", "g123", "u456", "Hello IM", null);
    }

    @Test
    @DisplayName("sendToIm: direct_<t>_<u> + cookie=<u> → 200")
    void sendToImDirect200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("direct_t789_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(imMessageService.sendMessage("direct", "t789", "u456", "hi", null)).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        verify(imMessageService).sendMessage("direct", "t789", "u456", "hi", null);
    }

    @Test
    @DisplayName("sendToIm: invalid businessSessionId prefix → 400")
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
        verify(imMessageService, never()).sendMessage(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendToIm: wrong segment count → 400")
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
        verify(imMessageService, never()).sendMessage(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendToIm: sender mismatch → 403")
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
        verify(imMessageService, never()).sendMessage(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendToIm: missing cookie → ProtocolException 400")
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
        verify(imMessageService, never()).sendMessage(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendToIm: blank content → 400")
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
    @DisplayName("sendToIm: content > 4000 chars → 400")
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
    @DisplayName("sendToIm: IM downstream fails → 500")
    void sendToImDownstreamFails500() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g123_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(imMessageService.sendMessage("group", "g123", "u456", "hi", null)).thenReturn(false);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("hi");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(500, response.getBody().getCode());
        assertEquals("Failed to send message to IM", response.getBody().getErrormsg());
    }

    @Test
    @DisplayName("sendToIm: msg_ext enabled → IM body includes msg_ext")
    void sendToImWithMsgExtEnabled() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g123_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(sysConfigService.getValue("msg_ext", "enabled")).thenReturn("1");
        when(sysConfigService.getValue("msg_ext", "content"))
                .thenReturn("{\"skillProviderCnName\":\"员工助手\",\"skillProviderEnName\":\"My Agent\"}");
        when(imMessageService.sendMessage(eq("group"), eq("g123"), eq("u456"), eq("Hello IM"),
                eq("{\"skillProviderCnName\":\"员工助手\",\"skillProviderEnName\":\"My Agent\"}")))
                .thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertEquals(true, response.getBody().getData().get("success"));
        verify(imMessageService).sendMessage("group", "g123", "u456", "Hello IM",
                "{\"skillProviderCnName\":\"员工助手\",\"skillProviderEnName\":\"My Agent\"}");
    }

    @Test
    @DisplayName("sendToIm: msg_ext disabled → IM body without msg_ext")
    void sendToImWithMsgExtDisabled() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g123_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(sysConfigService.getValue("msg_ext", "enabled")).thenReturn("0");
        when(imMessageService.sendMessage("group", "g123", "u456", "Hello IM", null)).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertEquals(true, response.getBody().getData().get("success"));
        verify(imMessageService).sendMessage("group", "g123", "u456", "Hello IM", null);
        verify(sysConfigService, never()).getValue("msg_ext", "content");
    }

    @Test
    @DisplayName("sendToIm: msg_ext enabled but content null → msg_ext=null")
    void sendToImWithMsgExtEnabledButContentNull() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setBusinessSessionId("group_g123_u456");
        when(accessControlService.requireSessionAccess(1L, "u456")).thenReturn(session);
        when(sysConfigService.getValue("msg_ext", "enabled")).thenReturn("1");
        when(sysConfigService.getValue("msg_ext", "content")).thenReturn(null);
        when(imMessageService.sendMessage("group", "g123", "u456", "Hello IM", null)).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        var response = controller.sendToIm("u456", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertEquals(true, response.getBody().getData().get("success"));
        verify(imMessageService).sendMessage("group", "g123", "u456", "Hello IM", null);
    }
}
