package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessageView;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ImMessageService;
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
import java.util.Map;

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

    private SkillMessageController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillMessageController(
                messageService, sessionService, gatewayRelayService,
                imMessageService, new ObjectMapper(), partRepository);
    }

    @Test
    @DisplayName("sendMessage returns 201 and invokes AI gateway")
    void sendMessage201() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAgentId(99L);
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.getSession(1L)).thenReturn(session);

        SkillMessage msg = SkillMessage.builder()
                .id(1L).role(SkillMessage.Role.USER).content("Hello").build();
        when(messageService.saveUserMessage(eq(1L), eq("Hello"))).thenReturn(msg);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage(1L, request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("1"), eq("chat"), any());
    }

    @Test
    @DisplayName("sendMessage returns 400 for empty content")
    void sendMessageEmptyContent400() {
        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("");

        ResponseEntity<?> response = controller.sendMessage(1L, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("sendMessage returns 409 for closed session")
    void sendMessageClosedSession409() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.CLOSED);
        when(sessionService.getSession(1L)).thenReturn(session);

        var request = new SkillMessageController.SendMessageRequest();
        request.setContent("Hello");

        ResponseEntity<?> response = controller.sendMessage(1L, request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    @DisplayName("getMessageHistory returns 200")
    void getMessages200() {
        when(sessionService.getSession(1L)).thenReturn(new SkillSession());
        when(messageService.getMessageHistory(1L, 0, 50))
                .thenReturn(new PageResult<>(List.of(), 0, 0, 50));

        ResponseEntity<PageResult<SkillMessageView>> response =
                controller.getMessages(1L, 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("replyPermission returns 200 with success")
    void permissionReply200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAgentId(99L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.getSession(1L)).thenReturn(session);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setApproved(true);

        ResponseEntity<Map<String, Object>> response = controller.replyPermission(1L, "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("success"));
        assertEquals("p-abc", response.getBody().get("permissionId"));
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("1"), eq("permission_reply"), any());
    }

    @Test
    @DisplayName("replyPermission returns 400 when approved is null")
    void permissionReplyMissingApproved400() {
        var request = new SkillMessageController.PermissionReplyRequest();
        // approved is null

        ResponseEntity<Map<String, Object>> response = controller.replyPermission(1L, "p-abc", request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("replyPermission returns 409 for closed session")
    void permissionReplyClosedSession409() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.CLOSED);
        when(sessionService.getSession(1L)).thenReturn(session);

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setApproved(true);

        ResponseEntity<Map<String, Object>> response = controller.replyPermission(1L, "p-abc", request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    @DisplayName("sendToIm returns 200 with success")
    void sendToIm200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setImChatId("chat-123");
        when(sessionService.getSession(1L)).thenReturn(session);
        when(imMessageService.sendMessage("chat-123", "Hello IM")).thenReturn(true);

        var request = new SkillMessageController.SendToImRequest();
        request.setContent("Hello IM");

        ResponseEntity<Map<String, Object>> response = controller.sendToIm(1L, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("success"));
    }
}
