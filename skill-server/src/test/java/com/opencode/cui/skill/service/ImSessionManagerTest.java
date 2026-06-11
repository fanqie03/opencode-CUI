package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.AssistantSessionIdentity;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.PendingChatRequest;
import com.opencode.cui.skill.model.SkillSession;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ImSessionManager} PR3 单元测试：验证 {@code createSessionAsync} 个人助手分支
 * 是否构造完整 {@link PendingChatRequest} 调用 {@link GatewayRelayService#rebuildToolSession(String, SkillSession, PendingChatRequest)} 新签名。
 *
 * <p>覆盖 PRD §Acceptance Criteria 1-2 + PR3-F 测试清单。
 */
@ExtendWith(MockitoExtension.class)
class ImSessionManagerTest {

    @Mock
    private SkillSessionService sessionService;
    @Mock
    private GatewayRelayService gatewayRelayService;
    @Mock
    private SessionRebuildService rebuildService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private AssistantScopeStrategy personalStrategy;
    @Mock
    private AssistantScopeStrategy businessStrategy;
    @Mock
    private AllowedSlashCommandsResolver allowedSlashCommandsResolver;

    private ImSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 锁获取成功（lock 路径不在本测试关注范围内）
        lenient().when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);
        // findByBusinessSession 默认返 null，走"新建 session"路径
        lenient().when(sessionService.findByBusinessSession(any(), any(), any(), any(), any()))
                .thenReturn(null);
        // resolver 默认返 null（未配置，与生产兜底语义一致）
        lenient().when(allowedSlashCommandsResolver.resolve(anyString(), anyString())).thenReturn(null);
        lenient().when(scopeDispatcher.getStrategy(
                nullable(String.class), nullable(String.class), nullable(AssistantInfo.class)))
                .thenAnswer(invocation -> scopeDispatcher.getStrategy(
                        (AssistantInfo) invocation.getArgument(2)));

        sessionManager = new ImSessionManager(
                sessionService,
                gatewayRelayService,
                rebuildService,
                redisTemplate,
                new ObjectMapper(),
                assistantInfoService,
                scopeDispatcher,
                allowedSlashCommandsResolver,
                30);
    }

    /** 构造一个 createSession 返回的 SkillSession（fixed id 用于断言）。 */
    private SkillSession stubCreate(Long id, String ak) {
        SkillSession created = new SkillSession();
        created.setId(id);
        created.setAk(ak);
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        return created;
    }

    /** stub assistantInfoService + scopeDispatcher 为 personal 策略（generateToolSessionId=null）。 */
    private void stubPersonalScope(String ak) {
        AssistantInfo personalInfo = new AssistantInfo();
        personalInfo.setAssistantScope("personal");
        lenient().when(assistantInfoService.getAssistantInfo(eq(ak), nullable(String.class))).thenReturn(personalInfo);
        when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(personalStrategy);
        when(personalStrategy.generateToolSessionId()).thenReturn(null);
    }

    /** stub assistantInfoService + scopeDispatcher 为 business 策略（generateToolSessionId=cloud-xxx）。 */
    private void stubBusinessScope(String ak, String toolSessionId) {
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        lenient().when(assistantInfoService.getAssistantInfo(eq(ak), nullable(String.class))).thenReturn(bizInfo);
        when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(businessStrategy);
        when(businessStrategy.generateToolSessionId()).thenReturn(toolSessionId);
    }

    // ==================== personal 首次对话：PendingChatRequest 字段填充 ====================

    @Test
    @DisplayName("personal direct 首次对话 → requestToolSession 入队 PendingChatRequest, sender 用真实发送者, imGroupId=null")
    void personal_directFirstChat_appendsFullPendingChatRequest() {
        SkillSession created = stubCreate(1001L, "ak-personal");
        stubPersonalScope("ak-personal");

        sessionManager.createSessionAsync("im", "direct", "dm-001", "ak-personal",
                "owner-001", "assist-001", "user-real-1", "你好", null);

        verify(sessionService).createSession(eq("user-real-1"), eq("ak-personal"), anyString(),
                eq("im"), eq("direct"), eq("dm-001"), eq("assist-001"));
        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(gatewayRelayService).rebuildToolSession(eq("1001"), eq(created), captor.capture(), eq("owner-001"));
        PendingChatRequest req = captor.getValue();
        assertNotNull(req);
        assertEquals("你好", req.text());
        assertEquals("assist-001", req.assistantAccount());
        assertEquals("user-real-1", req.sendUserAccount(), "direct: sender 用真实发送者，不再 fallback owner");
        assertNull(req.imGroupId(), "direct: imGroupId 为 null");
        assertNotNull(req.messageId(), "messageId 应非空（System.currentTimeMillis）");
        assertNull(req.businessExtParam());
    }

    @Test
    @DisplayName("PR3: personal group 首次对话 → sender 用真实发送者, imGroupId == sessionId")
    void personal_groupFirstChat_appendsRealSenderAndImGroupId() {
        SkillSession created = stubCreate(1002L, "ak-personal");
        stubPersonalScope("ak-personal");

        sessionManager.createSessionAsync("im", "group", "grp-555", "ak-personal",
                "owner-002", "assist-002", "real-sender-99", "群消息", null);

        verify(sessionService).createSession(isNull(), eq("ak-personal"), anyString(),
                eq("im"), eq("group"), eq("grp-555"), eq("assist-002"));
        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(gatewayRelayService).rebuildToolSession(eq("1002"), eq(created), captor.capture(), eq("owner-002"));
        PendingChatRequest req = captor.getValue();
        assertNotNull(req);
        assertEquals("群消息", req.text());
        assertEquals("assist-002", req.assistantAccount());
        assertEquals("real-sender-99", req.sendUserAccount(), "group: sender 用真实发送者");
        assertEquals("grp-555", req.imGroupId(), "group: imGroupId == 业务 sessionId");
    }

    @Test
    @DisplayName("personal direct 首次对话 非 owner 真实 sender → 透传到 PendingChatRequest（不再覆盖为 owner）")
    void personal_directFirstChat_nonOwnerSenderPassThrough() {
        SkillSession created = stubCreate(1003L, "ak-personal");
        stubPersonalScope("ak-personal");

        sessionManager.createSessionAsync("im", "direct", "dm-003", "ak-personal",
                "owner-003", "assist-003", "user-non-owner", "单聊非 owner", null);

        verify(sessionService).createSession(eq("user-non-owner"), eq("ak-personal"), anyString(),
                eq("im"), eq("direct"), eq("dm-003"), eq("assist-003"));
        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(gatewayRelayService).rebuildToolSession(eq("1003"), eq(created), captor.capture(), eq("owner-003"));
        PendingChatRequest req = captor.getValue();
        assertEquals("user-non-owner", req.sendUserAccount(),
                "direct: 非 owner 的真实 senderUserAccount 必须直接透传");
        assertNull(req.imGroupId(), "direct: imGroupId 为 null");
    }

    @Test
    @DisplayName("PR3: personal 首次对话 businessExtParam 透传到 PendingChatRequest")
    void personal_firstChat_businessExtParamPassedThrough() throws Exception {
        SkillSession created = stubCreate(1004L, "ak-personal");
        stubPersonalScope("ak-personal");

        ObjectMapper om = new ObjectMapper();
        JsonNode ext = om.readTree("{\"topicId\":42}");
        sessionManager.createSessionAsync("im", "direct", "dm-004", "ak-personal",
                "owner-004", "assist-004", "user-004", "带 ext 的消息", ext);

        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(gatewayRelayService).rebuildToolSession(eq("1004"), eq(created), captor.capture(), eq("owner-004"));
        PendingChatRequest req = captor.getValue();
        assertNotNull(req.businessExtParam());
        assertEquals(42, req.businessExtParam().get("topicId").asInt());
    }

    @Test
    @DisplayName("PR3: personal pendingMessage blank → requestToolSession(session, null) 不入队")
    void personal_blankPendingMessage_passesNullPendingRequest() {
        SkillSession created = stubCreate(1005L, "ak-personal");
        stubPersonalScope("ak-personal");

        // 入参 prompt 为空白 — 不应构造 PendingChatRequest
        sessionManager.createSessionAsync("im", "direct", "dm-005", "ak-personal",
                "owner-005", "assist-005", "user-005", "  ", null);

        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(gatewayRelayService).rebuildToolSession(eq("1005"), eq(created), captor.capture(), eq("owner-005"));
        assertNull(captor.getValue(), "pendingMessage blank 时应传 null");
    }

    @Test
    @DisplayName("PR3: personal pendingMessage=null → requestToolSession(session, null) 不入队")
    void personal_nullPendingMessage_passesNullPendingRequest() {
        SkillSession created = stubCreate(1006L, "ak-personal");
        stubPersonalScope("ak-personal");

        sessionManager.createSessionAsync("im", "direct", "dm-006", "ak-personal",
                "owner-006", "assist-006", "user-006", null, null);

        ArgumentCaptor<PendingChatRequest> captor = ArgumentCaptor.forClass(PendingChatRequest.class);
        verify(gatewayRelayService).rebuildToolSession(eq("1006"), eq(created), captor.capture(), eq("owner-006"));
        assertNull(captor.getValue());
    }

    // ==================== business 首次对话回归 ====================

    @Test
    @DisplayName("PR3: business 首次对话直接 chat invoke，不走 rebuildToolSession")
    void business_firstChat_doesNotCallRebuildToolSession() {
        SkillSession created = stubCreate(2001L, "ak-biz");
        stubBusinessScope("ak-biz", "cloud-fresh-uuid");

        sessionManager.createSessionAsync("im", "direct", "dm-biz", "ak-biz",
                "owner-biz", "assist-biz", "sender-biz", "你好", null);

        // 关键：business 路径不应调任何 rebuildToolSession 重载
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(String.class));
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(PendingChatRequest.class));
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(PendingChatRequest.class), any());

        // toolSessionId 已被本地更新
        verify(sessionService).updateToolSessionId(eq(2001L), eq("cloud-fresh-uuid"));

        // chat invoke 已发送（payload 含完整字段）
        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        InvokeCommand cmd = cmdCaptor.getValue();
        assertEquals(GatewayActions.CHAT, cmd.action());
        assertEquals("owner-biz", cmd.userId());
        assertTrue(cmd.payload().contains("cloud-fresh-uuid"));
        assertTrue(cmd.payload().contains("assist-biz"));
        assertTrue(cmd.payload().contains("\"sendUserAccount\":\"sender-biz\""));
        assertTrue(cmd.payload().contains("\"imGroupId\":\"\""));
    }

    @Test
    @DisplayName("PR3: business 首次对话 pending list 不入队（pending 仅服务 personal rebuild）")
    void business_firstChat_doesNotAppendPending() {
        stubCreate(2002L, "ak-biz");
        stubBusinessScope("ak-biz", "cloud-uuid-2");

        sessionManager.createSessionAsync("im", "direct", "dm-biz", "ak-biz",
                "owner-biz", "assist-biz", "sender-biz", "你好", null);

        verify(rebuildService, never()).appendPendingMessage(anyString(), any(PendingChatRequest.class));
    }

    @Test
    @DisplayName("默认助手首次对话 → domain/type 命中策略，虚拟 AK 不依赖 AssistantInfo")
    void defaultAssistant_firstChat_usesDomainAwareScope() {
        SkillSession created = stubCreate(3001L, "AK_V");
        when(scopeDispatcher.getStrategy(eq("helpdesk"), eq("direct"), nullable(AssistantInfo.class)))
                .thenReturn(businessStrategy);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-default");

        sessionManager.createSessionAsync("helpdesk", "direct", "dm-default",
                AssistantSessionIdentity.defaultAssistant("AK_V", "sender-real", "ACC_V"),
                "sender-real", "你好", null);

        verify(sessionService).findByBusinessSession("helpdesk", "direct", "dm-default", null, "ACC_V");
        verify(assistantInfoService, never()).getAssistantInfo(any(), any());
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(String.class));
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(PendingChatRequest.class));
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(PendingChatRequest.class), any());
        verify(sessionService).updateToolSessionId(eq(3001L), eq("cloud-default"));

        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        InvokeCommand cmd = cmdCaptor.getValue();
        assertEquals("AK_V", cmd.ak());
        assertEquals("sender-real", cmd.userId());
        assertEquals("helpdesk", cmd.domain());
        assertEquals("direct", cmd.domainType());
        assertTrue(cmd.payload().contains("\"assistantAccount\":\"ACC_V\""));
        assertTrue(cmd.payload().contains("\"sendUserAccount\":\"sender-real\""));
        assertTrue(cmd.payload().contains("\"imGroupId\":\"\""));
    }

    @Test
    @DisplayName("远端 no-AK 已有 business session → 按 assistantAccount 命中并直接发送 CHAT，不走 legacy rebuild")
    void remoteNoAk_existingBusinessSession_sendsImmediateChatWithoutLegacyRebuild() {
        SkillSession existing = new SkillSession();
        existing.setId(4001L);
        existing.setAk(null);
        existing.setUserId(null);
        existing.setAssistantAccount("assist-remote");
        existing.setBusinessSessionDomain("im");
        existing.setBusinessSessionType("group");
        existing.setBusinessSessionId("group-001");

        when(sessionService.findByBusinessSession("im", "group", "group-001", null, "assist-remote"))
                .thenReturn(existing);
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        when(assistantInfoService.getAssistantInfo(nullable(String.class), eq("assist-remote")))
                .thenReturn(bizInfo);
        when(scopeDispatcher.getStrategy(any(AssistantInfo.class))).thenReturn(businessStrategy);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-remote");

        sessionManager.createSessionAsync("im", "group", "group-001",
                new AssistantSessionIdentity(null, null, "assist-remote",
                        AssistantSessionIdentity.RouteKind.REMOTE),
                "sender-001", "你好", null);

        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any());
        verify(sessionService).updateToolSessionId(4001L, "cloud-remote");
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(String.class));
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(PendingChatRequest.class));
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any(PendingChatRequest.class), any());

        ArgumentCaptor<InvokeCommand> cmdCaptor = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(cmdCaptor.capture());
        InvokeCommand cmd = cmdCaptor.getValue();
        assertNull(cmd.ak());
        assertNull(cmd.userId());
        assertEquals("4001", cmd.sessionId());
        assertEquals("assist-remote", cmd.assistantAccount());
        assertTrue(cmd.payload().contains("\"assistantAccount\":\"assist-remote\""));
        assertTrue(cmd.payload().contains("\"imGroupId\":\"group-001\""));
        assertTrue(cmd.payload().contains("\"toolSessionId\":\"cloud-remote\""));
    }
}
