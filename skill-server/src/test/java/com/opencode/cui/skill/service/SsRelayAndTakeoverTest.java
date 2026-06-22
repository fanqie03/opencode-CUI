package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SS Relay pub/sub + dead-owner takeover unit tests (Task 2.6).
 *
 * Verifies the GatewayMessageRouter route() method handles:
 * - Local owner processing
 * - Remote owner relay via Redis pub/sub best-effort publish
 * - Dead owner takeover (heartbeat missing)
 * - Takeover conflict forwarding to winner
 * - Auto-claim when no owner exists
 */
@ExtendWith(MockitoExtension.class)
class SsRelayAndTakeoverTest {

    private static final String LOCAL_INSTANCE = "ss-local-1";
    private static final String REMOTE_INSTANCE = "ss-remote-2";
    private static final String SESSION_ID = "123456789";
    private static final String AK = "ak-test";
    private static final String USER_ID = "user-001";
    private static final int DEAD_THRESHOLD_SECONDS = 120;

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
                DEAD_THRESHOLD_SECONDS,
                true,
                25,
                java.time.Clock.systemUTC(),
                com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        router.initConfirmDedupCache();
    }

    /**
     * Build a minimal tool_done JsonNode with welinkSessionId.
     */
    private JsonNode buildToolDoneNode(String sessionId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("welinkSessionId", sessionId);
        return node;
    }

    // ==================== route() relay tests ====================

    @Test
    @DisplayName("route: owner is local instance -> should process locally")
    void route_ownerIsLocal_shouldProcessLocally() {
        // owner is this instance
        when(sessionRouteService.getOwnerInstance(SESSION_ID)).thenReturn(LOCAL_INSTANCE);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        // Should NOT relay to any remote instance
        verify(redisMessageBroker, never()).publishToSsRelayBestEffort(anyString(), anyString());
    }

    @Test
    @DisplayName("route: owner is remote instance -> should relay via pub/sub")
    void route_ownerIsRemote_shouldRelay() {
        when(sessionRouteService.getOwnerInstance(SESSION_ID)).thenReturn(REMOTE_INSTANCE);
        when(redisMessageBroker.publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString())).thenReturn(true);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        // Should relay to remote instance
        verify(redisMessageBroker).publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString());
        // Should NOT do local processing (no session resolving for tool_done)
        verify(sessionRouteService, never()).tryTakeover(anyString(), anyString(), anyString());
        verify(skillInstanceRegistry, never()).isInstanceAlive(REMOTE_INSTANCE);
    }

    @Test
    @DisplayName("route: owner dead (heartbeat missing) -> should takeover")
    void route_ownerDead_heartbeatMissing_shouldTakeover() {
        // Remote owner exists but relay publish fails
        when(sessionRouteService.getOwnerInstance(SESSION_ID)).thenReturn(REMOTE_INSTANCE);
        when(redisMessageBroker.publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString())).thenReturn(false);
        // Heartbeat is missing
        when(skillInstanceRegistry.isInstanceAlive(REMOTE_INSTANCE)).thenReturn(false);
        // Takeover succeeds
        when(sessionRouteService.tryTakeover(SESSION_ID, REMOTE_INSTANCE, LOCAL_INSTANCE)).thenReturn(true);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        verify(sessionRouteService).tryTakeover(SESSION_ID, REMOTE_INSTANCE, LOCAL_INSTANCE);
    }

    @Test
    @DisplayName("route: takeover succeeds -> should process locally")
    void route_takeoverSuccess_shouldProcessLocally() {
        when(sessionRouteService.getOwnerInstance(SESSION_ID)).thenReturn(REMOTE_INSTANCE);
        when(redisMessageBroker.publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString())).thenReturn(false);
        when(skillInstanceRegistry.isInstanceAlive(REMOTE_INSTANCE)).thenReturn(false);
        when(sessionRouteService.tryTakeover(SESSION_ID, REMOTE_INSTANCE, LOCAL_INSTANCE)).thenReturn(true);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        // Takeover succeeded → should process locally (tool_done marks completion)
        verify(sessionRouteService).tryTakeover(SESSION_ID, REMOTE_INSTANCE, LOCAL_INSTANCE);
        // Should NOT relay after takeover
        verify(redisMessageBroker).publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString()); // only the first relay attempt
    }

    @Test
    @DisplayName("route: takeover conflict -> should forward to winner")
    void route_takeoverConflict_shouldForwardToWinner() {
        String winnerInstance = "ss-winner-3";
        when(sessionRouteService.getOwnerInstance(SESSION_ID))
                .thenReturn(REMOTE_INSTANCE)    // first call: original owner
                .thenReturn(winnerInstance);     // second call: after takeover conflict
        when(redisMessageBroker.publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString())).thenReturn(false);
        when(skillInstanceRegistry.isInstanceAlive(REMOTE_INSTANCE)).thenReturn(false);
        // Takeover fails — someone else won
        when(sessionRouteService.tryTakeover(SESSION_ID, REMOTE_INSTANCE, LOCAL_INSTANCE)).thenReturn(false);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        // Should forward to the winner
        verify(redisMessageBroker).publishToSsRelayBestEffort(eq(winnerInstance), anyString());
    }

    @Test
    @DisplayName("route: best-effort relay accepted -> should not takeover even if subscriber count is unknown")
    void route_bestEffortRelayAccepted_shouldNotTakeover() {
        when(sessionRouteService.getOwnerInstance(SESSION_ID)).thenReturn(REMOTE_INSTANCE);
        when(redisMessageBroker.publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString())).thenReturn(true);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        verify(redisMessageBroker).publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString());
        verify(skillInstanceRegistry, never()).isInstanceAlive(REMOTE_INSTANCE);
        verify(sessionRouteService, never()).tryTakeover(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("route: relay publish fails but heartbeat alive -> should not takeover")
    void route_publishFailsButHeartbeatAlive_shouldNotTakeover() {
        when(sessionRouteService.getOwnerInstance(SESSION_ID)).thenReturn(REMOTE_INSTANCE);
        when(redisMessageBroker.publishToSsRelayBestEffort(eq(REMOTE_INSTANCE), anyString())).thenReturn(false);
        when(skillInstanceRegistry.isInstanceAlive(REMOTE_INSTANCE)).thenReturn(true);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        verify(sessionRouteService, never()).tryTakeover(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("route: no owner -> should auto-claim and process locally")
    void route_noOwner_shouldAutoClaimAndProcess() {
        // No owner exists
        when(sessionRouteService.getOwnerInstance(SESSION_ID)).thenReturn(null);
        // Auto-claim succeeds
        when(sessionRouteService.ensureRouteOwnership(SESSION_ID, AK, USER_ID)).thenReturn(true);

        JsonNode node = buildToolDoneNode(SESSION_ID);
        router.route("tool_done", AK, USER_ID, node);

        verify(sessionRouteService).ensureRouteOwnership(SESSION_ID, AK, USER_ID);
        // Should NOT relay
        verify(redisMessageBroker, never()).publishToSsRelayBestEffort(anyString(), anyString());
    }
}
