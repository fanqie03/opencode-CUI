package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import com.opencode.cui.skill.telemetry.metrics.MessageTurnLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** GatewayRelayService 单元测试：验证 invoke 命令发送和协议消息发布到 Gateway。 */
class GatewayRelayServiceTest {

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
        private GatewayRelayService.GatewayRelayTarget gatewayRelayTarget;
        @Mock
        private AssistantIdResolverService assistantIdResolverService;
        @Mock
        private AssistantInfoService assistantInfoService;
        @Mock
        private ChannelLookupService channelLookupService;
        @Mock
        private ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService;
        @Mock
        private AssistantScopeDispatcher scopeDispatcher;
        @Mock
        private AssistantScopeStrategy scopeStrategy;
        @Mock
        private com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher outboundDeliveryDispatcher;
         @Mock
         com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;

         @Mock
         private DefaultAssistantRuleService defaultAssistantRuleService;

         @Mock
         private MessageTurnLifecycle messageTurnLifecycle;

         @Mock
         private ApiCallMetricsService apiCallMetricsService;

         private GatewayMessageRouter messageRouter;
         private GatewayRelayService service;

        private static final String LOCAL_INSTANCE = "ss-test-local";

        @BeforeEach
        void setUp() {
                // ownership 检查默认放行，确保消息不被 SKIP
                org.mockito.Mockito.lenient().when(sessionRouteService.ensureRouteOwnership(any(), any(), any())).thenReturn(true);
                org.mockito.Mockito.lenient().when(sessionRouteService.isMySession(any())).thenReturn(true);
                org.mockito.Mockito.lenient().when(sessionRouteService.isMySession(any())).thenReturn(true);
                // Make getOwnerInstance return LOCAL_INSTANCE so route() processes locally
                org.mockito.Mockito.lenient().when(sessionRouteService.getOwnerInstance(any())).thenReturn(LOCAL_INSTANCE);
                org.mockito.Mockito.lenient().when(skillInstanceRegistry.getInstanceId()).thenReturn(LOCAL_INSTANCE);
                // scopeDispatcher always returns a lenient strategy to avoid NPE in tool_event handling
                // nullable 覆盖 getAssistantInfo 返回 null 的情况
                org.mockito.Mockito.lenient().when(scopeDispatcher.getStrategy(nullable(AssistantInfo.class))).thenReturn(scopeStrategy);
                // PR3 收口：sendInvokeToGateway 走 3-arg API；默认返 personal scopeStrategy 行为不变
                org.mockito.Mockito.lenient().when(scopeDispatcher.getStrategy(
                                nullable(String.class), nullable(String.class), nullable(AssistantInfo.class)))
                                .thenReturn(scopeStrategy);
                org.mockito.Mockito.lenient().when(scopeStrategy.getScope()).thenReturn("personal");
                // scopeStrategy.translateEvent 默认委派给 translator.translate（模拟 PersonalScopeStrategy 行为）
                org.mockito.Mockito.lenient().when(scopeStrategy.translateEvent(any(), any()))
                        .thenAnswer(invocation -> translator.translate(invocation.getArgument(0)));
                // scopeStrategy.requiresOnlineCheck 默认返回 true（personal 策略行为）
                org.mockito.Mockito.lenient().when(scopeStrategy.requiresOnlineCheck()).thenReturn(true);

         messageRouter = new GatewayMessageRouter(
                                  new ObjectMapper(),
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
                                  defaultAssistantRuleService,
                                  messageTurnLifecycle,
                                  120,
                                  true,
                                  25,
                                  java.time.Clock.systemDefaultZone(),
                                  com.github.benmanes.caffeine.cache.Ticker.disabledTicker());
                 messageRouter.initConfirmDedupCache();
                  service = new GatewayRelayService(
                                  new ObjectMapper(),
                                  messageRouter,
                                  rebuildService,
                                  redisMessageBroker,
                                  assistantIdResolverService,
                                  assistantInfoService,
                                  scopeDispatcher,
                                  emitter,
                                  apiCallMetricsService);
                service.setGatewayRelayTarget(gatewayRelayTarget);
        }

        @Test
        @DisplayName("tool_event persists and broadcasts to Skill Redis")
        void toolEventPersistsAndBroadcasts() {
                String msg = "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":123,\"event\":{\"data\":\"hello\"}}";
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .sessionId("ses_internal_1")
                                .partId("part-1")
                                .content("hello")
                                .build());

