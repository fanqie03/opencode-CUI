package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.service.scope.BusinessScopeStrategy;
import com.opencode.cui.skill.service.scope.PersonalScopeStrategy;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM 出站过滤测试（S64-S71）。
 *
 * <p>验证 business 助手 IM 场景中，只有 text.done 等核心事件被发送，
 * planning/thinking/searching/search_result/reference/ask_more 等事件被过滤。
 * personal 助手 IM 场景不做过滤。</p>
 *
 * <p>测试策略：通过 GatewayMessageRouter.route() 发送 tool_event 消息，
 * 验证 imOutboundService.sendTextToIm() 是否被调用。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImOutboundFilterTest")
class ImOutboundFilterTest {

    private static final String LOCAL_INSTANCE = "ss-test-local";
    private static final String SESSION_ID = "100";
    private static final String AK = "ak-biz-001";
    private static final String USER_ID = "user-001";
    private static final String ASSISTANT_ACCOUNT = "bot-biz-001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private SkillMessageService messageService;
    @Mock private SkillSessionService sessionService;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private OpenCodeEventTranslator translator;
    @Mock private MessagePersistenceService persistenceService;
    @Mock private StreamBufferService bufferService;
    @Mock private SessionRebuildService rebuildService;
    @Mock private ImInteractionStateService interactionStateService;
    @Mock private ImOutboundService imOutboundService;
    @Mock private SessionRouteService sessionRouteService;
    @Mock private SkillInstanceRegistry skillInstanceRegistry;
    @Mock private AssistantInfoService assistantInfoService;
    @Mock private ChannelLookupService channelLookupService;
    @Mock private ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService;
    @Mock private AssistantScopeDispatcher scopeDispatcher;
    @Mock private AssistantScopeStrategy businessScopeStrategy;
    @Mock private AssistantScopeStrategy personalScopeStrategy;
    @Mock private com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher outboundDeliveryDispatcher;
    @Mock com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
    @Mock private MessageTurnLifecycle messageTurnLifecycle;

    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() {
        lenient().when(skillInstanceRegistry.getInstanceId()).thenReturn(LOCAL_INSTANCE);
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
     * 构建 IM 会话的 SkillSession（非 miniapp）。
     */
    private SkillSession buildImSession() {
        return SkillSession.builder()
                .id(100L)
                .ak(AK)
                .assistantAccount(ASSISTANT_ACCOUNT)
                .businessSessionDomain(SkillSession.DOMAIN_IM)
                .businessSessionType(SkillSession.SESSION_TYPE_DIRECT)
                .businessSessionId("im-session-001")
                .build();
    }

    /**
     * 构建 tool_event 节点，其中 event 包含指定 type 的事件。
     */
    private ObjectNode buildToolEventNode(String eventType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_event");
        node.put("welinkSessionId", SESSION_ID);
        node.put("ak", AK);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", eventType);
        // text.done 需要 content 字段以便 buildImText 生成非空文本
        if (StreamMessage.Types.TEXT_DONE.equals(eventType)) {
            event.put("content", "response text");
            event.put("role", "assistant");
        }
        node.set("event", event);
        return node;
    }

    /**
     * 配置 business scope 的公共 mock。
     */
    private void setupBusinessScope(String eventType, StreamMessage translatedMsg) {
        AssistantInfo bizInfo = new AssistantInfo();
        bizInfo.setAssistantScope("business");
        lenient().when(assistantInfoService.getAssistantInfo(AK)).thenReturn(bizInfo);
        when(assistantInfoService.getAssistantInfo(AK, ASSISTANT_ACCOUNT)).thenReturn(bizInfo);
        // getCachedScope 仍被 routeAssistantMessage IM 过滤路径使用
        lenient().when(assistantInfoService.getCachedScope(AK)).thenReturn("business");
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(businessScopeStrategy);
        when(scopeDispatcher.getStrategy(any(), any(), nullable(AssistantInfo.class))).thenReturn(businessScopeStrategy);
        lenient().when(businessScopeStrategy.translateEvent(any(), eq(SESSION_ID))).thenReturn(translatedMsg);
        lenient().when(sessionService.findByIdSafe(100L)).thenReturn(buildImSession());
    }

    /**
     * 配置 personal scope 的公共 mock。
     */
    private void setupPersonalScope(String eventType, StreamMessage translatedMsg) {
        AssistantInfo personalInfo = new AssistantInfo();
        personalInfo.setAssistantScope("personal");
        lenient().when(assistantInfoService.getAssistantInfo(AK)).thenReturn(personalInfo);
        when(assistantInfoService.getAssistantInfo(AK, ASSISTANT_ACCOUNT)).thenReturn(personalInfo);
        lenient().when(assistantInfoService.getCachedScope(AK)).thenReturn("personal");
        lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(personalScopeStrategy);
        when(scopeDispatcher.getStrategy(any(), any(), nullable(AssistantInfo.class))).thenReturn(personalScopeStrategy);
        lenient().when(personalScopeStrategy.translateEvent(any(), eq(SESSION_ID))).thenReturn(translatedMsg);
        lenient().when(sessionService.findByIdSafe(100L)).thenReturn(buildImSession());
    }

    // ==================== S64: business IM + text.done -> not filtered ====================

    @Test
    @DisplayName("S64: business IM + text.done is NOT filtered (sent to IM)")
    void businessIm_textDone_notFiltered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .content("response text")
                .role("assistant")
                .build();
        setupBusinessScope(StreamMessage.Types.TEXT_DONE, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.TEXT_DONE));

