package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SkillMessageController (plain Mockito, no Spring context).
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
    private ImMessageService imMessageService;
    @Mock
    private SkillMessagePartRepository partRepository;
    @Mock
    private MessagePersistenceService messagePersistenceService;
    @Mock
    private SessionAccessControlService accessControlService;

    private SkillMessageController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillMessageController(
                messageService, sessionService, gatewayRelayService,
                imMessageService, new ObjectMapper(), partRepository,
                messagePersistenceService, accessControlService);
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
        verify(messagePersistenceService).finalizeActiveAssistantTurn(1L);
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("1"), eq("1"), eq("chat"), any());
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
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("1"), eq("1"), eq("question_reply"), any());
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
        SkillMessage message = SkillMessage.builder()
                .id(10L)
                .sessionId(1L)
                .messageId("msg-10")
                .messageSeq(3)
                .role(SkillMessage.Role.ASSISTANT)
                .content("Done")
                .contentType(SkillMessage.ContentType.MARKDOWN)
                .createdAt(LocalDateTime.of(2026, 3, 11, 9, 0))
                .build();
        when(messageService.getMessageHistory(1L, 0, 50))
                .thenReturn(new PageResult<>(List.of(message), 1, 0, 50));
        when(partRepository.findByMessageId(10L)).thenReturn(List.of(
                SkillMessagePart.builder()
                        .messageId(10L)
                        .partId("part-1")
                        .seq(1)
                        .partType("tool")
                        .toolName("bash")
                        .toolCallId("call-1")
                        .toolStatus("completed")
                        .toolInput("{\"command\":\"pwd\"}")
                        .toolOutput("/tmp")
                        .build()));

        var response = controller.getMessages("1", "1", 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("1", response.getBody().getData().getContent().get(0).getWelinkSessionId());
        assertEquals("assistant", response.getBody().getData().getContent().get(0).getRole());
        assertEquals("markdown", response.getBody().getData().getContent().get(0).getContentType());
        assertEquals("tool", response.getBody().getData().getContent().get(0).getParts().get(0).getType());
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
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("1"), eq("1"), eq("permission_reply"), any());
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
    @DisplayName("sendToIm returns 200 with success")
    void sendToIm200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setImGroupId("chat-123");
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(imMessageService.sendMessage("chat-123", "Hello IM")).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        var response = controller.sendToIm("1", "1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().getData().get("success"));
    }
}
