package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.ProtocolException;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * SkillSessionController 单元测试（纯 Mockito，不加载 Spring 上下文）。
 */
@ExtendWith(MockitoExtension.class)
class SkillSessionControllerTest {

    @Mock
    private SkillSessionService sessionService;
    @Mock
    private GatewayRelayService gatewayRelayService;
    @Mock
    private SessionAccessControlService accessControlService;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private AssistantAccountResolverService assistantAccountResolverService;

    private SkillSessionController controller;

    @BeforeEach
    void setUp() {
        // 默认 scopeDispatcher 返回 personal 策略（generateToolSessionId=null, requiresOnlineCheck=true）
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy personalStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        org.mockito.Mockito.lenient().when(personalStrategy.generateToolSessionId()).thenReturn(null);
        org.mockito.Mockito.lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        org.mockito.Mockito.lenient().when(scopeDispatcher.getStrategy(any())).thenReturn(personalStrategy);
        org.mockito.Mockito.lenient().when(assistantInfoService.getCachedScope(any())).thenReturn("personal");
        // 默认 resolver 行为：开关 ON（null 放行），非 null 返 EXISTS
        org.mockito.Mockito.lenient().when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(true);
        org.mockito.Mockito.lenient().when(assistantAccountResolverService.getDeletionMessage()).thenReturn("该助理已被删除");
        org.mockito.Mockito.lenient().when(assistantAccountResolverService.check(any())).thenReturn(ExistenceStatus.EXISTS);

        controller = new SkillSessionController(sessionService, gatewayRelayService, accessControlService,
                new ObjectMapper(), assistantInfoService, scopeDispatcher, assistantAccountResolverService);
    }

    @Test
    @DisplayName("createSession returns 200 OK")
    void createSession200() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireUserId("1")).thenReturn("1");
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAk("3");
        request.setTitle("Test");

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
        assertNotNull(response.getBody().getData());
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("3", cmdCaptor.getValue().ak());
        assertEquals("create_session", cmdCaptor.getValue().action());
        assertTrue(cmdCaptor.getValue().payload().contains("Test"));
    }

    @Test
    @DisplayName("createSession throws ProtocolException when userId is null")
    void createSessionBadRequest() {
        var request = new SkillSessionController.CreateSessionRequest();
        when(accessControlService.requireUserId(null)).thenThrow(new ProtocolException(400, "userId is required"));

        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> controller.createSession(null, request));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("getSession returns 200 OK")
    void getSession200() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);

        var response = controller.getSession("1", "42");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(42L, response.getBody().getData().getId());
    }

    @Test
    @DisplayName("getSession throws when not found")
    void getSession404() {
        when(accessControlService.requireSessionAccess(999L, "1")).thenThrow(new IllegalArgumentException("Not found"));

        assertThrows(IllegalArgumentException.class,
                () -> controller.getSession("1", "999"));
    }

    @Test
    @DisplayName("closeSession returns 200 OK")
    void closeSession200() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);

        var response = controller.closeSession("1", "42");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("42", response.getBody().getData().get("welinkSessionId"));
        verify(sessionService).closeSession(42L);
        verify(gatewayRelayService, never()).publishProtocolMessage(anyString(), any());
    }

    @Test
    @DisplayName("closeSession sends close_session invoke to gateway when toolSessionId exists")
    void closeSessionSendsGatewayInvoke() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("ts-abc");
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);

        controller.closeSession("1", "42");
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("99", cmdCaptor.getValue().ak());
        assertEquals("close_session", cmdCaptor.getValue().action());
    }

    @Test
    @DisplayName("abortSession returns 200 and sends abort_session invoke")
    void abortSession200() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("ts-abc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);

        var response = controller.abortSession("1", "42");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("aborted", response.getBody().getData().get("status"));
        assertEquals("42", response.getBody().getData().get("welinkSessionId"));
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("99", cmdCaptor.getValue().ak());
        assertEquals("abort_session", cmdCaptor.getValue().action());
        verify(sessionService, never()).closeSession(anyLong());
        verify(gatewayRelayService, never()).publishProtocolMessage(anyString(), any());
    }

    @Test
    @DisplayName("abortSession throws when session not found")
    void abortSession404() {
        when(accessControlService.requireSessionAccess(999L, "1")).thenThrow(new IllegalArgumentException("Not found"));

        assertThrows(IllegalArgumentException.class,
                () -> controller.abortSession("1", "999"));
    }

    // ==================== 助理删除校验 ====================

    @Test
    @DisplayName("createSession: null assistantAccount + 开关 ON → 放行 200")
    void createSessionNullAssistantAccountSkipOn() {
        when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(true);
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireUserId("1")).thenReturn("1");
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAk("3");
        request.setTitle("Test");
        // assistantAccount 为 null

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        verify(sessionService).createSession(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createSession: null assistantAccount + 开关 OFF → 400 'assistantAccount is required'")
    void createSessionNullAssistantAccountSkipOffReturns400() {
        when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(false);
        when(accessControlService.requireUserId("1")).thenReturn("1");

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAk("3");
        // assistantAccount 为 null

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("createSession: NOT_EXISTS → 410 + deletion message（不落 DB 不发 Gateway）")
    void createSessionAssistantNotExistsReturns410() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        when(assistantAccountResolverService.check("deleted-acc")).thenReturn(ExistenceStatus.NOT_EXISTS);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAk("3");
        request.setAssistantAccount("deleted-acc");

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(410, response.getBody().getCode());
        assertEquals("该助理已被删除", response.getBody().getErrormsg());
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("createSession: UNKNOWN → 继续放行（best-effort 阻断）")
    void createSessionAssistantUnknownAllows() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        when(assistantAccountResolverService.check("unknown-acc")).thenReturn(ExistenceStatus.UNKNOWN);
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAk("3");
        request.setAssistantAccount("unknown-acc");

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        verify(sessionService).createSession(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createSession: EXISTS → 放行 200（happy path）")
    void createSessionAssistantExistsAllows() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        when(assistantAccountResolverService.check("exists-acc")).thenReturn(ExistenceStatus.EXISTS);
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAk("3");
        request.setAssistantAccount("exists-acc");

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
    }
}
