package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GatewayMessageRouter.handleImPush 单元测试。
 *
 * <p>验证云端 IM 推送消息（im_push）的处理逻辑：
 * <ul>
 *   <li>单聊：userAccount 作为 imSessionId</li>
 *   <li>群聊：imGroupId 作为 imSessionId</li>
 *   <li>assistantAccount 与 session 不匹配时跳过</li>
 *   <li>content 为空时跳过</li>
 *   <li>assistantAccount 为空时跳过</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GatewayMessageRouterImPushTest {

    private static final String LOCAL_INSTANCE = "ss-test-local";
    private static final String SESSION_ID = "42";
    private static final String TOOL_SESSION_ID = "tool-session-001";
    private static final String ASSISTANT_ACCOUNT = "bot-001";
    private static final String USER_ACCOUNT = "user-welink-001";
    private static final String GROUP_ID = "group-001";
    private static final String CONTENT = "hello from cloud";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private OpenCodeEventTranslator translator;
    @Mock
    private MessagePersistenceService persistenceService;
    @Mock
    private StreamBufferService bufferService;
    @Mock
    private SessionRebuildService rebuildService;
    @Mock
    private ImInteractionStateService interactionStateService;
    @Mock
    private ImOutboundService imOutboundService;
    @Mock
    private SessionRouteService sessionRouteService;
    @Mock
    private SkillInstanceRegistry skillInstanceRegistry;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private ChannelLookupService channelLookupService;
    @Mock
    private ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher outboundDeliveryDispatcher;
    @Mock
    com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
    @Mock
    private MessageTurnLifecycle messageTurnLifecycle;

    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() {
        lenient().when(skillInstanceRegistry.getInstanceId()).thenReturn(LOCAL_INSTANCE);
        // 让路由总是本地处理
        lenient().when(sessionRouteService.getOwnerInstance(any())).thenReturn(LOCAL_INSTANCE);

        router = new GatewayMessageRouter(
                objectMapper,
                messageService,
                sessionService,
                redisMessageBroker,
                translator,
                persistenceService,
                bufferService,
                rebuildService,
                interactionStateService,
                imOutboundService,
                sessionRouteService,
                skillInstanceRegistry,
                assistantInfoService,
                channelLookupService,
                channelSuppressReplyWhitelistService,
                scopeDispatcher,
                outboundDeliveryDispatcher,
                emitter,
                null,
                mock(DefaultAssistantRuleService.class),
                messageTurnLifecycle,
                120,
                true,
                25,
                java.time.Clock.systemUTC(),
                com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        router.initConfirmDedupCache();
    }

    /**
     * 构建一个 im_push 消息节点。
     */
    private ObjectNode buildImPushNode(String assistantAccount, String userAccount,
            String imGroupId, String content, String welinkSessionId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "im_push");
        node.put("toolSessionId", TOOL_SESSION_ID);
        if (welinkSessionId != null) {
            node.put("welinkSessionId", welinkSessionId);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        if (assistantAccount != null) {
            payload.put("assistantAccount", assistantAccount);
        }
        if (userAccount != null) {
            payload.put("userAccount", userAccount);
        }
        if (imGroupId != null) {
            payload.put("imGroupId", imGroupId);
        }
        if (content != null) {
            payload.put("content", content);
        }
        node.set("payload", payload);
        return node;
    }

    @Test
    @DisplayName("单聊 im_push：以 userAccount 为 imSessionId 调用出站服务")
    void directImPushSendsToUserAccount() {
        // 通过 welinkSessionId 路由，避免 toolSessionId 查询
        ObjectNode node = buildImPushNode(ASSISTANT_ACCOUNT, USER_ACCOUNT, null, CONTENT, SESSION_ID);

        // session 存在，assistantAccount 匹配
        SkillSession session = SkillSession.builder()
                .id(42L)
                .assistantAccount(ASSISTANT_ACCOUNT)
                .businessSessionDomain(SkillSession.DOMAIN_IM)
                .businessSessionType(SkillSession.SESSION_TYPE_DIRECT)
                .build();
        when(sessionService.findByIdSafe(42L)).thenReturn(session);

        router.route("im_push", null, null, node);

        verify(imOutboundService).sendTextToIm(
                eq(SkillSession.SESSION_TYPE_DIRECT),
                eq(USER_ACCOUNT),
                eq(CONTENT),
                eq(ASSISTANT_ACCOUNT));
    }

    @Test
    @DisplayName("群聊 im_push：以 imGroupId 为 imSessionId 调用出站服务")
    void groupImPushSendsToGroupId() {
        ObjectNode node = buildImPushNode(ASSISTANT_ACCOUNT, USER_ACCOUNT, GROUP_ID, CONTENT, SESSION_ID);

        SkillSession session = SkillSession.builder()
                .id(42L)
                .assistantAccount(ASSISTANT_ACCOUNT)
                .businessSessionDomain(SkillSession.DOMAIN_IM)
                .businessSessionType(SkillSession.SESSION_TYPE_GROUP)
                .build();
        when(sessionService.findByIdSafe(42L)).thenReturn(session);

        router.route("im_push", null, null, node);

        verify(imOutboundService).sendTextToIm(
                eq(SkillSession.SESSION_TYPE_GROUP),
                eq(GROUP_ID),
                eq(CONTENT),
                eq(ASSISTANT_ACCOUNT));
    }

    @Test
    @DisplayName("assistantAccount 与 session 不匹配时跳过发送")
    void assistantAccountMismatchSkipsSend() {
        ObjectNode node = buildImPushNode("wrong-bot", USER_ACCOUNT, null, CONTENT, SESSION_ID);

        SkillSession session = SkillSession.builder()
                .id(42L)
                .assistantAccount(ASSISTANT_ACCOUNT) // 与 wrong-bot 不匹配
                .businessSessionDomain(SkillSession.DOMAIN_IM)
                .businessSessionType(SkillSession.SESSION_TYPE_DIRECT)
                .build();
        when(sessionService.findByIdSafe(42L)).thenReturn(session);

        router.route("im_push", null, null, node);

        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
    }

    @Test
    @DisplayName("content 为空时跳过发送")
    void blankContentSkipsSend() {
        ObjectNode node = buildImPushNode(ASSISTANT_ACCOUNT, USER_ACCOUNT, null, "   ", SESSION_ID);

        router.route("im_push", null, null, node);

        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
    }

    @Test
    @DisplayName("assistantAccount 为空时跳过发送")
    void blankAssistantAccountSkipsSend() {
        ObjectNode node = buildImPushNode(null, USER_ACCOUNT, null, CONTENT, SESSION_ID);

        router.route("im_push", null, null, node);

        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
    }

    @Test
    @DisplayName("session 不存在时仍直接发送（不验证 assistantAccount）")
    void sessionNotFoundStillSends() {
        ObjectNode node = buildImPushNode(ASSISTANT_ACCOUNT, USER_ACCOUNT, null, CONTENT, SESSION_ID);
        when(sessionService.findByIdSafe(42L)).thenReturn(null);

        router.route("im_push", null, null, node);

        verify(imOutboundService).sendTextToIm(
                eq(SkillSession.SESSION_TYPE_DIRECT),
                eq(USER_ACCOUNT),
                eq(CONTENT),
                eq(ASSISTANT_ACCOUNT));
    }
}
