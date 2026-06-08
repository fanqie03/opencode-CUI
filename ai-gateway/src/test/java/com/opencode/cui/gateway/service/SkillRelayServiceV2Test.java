package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * SkillRelayService V2 tests: consistent hash routing, UpstreamRoutingTable, 3-tier invoke delivery.
 */
@ExtendWith(MockitoExtension.class)
class SkillRelayServiceV2Test {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private EventRelayService eventRelayService;
    @Mock
    private WebSocketSession ss1Session;
    @Mock
    private WebSocketSession ss1SessionB;
    @Mock
    private WebSocketSession ss2Session;
    @Mock
    private WebSocketSession bpSession;

    private SkillRelayService service;
    private UpstreamRoutingTable routingTable;
    private GatewayMessageIdentityService messageIdentityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INSTANCE_ID = "gw-local";
    private static final String SOURCE_TYPE_SKILL = "skill-server";
    private static final String SOURCE_TYPE_BOT = "bot-platform";

    @BeforeEach
    void setUp() {
        routingTable = new UpstreamRoutingTable(100000, 30);
        messageIdentityService = new GatewayMessageIdentityService();
        service = new SkillRelayService(redisMessageBroker, objectMapper, INSTANCE_ID, routingTable,
                messageIdentityService, List.of());
        service.setEventRelayService(eventRelayService);
    }

    /** Wait for AsyncSessionSender background thread to flush the send queue. */
    private static void awaitSend() throws InterruptedException {
        Thread.sleep(1000);
    }

