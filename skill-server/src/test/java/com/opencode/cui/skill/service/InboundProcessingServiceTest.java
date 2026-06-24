package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.AssistantSessionIdentity;
import com.opencode.cui.skill.model.AvailabilityResult;
import com.opencode.cui.skill.model.AvailabilitySource;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.PendingChatRequest;
import com.opencode.cui.skill.model.ResolveOutcome;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
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
import java.util.Objects;
import java.util.Optional;

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
    private MessagePersistenceService persistenceService;
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
    private AssistantAvailabilityService availabilityService;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ChannelLookupService channelLookupService;
    @Mock
    private ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService;
    @Mock
    private DefaultAssistantRuleService ruleService;
    @Mock
    private AllowedSlashCommandsResolver allowedSlashCommandsResolver;
    @Mock
    private MessageTurnLifecycle messageTurnLifecycle;

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

        // resolver 默认返 null（未配置）
        lenient().when(allowedSlashCommandsResolver.resolve(anyString(), anyString())).thenReturn(null);

        service = new InboundProcessingService(
                resolverService,
                assistantIdProperties,
                gatewayApiClient,
                sessionManager,
                contextInjectionService,
                gatewayRelayService,
                messageService,
                persistenceService,
                rebuildService,
                objectMapper,
                assistantInfoService,
                scopeDispatcher,
                emitter,
                deliveryProperties,
                redisMessageBroker,
                offlineMessageProvider,
                availabilityService,
                sessionService,
                redisTemplate,
                channelLookupService,
                channelSuppressReplyWhitelistService,
                ruleService,
                allowedSlashCommandsResolver,
                messageTurnLifecycle);

        // 默认 scope 策略：personal（requiresOnlineCheck=true）
        AssistantScopeStrategy personalStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(personalStrategy.requiresOnlineCheck()).thenReturn(true);
        // nullable 覆盖 getAssistantInfo 返回 null 的情况（降级场景）
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(personalStrategy);
        lenient().when(scopeDispatcher.getStrategy(
                nullable(String.class), nullable(String.class), nullable(AssistantInfo.class)))
                .thenReturn(personalStrategy);
        lenient().when(ruleService.lookup(any(), any())).thenReturn(Optional.empty());
        // 默认 getAssistantInfo 返回 personal info（personal 策略不含 business tag）
        AssistantInfo defaultPersonalInfo = new AssistantInfo();
        defaultPersonalInfo.setAssistantScope("personal");
        lenient().when(assistantInfoService.getAssistantInfo(any())).thenReturn(defaultPersonalInfo);
        lenient().when(assistantInfoService.getAssistantInfo(any(), any())).thenReturn(defaultPersonalInfo);
        // 默认 Agent 在线
        lenient().when(availabilityService.resolve(any()))
                .thenReturn(AvailabilityResult.ofOnline());
        lenient().when(offlineMessageProvider.get()).thenReturn(MOCK_OFFLINE_MSG);

        // 默认 business heal 锁行为：拿到锁成功，pending list 为空
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        // PR3: 切到新签名 consumePendingRequests（返回 List<PendingChatRequest>）
        lenient().when(rebuildService.consumePendingRequests(anyString()))
                .thenReturn(Collections.emptyList());

        // 助理删除文案（用于 NOT_EXISTS 410 分支）
        lenient().when(resolverService.getDeletionMessage()).thenReturn("该助理已被删除");

        // 默认 channel 查询行为：不命中白名单（即默认不抑制）
        lenient().when(channelLookupService.getToolType(any())).thenReturn(java.util.Optional.empty());
        lenient().when(channelSuppressReplyWhitelistService.shouldSuppress(any())).thenReturn(false);
    }

    // ==================== processChat ====================

    @Test
    @DisplayName("processChat: session ready → sends CHAT invoke")
    void processChatSessionReady() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "user-real-1", "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals("ak-001", captor.getValue().ak());
        assertEquals("owner-001", captor.getValue().userId());
        assertEquals(GatewayActions.CHAT, captor.getValue().action());
        assertTrue(captor.getValue().payload().contains("tool-001"));
        verify(messageService).saveUserMessage(101L, "hello");
        // PR3: 情况 C personal appendToPending 走新 API，传完整结构化 PendingChatRequest
        ArgumentCaptor<PendingChatRequest> reqCap = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(rebuildService).appendPendingMessage(eq("101"), reqCap.capture());
        PendingChatRequest appended = reqCap.getValue();
        assertEquals("hello", appended.text());
        assertEquals("assist-001", appended.assistantAccount());
        assertEquals("user-real-1", appended.sendUserAccount(), "direct chat: sender 使用真实发送者，不再 fallback owner");
        assertNull(appended.imGroupId(), "direct session: imGroupId 为 null");
        assertNotNull(appended.messageId());
        JsonNode chatPayload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("user-real-1", chatPayload.get("sendUserAccount").asText(),
                "direct chat should put real sender as sendUserAccount");
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
        verify(sessionManager, never()).createSessionAsync(
                any(), any(), any(), any(AssistantSessionIdentity.class), any(), any(), any());
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
        verify(sessionManager, never()).createSessionAsync(
                any(), any(), any(), any(AssistantSessionIdentity.class), any(), any(), any());
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processChat: 默认助手规则命中 → 跳过账号解析和在线检查，使用虚拟 AK/account")
    void processChatDefaultAssistantUsesRuleIdentity() throws Exception {
        stubDefaultAssistantRoute();
        SkillSession session = buildDefaultAssistantSession();
        when(sessionManager.findSession("helpdesk", "direct", "dm-default", null, "ACC_V"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "helpdesk", "direct", "dm-default", "request-acc",
                "sender-real", "hello", "text", null, null, "EXTERNAL", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        InvokeCommand command = captor.getValue();
        assertEquals("AK_V", command.ak());
        assertEquals("sender-real", command.userId());
        JsonNode payload = objectMapper.readTree(command.payload());
        assertEquals("ACC_V", payload.get("assistantAccount").asText());
        assertEquals("sender-real", payload.get("sendUserAccount").asText());
        assertEquals("", payload.get("imGroupId").asText(),
                "direct chat payload should carry imGroupId as empty string");
        verify(resolverService, never()).resolveWithStatus(anyString());
        verify(availabilityService, never()).resolve(anyString());
        verify(assistantInfoService, never()).getAssistantInfo("AK_V");
        verify(rebuildService, never()).appendPendingMessage(anyString(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processQuestionReply: 默认助手规则命中 → 不按 assistantAccount 反查 AK")
    void processQuestionReplyDefaultAssistantSkipsResolver() throws Exception {
        stubDefaultAssistantRoute();
        SkillSession session = buildDefaultAssistantSession();
        when(sessionManager.findSession("helpdesk", "direct", "dm-default", null, "ACC_V"))
                .thenReturn(session);

        InboundResult result = service.processQuestionReply(
                "helpdesk", "direct", "dm-default", "request-acc",
                "sender-real", "yes", "tc-001", null, "EXTERNAL", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("AK_V", captor.getValue().ak());
        assertEquals("ACC_V", payload.get("assistantAccount").asText());
        assertEquals("sender-real", payload.get("sendUserAccount").asText());
        verify(resolverService, never()).resolveWithStatus(anyString());
        verify(availabilityService, never()).resolve(anyString());
    }

    @Test
    @DisplayName("processPermissionReply: 默认助手规则命中 → 不按 assistantAccount 反查 AK")
    void processPermissionReplyDefaultAssistantSkipsResolver() throws Exception {
        stubDefaultAssistantRoute();
        SkillSession session = buildDefaultAssistantSession();
        when(sessionManager.findSession("helpdesk", "direct", "dm-default", null, "ACC_V"))
                .thenReturn(session);

        InboundResult result = service.processPermissionReply(
                "helpdesk", "direct", "dm-default", "request-acc",
                "sender-real", "perm-1", "once", null, "EXTERNAL", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("AK_V", captor.getValue().ak());
        assertEquals("ACC_V", payload.get("assistantAccount").asText());
        assertEquals("sender-real", payload.get("sendUserAccount").asText());
        verify(resolverService, never()).resolveWithStatus(anyString());
        verify(availabilityService, never()).resolve(anyString());
    }

    @Test
    @DisplayName("processRebuild: 默认助手规则命中 → createSessionAsync 使用虚拟 AK/account")
    void processRebuildDefaultAssistantCreatesWithRuleIdentity() {
        stubDefaultAssistantRoute();
        when(sessionManager.findSession("helpdesk", "direct", "dm-default", null, "ACC_V"))
                .thenReturn(null);

        InboundResult result = service.processRebuild(
                "helpdesk", "direct", "dm-default", "request-acc", "sender-real");

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                eq("helpdesk"), eq("direct"), eq("dm-default"),
                identityMatching(AssistantSessionIdentity.RouteKind.DEFAULT,
                        "AK_V", "sender-real", "ACC_V", null, "ACC_V"),
                eq("sender-real"), isNull(), isNull());
        verify(resolverService, never()).resolveWithStatus(anyString());
        verify(availabilityService, never()).resolve(anyString());
    }

    @Test
    @DisplayName("processChat: session not found → calls createSessionAsync")
    void processChatNoSession() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001", "assist-001"))
                .thenReturn(null);
        when(contextInjectionService.resolvePrompt("direct", "first msg", null))
                .thenReturn("first msg");

        InboundResult result = service.processChat(
                "im", "direct", "dm-new", "assist-001",
                "user-first", "first msg", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                eq("im"), eq("direct"), eq("dm-new"),
                identityMatching(AssistantSessionIdentity.RouteKind.LOCAL,
                        "ak-001", "owner-001", "assist-001", "ak-001", "assist-001"),
                eq("user-first"), eq("first msg"), isNull());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: no-AK remote assistant -> business route, no UNKNOWN block")
    void processChatNoAkRemoteAssistantCreatesBusinessSession() {
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        bizInfo.setBusinessTag("tag-001");
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        when(businessStrategy.requiresOnlineCheck()).thenReturn(false);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, null, null,
                        "assist-001", true, "tag-001"));
        when(assistantInfoService.getAssistantInfo(null, "assist-001")).thenReturn(bizInfo);
        when(scopeDispatcher.getStrategy("im", "group", bizInfo)).thenReturn(businessStrategy);
        when(sessionManager.findSession("im", "group", "group-001", null, "assist-001")).thenReturn(null);
        when(contextInjectionService.resolvePrompt("group", "first msg", null))
                .thenReturn("first msg");

        InboundResult result = service.processChat(
                "im", "group", "group-001", "assist-001",
                "sender-001", "first msg", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(availabilityService, never()).resolve(any());
        verify(sessionManager).createSessionAsync(
                eq("im"), eq("group"), eq("group-001"),
                identityMatching(AssistantSessionIdentity.RouteKind.REMOTE,
                        null, null, "assist-001", null, "assist-001"),
                eq("sender-001"), eq("first msg"), isNull());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: agent offline returns 503")
    void processChatAgentOfflineReturns503() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(availabilityService.resolve("ak-001")).thenReturn(AvailabilityResult.ofOfflineDefault(MOCK_OFFLINE_MSG, null)); // 离线

        SkillSession existing = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
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
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "user-001", "hello", "text", null, null, "EXTERNAL", null);

        assertTrue(result.success());
        verify(availabilityService, never()).resolve(anyString()); // 关键：没查在线状态
        verify(gatewayRelayService).sendInvokeToGateway(any(InvokeCommand.class));
    }

    @Test
    @DisplayName("processChat: direct + 真实 sender 非 owner → sendUserAccount 透传真实发送者（不再回落 owner）")
    void processChatDirectNonOwnerSenderUsedAsIs() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt(eq("direct"), eq("hello"), any()))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "user-non-owner",
                "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals("owner-001", captor.getValue().userId(),
                "direct chat invoke userId should use assistant owner for local routing");
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("user-non-owner", payload.get("sendUserAccount").asText(),
                "direct chat: 非 owner 的真实 senderUserAccount 必须直接透传，不能被覆盖为 owner");
        assertEquals("", payload.get("imGroupId").asText(),
                "direct chat payload should carry imGroupId as empty string");
    }

    // ==================== suppressReply 4-branch matrix（PRD AC） ====================
    // 矩阵：sessionType ∈ {group, direct} × channel ∈ {whitelist hit, miss}
    // 期望：仅 group + hit → suppressReply == TRUE；其余三档 == null。

    @Test
    @DisplayName("dispatchChatToGateway: groupChat + channelInWhitelist → suppressReply=TRUE")
    void dispatchChatToGateway_groupChat_channelInWhitelist_setsSuppressReply() {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "group", "grp-001", "ak-001", "assist-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt(eq("group"), eq("hello"), any()))
                .thenReturn("hello");
        when(channelLookupService.getToolType("ak-001"))
                .thenReturn(java.util.Optional.of("opencode"));
        when(channelSuppressReplyWhitelistService.shouldSuppress("opencode")).thenReturn(true);

        InboundResult result = service.processChat(
                "im", "group", "grp-001", "assist-001",
                "user-x", "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().suppressReply(),
                "group + whitelist hit should mark suppressReply=true");
    }

    @Test
    @DisplayName("dispatchChatToGateway: groupChat + channelNotInWhitelist → suppressReply=null")
    void dispatchChatToGateway_groupChat_channelNotInWhitelist_noSuppress() {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "group", "grp-001", "ak-001", "assist-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt(eq("group"), eq("hello"), any()))
                .thenReturn("hello");
        when(channelLookupService.getToolType("ak-001"))
                .thenReturn(java.util.Optional.of("assistant"));
        when(channelSuppressReplyWhitelistService.shouldSuppress("assistant")).thenReturn(false);

        InboundResult result = service.processChat(
                "im", "group", "grp-001", "assist-001",
                "user-x", "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertNull(captor.getValue().suppressReply(),
                "group + whitelist miss should leave suppressReply=null");
    }

    @Test
    @DisplayName("dispatchChatToGateway: directChat + channelInWhitelist → suppressReply=null（白名单仅群聊生效）")
    void dispatchChatToGateway_directChat_channelInWhitelist_noSuppress() {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null))
                .thenReturn("hello");
        // 即便 channel 在白名单，单聊也不应触发 suppressReply
        lenient().when(channelLookupService.getToolType("ak-001"))
                .thenReturn(java.util.Optional.of("opencode"));
        lenient().when(channelSuppressReplyWhitelistService.shouldSuppress("opencode")).thenReturn(true);

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertNull(captor.getValue().suppressReply(),
                "direct chat must never set suppressReply, regardless of whitelist");
        // 关键：单聊路径不应查询白名单（避免无谓 RPC / DB 调用）
        verify(channelSuppressReplyWhitelistService, never()).shouldSuppress(anyString());
    }

    @Test
    @DisplayName("dispatchChatToGateway: directChat + channelNotInWhitelist → suppressReply=null")
    void dispatchChatToGateway_directChat_channelNotInWhitelist_noSuppress() {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
                .thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null))
                .thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertNull(captor.getValue().suppressReply());
        verify(channelSuppressReplyWhitelistService, never()).shouldSuppress(anyString());
    }

    // ==================== processQuestionReply ====================

    @Test
    @DisplayName("processQuestionReply: session ready → sends QUESTION_REPLY invoke")
    void processQuestionReplySessionReady() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
                .thenReturn(session);

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "yes", "tc-001", null, null, null);

        assertTrue(result.success());
        ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
        assertEquals(GatewayActions.QUESTION_REPLY, captor.getValue().action());
        assertEquals("owner-001", captor.getValue().userId(),
                "direct question_reply invoke userId should use assistant owner for local routing");
        assertTrue(captor.getValue().payload().contains("yes"));
        assertTrue(captor.getValue().payload().contains("tc-001"));
        assertTrue(captor.getValue().payload().contains("tool-001"));
        JsonNode payload = objectMapper.readTree(captor.getValue().payload());
        assertEquals("user-001", payload.get("sendUserAccount").asText(),
                "gateway payload should bind sendUserAccount=user-001");
        // business（云端）助理通过 BusinessScopeStrategy.extractField 从 payload 反向取这些字段，
        // 缺一个就会在 DefaultCloudRequestStrategy 校验时抛 'assistantAccount must not be blank'。
        assertEquals("assist-001", payload.get("assistantAccount").asText(),
                "gateway payload should carry assistantAccount for business cloud route");
        assertTrue(payload.has("messageId"), "gateway payload should carry messageId");
        assertEquals("", payload.get("imGroupId").asText(),
                "direct session: imGroupId should be empty string in downstream payload");
        verify(persistenceService).recordQuestionReply(101L, "tc-001", "yes", null);
    }

    @Test
    @DisplayName("processQuestionReply: session 存在 + agent 离线 → 返回 error(503, offline_msg)")
    void processQuestionReplyAgentOfflineReturns503() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(availabilityService.resolve("ak-001")).thenReturn(AvailabilityResult.ofOfflineDefault(MOCK_OFFLINE_MSG, null)); // 离线

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
        when(sessionManager.findSession(any(), any(), any(), any(), any())).thenReturn(null);
        lenient().when(availabilityService.resolve("ak-001")).thenReturn(AvailabilityResult.ofOfflineDefault(MOCK_OFFLINE_MSG, null)); // 离线（场景注释，404 优先不会调用）

        InboundResult result = service.processQuestionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "answer", "tool-call-1", null, "EXTERNAL", null);

        assertFalse(result.success());
        assertEquals(404, result.code());
        assertEquals("Session not found or not ready", result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertNull(result.welinkSessionId());
        verify(availabilityService, never()).resolve(anyString()); // 404 优先，未查在线
    }

    // ==================== processPermissionReply ====================

    @Test
    @DisplayName("processPermissionReply: session ready → sends PERMISSION_REPLY invoke + broadcasts")
    void processPermissionReplySessionReady() throws Exception {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
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
        assertEquals("owner-001", invokeCaptor.getValue().userId(),
                "direct permission_reply invoke userId should use assistant owner for local routing");
        assertTrue(invokeCaptor.getValue().payload().contains("perm-001"));
        assertTrue(invokeCaptor.getValue().payload().contains("allow"));
        JsonNode permissionPayload = objectMapper.readTree(invokeCaptor.getValue().payload());
        assertEquals("user-001", permissionPayload.get("sendUserAccount").asText(),
                "gateway payload should bind sendUserAccount=user-001");
        // business（云端）助理协议依赖：缺一个就会在 DefaultCloudRequestStrategy 校验时 fast-fail。
        assertEquals("assist-001", permissionPayload.get("assistantAccount").asText(),
                "gateway payload should carry assistantAccount for business cloud route");
        assertTrue(permissionPayload.has("messageId"), "gateway payload should carry messageId");
        assertEquals("", permissionPayload.get("imGroupId").asText(),
                "direct session: imGroupId should be empty string in downstream payload");

        // 验证广播
        ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(gatewayRelayService).publishProtocolMessage(eq("101"), msgCaptor.capture());
        assertEquals(StreamMessage.Types.PERMISSION_REPLY, msgCaptor.getValue().getType());
        assertEquals("perm-001", msgCaptor.getValue().getPermission().getPermissionId());
        assertEquals("allow", msgCaptor.getValue().getPermission().getResponse());
        verify(persistenceService).recordPermissionReply(101L, "perm-001", "allow");
    }

    @Test
    @DisplayName("processPermissionReply: session 存在 + agent 离线 → 返回 error(503, offline_msg)")
    void processPermissionReplyAgentOfflineReturns503() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(availabilityService.resolve("ak-001")).thenReturn(AvailabilityResult.ofOfflineDefault(MOCK_OFFLINE_MSG, null)); // 离线

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
        when(sessionManager.findSession(any(), any(), any(), any(), any())).thenReturn(null);
        lenient().when(availabilityService.resolve("ak-001")).thenReturn(AvailabilityResult.ofOfflineDefault(MOCK_OFFLINE_MSG, null)); // 离线（场景注释，404 优先不会调用）

        InboundResult result = service.processPermissionReply(
                "im", "direct", "dm-001", "assist-001",
                "user-001",
                "perm-1", "once", null, "EXTERNAL", null);

        assertFalse(result.success());
        assertEquals(404, result.code());
        assertEquals("Session not found or not ready", result.message());
        assertEquals("dm-001", result.businessSessionId());
        assertNull(result.welinkSessionId());
        verify(availabilityService, never()).resolve(anyString());
    }

    // ==================== processRebuild ====================

    @Test
    @DisplayName("processRebuild: session exists → calls requestToolSession")
    void processRebuildSessionExists() {
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
                .thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        // PR3: processRebuild personal 分支调 route-user-aware requestToolSession
        verify(sessionManager).requestToolSession(eq(session), (PendingChatRequest) isNull(), eq("owner-001"));
        verify(sessionManager, never()).createSessionAsync(
                any(), any(), any(), any(AssistantSessionIdentity.class), any(), any(), any());
    }

    @Test
    @DisplayName("processRebuild: session not found → calls createSessionAsync")
    void processRebuildNoSession() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-new", "ak-001", "assist-001"))
                .thenReturn(null);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-new", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionManager).createSessionAsync(
                eq("im"), eq("direct"), eq("dm-new"),
                identityMatching(AssistantSessionIdentity.RouteKind.LOCAL,
                        "ak-001", "owner-001", "assist-001", "ak-001", "assist-001"),
                eq("user-001"), isNull(), isNull());
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processRebuild: agent 离线时返回 error(503, offline_msg, sid, wsid)，不创建 session")
    void processRebuildAgentOfflineReturns503() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(availabilityService.resolve("ak-001")).thenReturn(AvailabilityResult.ofOfflineDefault(MOCK_OFFLINE_MSG, null)); // 离线

        SkillSession existing = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001"))
                .thenReturn(existing);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertFalse(result.success());
        assertEquals(503, result.code());
        assertEquals(MOCK_OFFLINE_MSG, result.message());
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
        verify(sessionManager, never()).createSessionAsync(
                any(), any(), any(), any(AssistantSessionIdentity.class), any(), any(), any());
    }

    // ==================== business self-heal (R1) ====================

    @Test
    @DisplayName("processChat: business 助手 session 存在但 toolSessionId 缺失 → 自愈后正常转发 CHAT")
    void processChatBusinessSessionMissingToolSessionId_selfHealsAndForwards() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-healed");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
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
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processChat: business 助手 toolSessionId 缺失 + DB 已被别的实例补齐 → 复用而不再 update")
    void processChatBusinessSessionMissingToolSessionId_secondaryCheckHit_reusesExisting() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-will-not-be-used");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession stale = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(stale);
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
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processChat: personal 助手 session 存在但 toolSessionId 缺失 → 保持 requestToolSession 路径")
    void processChatPersonalSessionMissingToolSessionId_keepsRequestToolSession() {
        // 默认 setUp 即为 personal（generateToolSessionId 返回 null）
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "sender-001", "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(sessionManager).requestToolSession(eq(session), captor.capture(), eq("owner-001"));
        PendingChatRequest pending = captor.getValue();
        assertEquals("hello", pending.text());
        assertEquals("assist-001", pending.assistantAccount());
        assertEquals("sender-001", pending.sendUserAccount());
        assertNull(pending.imGroupId());
        verify(sessionService, never()).updateToolSessionId(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
    }

    @Test
    @DisplayName("processChat: personal group session missing toolSessionId -> rebuild keeps sender and route user")
    void processChatPersonalGroupSessionMissingToolSessionId_rebuildKeepsSenderAndRouteUser() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        session.setUserId(null);
        session.setAssistantAccount("assist-001");
        session.setBusinessSessionType("group");
        session.setBusinessSessionId("group-001");
        when(sessionManager.findSession("im", "group", "group-001", "ak-001", "assist-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("group", "@assist hello", null)).thenReturn("@assist hello");
        JsonNode businessExtParam = objectMapper.createObjectNode().put("businessMessageId", "msg-001");

        InboundResult result = service.processChat(
                "im", "group", "group-001", "assist-001",
                "sender-001", "@assist hello", "text", null, null, "IM", businessExtParam);

        assertTrue(result.success());
        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(sessionManager).requestToolSession(eq(session), captor.capture(), eq("owner-001"));
        PendingChatRequest pending = captor.getValue();
        assertEquals("@assist hello", pending.text());
        assertEquals("assist-001", pending.assistantAccount());
        assertEquals("sender-001", pending.sendUserAccount());
        assertEquals("group-001", pending.imGroupId());
        assertEquals("im", pending.businessSessionDomain());
        assertEquals("group", pending.businessSessionType());
        assertSame(businessExtParam, pending.businessExtParam());
        assertNull(session.getUserId(), "group session must keep null userId");
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionService, never()).updateToolSessionId(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processChat: scope 识别降级为 personal → 不触发自愈，保持 requestToolSession 路径")
    void processChatScopeDegradedToPersonal_keepsRequestToolSession() {
        // 降级语义：上游故障，getAssistantInfo 返回 null（personal 降级）
        // dispatcher.getStrategy(null) 永远返回 personal strategy（generateToolSessionId=null）
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(null);
        // setUp 中的默认 personalStrategy 已经 generateToolSessionId() == null (未 stub)

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                "sender-001", "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(sessionManager).requestToolSession(eq(session), captor.capture(), eq("owner-001"));
        assertEquals("sender-001", captor.getValue().sendUserAccount());
        verify(sessionService, never()).updateToolSessionId(any(), any());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
    }

    // ==================== business rebuild (R2) ====================

    @Test
    @DisplayName("processRebuild: business 助手 session 已有 toolSessionId → 无条件重生成，不发 Gateway 消息")
    void processRebuildBusinessExistingSession_regeneratesAndReturnsOk() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-new-one");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession(); // 已有 tool-001
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionService).updateToolSessionId(eq(101L), argThat((String s) -> s != null && s.startsWith("cloud-")));
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processRebuild: business 助手 session 已有但 toolSessionId=null → 也重生成，不发 Gateway 消息")
    void processRebuildBusinessExistingSessionNullToolSessionId_regeneratesAndReturnsOk() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-from-null");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionService).updateToolSessionId(eq(101L), argThat((String s) -> s != null && s.startsWith("cloud-")));
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }

    @Test
    @DisplayName("processRebuild: 远端 no-AK business 助手 → 按 assistantAccount 命中并本地重生成")
    void processRebuildRemoteNoAkBusinessExistingSession_regeneratesByAssistantAccount() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-remote-new");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo(null, "assist-001")).thenReturn(bizInfo);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, null, null,
                        "assist-001", true, "tag-001"));
        SkillSession session = buildReadySession();
        session.setAk(null);
        session.setAssistantAccount("assist-001");
        session.setBusinessSessionType("group");
        when(sessionManager.findSession("im", "group", "group-001", null, "assist-001"))
                .thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "group", "group-001", "assist-001", "sender-001");

        assertTrue(result.success());
        verify(sessionService).updateToolSessionId(eq(101L), argThat((String s) -> s != null && s.startsWith("cloud-")));
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processRebuild: personal 助手 session 存在 → 保持 requestToolSession 路径")
    void processRebuildPersonalExistingSession_callsRequestToolSession() {
        // 默认 setUp 是 personal
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        // PR3: processRebuild personal 分支调 route-user-aware requestToolSession
        verify(sessionManager).requestToolSession(eq(session), (PendingChatRequest) isNull(), eq("owner-001"));
        verify(sessionService, never()).updateToolSessionId(any(), any());
    }

    @Test
    @DisplayName("processRebuild: group personal session passes owner route user while keeping session.userId null")
    void processRebuildGroupPersonalSessionPassesOwnerRouteUser() {
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        session.setUserId(null);
        session.setBusinessSessionType("group");
        session.setBusinessSessionId("group-001");
        when(sessionManager.findSession("im", "group", "group-001", "ak-001", "assist-001"))
                .thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "group", "group-001", "assist-001", "sender-001");

        assertTrue(result.success());
        verify(sessionManager).requestToolSession(eq(session), (PendingChatRequest) isNull(), eq("owner-001"));
        assertNull(session.getUserId(), "group session must keep null userId");
        verify(sessionService, never()).updateToolSessionId(any(), any());
    }

    // ==================== P1/P2 concurrency & pending replay ====================

    @Test
    @DisplayName("processChat: business 自愈锁被别人持有 + DB 已被他 heal → 复用新 toolSessionId，无 update")
    void processChatBusinessSessionMissingToolSessionId_lockHeldButDbHealedByOther_reusesAndContinues() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-would-have-generated");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

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
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
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
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processChat: business case C 不向 pending list append（避免 self-heal 重放放大）")
    void processChatBusinessCaseC_doesNotAppendPending() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-new");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        // session 已就绪 → 进 case C
        SkillSession session = buildReadySession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(gatewayRelayService).sendInvokeToGateway(any());
        // 关键：business 不写 pending list
        // PR3: appendPendingMessage(String, String) 重载已删除，新签名是 (String, PendingChatRequest)
        verify(rebuildService, never()).appendPendingMessage(anyString(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processChat: business 自愈分支 dispatch 也不 append pending（避免 peer 误消费）")
    void processChatBusinessSelfHeal_doesNotAppendPending() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-healed");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        SkillSession session = buildSessionWithoutToolSession();
        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(sessionService.findByIdSafe(101L)).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        InboundResult result = service.processChat(
                "im", "direct", "dm-001", "assist-001",
                null, "hello", "text", null, null, "IM", null);

        assertTrue(result.success());
        verify(gatewayRelayService).sendInvokeToGateway(any());
        // 关键：self-heal 分支也不 append
        // PR3: appendPendingMessage(String, String) 重载已删除，新签名是 (String, PendingChatRequest)
        verify(rebuildService, never()).appendPendingMessage(anyString(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processChat: business 自愈时消费 pending list 中的 legacy 消息并重放")
    void processChatBusinessSessionMissingToolSessionId_consumesLegacyPendingMessages() throws Exception {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-fresh");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildSessionWithoutToolSession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);
        when(sessionService.findByIdSafe(101L)).thenReturn(session);
        when(contextInjectionService.resolvePrompt("direct", "hello", null)).thenReturn("hello");

        // PR3: pending list 里有两条 legacy + 一条与当前 prompt 重复（应被 skip）— 切到 consumePendingRequests
        List<PendingChatRequest> legacy = Arrays.asList(
                new PendingChatRequest("legacy-1", "old-assist", "old-sender", null, "old-id-1", null, null, null),
                new PendingChatRequest("legacy-2", "old-assist", "old-sender", null, "old-id-2", null, null, null),
                new PendingChatRequest("hello", "old-assist", "old-sender", null, "old-id-3", null, null, null));
        when(rebuildService.consumePendingRequests("101")).thenReturn(legacy);

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
        // PR3 + D17：重放时用当前请求的 sender/assistantAccount，不复用 legacy 的
        assertEquals("assist-001", p1.get("assistantAccount").asText(),
                "PR3+D17: legacy 重放用当前请求的 assistantAccount, 不复用 legacy entry");
        assertEquals("assist-001", p2.get("assistantAccount").asText());
        verify(rebuildService).consumePendingRequests("101");
        // 关键：重放过程中每次 dispatch 都不 append，避免 peer 误消费本请求刚写入的 prompt
        // PR3: appendPendingMessage(String, String) 重载已删除，新签名是 (String, PendingChatRequest)
        verify(rebuildService, never()).appendPendingMessage(anyString(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("processRebuild: business 锁被别人持有 → 视为并发合并，直接返回 ok，不 update")
    void processRebuildBusinessLockHeldByOther_returnsOkWithoutUpdate() {
        AssistantScopeStrategy businessStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-would-have-been");
        stubScopeStrategy(businessStrategy);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo("ak-001", "assist-001")).thenReturn(bizInfo);

        when(valueOperations.setIfAbsent(startsWith("skill:im-session:heal:"), anyString(), any(Duration.class)))
                .thenReturn(false);

        when(resolverService.resolveWithStatus("assist-001"))
                .thenReturn(new ResolveOutcome(ExistenceStatus.EXISTS, "ak-001", "owner-001"));
        SkillSession session = buildReadySession();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-001", "assist-001")).thenReturn(session);

        InboundResult result = service.processRebuild(
                "im", "direct", "dm-001", "assist-001", "user-001");

        assertTrue(result.success());
        verify(sessionService, never()).updateToolSessionId(any(), any());
        // PR3: 两个重载都不应被调（String + PendingChatRequest）
        verify(sessionManager, never()).requestToolSession(any(), any(String.class));
        verify(sessionManager, never()).requestToolSession(any(), any(PendingChatRequest.class));
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
                eq("ext"), eq("single"), eq("101"), eq("ak-x"), eq("assistant-x")))
                .thenReturn(session);

        // when
        service.handleAgentOffline(
                "ext", "single", "101", "ak-x", "assistant-x", MOCK_OFFLINE_MSG);

        // then: 走 emitter.emitToSession，msg 携带 ERROR 类型 + error 来自 provider
        ArgumentCaptor<StreamMessage> cap = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter).emitToSession(eq(session), eq("101"), isNull(), cap.capture());
        assertEquals(StreamMessage.Types.ERROR, cap.getValue().getType());
        assertEquals(MOCK_OFFLINE_MSG, cap.getValue().getError());
    }

    // ==================== helper ====================

    private void stubScopeStrategy(AssistantScopeStrategy strategy) {
        lenient().when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(strategy);
        when(scopeDispatcher.getStrategy(nullable(String.class), nullable(String.class), any(AssistantInfo.class)))
                .thenReturn(strategy);
    }

    private AssistantSessionIdentity identityMatching(AssistantSessionIdentity.RouteKind routeKind,
                                                      String ak,
                                                      String gatewayUserId,
                                                      String assistantAccount,
                                                      String lookupAk,
                                                      String lookupAssistantAccount) {
        return argThat(identity -> identity != null
                && identity.routeKind() == routeKind
                && Objects.equals(ak, identity.ak())
                && Objects.equals(gatewayUserId, identity.gatewayUserId())
                && Objects.equals(assistantAccount, identity.assistantAccount())
                && Objects.equals(lookupAk, identity.lookupAk())
                && Objects.equals(lookupAssistantAccount, identity.lookupAssistantAccount()));
    }

    private void stubDefaultAssistantRoute() {
        when(ruleService.lookup("helpdesk", "direct"))
                .thenReturn(Optional.of(new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square")));
        AssistantScopeStrategy defaultStrategy = mock(AssistantScopeStrategy.class);
        lenient().when(defaultStrategy.requiresOnlineCheck()).thenReturn(false);
        lenient().when(defaultStrategy.generateToolSessionId()).thenReturn("cloud-default");
        lenient().when(scopeDispatcher.getStrategy(eq("helpdesk"), eq("direct"), isNull()))
                .thenReturn(defaultStrategy);
    }

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

    private SkillSession buildDefaultAssistantSession() {
        SkillSession session = new SkillSession();
        session.setId(201L);
        session.setAk("AK_V");
        session.setUserId("sender-real");
        session.setAssistantAccount("ACC_V");
        session.setBusinessSessionDomain("helpdesk");
        session.setBusinessSessionType("direct");
        session.setToolSessionId("cloud-default");
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
