package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import com.opencode.cui.skill.service.AssistantAccountResolverService;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.AsyncTaskService;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.ProtocolException;
import com.opencode.cui.skill.service.SessionAccessControlService;
import com.opencode.cui.skill.service.SkillSessionFlowService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.DefaultAssistantScopeStrategy;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
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
    @Mock
    private DefaultAssistantRuleService ruleService;
    @Mock
    private DefaultAssistantScopeStrategy defaultAssistantScopeStrategy;
    @Mock
    private MessagePersistenceService persistenceService;
    @Mock
    private StreamBufferService bufferService;
    @Mock
    private SkillMessageRepository messageRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AsyncTaskService asyncTaskService;

    private SkillSessionController controller;

    @BeforeEach
    void setUp() {
        // 默认 scopeDispatcher 返回 personal 策略（generateToolSessionId=null, requiresOnlineCheck=true）
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy personalStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        org.mockito.Mockito.lenient().when(personalStrategy.generateToolSessionId()).thenReturn(null);
        org.mockito.Mockito.lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        org.mockito.Mockito.lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(personalStrategy);
        org.mockito.Mockito.lenient().when(scopeDispatcher.getStrategy(
                nullable(String.class), nullable(String.class), nullable(AssistantInfo.class)))
                .thenReturn(personalStrategy);
        AssistantInfo defaultPersonalInfo = new AssistantInfo();
        defaultPersonalInfo.setAssistantScope("personal");
        org.mockito.Mockito.lenient().when(assistantInfoService.getAssistantInfo(any())).thenReturn(defaultPersonalInfo);
        // 默认 resolver 行为：开关 ON（null 放行），非 null 返 EXISTS
        org.mockito.Mockito.lenient().when(assistantAccountResolverService.isSkipOnNullAssistantAccount()).thenReturn(true);
        org.mockito.Mockito.lenient().when(assistantAccountResolverService.getDeletionMessage()).thenReturn("该助理已被删除");
        org.mockito.Mockito.lenient().when(assistantAccountResolverService.check(any())).thenReturn(ExistenceStatus.EXISTS);
        // 默认 ruleService 未命中规则（PR3 老路径行为不变）
        org.mockito.Mockito.lenient().when(ruleService.lookup(any(), any())).thenReturn(Optional.empty());

        SkillSessionFlowService flowService = new SkillSessionFlowService(
                sessionService, gatewayRelayService, new ObjectMapper(),
                assistantInfoService, scopeDispatcher, assistantAccountResolverService,
                ruleService, defaultAssistantScopeStrategy, persistenceService, bufferService,
                messageRepository, asyncTaskService, eventPublisher);
        controller = new SkillSessionController(sessionService, accessControlService, flowService);
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
    @DisplayName("createSession supports remote assistant without AK by assistantAccount")
    void createSessionRemoteAssistantWithoutAkGeneratesToolSession() {
        SkillSession session = new SkillSession();
        session.setId(10L);
        session.setAk(null);
        session.setAssistantAccount("bot-001");
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("dm");
        session.setBusinessSessionId("dm-001");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireUserId("1")).thenReturn("1");
        when(sessionService.createSession(eq("1"), isNull(), eq("Remote"), eq("im"), eq("dm"), eq("dm-001"), eq("bot-001")))
                .thenReturn(session);

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("remote-tag");
        when(assistantInfoService.getAssistantInfo(null, "bot-001")).thenReturn(info);
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy businessStrategy =
                mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        when(businessStrategy.generateToolSessionId()).thenReturn("tool-remote");
        when(scopeDispatcher.getStrategy(eq("im"), eq("dm"), eq(info))).thenReturn(businessStrategy);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setTitle("Remote");
        request.setAssistantAccount("bot-001");
        request.setBusinessSessionDomain("im");
        request.setBusinessSessionType("dm");
        request.setBusinessSessionId("dm-001");

        var response = controller.createSession("1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(sessionService).updateToolSessionId(10L, "tool-remote");
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
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

    @Test
    @DisplayName("abortSession persists buffered delta, clears resume state, and idles session")
    void abortSessionFinalizesLocalState() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("ts-abc");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .messageId("msg-1")
                .partId("part-1")
                .content("partial reply")
                .role("assistant")
                .build()));

        controller.abortSession("1", "42");

        ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(persistenceService, times(2)).persistIfFinal(eq(42L), msgCaptor.capture());
        List<StreamMessage> persisted = msgCaptor.getAllValues();
        assertEquals(StreamMessage.Types.TEXT_DONE, persisted.get(0).getType());
        assertEquals("partial reply", persisted.get(0).getContent());
        assertEquals(StreamMessage.Types.SESSION_STATUS, persisted.get(1).getType());
        assertEquals("idle", persisted.get(1).getSessionStatus());
        verify(sessionService).markSessionIdle(42L);
        verify(bufferService).clearSession("42");
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

    // ==================== PR3 AC §B: 默认助手路径 ====================

    @Test
    @DisplayName("AC §B: createSession 命中规则 (domain+type) → 注入 ak/assistantAccount + 单事务 + 不发 CREATE_SESSION")
    void createSessionRuleHitInjects() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        // 命中规则
        DefaultAssistantRule rule = new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square");
        when(ruleService.lookup("helpdesk", "direct")).thenReturn(Optional.of(rule));
        when(defaultAssistantScopeStrategy.generateToolSessionId()).thenReturn("ts-snowflake-1");

        SkillSession injected = new SkillSession();
        injected.setId(100L);
        injected.setAk("AK_V");
        injected.setAssistantAccount("ACC_V");
        injected.setToolSessionId("ts-snowflake-1");
        injected.setBusinessSessionDomain("helpdesk");
        injected.setBusinessSessionType("direct");
        injected.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.createSessionWithDefaultAssistant(
                eq("1"), eq("AK_V"), eq("ACC_V"), any(),
                eq("helpdesk"), eq("direct"), any(), eq("ts-snowflake-1")))
                .thenReturn(injected);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setTitle("Test");
        request.setBusinessSessionDomain("helpdesk");
        request.setBusinessSessionType("direct");
        request.setBusinessSessionId("biz-1");
        // 不传 ak / assistantAccount

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertEquals("AK_V", response.getBody().getData().getAk());
        assertEquals("ACC_V", response.getBody().getData().getAssistantAccount());
        assertEquals("ts-snowflake-1", response.getBody().getData().getToolSessionId());
        assertEquals(SkillSession.Status.ACTIVE, response.getBody().getData().getStatus());

        // 不走老 createSession 流程
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any());
        // 不发 CREATE_SESSION 给 gateway
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        // 不走 deletion check
        verify(assistantAccountResolverService, never()).check(any());
        // 调用了单事务方法
        verify(sessionService).createSessionWithDefaultAssistant(
                eq("1"), eq("AK_V"), eq("ACC_V"), eq("Test"),
                eq("helpdesk"), eq("direct"), eq("biz-1"), eq("ts-snowflake-1"));
    }

    @Test
    @DisplayName("AC §B: createSession 不传 ak/assistantAccount + 规则未命中 → 400 'ak 和 assistantAccount 必填'")
    void createSessionNoExplicitNoRuleMiss400() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        // 规则未命中
        when(ruleService.lookup(any(), any())).thenReturn(Optional.empty());

        var request = new SkillSessionController.CreateSessionRequest();
        request.setBusinessSessionDomain("unknown");
        request.setBusinessSessionType("unknown");
        // 不传 ak / assistantAccount

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertEquals("ak 和 assistantAccount 必填", response.getBody().getErrormsg());
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any());
        verify(sessionService, never()).createSessionWithDefaultAssistant(any(), any(), any(), any(), any(), any(), any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("AC §B: createSession 不传 ak/assistantAccount + domain 为空 → 400 (rule lookup 内部返 empty)")
    void createSessionNoExplicitDomainBlank400() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        // lookup 内部对 blank domain 返 empty
        when(ruleService.lookup(eq(null), any())).thenReturn(Optional.empty());

        var request = new SkillSessionController.CreateSessionRequest();
        request.setBusinessSessionDomain(null);
        request.setBusinessSessionType("direct");

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(400, response.getBody().getCode());
        assertEquals("ak 和 assistantAccount 必填", response.getBody().getErrormsg());
    }

    // ==================== PR3 AC §C: 老路径不回归 ====================

    @Test
    @DisplayName("AC §C: 显式传 ak (即使 domain/type 命中规则) → 不走规则注入；走老路径；DB ak 是显式值")
    void createSessionExplicitAkSkipsRuleEvenIfRuleMatches() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        // 即使规则命中
        DefaultAssistantRule rule = new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square");
        org.mockito.Mockito.lenient().when(ruleService.lookup("helpdesk", "direct")).thenReturn(Optional.of(rule));

        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("REAL_AK");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.createSession(any(), eq("REAL_AK"), any(), any(), any(), any(), any())).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAk("REAL_AK");
        request.setBusinessSessionDomain("helpdesk");
        request.setBusinessSessionType("direct");

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        assertEquals("REAL_AK", response.getBody().getData().getAk());

        // 走老 createSession，不走单事务方法
        verify(sessionService).createSession(any(), eq("REAL_AK"), any(), any(), any(), any(), any());
        verify(sessionService, never()).createSessionWithDefaultAssistant(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC §C: 显式传 assistantAccount (即使 domain/type 命中规则) → 不走规则注入")
    void createSessionExplicitAssistantAccountSkipsRule() {
        when(accessControlService.requireUserId("1")).thenReturn("1");
        DefaultAssistantRule rule = new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square");
        org.mockito.Mockito.lenient().when(ruleService.lookup("helpdesk", "direct")).thenReturn(Optional.of(rule));

        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setStatus(SkillSession.Status.ACTIVE);
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), eq("EXPLICIT_ACC"))).thenReturn(session);

        var request = new SkillSessionController.CreateSessionRequest();
        request.setAssistantAccount("EXPLICIT_ACC");
        request.setBusinessSessionDomain("helpdesk");
        request.setBusinessSessionType("direct");

        var response = controller.createSession("1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());

        verify(sessionService).createSession(any(), any(), any(), any(), any(), any(), eq("EXPLICIT_ACC"));
        verify(sessionService, never()).createSessionWithDefaultAssistant(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== PR3 close/abort: 默认助手会话跳过发 GW invoke ====================

    @Test
    @DisplayName("PR3 D7: closeSession 命中默认助手规则 → 只 DB 标 CLOSED，不发 CLOSE_SESSION invoke")
    void closeSessionDefaultAssistantSkipsGatewayInvoke() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("AK_V");
        session.setUserId("1");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);
        // 命中规则
        when(ruleService.lookup("helpdesk", "direct"))
                .thenReturn(Optional.of(new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square")));

        controller.closeSession("1", "42");
        verify(sessionService).closeSession(42L);
        // 不发 invoke
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("abortSession 命中默认助手规则 → 发 ABORT_SESSION invoke")
    void abortSessionDefaultAssistantSendsGatewayInvoke() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("AK_V");
        session.setUserId("1");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);

        var response = controller.abortSession("1", "42");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("AK_V", cmdCaptor.getValue().ak());
        assertEquals("abort_session", cmdCaptor.getValue().action());
        assertTrue(cmdCaptor.getValue().payload().contains("ts-1"));
    }

    @Test
    @DisplayName("PR3 D7: closeSession 未命中规则（老 personal scope）→ 仍发 CLOSE_SESSION invoke")
    void closeSessionLegacyStillSendsInvoke() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("miniapp");
        session.setBusinessSessionType(null);
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);
        when(ruleService.lookup(any(), any())).thenReturn(Optional.empty());

        controller.closeSession("1", "42");
        // 仍发 invoke
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("close_session", cmdCaptor.getValue().action());
        // InvokeCommand 带 domain/domainType
        assertEquals("miniapp", cmdCaptor.getValue().domain());
    }

    @Test
    @DisplayName("PR3 D7: abortSession 未命中规则（老 personal scope）→ 仍发 ABORT_SESSION invoke")
    void abortSessionLegacyStillSendsInvoke() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("ts-1");
        session.setBusinessSessionDomain("miniapp");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(42L, "1")).thenReturn(session);

        controller.abortSession("1", "42");
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        assertEquals("abort_session", cmdCaptor.getValue().action());
        assertEquals("miniapp", cmdCaptor.getValue().domain());
    }
}