    private static Map<String, Object> mutableAttrs(String source, String instanceId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SkillRelayService.SOURCE_ATTR, source);
        if (instanceId != null) {
            attrs.put(SkillRelayService.INSTANCE_ID_ATTR, instanceId);
        }
        return attrs;
    }

    private void registerSs1() {
        lenient().when(ss1Session.getId()).thenReturn("ss1-link");
        lenient().when(ss1Session.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_SKILL, "ss-1"));
        lenient().when(ss1Session.isOpen()).thenReturn(true);
        service.registerSourceSession(ss1Session);
    }

    private void registerSs2() {
        lenient().when(ss2Session.getId()).thenReturn("ss2-link");
        lenient().when(ss2Session.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_SKILL, "ss-2"));
        lenient().when(ss2Session.isOpen()).thenReturn(true);
        service.registerSourceSession(ss2Session);
    }

    private void registerBp() {
        lenient().when(bpSession.getId()).thenReturn("bp-link");
        lenient().when(bpSession.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_BOT, "bp-1"));
        lenient().when(bpSession.isOpen()).thenReturn(true);
        service.registerSourceSession(bpSession);
    }

    private void registerSs1B() {
        lenient().when(ss1SessionB.getId()).thenReturn("ss1-link-b");
        lenient().when(ss1SessionB.getAttributes()).thenReturn(mutableAttrs(SOURCE_TYPE_SKILL, "ss-1"));
        lenient().when(ss1SessionB.isOpen()).thenReturn(true);
        service.registerSourceSession(ss1SessionB);
    }

    // ==================== V2 upstream routing with known route ====================

    @Nested
    @DisplayName("V2 upstream routing with known route")
    class KnownRouteTests {

        @Test
        @DisplayName("relayToSkill with known route should hash to connection")
        void relayToSkill_withKnownRoute_shouldHashToConnection() throws Exception {
            registerSs1();
            registerSs2();

            // Teach routing table: toolSessionId "T1" → skill-server
            GatewayMessage invokeMsg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .toolSessionId("T1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();
            routingTable.learnRoute(invokeMsg, SOURCE_TYPE_SKILL);

            // Now relay upstream — should hash-select one of the skill-server connections
            GatewayMessage upstreamMsg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .build();

            boolean result = service.relayToSkill(upstreamMsg);

            assertTrue(result);
            awaitSend();
            // At least one session should receive the message (hash-selected)
            int sendCount = 0;
            try {
                verify(ss1Session).sendMessage(any(TextMessage.class));
                sendCount++;
            } catch (AssertionError ignored) {
            }
            try {
                verify(ss2Session).sendMessage(any(TextMessage.class));
                sendCount++;
            } catch (AssertionError ignored) {
            }
            assertTrue(sendCount >= 1, "At least one session should receive the message");
        }

        @Test
        @DisplayName("relayToSkill resolves payload.toolSessionId from routing table")
        void relayToSkill_payloadToolSessionRoute_shouldHashToSkillOnly() throws Exception {
            registerSs1();
            registerBp();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("toolSessionId", "T-payload");

            routingTable.learnRoute(GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .source(SOURCE_TYPE_SKILL)
                    .payload(payload)
                    .build(), SOURCE_TYPE_SKILL);

            GatewayMessage upstreamMsg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .payload(payload)
                    .build();

            boolean result = service.relayToSkill(upstreamMsg);

            assertTrue(result);
            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(bpSession, never()).sendMessage(any(TextMessage.class));
            verify(redisMessageBroker, never()).discoverAllSourceGwInstances();
        }
    }

    // ==================== V2 upstream routing with unknown route ====================

    @Nested
    @DisplayName("V2 upstream routing with unknown route")
    class UnknownRouteTests {

        @Test
        @DisplayName("relayToSkill with unknown route defaults to one local skill-server")
        void relayToSkill_withUnknownRoute_shouldDefaultToOneLocalSkillServer() throws Exception {
            registerSs1();
            registerBp();

            // No routing table entry defaults to skill-server only.
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T-unknown")
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(bpSession, never()).sendMessage(any(TextMessage.class));
            verify(redisMessageBroker, never()).discoverAllSourceGwInstances();
        }

        @Test
        @DisplayName("relayToSkill with message.source routes to that source type only")
        void relayToSkill_withMessageSource_routesToSourceType() throws Exception {
            registerSs1();
            registerBp();

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T-unknown")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(bpSession, never()).sendMessage(any(TextMessage.class));
        }
    }

    // ==================== handleInvokeFromSkill route learning ====================

    @Nested
    @DisplayName("handleInvokeFromSkill V2 route learning")
    class InvokeRouteLearningTests {

        @Test
        @DisplayName("handleInvokeFromSkill should learn route in UpstreamRoutingTable")
        void handleInvokeFromSkill_shouldLearnRoute() throws Exception {
            registerSs1();

            when(redisMessageBroker.getAgentUser("ak1")).thenReturn("user1");
            when(eventRelayService.sendToLocalAgentIfPresent(eq("ak1"), any())).thenReturn(true);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .userId("user1")
                    .source(SOURCE_TYPE_SKILL)
                    .welinkSessionId("W1")
                    .build();

            service.handleInvokeFromSkill(ss1Session, msg);

            // Verify route was learned
            String resolvedType = routingTable.resolveSourceType(
                    GatewayMessage.builder().welinkSessionId("W1").build());
            assertEquals(SOURCE_TYPE_SKILL, resolvedType);
        }
    }

    // ==================== handleInvokeFromSkill local delivery ====================

    @Nested
    @DisplayName("handleInvokeFromSkill local delivery")
    class InvokeLocalDeliveryTests {

        @Test
        @DisplayName("handleInvokeFromSkill should deliver locally when agent is on this GW")
        void handleInvokeFromSkill_agentLocal_shouldDeliverLocally() throws Exception {
            registerSs1();

            when(redisMessageBroker.getAgentUser("ak1")).thenReturn("user1");
            when(eventRelayService.sendToLocalAgentIfPresent(eq("ak1"), any())).thenReturn(true);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .userId("user1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.handleInvokeFromSkill(ss1Session, msg);

            verify(eventRelayService).sendToLocalAgentIfPresent(eq("ak1"), any());
            // Should NOT relay to remote or enqueue pending
            verify(redisMessageBroker, never()).getInternalAgentInstance(anyString());
            verify(redisMessageBroker, never()).enqueuePending(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("handleInvokeFromSkill should deliver locally when userId is blank")
        void handleInvokeFromSkill_missingUserId_shouldDeliverLocally() throws Exception {
            registerSs1();

            when(eventRelayService.sendToLocalAgentIfPresent(eq("ak1"), any())).thenReturn(true);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.handleInvokeFromSkill(ss1Session, msg);

            verify(eventRelayService).sendToLocalAgentIfPresent(eq("ak1"), any());
            verify(redisMessageBroker, never()).getAgentUser(anyString());
            verify(redisMessageBroker, never()).getInternalAgentInstance(anyString());
            verify(redisMessageBroker, never()).enqueuePending(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("handleInvokeFromSkill should deliver locally when userId does not match AK owner")
        void handleInvokeFromSkill_mismatchedUserId_shouldDeliverLocally() throws Exception {
            registerSs1();

            when(redisMessageBroker.getAgentUser("ak1")).thenReturn("owner1");
            when(eventRelayService.sendToLocalAgentIfPresent(eq("ak1"), any())).thenReturn(true);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .userId("sender1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.handleInvokeFromSkill(ss1Session, msg);

            verify(redisMessageBroker).getAgentUser("ak1");
            verify(eventRelayService).sendToLocalAgentIfPresent(eq("ak1"), any());
            verify(redisMessageBroker, never()).getInternalAgentInstance(anyString());
            verify(redisMessageBroker, never()).enqueuePending(anyString(), anyString(), any(Duration.class));
        }
    }

    // ==================== handleInvokeFromSkill remote relay ====================

    @Nested
    @DisplayName("handleInvokeFromSkill remote relay")
    class InvokeRemoteRelayTests {

        @Test
        @DisplayName("handleInvokeFromSkill should relay via pub/sub when agent is on remote GW")
        void handleInvokeFromSkill_agentRemote_shouldRelayViaPubSub() throws Exception {
            registerSs1();

            when(redisMessageBroker.getAgentUser("ak1")).thenReturn("user1");
            when(eventRelayService.sendToLocalAgentIfPresent(eq("ak1"), any())).thenReturn(false);
            when(redisMessageBroker.getInternalAgentInstance("ak1")).thenReturn("gw-remote");

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .userId("user1")
                    .source(SOURCE_TYPE_SKILL)
                    .welinkSessionId("W1")
                    .toolSessionId("T1")
                    .build();

            service.handleInvokeFromSkill(ss1Session, msg);

            // Should relay to remote GW
            verify(redisMessageBroker).publishToGwRelay(eq("gw-remote"), anyString());
            // Should NOT enqueue pending
            verify(redisMessageBroker, never()).enqueuePending(anyString(), anyString(), any(Duration.class));
        }
    }

    // ==================== handleInvokeFromSkill pending enqueue ====================

    @Nested
    @DisplayName("handleInvokeFromSkill pending enqueue")
    class InvokePendingTests {

        @Test
        @DisplayName("handleInvokeFromSkill should enqueue to pending when agent is offline")
        void handleInvokeFromSkill_agentOffline_shouldEnqueuePending() throws Exception {
            registerSs1();

            when(redisMessageBroker.getAgentUser("ak1")).thenReturn("user1");
            when(eventRelayService.sendToLocalAgentIfPresent(eq("ak1"), any())).thenReturn(false);
            when(redisMessageBroker.getInternalAgentInstance("ak1")).thenReturn(null);

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak1")
                    .userId("user1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.handleInvokeFromSkill(ss1Session, msg);

            // Should enqueue to pending
            verify(redisMessageBroker).enqueuePending(eq("ak1"), anyString(), any(Duration.class));
        }
    }

    // ==================== Hash ring management ====================

    @Nested
    @DisplayName("Hash ring management")
    class HashRingTests {

        @Test
        @DisplayName("registerSourceSession should update hash ring")
        void registerSourceSession_shouldUpdateHashRing() {
            registerSs1();

            ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
            assertNotNull(ring);
            assertEquals(1, ring.size());
        }

        @Test
        @DisplayName("removeSourceSession should update hash ring")
        void removeSourceSession_shouldUpdateHashRing() {
            registerSs1();
            registerSs2();

            ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
            assertNotNull(ring);
            assertEquals(2, ring.size());

            // Remove ss1
            service.removeSourceSession(ss1Session);

            ring = service.getHashRing(SOURCE_TYPE_SKILL);
            assertNotNull(ring);
            assertEquals(1, ring.size());
        }

        @Test
        @DisplayName("removeSourceSession should remove hash ring when last node is removed")
        void removeSourceSession_lastNode_shouldRemoveHashRing() {
            registerSs1();

            service.removeSourceSession(ss1Session);

            ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
            assertNull(ring, "Hash ring should be removed when empty");
        }

        @Test
        @DisplayName("different source types have separate hash rings")
        void registerMultipleSourceTypes_separateHashRings() {
            registerSs1();
            registerBp();

            ConsistentHashRing<WebSocketSession> skillRing = service.getHashRing(SOURCE_TYPE_SKILL);
            ConsistentHashRing<WebSocketSession> botRing = service.getHashRing(SOURCE_TYPE_BOT);

            assertNotNull(skillRing);
            assertNotNull(botRing);
            assertEquals(1, skillRing.size());
            assertEquals(1, botRing.size());
        }
    }

    // ==================== route_confirm / route_reject ====================

    @Nested
    @DisplayName("route_confirm and route_reject")
    class RouteConfirmRejectTests {

        @Test
        @DisplayName("handleRouteConfirm is compatibility-only and does not learn routes")
        void handleRouteConfirm_shouldNotLearnRoute() {
            GatewayMessage confirm = GatewayMessage.builder()
                    .type("route_confirm")
                    .toolSessionId("T1")
                    .welinkSessionId("W1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            service.handleRouteConfirm(confirm);

            String resolvedType = routingTable.resolveSourceType(
                    GatewayMessage.builder().toolSessionId("T1").build());
            assertNull(resolvedType);
        }

        @Test
        @DisplayName("handleRouteReject should not throw")
        void handleRouteReject_shouldNotThrow() {
            GatewayMessage reject = GatewayMessage.builder()
                    .type("route_reject")
                    .toolSessionId("T1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            // Should not throw
            service.handleRouteReject(reject);
        }
    }

    // ==================== Heartbeat refresh ====================

    @Nested
    @DisplayName("Heartbeat refresh with connection-level granularity")
    class HeartbeatTests {

        @Test
        @DisplayName("refreshSourceConnectionHeartbeats should call Redis with sessionId for each open session")
        void heartbeat_callsRedisWithSessionId() {
            registerSs1();
            registerSs1B();

            service.refreshSourceConnectionHeartbeats();

            verify(redisMessageBroker).refreshSourceConnectionHeartbeat(
                    eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
            verify(redisMessageBroker).refreshSourceConnectionHeartbeat(
                    eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link-b"));
        }

        @Test
        @DisplayName("refreshSourceConnectionHeartbeats should skip and clean closed sessions")
        void heartbeat_skipsClosedSessions() {
            registerSs1();
            registerSs1B();

            // Close ss1SessionB
            when(ss1SessionB.isOpen()).thenReturn(false);

            service.refreshSourceConnectionHeartbeats();

            // Only ss1Session should be refreshed
            verify(redisMessageBroker).refreshSourceConnectionHeartbeat(
                    eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
            verify(redisMessageBroker, never()).refreshSourceConnectionHeartbeat(
                    eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link-b"));

            // Stale session should be unregistered
            verify(redisMessageBroker).unregisterSourceConnection(
                    eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link-b"));

            // Hash ring should only have 1 node left
            ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
            assertNotNull(ring);
            assertEquals(1, ring.size());
        }
    }

    // ==================== L2 source stream routing ====================

    @Nested
    @DisplayName("L2 source stream routing")
    class L2RoutingTests {

        @Test
        @DisplayName("no local skill-server enqueues one target-GW mailbox work item")
        void l2Routing_noLocalSkillServerEnqueuesMailboxWorkItem() throws Exception {
            when(redisMessageBroker.discoverSourceGwInstances(SOURCE_TYPE_SKILL))
                    .thenReturn(Set.of("gw-remote-1"));
            when(redisMessageBroker.enqueueSourceL2Work(
                    eq(SOURCE_TYPE_SKILL), eq("gw-remote-1"), anyString(), eq("trace-1"), anyString(),
                    eq(GatewayMessage.Type.TOOL_EVENT), anyLong()))
                    .thenReturn("1-0");
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("T1")
                    .traceId("trace-1")
                    .source(SOURCE_TYPE_SKILL)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            verify(redisMessageBroker).enqueueSourceL2Work(
                    eq(SOURCE_TYPE_SKILL), eq("gw-remote-1"), anyString(), eq("trace-1"), anyString(),
                    eq(GatewayMessage.Type.TOOL_EVENT), anyLong());
            verify(redisMessageBroker, never()).getSessionRoute(anyString());
            verify(redisMessageBroker, never()).publishToSourceRelay(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("L2 enqueue uses messageId before payload.toolSessionId")
        void l2Routing_messageIdEnqueuedAsRoutingKey() throws Exception {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("toolSessionId", "T-payload-l2");
            when(redisMessageBroker.discoverSourceGwInstances(SOURCE_TYPE_SKILL))
                    .thenReturn(Set.of("gw-remote-1"));
            when(redisMessageBroker.enqueueSourceL2Work(
                    eq(SOURCE_TYPE_SKILL), eq("gw-remote-1"), anyString(), eq("msg-1"), anyString(),
                    eq(GatewayMessage.Type.TOOL_EVENT), anyLong()))
                    .thenReturn("1-1");
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .source(SOURCE_TYPE_SKILL)
                    .messageId("msg-1")
                    .payload(payload)
                    .build();

            boolean result = service.relayToSkill(msg);

            assertTrue(result);
            verify(redisMessageBroker).enqueueSourceL2Work(
                    eq(SOURCE_TYPE_SKILL), eq("gw-remote-1"), anyString(), eq("msg-1"), anyString(),
                    eq(GatewayMessage.Type.TOOL_EVENT), anyLong());
            verify(redisMessageBroker, never()).getSessionRoute(anyString());
            verify(redisMessageBroker, never()).publishToSourceRelay(anyString(), anyString(), anyString(), anyString());
            verify(redisMessageBroker, never()).discoverAllSourceGwInstances();
        }

        @Test
        @DisplayName("consumer delivers one L2 work item to local skill-server and acks")
        void l2Consumer_deliversToLocalSkillServerAndAcks() throws Exception {
            registerSs1();
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_DONE)
                    .toolSessionId("T1")
                    .build();
            RedisMessageBroker.SourceL2Work work = new RedisMessageBroker.SourceL2Work("1-0", Map.of(
                    "payload", objectMapper.writeValueAsString(msg),
                    "routingKey", "T1",
                    "messageType", GatewayMessage.Type.TOOL_DONE,
                    "attempt", "0"));
            when(redisMessageBroker.readSourceL2Work(
                    eq(SOURCE_TYPE_SKILL), eq(INSTANCE_ID), eq(INSTANCE_ID), anyInt(), any(Duration.class)))
                    .thenReturn(List.of(work));

            service.consumeSkillServerL2Work();

            awaitSend();
            verify(ss1Session).sendMessage(any(TextMessage.class));
            verify(redisMessageBroker).ackSourceL2Work(SOURCE_TYPE_SKILL, INSTANCE_ID, "1-0");
        }

        @Test
        @DisplayName("consumer skips Redis read when no local skill-server is connected")
        void l2Consumer_noLocalSkillServer_skipsRead() {
            service.consumeSkillServerL2Work();

            verify(redisMessageBroker, never()).readSourceL2Work(
                    anyString(), anyString(), anyString(), anyInt(), any(Duration.class));
        }

        @Test
        @DisplayName("cleanup uses active source GW registry for target mailbox lifecycle")
        void cleanupSourceL2MailboxesUsesActiveSourceGwRegistry() {
            Set<String> activeGwIds = Set.of(INSTANCE_ID, "gw-remote");
            when(redisMessageBroker.discoverSourceGwInstances(SOURCE_TYPE_SKILL)).thenReturn(activeGwIds);
            when(redisMessageBroker.cleanupOrphanSourceL2Mailboxes(
                    eq(SOURCE_TYPE_SKILL), eq(activeGwIds), eq(Duration.ofSeconds(600))))
                    .thenReturn(new RedisMessageBroker.SourceL2MailboxCleanupResult(2, 1, 1, 0, 0));

            service.cleanupSourceL2Mailboxes();

            verify(redisMessageBroker).cleanupOrphanSourceL2Mailboxes(
                    SOURCE_TYPE_SKILL, activeGwIds, Duration.ofSeconds(600));
        }
    }

    // ==================== Connection-level (multi-session per instance) ====================

    @Nested
    @DisplayName("Connection-level tests (multiple sessions per SS instance)")
    class ConnectionLevelTests {

        @Test
        @DisplayName("Two sessions from same instanceId should produce hash ring size = 2")
        void twoSessionsSameInstance_hashRingSizeTwo() {
            registerSs1();
            registerSs1B();

            ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
            assertNotNull(ring);
            assertEquals(2, ring.size());
        }

        @Test
        @DisplayName("Remove one session should leave hash ring size = 1")
        void removeOneSession_hashRingSizeOne() {
            registerSs1();
            registerSs1B();

            service.removeSourceSession(ss1Session);

            ConsistentHashRing<WebSocketSession> ring = service.getHashRing(SOURCE_TYPE_SKILL);
            assertNotNull(ring);
            assertEquals(1, ring.size());
        }

        @Test
        @DisplayName("Register calls Redis with sessionId (4-param)")
        void register_callsRedisWithSessionId() {
            registerSs1();

            verify(redisMessageBroker).registerSourceConnection(
                    eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
        }

        @Test
        @DisplayName("Remove calls Redis unregister with sessionId (4-param)")
        void remove_callsRedisUnregisterWithSessionId() {
            registerSs1();
            service.removeSourceSession(ss1Session);

            verify(redisMessageBroker).unregisterSourceConnection(
                    eq(SOURCE_TYPE_SKILL), eq("ss-1"), eq(INSTANCE_ID), eq("ss1-link"));
        }

        @Test
        @DisplayName("getActiveSourceConnectionCount counts all connections")
        void getActiveSourceConnectionCount_countsAll() {
            registerSs1();
            registerSs1B();
            registerSs2();

            assertEquals(3, service.getActiveSourceConnectionCount());
        }

        @Test
        @DisplayName("same messageId uses the same local source link")
        void sameMessageIdUsesSameLocalLink() throws Exception {
            registerSs1();
            registerSs2();

            GatewayMessage first = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .messageId("msg-affinity")
                    .toolSessionId("tool-a")
                    .build();
            GatewayMessage second = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .messageId("msg-affinity")
                    .toolSessionId("tool-b")
                    .build();

            assertTrue(service.relayToSkill(first));
            assertTrue(service.relayToSkill(second));

            awaitSend();
            try {
                verify(ss1Session, times(2)).sendMessage(any(TextMessage.class));
                verify(ss2Session, never()).sendMessage(any(TextMessage.class));
            } catch (AssertionError notSs1) {
                verify(ss2Session, times(2)).sendMessage(any(TextMessage.class));
                verify(ss1Session, never()).sendMessage(any(TextMessage.class));
            }
        }

        @Test
        @DisplayName("findLocalSourceConnection returns an open session")
        void findLocalSourceConnection_returnsOpenSession() {
            registerSs1();
            registerSs1B();

            WebSocketSession found = service.findLocalSourceConnection(SOURCE_TYPE_SKILL, "ss-1");
            assertNotNull(found);
            assertTrue(found.isOpen());
        }
    }
}
