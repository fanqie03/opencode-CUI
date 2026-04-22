package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboundProcessingServiceTest {

    @Mock
    private AssistantAccountResolverService resolverService;
    @Mock
    private GatewayApiClient gatewayApiClient;
    @Mock
    private ImSessionManager sessionManager;
    @Mock
    private ContextInjectionService contextInjectionService;
    @Mock
    private GatewayRelayService gatewayRelayService;
    @Mock
    private SkillMessageService messageService;
    @Mock
    private SessionRebuildService rebuildService;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private StreamMessageEmitter emitter;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private AssistantOfflineMessageProvider offlineMessageProvider;

    private AssistantIdProperties assistantIdProperties;
    private DeliveryProperties deliveryProperties;
    private InboundProcessingService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MOCK_OFFLINE_MSG = "MOCK_OFFLINE_MSG";

    @BeforeEach
    void setUp() {
        assistantIdProperties = new AssistantIdProperties();
        assistantIdProperties.setEnabled(true);
        assistantIdProperties.setTargetToolType("assistant");

        deliveryProperties = new DeliveryProperties();

        service = new InboundProcessingService(
                resolverService,
                assistantIdProperties,
                gatewayApiClient,
                sessionManager,
                contextInjectionService,
                gatewayRelayService,
                messageService,
                rebuildService,
                objectMapper,
                assistantInfoService,
                scopeDispatcher,
                emitter,
                deliveryProperties,
                redisMessageBroker,
                offlineMessageProvider);

        // 默认 scope 策略：personal（requiresOnlineCheck=true）
        AssistantScopeStrategy personalStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        lenient().when(scopeDispatcher.getStrategy(any())).thenReturn(personalStrategy);
        lenient().when(assistantInfoService.getCachedScope(any())).thenReturn("personal");
        // 默认 Agent 在线
        lenient().when(gatewayApiClient.getAgentByAk(any()))
                .thenReturn(AgentSummary.builder().ak("ak-001").toolType("assistant").build());
        lenient().when(offlineMessageProvider.get()).thenReturn(MOCK_OFFLINE_MSG);
    }

    // ==================== processChat ====================

    @Test
    @DisplayName("processChat: session ready → sends CHAT invoke")
    void processChatSessionReady() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM");

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals("ak-001", captor.getValue().ak());
        assertEquals("owner-001", captor.getValue().userId());
        assertEquals(GatewayActions.CHAT, captor.getValue().action());
        assertTrue(captor.getValue().payload().contains("tool-001"));
        verify(messageService).saveUserMessage(101L, "hello");
        verify(rebuildService).appendPendingMessage("101", "hello");
        JsonNode chatPayload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("owner-001", chatPayload.get("sendUserAccount").asText(),
                "direct chat should put ownerWelinkId as sendUserAccount");
    }

    @Test
    @DisplayName("processChat: invalid assistant → returns error(404)")
    void processChatInvalidAssistant() {
        when(resolverService.resolve("unknown")).thenReturn(null);

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "unknown",
                null, "hello", "text", null, null, "IM");

        assertFalse(result.success());
        assertEquals(404, result.code());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: session not found → calls createSessionAsync")
    void processChatNoSession() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001"))
                .thenReturn(null);
        when(contextInjectionService.resolvePrompt("direct", "first msg", null))
                .thenReturn("first msg");

        InboundResult result = service.processChat(
                "im", "direct", "dm-new", "assist-001",
                null, "first msg", "text", null, null, "IM");

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                "im", "direct", "dm-new", "ak-001",
                "owner-001", "assist-001", null, "first msg");
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: agent 离线时返回 error(503, offline_msg, sid, wsid) 且调用 handleAgentOffline 副作用")
    void processChatAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        SkillSession existing = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(existing);

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "user-001", "hello", "text", null, null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals(MOCK_OFFLINE_MSG, result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertEquals(String.valueOf(existing.getId()), result.welinkSessionId());

        verify(emitter).emitToSession(eq(existing), anyString(), isNull(), any(StreamMessage.class));
    }

    @Test
    @DisplayName("processChat: business 助手（requiresOnlineCheck=false）跳过在线检查，正常转发")
    void processChatBusinessScopeSkipsOnlineCheck() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "user-001", "hello", "text", null, null, "EXTERNAL");

        assertTrue(result.success());
        verify(gatewayApiClient, never()).getAgentByAk(anyString()); // 关键：没查在线状态
        verify(gatewayRelayService).sendInvokeToGateway(any(InvokeCommand.class));
    }

    @Test
    @DisplayName("processChat: group + null sender → sendUserAccount falls back to ownerWelinkId")
    void processChatGroupNullSenderFallsBackToOwner() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "group", "grp-001", "ak-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt(eq("group"), eq("hello"), any()))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "group", "grp-001", "assist-001",
                null,
                "hello", "text", null, null, "IM");

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("owner-001", payload.get("sendUserAccount").asText(),
                "group chat with null sender should fall back to ownerWelinkId");
    }

    // ==================== processQuestionReply ====================

    @Test
    @DisplayName("processQuestionReply: session ready → sends QUESTION_REPLY invoke")
    void processQuestionReplySessionReady() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "yes", "tc-001", null, null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals(GatewayActions.QUESTION_REPLY, captor.getValue().action());
        assertTrue(captor.getValue().payload().contains("yes"));
        assertTrue(captor.getValue().payload().contains("tc-001"));
        assertTrue(captor.getValue().payload().contains("tool-001"));
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("user-001", payload.get("sendUserAccount").asText(),
                "gateway payload should bind sendUserAccount=user-001");
    }

    @Test
    @DisplayName("processQuestionReply: session 存在 + agent 离线 → 返回 error(503, offline_msg)")
    void processQuestionReplyAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "answer", "tool-call-1", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals(MOCK_OFFLINE_MSG, result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertEquals(String.valueOf(session.getId()), result.welinkSessionId());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any(InvokeCommand.class));
    }

    @Test
    @DisplayName("processQuestionReply: session 不存在优先返回 404（即使 agent 离线）")
    void processQuestionReplyMissingSessionReturns404EvenIfOffline() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession(any(), any(), any(), any())).thenReturn(null);
        lenient().when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线（场景注释，404 优先不会调用）

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "answer", "tool-call-1", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(404, result.code());
        assertEquals("Session not found or not ready", result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertNull(result.welinkSessionId());
        verify(gatewayApiClient, never()).getAgentByAk(anyString()); // 404 优先，未查在线
    }

    // ==================== processPermissionReply ====================

    @Test
    @DisplayName("processPermissionReply: session ready → sends PERMISSION_REPLY invoke + broadcasts")
    void processPermissionReplySessionReady() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "perm-001", "allow", null, null);

        assertTrue(result.success());
        // 验证 invoke
        ArgumentCaptor<InvokeCommand> invokeCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(invokeCaptor.capture());
        assertEquals(GatewayActions.PERMISSION_REPLY, invokeCaptor.getValue().action());
        assertTrue(invokeCaptor.getValue().payload().contains("perm-001"));
        assertTrue(invokeCaptor.getValue().payload().contains("allow"));
        JsonNode permissionPayload = objectMapper.readTree(invokeCaptor.getValue().payload());
        assertEquals("user-001", permissionPayload.get("sendUserAccount").asText(),
                "gateway payload should bind sendUserAccount=user-001");

        // 验证广播
        ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(gatewayRelayService).publishProtocolMessage(eq("101"), msgCaptor.capture());
        assertEquals(StreamMessage.Types.PERMISSION_REPLY, msgCaptor.getValue().getType());
        assertEquals("perm-001", msgCaptor.getValue().getPermission().getPermissionId());
        assertEquals("allow", msgCaptor.getValue().getPermission().getResponse());
    }

    @Test
    @DisplayName("processPermissionReply: session 存在 + agent 离线 → 返回 error(503, offline_msg)")
    void processPermissionReplyAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "perm-1", "once", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals(MOCK_OFFLINE_MSG, result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertEquals(String.valueOf(session.getId()), result.welinkSessionId());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any(InvokeCommand.class));
    }

    @Test
    @DisplayName("processPermissionReply: session 不存在优先返回 404（即使 agent 离线）")
    void processPermissionReplyMissingSessionReturns404EvenIfOffline() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession(any(), any(), any(), any())).thenReturn(null);
        lenient().when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线（场景注释，404 优先不会调用）

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "perm-1", "once", null, "EXTERNAL");

        assertFalse(result.success());
        assertEquals(404, result.code());
        assertEquals("Session not found or not ready", result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertNull(result.welinkSessionId());
        verify(gatewayApiClient, never()).getAgentByAk(anyString());
    }

    // ==================== processRebuild ====================

    @Test
    @DisplayName("processRebuild: session exists → calls requestToolSession")
    void processRebuildSessionExists() {
        SkillSession session = buildReadySession();
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionManager).requestToolSession(session, null);
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("processRebuild: session not found → calls createSessionAsync")
    void processRebuildNoSession() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001"))
                .thenReturn(null);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-new", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                "im", "direct", "dm-new", "ak-001",
                "owner-001", "assist-001", "user-001", null);
        verify(sessionManager, never()).requestToolSession(any(), any());
    }

    @Test
    @DisplayName("processRebuild: agent 离线时返回 error(503, offline_msg, sid, wsid)，不创建 session")
    void processRebuildAgentOfflineReturns503() {
        when(resolverService.resolve("assist-001"))
                .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        SkillSession existing = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(existing);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals(MOCK_OFFLINE_MSG, result.message());
        verify(sessionManager, never()).requestToolSession(any(), any());
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== handleAgentOffline ====================

    @Test
    @DisplayName("handleAgentOffline: external-ws session → routes via emitter (regression for welinkSessionId bug)")
    void handleAgentOffline_ExternalWs_shouldRouteViaEmitter() {
        // given: 非 miniapp/IM domain 的 external ws session
        SkillSession session = mock(SkillSession.class);
        when(session.getId()).thenReturn(101L);
        when(session.isImDirectSession()).thenReturn(false);
        when(sessionManager.findSession(
                eq("ext"), eq("single"), eq("101"), eq("ak-x")))
                .thenReturn(session);

        // when
        service.handleAgentOffline(
                "ext", "single", "101", "ak-x", "assistant-x");

        // then: 走 emitter.emitToSession，msg 携带 ERROR 类型 + error 来自 provider
        ArgumentCaptor<StreamMessage> cap = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter).emitToSession(eq(session), eq("101"), isNull(), cap.capture());
        assertEquals(StreamMessage.Types.ERROR, cap.getValue().getType());
        assertEquals(MOCK_OFFLINE_MSG, cap.getValue().getError());
    }

    // ==================== helper ====================

    private SkillSession buildReadySession() {
        SkillSession session = new SkillSession();
        session.setId(101L);
        session.setAk("ak-001");
        session.setUserId("owner-001");
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("direct");
        session.setToolSessionId("tool-001");
        return session;
    }
}
