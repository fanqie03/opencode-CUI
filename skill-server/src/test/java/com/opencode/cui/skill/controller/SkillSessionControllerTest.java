package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        when(sessionService.createSession(any(), any(), any(), any(), any())).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setUserId(1L);
        request.setSkillDefinitionId(2L);
        request.setAgentId(3L);
        request.setTitle("Test");

        ResponseEntity<SkillSession> response = controller.createSession(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    @DisplayName("createSession returns 400 when userId is null")
    void createSessionBadRequest() {
        var request = new SkillSessionController.CreateSessionRequest();
        // userId is null

        ResponseEntity<SkillSession> response = controller.createSession(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("getSession returns 200 OK")
    void getSession200() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        when(sessionService.getSession(42L)).thenReturn(session);

        ResponseEntity<SkillSession> response = controller.getSession(42L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(42L, response.getBody().getId());
    }

    @Test
    @DisplayName("getSession returns 404 when not found")
    void getSession404() {
        when(sessionService.getSession(999L)).thenThrow(new IllegalArgumentException("Not found"));

        ResponseEntity<SkillSession> response = controller.getSession(999L);
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
    }

    @Test
    @DisplayName("closeSession sends close_session invoke to gateway when toolSessionId exists")
    void closeSessionSendsGatewayInvoke() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAgentId(99L);
        session.setToolSessionId("ts-abc");
        when(sessionService.getSession(42L)).thenReturn(session);

        controller.closeSession(42L);
        verify(gatewayRelayService).sendInvokeToGateway(eq("99"), eq("42"), eq("close_session"), any());
    }
}
