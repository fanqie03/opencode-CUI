package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Ticker;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GatewayMessageRouter route_confirm 去重单元测试。
 *
 * <p>覆盖 PRD 04-24-skill-server-route-confirm-dedup 的 7 个测试用例：
 * <ol>
 *   <li>5min 内 100 条上行 → 仅 1 次 sendRouteConfirm</li>
 *   <li>TTL（25min）过期后再上行 → 重发 1 次</li>
 *   <li>toolSessionId remap 到新 welinkSessionId → 立即重发</li>
 *   <li>5min force-reconfirm（lease 到期）→ 触发 1 次 reconfirm</li>
 *   <li>sendRouteConfirm 返回 false → 不写 cache，下条上行重试</li>
 *   <li>DB 查询抛异常 → 不发 route_reject，仅 log</li>
 *   <li>enabled=false → 100 条上行 → 100 次 sendRouteConfirm</li>
 * </ol>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GatewayMessageRouterTest {

    private static final String LOCAL_INSTANCE = "ss-test-local";
    private static final String TOOL_SESSION_ID = "tool-session-001";
    private static final String WELINK_SESSION_ID = "42";
    private static final String WELINK_SESSION_ID_NEW = "99";
    private static final int CONFIRM_CACHE_EXPIRE_MINUTES = 25;

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
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private AssistantScopeStrategy scopeStrategy;
    @Mock
    private com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher outboundDeliveryDispatcher;
    @Mock
    com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
    @Mock
    GatewayMessageRouter.RouteResponseSender routeResponseSender;

    private MutableClock clock;
    private FakeTicker ticker;
    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() {
        lenient().when(skillInstanceRegistry.getInstanceId()).thenReturn(LOCAL_INSTANCE);
        // 让路由总是本地处理
        lenient().when(sessionRouteService.getOwnerInstance(any())).thenReturn(LOCAL_INSTANCE);
        // scope 默认放行
        lenient().when(scopeDispatcher.getStrategy(any())).thenReturn(scopeStrategy);
        lenient().when(scopeStrategy.translateEvent(any(), any()))
                .thenAnswer(inv -> translator.translate(inv.getArgument(0)));
        lenient().when(translator.translate(any())).thenReturn(StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .partId("part-1")
                .content("hi")
                .build());

        clock = new MutableClock(Instant.parse("2026-04-24T00:00:00Z"));
        ticker = new FakeTicker();
    }

    private GatewayMessageRouter buildRouter(boolean dedupEnabled) {
        GatewayMessageRouter r = new GatewayMessageRouter(
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
                scopeDispatcher,
                outboundDeliveryDispatcher,
                emitter,
                120,
                dedupEnabled,
                CONFIRM_CACHE_EXPIRE_MINUTES,
                clock,
                ticker);
        r.initConfirmDedupCache();
        r.setRouteResponseSender(routeResponseSender);
        return r;
    }

    /** 构造一条 tool_event：仅带 toolSessionId（强制走 DB/Redis 反查路径以触发 confirm）。 */
    private ObjectNode buildToolEventByToolSession(String toolSessionId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_event");
        node.put("toolSessionId", toolSessionId);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("data", "x");
        node.set("event", event);
        return node;
    }

    // ============= 用例 1：100 条上行 5min 内仅 1 次 confirm =============

    @Test
    @DisplayName("用例1: 同一 toolSessionId 5min 内 100 条上行仅触发 1 次 sendRouteConfirm")
    void case1_dedup100MessagesWithinLease() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        for (int i = 0; i < 100; i++) {
            router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        }

        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 2：TTL 过期（25min）后再上行 → 重发 1 次 =============

    @Test
    @DisplayName("用例2: cache TTL（25min）过期后下一条上行重发 1 次 confirm")
    void case2_reconfirmAfterCacheTtl() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 推进 ticker 25min + clock 25min（cache 失效 + lease 也 stale）
        ticker.advance(CONFIRM_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES);
        clock.advanceMinutes(CONFIRM_CACHE_EXPIRE_MINUTES);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 3：toolSessionId remap 到新 welinkSessionId → 立即重发 =============

    @Test
    @DisplayName("用例3: 同一 toolSessionId 解析到新 welinkSessionId（remap）立即重发 confirm")
    void case3_remapTriggersImmediateReconfirm() {
        router = buildRouter(true);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        // 第 1 条：解析到 WELINK_SESSION_ID
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 第 2 条：同一 toolSessionId 解析到新的 WELINK_SESSION_ID_NEW（remap）
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID_NEW);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID_NEW));
    }

    // ============= 用例 4：5min force-reconfirm（lease 到期）触发重发 =============

    @Test
    @DisplayName("用例4: 5min lease 到期后下一条上行强制 reconfirm 1 次")
    void case4_forceReconfirmAfterLease() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 推进 5min（恰好到 lease 边界）—— 注意：cache TTL 25min 还未到，cache 仍命中，但 lease 已 stale
        clock.advanceMinutes(5);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 紧接着再来 1 条（lease 已被刷新）→ 不再发
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 5：sendRouteConfirm 返回 false → 不写 cache，下条重试 =============

    @Test
    @DisplayName("用例5: sendRouteConfirm 返回 false（GW 未连接）不写 cache，下条上行再次尝试")
    void case5_sendFailureDoesNotPoisonCache() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);

        // 第 1 条 send 失败
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(false);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 第 2 条 send 仍失败 → 又一次尝试（说明 cache 未污染）
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(2)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        // 第 3 条 send 成功 → 第 4 条应被 dedup
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(3)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        verify(routeResponseSender, times(3)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 用例 6：DB 查询抛异常 → 不发 route_reject =============

    @Test
    @DisplayName("用例6: DB 查询抛异常时不发 route_reject，仅 log")
    void case6_exceptionDoesNotTriggerRouteReject() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(null);
        when(sessionService.findByToolSessionId(TOOL_SESSION_ID))
                .thenThrow(new RuntimeException("DB connection failure"));

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));

        verify(routeResponseSender, never()).sendRouteReject(any());
        verify(routeResponseSender, never()).sendRouteConfirm(any(), any());
    }

    // ============= 用例 7：enabled=false → 每条上行都发 confirm =============

    @Test
    @DisplayName("用例7: dedup 关闭时 100 条上行触发 100 次 sendRouteConfirm（与改动前行为一致）")
    void case7_dedupDisabledSendsEveryTime() {
        router = buildRouter(false);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        for (int i = 0; i < 100; i++) {
            router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        }

        verify(routeResponseSender, times(100)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= 边界：DB 命中（Redis miss）路径同样去重 =============

    @Test
    @DisplayName("边界: Redis miss + DB hit 路径也走去重逻辑")
    void edge_dbHitPathAlsoDedupes() {
        router = buildRouter(true);
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(null);
        SkillSession session = SkillSession.builder().id(42L).build();
        when(sessionService.findByToolSessionId(TOOL_SESSION_ID)).thenReturn(session);
        when(routeResponseSender.sendRouteConfirm(any(), any())).thenReturn(true);

        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));
        // 第 2 条改回 Redis 命中（同一 welinkSessionId）→ 应被 dedup
        when(redisMessageBroker.getToolSessionMapping(TOOL_SESSION_ID)).thenReturn(WELINK_SESSION_ID);
        router.route("tool_event", null, null, buildToolEventByToolSession(TOOL_SESSION_ID));

        verify(routeResponseSender, times(1)).sendRouteConfirm(eq(TOOL_SESSION_ID), eq(WELINK_SESSION_ID));
    }

    // ============= Helpers: MutableClock + FakeTicker =============

    /** 测试专用可推进 Clock。 */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void advanceMinutes(long minutes) {
            this.now = this.now.plusSeconds(minutes * 60L);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 测试专用 Caffeine Ticker，可推进虚拟纳秒时间。 */
    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong(0);

        void advance(long amount, TimeUnit unit) {
            nanos.addAndGet(unit.toNanos(amount));
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