        verify(emitter).emitToSession(any(SkillSession.class), eq(SESSION_ID), eq(USER_ID), any(StreamMessage.class));
    }

    // ==================== S65: business IM + planning.delta -> filtered ====================

    @Test
    @DisplayName("S65: business IM + planning.delta is filtered (NOT sent to IM)")
    void businessIm_planningDelta_filtered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.PLANNING_DELTA)
                .content("plan step 1")
                .build();
        setupBusinessScope(StreamMessage.Types.PLANNING_DELTA, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.PLANNING_DELTA));

        verify(emitter, never()).emitToSession(any(), any(), any(), any());
    }

    // ==================== S66: business IM + thinking.delta -> filtered ====================

    @Test
    @DisplayName("S66: business IM + thinking.delta is filtered (NOT sent to IM)")
    void businessIm_thinkingDelta_filtered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.THINKING_DELTA)
                .content("thinking...")
                .build();
        setupBusinessScope(StreamMessage.Types.THINKING_DELTA, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.THINKING_DELTA));

        verify(emitter, never()).emitToSession(any(), any(), any(), any());
    }

    // ==================== S67: business IM + searching -> filtered ====================

    @Test
    @DisplayName("S67: business IM + searching is filtered (NOT sent to IM)")
    void businessIm_searching_filtered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SEARCHING)
                .build();
        setupBusinessScope(StreamMessage.Types.SEARCHING, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.SEARCHING));

        verify(emitter, never()).emitToSession(any(), any(), any(), any());
    }

    // ==================== S68: business IM + search_result -> filtered ====================

    @Test
    @DisplayName("S68: business IM + search_result is filtered (NOT sent to IM)")
    void businessIm_searchResult_filtered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SEARCH_RESULT)
                .build();
        setupBusinessScope(StreamMessage.Types.SEARCH_RESULT, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.SEARCH_RESULT));

        verify(emitter, never()).emitToSession(any(), any(), any(), any());
    }

    // ==================== S69: business IM + reference -> filtered ====================

    @Test
    @DisplayName("S69: business IM + reference is filtered (NOT sent to IM)")
    void businessIm_reference_filtered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.REFERENCE)
                .build();
        setupBusinessScope(StreamMessage.Types.REFERENCE, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.REFERENCE));

        verify(emitter, never()).emitToSession(any(), any(), any(), any());
    }

    // ==================== S70: business IM + ask_more -> filtered ====================

    @Test
    @DisplayName("S70: business IM + ask_more is filtered (NOT sent to IM)")
    void businessIm_askMore_filtered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.ASK_MORE)
                .build();
        setupBusinessScope(StreamMessage.Types.ASK_MORE, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.ASK_MORE));

        verify(emitter, never()).emitToSession(any(), any(), any(), any());
    }

    // ==================== S71: personal IM + text.done -> not filtered (regression) ====================

    @Test
    @DisplayName("S71: personal IM + text.done is NOT filtered (regression)")
    void personalIm_textDone_notFiltered() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .content("personal response")
                .role("assistant")
                .build();
        setupPersonalScope(StreamMessage.Types.TEXT_DONE, msg);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(StreamMessage.Types.TEXT_DONE));

        verify(emitter).emitToSession(any(SkillSession.class), eq(SESSION_ID), eq(USER_ID), any(StreamMessage.class));
    }
}
