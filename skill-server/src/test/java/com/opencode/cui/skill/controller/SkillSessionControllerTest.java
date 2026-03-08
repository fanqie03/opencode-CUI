package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.SkillSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SkillSessionController (plain Mockito, no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class SkillSessionControllerTest {

    @Mock
    private SkillSessionService sessionService;
    @Mock
    private GatewayRelayService gatewayRelayService;

    private SkillSessionController controller;

    @BeforeEach
    void setUp() {
        controller = new SkillSessionController(sessionService, gatewayRelayService, new ObjectMapper());
    }

    @Test
    @DisplayName("createSession returns 201 CREATED")
    void createSession201() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.createSession(any(), any(), any(), any())).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setUserId(1L);
        request.setAk("3");
        request.setTitle("Test");

        var response = controller.createSession(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertNotNull(response.getBody().getData());
        verify(gatewayRelayService).subscribeToSessionBroadcast("1");
        verify(gatewayRelayService).sendInvokeToGateway(eq("3"), eq("1"), eq("create_session"), isNull());
    }

    @Test
    @DisplayName("createSession returns 400 when userId is null")
    void createSessionBadRequest() {
        var request = new SkillSessionController.CreateSessionRequest();
        // userId is null

        var response = controller.createSession(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("getSession returns 200 OK")
    void getSession200() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        when(sessionService.getSession(42L)).thenReturn(session);

        var response = controller.getSession(42L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(42L, response.getBody().getData().getId());
    }

    @Test
    @DisplayName("getSession returns 404 when not found")
    void getSession404() {
        when(sessionService.getSession(999L)).thenThrow(new IllegalArgumentException("Not found"));

        var response = controller.getSession(999L);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("closeSession returns 200 OK")
    void closeSession200() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.getSession(42L)).thenReturn(session);

        var response = controller.closeSession(42L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(sessionService).closeSession(42L);
        verify(gatewayRelayService).unsubscribeFromSession("42");
    }

    @Test
    @DisplayName("closeSession sends close_session invoke to gateway when toolSessionId exists")
    void closeSessionSendsGatewayInvoke() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("99");
        session.setToolSessionId("ts-abc");
        when(sessionService.getSession(42L)).thenReturn(session);

        controller.closeSession(42L);
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("42"), eq("close_session"), any());
    }

    @Test
    @DisplayName("abortSession returns 200 and sends abort_session invoke")
    void abortSession200() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("99");
        session.setToolSessionId("ts-abc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.getSession(42L)).thenReturn(session);

        var response = controller.abortSession(42L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("aborted", response.getBody().getData().get("status"));
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("42"), eq("abort_session"), any());
        verify(sessionService).closeSession(42L);
        verify(gatewayRelayService).unsubscribeFromSession("42");
    }

    @Test
    @DisplayName("abortSession returns 404 when session not found")
    void abortSession404() {
        when(sessionService.getSession(999L)).thenThrow(new IllegalArgumentException("Not found"));

        var response = controller.abortSession(999L);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
