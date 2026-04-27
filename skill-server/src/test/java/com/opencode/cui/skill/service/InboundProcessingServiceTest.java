package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.ResolveOutcome;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

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
                offlineMessageProvider,
                sessionService,
                redisTemplate);

        // 默认 scope 策略：personal（requiresOnlineCheck=true）
        AssistantScopeStrategy personalStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        lenient().when(scopeDispatcher.getStrategy(any())).thenReturn(personalStrategy);
        lenient().when(assistantInfoService.getCachedScope(any())).thenReturn("personal");
        // 默认 Agent 在线
        lenient().when(gatewayApiClient.getAgentByAk(any()))
                .thenReturn(AgentSummary.builder().ak("ak-001").toolType("assistant").build());
        lenient().when(offlineMessageProvider.get()).thenReturn(MOCK_OFFLINE_MSG);

        // 默认 business heal 锁行为：拿到锁成功，pending list 为空
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        lenient().when(rebuildService.consumePendingMessages(anyString()))
                .thenReturn(Collections.emptyList());

        // 助理删除文案（用于 NOT_EXISTS 410 分支）
        lenient().when(resolverService.getDeletionMessage()).thenReturn("该助理已被删除");
    }

    // ==================== processChat ====================

    @Test
    @DisplayName("processChat: session ready → sends CHAT invoke")
    void processChatSessionReady() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

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
    @DisplayName("processChat: UNKNOWN → 保留现今 404 'Invalid assistant account' 语义")
    void processChatAssistantUnknownReturns404() {
        when(resolverService.resolveWithStatus("unknown"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null));

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "unknown",
                null, "hello", "text", null, null, "IM", null);

        assertFalse(result.success());
        assertEquals(404, result.code());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: NOT_EXISTS → 返回 410 + deletion message + businessSessionId 信封")
    void processChatAssistantNotExistsReturns410() {
        when(resolverService.resolveWithStatus("deleted"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null));

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "deleted",
                null, "hello", "text", null, null, "IM", null);

        assertFalse(result.success());
        assertEquals(410, result.code());
        assertEquals("该助理已被删除", result.message());
        assertEquals("dm-001", result.businessSessionId());
        // ak 未知，无法从 findSession 反查 skillSession
        assertNull(result.welinkSessionId());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("processQuestionReply: NOT_EXISTS → 返回 410 + deletion message")
    void processQuestionReplyAssistantNotExistsReturns410() {
        when(resolverService.resolveWithStatus("deleted"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null));

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "deleted",
                "user-001", "answer", "tc-001", null, "EXTERNAL", null);

        assertFalse(result.success());
        assertEquals(410, result.code());
        assertEquals("该助理已被删除", result.message());
        assertEquals("dm-001", result.businessSessionId());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processPermissionReply: NOT_EXISTS → 返回 410 + deletion message")
    void processPermissionReplyAssistantNotExistsReturns410() {
        when(resolverService.resolveWithStatus("deleted"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null));

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "deleted",
                "user-001", "perm-1", "once", null, "EXTERNAL", null);

        assertFalse(result.success());
        assertEquals(410, result.code());
        assertEquals("该助理已被删除", result.message());
        assertEquals("dm-001", result.businessSessionId());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processRebuild: NOT_EXISTS → 返回 410 + deletion message（不创建 session）")
    void processRebuildAssistantNotExistsReturns410() {
        when(resolverService.resolveWithStatus("deleted"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null));

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "deleted", "user-001");

        assertFalse(result.success());
        assertEquals(410, result.code());
        assertEquals("该助理已被删除", result.message());
        assertEquals("dm-001", result.businessSessionId());
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(sessionManager, never()).requestToolSession(any(), any());
    }

    @Test
    @DisplayName("processChat: session not found → calls createSessionAsync")
    void processChatNoSession() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001"))
                .thenReturn(null);
        when(contextInjectionService.resolvePrompt("direct", "first msg", null))
                .thenReturn("first msg");

        InboundResult result = service.processChat(
                "im", "direct", "dm-new", "assist-001",
                null, "first msg", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                "im", "direct", "dm-new", "ak-001",
                "owner-001", "assist-001", null, "first msg", null);
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: agent 离线时返回 error(503, offline_msg, sid, wsid) 且调用 handleAgentOffline 副作用")
    void processChatAgentOfflineReturns503() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        SkillSession existing = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(existing);

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "user-001", "hello", "text", null, null, "EXTERNAL", null);

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

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "user-001", "hello", "text", null, null, "EXTERNAL", null);

        assertTrue(result.success());
        verify(gatewayApiClient, never()).getAgentByAk(anyString()); // 关键：没查在线状态
        verify(gatewayRelayService).sendInvokeToGateway(any(InvokeCommand.class));
    }

    @Test
    @DisplayName("processChat: group + null sender → sendUserAccount falls back to ownerWelinkId")
    void processChatGroupNullSenderFallsBackToOwner() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "group", "grp-001", "ak-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt(eq("group"), eq("hello"), any()))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "group", "grp-001", "assist-001",
                null,
                "hello", "text", null, null, "IM", null);

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
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "yes", "tc-001", null, null, null);

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
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "answer", "tool-call-1", null, "EXTERNAL", null);

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
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession(any(), any(), any(), any())).thenReturn(null);
        lenient().when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线（场景注释，404 优先不会调用）

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "answer", "tool-call-1", null, "EXTERNAL", null);

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
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "perm-001", "allow", null, null, null);

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
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "perm-1", "once", null, "EXTERNAL", null);

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
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession(any(), any(), any(), any())).thenReturn(null);
        lenient().when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null); // 离线（场景注释，404 优先不会调用）

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "perm-1", "once", null, "EXTERNAL", null);

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
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001"))
                .thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionManager).requestToolSession(session, null);
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("processRebuild: session not found → calls createSessionAsync")
    void processRebuildNoSession() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001"))
                .thenReturn(null);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-new", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                "im", "direct", "dm-new", "ak-001",
                "owner-001", "assist-001", "user-001", null, null);
        verify(sessionManager, never()).requestToolSession(any(), any());
    }

    @Test
    @DisplayName("processRebuild: agent 离线时返回 error(503, offline_msg, sid, wsid)，不创建 session")
    void processRebuildAgentOfflineReturns503() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
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
        verify(sessionManager, never()).createSessionAsync(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== business self-heal (R1) ====================

    @Test
    @DisplayName("processChat: business 助手 session 存在但 toolSessionId 缺失 → 自愈后正常转发 CHAT")
    void processChatBusinessSessionMissingToolSessionId_selfHealsAndForwards() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-healed");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(sessionService.findByIdSafe(101L)).thenReturn(session); // 二次检查仍然为空
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(sessionService).updateToolSessionId(eq(101L), argThat((String s) -> s != null && s.startsWith("cloud-")));
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals(GatewayActions.CHAT, captor.getValue().action());
        // 自愈后 session 本地引用应携带新的 toolSessionId
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertTrue(payload.get("toolSessionId").asText().startsWith("cloud-"));
        // 确认没有走老的 rebuild 路径
        verify(sessionManager, never()).requestToolSession(any(), any());
    }

    @Test
    @DisplayName("processChat: business 助手 toolSessionId 缺失 + DB 已被别的实例补齐 → 复用而不再 update")
    void processChatBusinessSessionMissingToolSessionId_secondaryCheckHit_reusesExisting() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-will-not-be-used");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession stale = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(stale);
        // 二次检查拿到另一个实例已补齐的值
        SkillSession latest = buildSessionWithoutToolSession();
        latest.setToolSessionId("cloud-already-healed");
        when(sessionService.findByIdSafe(101L)).thenReturn(latest);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        // 关键：不再调用 updateToolSessionId
        verify(sessionService, never()).updateToolSessionId(any(), any());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("cloud-already-healed", payload.get("toolSessionId").asText());
        verify(sessionManager, never()).requestToolSession(any(), any());
    }

    @Test
    @DisplayName("processChat: personal 助手 session 存在但 toolSessionId 缺失 → 保持 requestToolSession 路径")
    void processChatPersonalSessionMissingToolSessionId_keepsRequestToolSession() {
        // 默认 setUp 即为 personal（generateToolSessionId 返回 null）
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(sessionManager).requestToolSession(session, "hello");
        verify(sessionService, never()).updateToolSessionId(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: scope 识别降级为 personal → 不触发自愈，保持 requestToolSession 路径")
    void processChatScopeDegradedToPersonal_keepsRequestToolSession() {
        // 降级语义：上游故障，getCachedScope 返回 "personal"（即使真实是 business）
        // dispatcher.getStrategy("personal") 永远返回 personal strategy（generateToolSessionId=null）
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("personal");
        // setUp 中的默认 personalStrategy 已经 generateToolSessionId() == null (未 stub)

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(sessionManager).requestToolSession(session, "hello");
        verify(sessionService, never()).updateToolSessionId(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    // ==================== business rebuild (R2) ====================

    @Test
    @DisplayName("processRebuild: business 助手 session 已有 toolSessionId → 无条件重生成，不发 Gateway 消息")
    void processRebuildBusinessExistingSession_regeneratesAndReturnsOk() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-new-one");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession(); // 已有 tool-001
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionService).updateToolSessionId(eq(101L), argThat((String s) -> s != null && s.startsWith("cloud-")));
        verify(sessionManager, never()).requestToolSession(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processRebuild: business 助手 session 已有但 toolSessionId=null → 也重生成，不发 Gateway 消息")
    void processRebuildBusinessExistingSessionNullToolSessionId_regeneratesAndReturnsOk() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-from-null");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionService).updateToolSessionId(eq(101L), argThat((String s) -> s != null && s.startsWith("cloud-")));
        verify(sessionManager, never()).requestToolSession(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processRebuild: personal 助手 session 存在 → 保持 requestToolSession 路径")
    void processRebuildPersonalExistingSession_callsRequestToolSession() {
        // 默认 setUp 是 personal
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionManager).requestToolSession(session, null);
        verify(sessionService, never()).updateToolSessionId(any(), any());
    }

    // ==================== P1/P2 concurrency & pending replay ====================

    @Test
    @DisplayName("processChat: business 自愈锁被别人持有 + DB 已被他 heal → 复用新 toolSessionId，无 update")
    void processChatBusinessSessionMissingToolSessionId_lockHeldButDbHealedByOther_reusesAndContinues() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-would-have-generated");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        // 锁被别人持有
        when(valueOperations.setIfAbsent(startsWith("skill:im-session:heal:"), anyString(), any(Duration.class)))
                .thenReturn(false);
        // 查 DB 已被别人补齐
        SkillSession latest = buildSessionWithoutToolSession();
        latest.setToolSessionId("cloud-peer-healed");
        when(sessionService.findByIdSafe(101L)).thenReturn(latest);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        // 关键：复用对方 heal 结果，不再自己 update
        verify(sessionService, never()).updateToolSessionId(any(), any());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("cloud-peer-healed", payload.get("toolSessionId").asText());
        verify(sessionManager, never()).requestToolSession(any(), any());
    }

    @Test
    @DisplayName("processChat: business case C 不向 pending list append（避免 self-heal 重放放大）")
    void processChatBusinessCaseC_doesNotAppendPending() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-new");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        // session 已就绪 → 进 case C
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(gatewayRelayService).sendInvokeToGateway(any());
        // 关键：business 不写 pending list
        verify(rebuildService, never()).appendPendingMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("processChat: business 自愈分支 dispatch 也不 append pending（避免 peer 误消费）")
    void processChatBusinessSelfHeal_doesNotAppendPending() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-healed");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        SkillSession session = buildSessionWithoutToolSession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(sessionService.findByIdSafe(101L)).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(gatewayRelayService).sendInvokeToGateway(any());
        // 关键：self-heal 分支也不 append
        verify(rebuildService, never()).appendPendingMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("processChat: business 自愈时消费 pending list 中的 legacy 消息并重放")
    void processChatBusinessSessionMissingToolSessionId_consumesLegacyPendingMessages() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-fresh");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);
        when(sessionService.findByIdSafe(101L)).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        // pending list 里有两条 legacy + 一条与当前 prompt 重复（应被 skip）
        List<String> legacy = Arrays.asList("legacy-1", "legacy-2", "hello");
        when(rebuildService.consumePendingMessages("101")).thenReturn(legacy);

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        // 3 次 invoke：legacy-1 + legacy-2 + 当前 prompt（重复的 "hello" 被跳过）
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService, times(3)).sendInvokeToGateway(captor.capture());
        List<InvokeCommand> sent = captor.getAllValues();
        JsonNode p1 = objectMapper.readTree(sent.get(0).payload());
        JsonNode p2 = objectMapper.readTree(sent.get(1).payload());
        JsonNode p3 = objectMapper.readTree(sent.get(2).payload());
        assertEquals("legacy-1", p1.get("text").asText());
        assertEquals("legacy-2", p2.get("text").asText());
        assertEquals("hello", p3.get("text").asText());
        verify(rebuildService).consumePendingMessages("101");
        // 关键：重放过程中每次 dispatch 都不 append，避免 peer 误消费本请求刚写入的 prompt
        verify(rebuildService, never()).appendPendingMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("processRebuild: business 锁被别人持有 → 视为并发合并，直接返回 ok，不 update")
    void processRebuildBusinessLockHeldByOther_returnsOkWithoutUpdate() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-would-have-been");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("ak-001")).thenReturn("business");

        when(valueOperations.setIfAbsent(startsWith("skill:im-session:heal:"), anyString(), any(Duration.class)))
                .thenReturn(false);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionService, never()).updateToolSessionId(any(), any());
        verify(sessionManager, never()).requestToolSession(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
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

    /** session 存在但 toolSessionId 为空的脏态（business 助手自愈场景） */
    private SkillSession buildSessionWithoutToolSession() {
        SkillSession session = new SkillSession();
        session.setId(101L);
        session.setAk("ak-001");
        session.setUserId("owner-001");
        session.setBusinessSessionDomain("im");
        session.setBusinessSessionType("direct");
        // toolSessionId 故意留 null
        return session;
    }
}