                service.handleGatewayMessage(msg);

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("123"), eq("user-1"), msgCaptor.capture());
                StreamMessage delivered = msgCaptor.getValue();
                assertEquals(StreamMessage.Types.TEXT_DELTA, delivered.getType());
                verify(bufferService).accumulate(eq("123"), any(StreamMessage.class));
                verify(persistenceService).persistIfFinal(eq(123L), any(StreamMessage.class));
        }

        @Test
        @DisplayName("IM direct assistant message routes to outbound service and persists")
        void imDirectAssistantMessageRoutesToOutbound() {
                SkillSession session = new SkillSession();
                session.setId(42L);
                session.setBusinessSessionDomain("im");
                session.setBusinessSessionType("direct");
                session.setBusinessSessionId("dm-001");
                session.setAssistantAccount("assist-001");
                when(sessionService.findByIdSafe(42L)).thenReturn(session);
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DONE)
                                .content("Agent reply")
                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"welinkSessionId\":42,\"event\":{\"type\":\"message.part.updated\"}}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(SkillSession.class), eq("42"), any(), msgCaptor.capture());
                assertEquals(StreamMessage.Types.TEXT_DONE, msgCaptor.getValue().getType());
                verify(persistenceService).persistIfFinal(eq(42L), any(StreamMessage.class));
        }

        @Test
        @DisplayName("IM group assistant message routes to outbound service without persistence")
        void imGroupAssistantMessageSkipsPersistence() {
                SkillSession session = new SkillSession();
                session.setId(42L);
                session.setBusinessSessionDomain("im");
                session.setBusinessSessionType("group");
                session.setBusinessSessionId("grp-001");
                session.setAssistantAccount("assist-001");
                when(sessionService.findByIdSafe(42L)).thenReturn(session);
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DONE)
                                .content("Group reply")
                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"welinkSessionId\":42,\"event\":{\"type\":\"message.part.updated\"}}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(SkillSession.class), eq("42"), any(), msgCaptor.capture());
                assertEquals(StreamMessage.Types.TEXT_DONE, msgCaptor.getValue().getType());
                verify(persistenceService, never()).persistIfFinal(eq(42L), any(StreamMessage.class));
        }

        @Test
        @DisplayName("IM question message stores pending interaction state")
        void imQuestionMessageStoresPendingInteractionState() {
                SkillSession session = new SkillSession();
                session.setId(42L);
                session.setBusinessSessionDomain("im");
                session.setBusinessSessionType("direct");
                session.setBusinessSessionId("dm-001");
                session.setAssistantAccount("assist-001");
                when(sessionService.findByIdSafe(42L)).thenReturn(session);
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.QUESTION)
                                .status("running")
                                .tool(StreamMessage.ToolInfo.builder()
                                                .toolName("question")
                                                .toolCallId("tool-call-1")
                                                .build())
                                .questionInfo(StreamMessage.QuestionInfo.builder()
                                                .header("Confirm")
                                                .question("Continue?")
                                                .options(java.util.List.of("yes", "no"))
                                                .build())
                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"welinkSessionId\":42,\"event\":{\"type\":\"question.asked\"}}");

                verify(interactionStateService).markQuestion(42L, "tool-call-1");
                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(SkillSession.class), eq("42"), any(), msgCaptor.capture());
                assertEquals(StreamMessage.Types.QUESTION, msgCaptor.getValue().getType());
        }

        @Test
        @DisplayName("tool_done broadcasts via Skill Redis")
        void toolDoneBroadcasts() {
                String msg = "{\"type\":\"tool_done\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"usage\":{\"tokens\":100}}";

                service.handleGatewayMessage(msg);

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), eq("user-1"), msgCaptor.capture());
                assertEquals("session.status", msgCaptor.getValue().getType());
                assertEquals("idle", msgCaptor.getValue().getSessionStatus());
                verify(bufferService).accumulate(eq("42"), any(StreamMessage.class));
                verify(persistenceService).persistIfFinal(eq(42L), any(StreamMessage.class));
        }

        @Test
        @DisplayName("user text echo from tool_event is silently skipped (user messages are persisted at inbound)")
        void userTextEchoFromToolEventIsSkipped() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DONE)
                                .role("user")
                                .content("CLI user message")
                                .build());

                service.handleGatewayMessage("{\"type\":\"tool_done\",\"userId\":\"user-1\",\"welinkSessionId\":42}");
                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"event\":{\"type\":\"message.part.updated\"}}");

                // user 角色事件在 tool_event 中不再持久化（已由 inbound controller 保存）
                verify(messageService, never()).saveUserMessage(any(), any());
                // tool_done delivers idle status, but user echo tool_event should NOT deliver
                verify(emitter, times(1)).emitToSession(any(), any(), any(), any());
        }

        @Test
        @DisplayName("tool event activation broadcasts busy status")
        void toolEventActivationBroadcastsBusyStatus() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .partId("part-1")
                                .content("hello")
                                .build());
                when(sessionService.activateSession(123L)).thenReturn(true);

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":123,\"event\":{\"data\":\"hello\"}}");

                // busy status broadcast via emitter.emitToClient (broadcastStreamMessage now delegates to emitter)
                ArgumentCaptor<StreamMessage> busyCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToClient(eq("123"), eq("user-1"), busyCaptor.capture());
                assertEquals("session.status", busyCaptor.getValue().getType());
                assertEquals("busy", busyCaptor.getValue().getSessionStatus());
                ArgumentCaptor<StreamMessage> bufferCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(bufferService, times(2)).accumulate(eq("123"), bufferCaptor.capture());
                assertTrue(bufferCaptor.getAllValues().stream()
                                .anyMatch(m -> StreamMessage.Types.SESSION_STATUS.equals(m.getType())
                                                && "busy".equals(m.getSessionStatus())));
                // text.delta delivered via dispatcher
                verify(emitter).emitToSession(any(), eq("123"), eq("user-1"), any(StreamMessage.class));
        }

        @Test
        @DisplayName("session rebuild broadcasts retry status")
        void sessionRebuildBroadcastsRetryStatus() {
                service.handleGatewayMessage(
                                "{\"type\":\"tool_error\",\"welinkSessionId\":42,\"error\":\"session_not_found\"}");

                verify(rebuildService).handleSessionNotFound(eq("42"), any(), any());
        }

        @Test
        @DisplayName("tool_error persists and broadcasts via Skill Redis")
        void toolErrorPersistsAndBroadcasts() {
                String msg = "{\"type\":\"tool_error\",\"userId\":\"user-42\",\"welinkSessionId\":42,\"error\":\"timeout\"}";

                service.handleGatewayMessage(msg);

                verify(messageService).saveSystemMessage(eq(42L), contains("timeout"));
                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), eq("user-42"), msgCaptor.capture());
                assertEquals(StreamMessage.Types.ERROR, msgCaptor.getValue().getType());
                assertTrue(msgCaptor.getValue().getError().contains("timeout"));
                verify(bufferService).clearSession("42");
        }

        @Test
        @DisplayName("agent_online broadcasts to all agent sessions via Redis")
        void agentOnlineBroadcastsToSessions() {
                SkillSession session = new SkillSession();
                session.setId(1L);
                session.setUserId("user-1");
                when(sessionService.findActiveByAk("99")).thenReturn(java.util.List.of(session));

                String msg = "{\"type\":\"agent_online\",\"ak\":\"99\",\"toolType\":\"channel\",\"toolVersion\":\"1.0\"}";
                service.handleGatewayMessage(msg);

                verify(emitter).emitToClient(eq("1"), eq("user-1"), argThat(m ->
                        StreamMessage.Types.AGENT_ONLINE.equals(m.getType())));
        }

        @Test
        @DisplayName("agent_offline broadcasts to all agent sessions via Redis")
        void agentOfflineBroadcastsToSessions() {
                SkillSession session = new SkillSession();
                session.setId(2L);
                session.setUserId("user-2");
                when(sessionService.findActiveByAk("99")).thenReturn(java.util.List.of(session));

                String msg = "{\"type\":\"agent_offline\",\"ak\":\"99\"}";
                service.handleGatewayMessage(msg);

                verify(emitter).emitToClient(eq("2"), eq("user-2"), argThat(m ->
                        StreamMessage.Types.AGENT_OFFLINE.equals(m.getType())));
        }

        @Test
        @DisplayName("session_created updates toolSessionId")
        void sessionCreatedUpdatesToolSessionId() {
                String msg = "{\"type\":\"session_created\",\"ak\":\"1\",\"welinkSessionId\":42,\"toolSessionId\":\"ts-abc\"}";

                service.handleGatewayMessage(msg);

                verify(sessionService).updateToolSessionId(eq(42L), eq("ts-abc"));
        }

        @Test
        @DisplayName("permission_request broadcasts via Redis")
        void permissionRequestBroadcasts() {
                when(translator.translatePermissionFromGateway(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.PERMISSION_ASK)
                                .permission(StreamMessage.PermissionInfo.builder().permissionId("p-1").build())
                                .build());

                String msg = "{\"type\":\"permission_request\",\"userId\":\"user-42\",\"welinkSessionId\":42,\"permissionId\":\"p-1\",\"command\":\"rm -rf /\",\"workingDirectory\":\"/tmp\"}";
                service.handleGatewayMessage(msg);

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), eq("user-42"), msgCaptor.capture());
                assertEquals("permission.ask", msgCaptor.getValue().getType());
        }

        @Test
        @DisplayName("IM permission request stores pending interaction state")
        void imPermissionRequestStoresPendingInteractionState() {
                SkillSession session = new SkillSession();
                session.setId(42L);
                session.setBusinessSessionDomain("im");
                session.setBusinessSessionType("group");
                session.setBusinessSessionId("grp-001");
                session.setAssistantAccount("assist-001");
                when(sessionService.findByIdSafe(42L)).thenReturn(session);
                when(translator.translatePermissionFromGateway(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.PERMISSION_ASK)
                                .title("Need approval")
                                .permission(StreamMessage.PermissionInfo.builder().permissionId("perm-1").build())
                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"permission_request\",\"welinkSessionId\":42,\"permissionId\":\"perm-1\"}");

                verify(interactionStateService).markPermission(42L, "perm-1");
                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(SkillSession.class), eq("42"), any(), msgCaptor.capture());
                assertEquals(StreamMessage.Types.PERMISSION_ASK, msgCaptor.getValue().getType());
        }

        @Test
        @DisplayName("tool_event with toolSessionId resolves via DB lookup")
        void toolEventLooksUpWelinkSessionId() {
                SkillSession session = new SkillSession();
                session.setId(42L);
                session.setUserId("user-42");
                when(sessionService.findByToolSessionId("ts-abc")).thenReturn(session);
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .partId("part-1")
                                .content("hello")
                                .build());

                // Message has toolSessionId but NO welinkSessionId
                String msg = "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-abc\",\"event\":{\"data\":\"hello\"}}";
                service.handleGatewayMessage(msg);

                verify(sessionService, times(2)).findByToolSessionId("ts-abc");
                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), any(), msgCaptor.capture());
                assertEquals(StreamMessage.Types.TEXT_DELTA, msgCaptor.getValue().getType());
        }

        @Test
        @DisplayName("unknown type logs warning without errors")
        void unknownTypeLogsWarning() {
                String msg = "{\"type\":\"unknown_type\",\"welinkSessionId\":42}";

                service.handleGatewayMessage(msg);

                verifyNoInteractions(redisMessageBroker);
        }

        @Test
        @DisplayName("malformed JSON does not throw")
        void malformedJsonDoesNotThrow() {
                service.handleGatewayMessage("not json at all");
        }

        @Test
        @DisplayName("tool_event with missing welinkSessionId logs warning")
        void toolEventMissingWelinkSessionId() {
                String msg = "{\"type\":\"tool_event\",\"event\":{\"data\":\"hello\"}}";

                service.handleGatewayMessage(msg);

                verifyNoInteractions(redisMessageBroker);
        }

        @Test
        @DisplayName("sendInvokeToGateway sends via round-robin")
        void sendInvokeSendsViaRoundRobin() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                service.sendInvokeToGateway(
                                new InvokeCommand("agent-1", "user-1", "session-1", "chat", "{\"text\":\"hello\"}"));

                verify(gatewayRelayTarget).sendToGateway(contains("invoke"));
        }

        @Test
        @DisplayName("sendInvokeToGateway serializes string welinkSessionId for create_session")
        void sendInvokeSerializesNumericWelinkSessionId() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                service.sendInvokeToGateway(
                                new InvokeCommand("agent-1", "user-1", "42", "create_session", "{\"title\":\"demo\"}"));

                verify(gatewayRelayTarget).sendToGateway(contains("\"welinkSessionId\":\"42\""));
        }

        @Test
        @DisplayName("sendInvokeToGateway drops when no active connection")
        void sendInvokeDropsWhenNoActiveConnection() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(false);

                service.sendInvokeToGateway(
                                new InvokeCommand("agent-1", "user-1", "session-1", "chat", "{\"text\":\"hello\"}"));

                verify(gatewayRelayTarget, never()).sendToGateway(any());
        }

        @Test
        @DisplayName("tool_event resolves userId from session when message omits it")
        void toolEventResolvesUserIdFromSession() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .partId("part-1")
                                .content("hello")
                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"welinkSessionId\":123,\"event\":{\"data\":\"hello\"}}");

                // userId is null in JSON, so it's passed as null to deliver
                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("123"), any(), msgCaptor.capture());
                assertEquals(StreamMessage.Types.TEXT_DELTA, msgCaptor.getValue().getType());
        }

        @Test
        @DisplayName("tool_done with toolSessionId resolves via DB lookup and publishes welinkSessionId")
        void toolDoneUsesRecoveredSessionAffinity() {
                SkillSession session = new SkillSession();
                session.setId(42L);
                session.setUserId("user-42");
                when(sessionService.findByToolSessionId("ts-abc")).thenReturn(session);

                service.handleGatewayMessage("{\"type\":\"tool_done\",\"toolSessionId\":\"ts-abc\"}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), any(), msgCaptor.capture());
                assertEquals("session.status", msgCaptor.getValue().getType());
                assertEquals("idle", msgCaptor.getValue().getSessionStatus());
        }

        @Test
        @DisplayName("tool_error with unresolved toolSessionId is dropped without side effects")
        void toolErrorWithoutResolvedSessionIsDropped() {
                when(sessionService.findByToolSessionId("missing")).thenReturn(null);

                service.handleGatewayMessage(
                                "{\"type\":\"tool_error\",\"toolSessionId\":\"missing\",\"error\":\"timeout\"}");

                verify(redisMessageBroker, never()).publishToUser(any(), any());
                verify(messageService, never()).saveSystemMessage(any(), any());
                verify(persistenceService, never()).finalizeActiveAssistantTurn(any());
        }

        @Test
        @DisplayName("tool_event publishes only to the user owning the resolved session")
        void toolEventPublishesOnlyToResolvedSessionOwner() {
                SkillSession session = new SkillSession();
                session.setId(42L);
                session.setUserId("user-a");
                when(sessionService.findByToolSessionId("ts-a")).thenReturn(session);
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .sessionId("999")
                                .partId("part-1")
                                .content("hello")
                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-a\",\"event\":{\"data\":\"hello\"}}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), any(), msgCaptor.capture());
                assertEquals(StreamMessage.Types.TEXT_DELTA, msgCaptor.getValue().getType());
        }

        @Test
        @DisplayName("tool_event after tool_done is suppressed")
        void toolEventAfterToolDoneIsSuppressed() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .content("stale")
                                .build());
                // Step 1: tool_done arrives → delivers idle via dispatcher
                service.handleGatewayMessage("{\"type\":\"tool_done\",\"userId\":\"user-1\",\"welinkSessionId\":42}");
                verify(emitter).emitToSession(any(), eq("42"), eq("user-1"), any(StreamMessage.class));

                // Step 2: stale tool_event arrives after tool_done → should be suppressed
                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"event\":{\"data\":\"stale\"}}");

                // Translator may still be called so the router can inspect role/type, but the
                // stale assistant event must not be delivered again.
                verify(translator).translate(any());

                // Dispatcher should only have been called once (for tool_done idle), NOT for the
                // stale tool_event
                verify(emitter, times(1)).emitToSession(any(), any(), any(), any());
        }

        @Test
        @DisplayName("new chat invoke after tool_done clears suppression")
        void newChatInvokeAfterToolDoneClearsSuppression() {
                // Step 1: tool_done arrives → session marked as completed
                service.handleGatewayMessage("{\"type\":\"tool_done\",\"userId\":\"user-1\",\"welinkSessionId\":42}");

                // Step 2: user sends a new message → sendInvokeToGateway("chat") clears the
                // mark
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);
                service.sendInvokeToGateway(
                                new InvokeCommand("test-ak", "user-1", "42", "chat", "{\"text\":\"hello\"}"));

                // Step 3: new tool_event arrives → should NOT be suppressed
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .partId("part-1")
                                .content("new response")
                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"event\":{\"data\":\"new\"}}");

                // Dispatcher called at least 2 times: idle (tool_done) + text.delta (new event)
                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter, org.mockito.Mockito.atLeast(2)).emitToSession(any(), any(), any(),
                                msgCaptor.capture());
                assertTrue(msgCaptor.getAllValues().stream().anyMatch(m -> StreamMessage.Types.TEXT_DELTA.equals(m.getType())));
        }

        @Test
        @DisplayName("permission reply after tool_done is not suppressed")
        void permissionReplyAfterToolDoneIsNotSuppressed() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.PERMISSION_REPLY)
                                .permission(StreamMessage.PermissionInfo.builder()
                                                .permissionId("perm-1")
                                                .response("once")
                                                .build())
                                .build());

                service.handleGatewayMessage("{\"type\":\"tool_done\",\"userId\":\"user-1\",\"welinkSessionId\":42}");
                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"event\":{\"type\":\"permission.updated\"}}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter, org.mockito.Mockito.atLeast(2)).emitToSession(any(), any(), any(),
                                msgCaptor.capture());
                assertTrue(msgCaptor.getAllValues().stream()
                                .anyMatch(m -> StreamMessage.Types.PERMISSION_REPLY.equals(m.getType())));
        }

        @Test
        @DisplayName("rejected permission tool error is synthesized into permission reply")
        void rejectedPermissionToolErrorSynthesizesPermissionReply() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TOOL_UPDATE)
                                .status("error")
                                .error("Error: The user rejected permission to use this specific tool call.")
                                .tool(StreamMessage.ToolInfo.builder()
                                                .toolName("bash")
                                                .toolCallId("toolu_function.bash:23")
                                                .build())
                                .build());
                when(persistenceService.synthesizePermissionReplyFromToolOutcome(eq(42L), any(StreamMessage.class)))
                                .thenReturn(StreamMessage.builder()
                                                .type(StreamMessage.Types.PERMISSION_REPLY)
                                                .partId("perm-1")
                                                .partSeq(3)
                                                .permission(StreamMessage.PermissionInfo.builder()
                                                                .permissionId("perm-1")
                                                                .permType("external_directory")
                                                                .response("reject")
                                                                .build())
                                                .status("completed")
                                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"event\":{\"type\":\"message.part.updated\"}}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), eq("user-1"), msgCaptor.capture());
                StreamMessage delivered = msgCaptor.getValue();
                assertEquals(StreamMessage.Types.PERMISSION_REPLY, delivered.getType());
                assertEquals("reject", delivered.getPermission().getResponse());
                verify(persistenceService).persistIfFinal(eq(42L), any(StreamMessage.class));
        }

        @Test
        @DisplayName("successful gated tool also synthesizes permission reply")
        void successfulGatedToolSynthesizesPermissionReply() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TOOL_UPDATE)
                                .status("completed")
                                .tool(StreamMessage.ToolInfo.builder()
                                                .toolName("write")
                                                .toolCallId("toolu_function.write:24")
                                                .output("Wrote file successfully.")
                                                .build())
                                .build());
                when(persistenceService.synthesizePermissionReplyFromToolOutcome(eq(42L), any(StreamMessage.class)))
                                .thenReturn(StreamMessage.builder()
                                                .type(StreamMessage.Types.PERMISSION_REPLY)
                                                .partId("perm-1")
                                                .partSeq(3)
                                                .permission(StreamMessage.PermissionInfo.builder()
                                                                .permissionId("perm-1")
                                                                .permType("external_directory")
                                                                .response("once")
                                                                .build())
                                                .status("completed")
                                                .build());

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"event\":{\"type\":\"message.part.updated\"}}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter, times(2)).emitToSession(any(), eq("42"), eq("user-1"), msgCaptor.capture());
                assertTrue(msgCaptor.getAllValues().stream()
                                .anyMatch(m -> StreamMessage.Types.PERMISSION_REPLY.equals(m.getType())));
                assertTrue(msgCaptor.getAllValues().stream()
                                .anyMatch(m -> StreamMessage.Types.TOOL_UPDATE.equals(m.getType())));
        }

        @Test
        @DisplayName("plain tool error is not converted to permission reply")
        void plainToolErrorIsNotConvertedToPermissionReply() {
                when(translator.translate(any())).thenReturn(StreamMessage.builder()
                                .type(StreamMessage.Types.TOOL_UPDATE)
                                .status("error")
                                .error("Error: command failed")
                                .tool(StreamMessage.ToolInfo.builder()
                                                .toolName("bash")
                                                .toolCallId("toolu_function.bash:23")
                                                .build())
                                .build());
                when(persistenceService.synthesizePermissionReplyFromToolOutcome(eq(42L), any(StreamMessage.class)))
                                .thenReturn(null);

                service.handleGatewayMessage(
                                "{\"type\":\"tool_event\",\"userId\":\"user-1\",\"welinkSessionId\":42,\"event\":{\"type\":\"message.part.updated\"}}");

                ArgumentCaptor<StreamMessage> msgCaptor = ArgumentCaptor.forClass(StreamMessage.class);
                verify(emitter).emitToSession(any(), eq("42"), eq("user-1"), msgCaptor.capture());
                assertEquals(StreamMessage.Types.TOOL_UPDATE, msgCaptor.getValue().getType());
        }

        @Test
        @DisplayName("buildInvokeMessage injects assistantId into payload when resolved")
        void buildInvokeMessageInjectsAssistantId() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);
                when(assistantIdResolverService.resolve("ak-001", "42")).thenReturn("persona-agent-id");

                service.sendInvokeToGateway(
                                new InvokeCommand("ak-001", "user-1", "42", "chat", "{\"text\":\"hello\"}"));

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());

                JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
                assertEquals("persona-agent-id", sent.path("payload").path("assistantId").asText());
                // 原有 payload 字段不受影响
                assertEquals("hello", sent.path("payload").path("text").asText());
        }

        @Test
        @DisplayName("buildInvokeMessage does not inject when resolver returns null")
        void buildInvokeMessageSkipsWhenResolverReturnsNull() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);
                when(assistantIdResolverService.resolve("ak-001", "42")).thenReturn(null);

                service.sendInvokeToGateway(
                                new InvokeCommand("ak-001", "user-1", "42", "chat", "{\"text\":\"hello\"}"));

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());

                JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
                assertTrue(sent.path("payload").path("assistantId").isMissingNode());
        }

        @Test
        @DisplayName("buildInvokeMessage creates payload ObjectNode when payload is null")
        void buildInvokeMessageCreatesPayloadWhenNull() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);
                when(assistantIdResolverService.resolve("ak-001", "42")).thenReturn("agent-id");

                service.sendInvokeToGateway(
                                new InvokeCommand("ak-001", "user-1", "42", "create_session", null));

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());

                JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
                assertEquals("agent-id", sent.path("payload").path("assistantId").asText());
        }

        @Test
        @DisplayName("buildInvokeMessage skips assistantId for non-chat/create_session actions")
        void buildInvokeMessageSkipsForNonChatActions() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                service.sendInvokeToGateway(
                                new InvokeCommand("ak-001", "user-1", "42", "question_reply",
                                                "{\"answer\":\"yes\",\"toolCallId\":\"tc-1\"}"));

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());

                JsonNode sent = objectMapper.readTree(msgCaptor.getValue());
                assertTrue(sent.path("payload").path("assistantId").isMissingNode());
                // resolver 不应被调用
                verify(assistantIdResolverService, never()).resolve(any(), any());
        }

        @Test
        @DisplayName("ⓕ-2 (PR2 platformExtParam 升级)：personal scope chat invoke 把 businessExtParam 搬到 extParameters.businessExtParam + 注入 platformExtParam 三字段")
        void personalScopeDoesNotStripBusinessExtParam() throws Exception {
                // PR2 升级前：personal 路径透传 businessExtParam 到 payload 顶层
                // PR2 升级后：personal 路径与 business 对齐, businessExtParam 搬到 extParameters.businessExtParam
                AssistantInfo personalInfo = new AssistantInfo();
                personalInfo.setAssistantScope("personal");
                when(assistantInfoService.getAssistantInfo("ak-personal")).thenReturn(personalInfo);

                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);
                // assistantIdResolverService.resolve → null：不注入 assistantId，避免其他断言干扰
                when(assistantIdResolverService.resolve("ak-personal", "42")).thenReturn(null);

                String payload = "{\"text\":\"hi\",\"toolSessionId\":\"open-1\","
                        + "\"businessExtParam\":{\"k\":\"v\"}}";
                // PR2: 9 参 InvokeCommand 携带 domain/domainType/businessSessionId, 让 buildInvokeMessage 注入 platformExtParam
                InvokeCommand cmd = new InvokeCommand("ak-personal", "u-1", "42", "chat", payload,
                                null, "im", "direct", "biz-42");
                service.sendInvokeToGateway(cmd);

                ArgumentCaptor<String> messageCap = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(messageCap.capture());

                // PR2 wire 形态: 顶层 businessExtParam 不再出现, 搬入 extParameters.businessExtParam
                JsonNode sent = objectMapper.readTree(messageCap.getValue());
                JsonNode sentPayload = sent.get("payload");
                assertNotNull(sentPayload);
                assertFalse(sentPayload.has("businessExtParam"),
                                "PR2: businessExtParam must NOT appear at payload top-level");

                JsonNode extParameters = sentPayload.get("extParameters");
                assertNotNull(extParameters, "PR2: extParameters envelope must be injected on personal path");
                assertTrue(extParameters.get("businessExtParam").isObject());
                assertEquals("v", extParameters.get("businessExtParam").get("k").asText());

                // platformExtParam 三字段
                JsonNode platform = extParameters.get("platformExtParam");
                assertEquals("im", platform.get("businessSessionDomain").asText());
                assertEquals("direct", platform.get("businessSessionType").asText());
                assertEquals("biz-42", platform.get("businessSessionId").asText());
        }

        // ==================== sendQuerySlashCommandsToGateway tests ====================

        @Test
        @DisplayName("sendQuerySlashCommandsToGateway: sends minimal invoke with correct structure")
        void sendQuerySlashCommandsToGateway_sendsMinimalInvoke() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                InvokeCommand cmd = new InvokeCommand("ak-001", "user-1", "42",
                                GatewayActions.QUERY_SLASH_COMMANDS, null);
                boolean sent = service.sendQuerySlashCommandsToGateway(cmd, "tool-session-001");

                assertTrue(sent);
                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());

                JsonNode sentJson = objectMapper.readTree(msgCaptor.getValue());
                assertEquals("invoke", sentJson.path("type").asText());
                assertEquals("ak-001", sentJson.path("ak").asText());
                assertEquals("skill-server", sentJson.path("source").asText());
                assertEquals("tool-session-001", sentJson.path("toolSessionId").asText());
                assertEquals("query_slash_commands", sentJson.path("action").asText());
                assertTrue(sentJson.has("traceId"));
                assertFalse(sentJson.path("traceId").asText().isBlank());

                // Verify extParameters envelope
                JsonNode payload = sentJson.path("payload");
                assertTrue(payload.has("extParameters"));
                JsonNode extParams = payload.path("extParameters");
                assertTrue(extParams.has("businessExtParam"));
                assertTrue(extParams.path("businessExtParam").isObject());
                assertTrue(extParams.has("platformExtParam"));
                JsonNode platform = extParams.path("platformExtParam");
                assertTrue(platform.has("businessSessionDomain"));
                assertTrue(platform.has("businessSessionType"));
                assertTrue(platform.has("businessSessionId"));
                assertTrue(platform.has("bizRobotTag"));
        }

        @Test
        @DisplayName("sendQuerySlashCommandsToGateway: includes assistantAccount when present")
        void sendQuerySlashCommandsToGateway_includesAssistantAccount() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                InvokeCommand cmd = new InvokeCommand("ak-001", "user-1", "42",
                                GatewayActions.QUERY_SLASH_COMMANDS, null, null, "miniapp", "direct",
                                "biz-001", null, "assist-001", null);
                service.sendQuerySlashCommandsToGateway(cmd, "tool-001");

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());
                JsonNode sentJson = objectMapper.readTree(msgCaptor.getValue());
                assertEquals("assist-001", sentJson.path("assistantAccount").asText());
        }

        @Test
        @DisplayName("sendQuerySlashCommandsToGateway: platformExtParam populated from command domain/type/bizId")
        void sendQuerySlashCommandsToGateway_platformExtParamFromCommand() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                InvokeCommand cmd = new InvokeCommand("ak-001", "user-1", "42",
                                GatewayActions.QUERY_SLASH_COMMANDS, null, null, "im", "group",
                                "biz-group-001");
                service.sendQuerySlashCommandsToGateway(cmd, "tool-001");

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());
                JsonNode sentJson = objectMapper.readTree(msgCaptor.getValue());
                JsonNode platform = sentJson.path("payload").path("extParameters").path("platformExtParam");
                assertEquals("im", platform.path("businessSessionDomain").asText());
                assertEquals("group", platform.path("businessSessionType").asText());
                assertEquals("biz-group-001", platform.path("businessSessionId").asText());
                assertTrue(platform.path("bizRobotTag").isNull());
        }

        @Test
        @DisplayName("sendQuerySlashCommandsToGateway: returns false when no active connection")
        void sendQuerySlashCommandsToGateway_noActiveConnectionReturnsFalse() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(false);

                InvokeCommand cmd = new InvokeCommand("ak-001", "user-1", "42",
                                GatewayActions.QUERY_SLASH_COMMANDS, null);
                boolean sent = service.sendQuerySlashCommandsToGateway(cmd, "tool-001");

                assertFalse(sent);
                verify(gatewayRelayTarget, never()).sendToGateway(any());
        }

        @Test
        @DisplayName("sendQuerySlashCommandsToGateway: returns false when relayTarget is null")
        void sendQuerySlashCommandsToGateway_nullRelayTargetReturnsFalse() {
                // Temporarily unset the relay target
                service.setGatewayRelayTarget(null);

                InvokeCommand cmd = new InvokeCommand("ak-001", "user-1", "42",
                                GatewayActions.QUERY_SLASH_COMMANDS, null);
                boolean sent = service.sendQuerySlashCommandsToGateway(cmd, "tool-001");

                assertFalse(sent);

                // Restore for other tests
                service.setGatewayRelayTarget(gatewayRelayTarget);
        }

        @Test
        @DisplayName("sendQuerySlashCommandsToGateway: returns false when send fails")
        void sendQuerySlashCommandsToGateway_sendFailsReturnsFalse() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(false);

                InvokeCommand cmd = new InvokeCommand("ak-001", "user-1", "42",
                                GatewayActions.QUERY_SLASH_COMMANDS, null);
                boolean sent = service.sendQuerySlashCommandsToGateway(cmd, "tool-001");

                assertFalse(sent);
        }

        @Test
        @DisplayName("sendQuerySlashCommandsToGateway: null domain/domainType produce JSON null in platformExtParam")
        void sendQuerySlashCommandsToGateway_nullDomainFieldsProduceNullNodes() throws Exception {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                // 5-arg constructor: domain/domainType/businessSessionId all null
                InvokeCommand cmd = new InvokeCommand("ak-001", "user-1", "42",
                                GatewayActions.QUERY_SLASH_COMMANDS, null);
                service.sendQuerySlashCommandsToGateway(cmd, "tool-001");

                ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(msgCaptor.capture());
                JsonNode sentJson = objectMapper.readTree(msgCaptor.getValue());
                JsonNode platform = sentJson.path("payload").path("extParameters").path("platformExtParam");
                assertTrue(platform.path("businessSessionDomain").isNull());
                assertTrue(platform.path("businessSessionType").isNull());
                assertTrue(platform.path("businessSessionId").isNull());
        }

        private JsonNode readPublishedMessage(String payload) {
                try {
                        return objectMapper.readTree(payload);
                } catch (Exception e) {
                        throw new AssertionError("Failed to parse published payload", e);
                }
        }

        // ==================== PR3: dispatcher 3-arg API 收口 ====================

        @Test
        @DisplayName("PR3: command 带 domain/domainType + 规则命中 → dispatcher 返 DefaultAssistantScopeStrategy → 走 strategy.buildInvoke")
        void sendInvokeWithDomainTriggersDefaultAssistantStrategy() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                // mock default_assistant strategy
                AssistantScopeStrategy defaultStrategy = org.mockito.Mockito.mock(AssistantScopeStrategy.class);
                org.mockito.Mockito.when(defaultStrategy.getScope()).thenReturn("default_assistant");
                org.mockito.Mockito.when(defaultStrategy.buildInvoke(any(InvokeCommand.class), nullable(AssistantInfo.class)))
                                .thenReturn("{\"type\":\"invoke\",\"assistantScope\":\"business\",\"payload\":{\"cloudProfile\":\"assistant_square\"}}");
                // virtual ak 上游不存在
                when(assistantInfoService.getAssistantInfo("AK_V")).thenReturn(null);
                // dispatcher 3-arg API 命中规则 → 返 defaultStrategy
                org.mockito.Mockito.when(scopeDispatcher.getStrategy(eq("helpdesk"), eq("direct"), nullable(AssistantInfo.class)))
                                .thenReturn(defaultStrategy);

                InvokeCommand cmd = new InvokeCommand("AK_V", "u-1", "100", "chat",
                                "{\"text\":\"hi\"}", null, "helpdesk", "direct");
                service.sendInvokeToGateway(cmd);

                // strategy.buildInvoke 被调
                verify(defaultStrategy).buildInvoke(eq(cmd), nullable(AssistantInfo.class));
                // 通过 GW sendToGateway
                verify(gatewayRelayTarget).sendToGateway(contains("assistant_square"));
        }

        @Test
        @DisplayName("PR3: command 不带 domain (老 caller) → dispatcher 内部 lookup(null, null) 返 empty → 委托老 strategy")
        void sendInvokeWithoutDomainDelegatesToLegacyStrategy() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                // dispatcher 3-arg API：domain=null/null → 返 personal scopeStrategy
                org.mockito.Mockito.when(scopeDispatcher.getStrategy(nullable(String.class), nullable(String.class), nullable(AssistantInfo.class)))
                                .thenReturn(scopeStrategy);
                org.mockito.Mockito.when(scopeStrategy.getScope()).thenReturn("personal");

                // 老 5 参数构造器 (domain/domainType 默认 null)
                InvokeCommand cmd = new InvokeCommand("legacy-ak", "u-1", "42", "chat", "{\"text\":\"hi\"}");
                service.sendInvokeToGateway(cmd);

                // 走 buildInvokeMessage 本地构造（含 ak 字段）
                verify(gatewayRelayTarget).sendToGateway(contains("\"ak\":\"legacy-ak\""));
        }

        @Test
        @DisplayName("PR3: command 带 domain + 规则未命中 + business scope → 走 strategy.buildInvoke (business)")
        void sendInvokeWithDomainAndBusinessScopeStillBuildsInvoke() {
                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);

                AssistantScopeStrategy businessStrategy = org.mockito.Mockito.mock(AssistantScopeStrategy.class);
                org.mockito.Mockito.when(businessStrategy.getScope()).thenReturn("business");
                org.mockito.Mockito.when(businessStrategy.buildInvoke(any(InvokeCommand.class), any(AssistantInfo.class)))
                                .thenReturn("{\"type\":\"invoke\",\"assistantScope\":\"business\"}");
                AssistantInfo info = new AssistantInfo();
                info.setAssistantScope("business");
                when(assistantInfoService.getAssistantInfo("real-business-ak")).thenReturn(info);
                // dispatcher 3-arg：domain 命中规则失败 → 委托老 API 拿 business
                org.mockito.Mockito.when(scopeDispatcher.getStrategy(eq("unknown"), eq("unknown"), eq(info)))
                                .thenReturn(businessStrategy);

                InvokeCommand cmd = new InvokeCommand("real-business-ak", "u-1", "42", "chat",
                                "{\"text\":\"hi\"}", null, "unknown", "unknown");
                service.sendInvokeToGateway(cmd);

                verify(businessStrategy).buildInvoke(eq(cmd), eq(info));
        }
}
