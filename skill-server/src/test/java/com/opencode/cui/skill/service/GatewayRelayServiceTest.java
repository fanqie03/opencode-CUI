package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
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
        private AssistantScopeDispatcher scopeDispatcher;
        @Mock
        private AssistantScopeStrategy scopeStrategy;
        @Mock
        private com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher outboundDeliveryDispatcher;
        @Mock
        com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;

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
                org.mockito.Mockito.lenient().when(scopeDispatcher.getStrategy(any())).thenReturn(scopeStrategy);
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
                                scopeDispatcher,
                                outboundDeliveryDispatcher,
                                emitter,
                                120);
                service = new GatewayRelayService(
                                new ObjectMapper(),
                                messageRouter,
                                rebuildService,
                                redisMessageBroker,
                                assistantIdResolverService,
                                assistantInfoService,
                                scopeDispatcher,
                                emitter);
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

                verify(sessionService).findByToolSessionId("ts-abc");
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
        @DisplayName("ⓕ-2: personal scope chat invoke：businessExtParam 字段随 payload 原样透下游不剥离")
        void personalScopeDoesNotStripBusinessExtParam() throws Exception {
                // personal scope：isBusiness() == false → GatewayRelayService 走 buildInvokeMessage(command)
                // buildInvokeMessage 把 payload 解析后直接 set，不剥离任何字段
                AssistantInfo personalInfo = new AssistantInfo();
                personalInfo.setAssistantScope("personal");
                when(assistantInfoService.getAssistantInfo("ak-personal")).thenReturn(personalInfo);

                when(gatewayRelayTarget.hasActiveConnection()).thenReturn(true);
                when(gatewayRelayTarget.sendToGateway(any())).thenReturn(true);
                // assistantIdResolverService.resolve → null：不注入 assistantId，避免其他断言干扰
                when(assistantIdResolverService.resolve("ak-personal", "42")).thenReturn(null);

                String payload = "{\"text\":\"hi\",\"toolSessionId\":\"open-1\","
                        + "\"businessExtParam\":{\"k\":\"v\"}}";
                InvokeCommand cmd = new InvokeCommand("ak-personal", "u-1", "42", "chat", payload);
                service.sendInvokeToGateway(cmd);

                ArgumentCaptor<String> messageCap = ArgumentCaptor.forClass(String.class);
                verify(gatewayRelayTarget).sendToGateway(messageCap.capture());

                // buildInvokeMessage: message.set("payload", parsedPayloadNode)
                // → 出站消息的 payload 节点即为解析后的 payload，businessExtParam 应原样保留
                JsonNode sent = objectMapper.readTree(messageCap.getValue());
                JsonNode sentPayload = sent.get("payload");
                assertNotNull(sentPayload);
                assertTrue(sentPayload.has("businessExtParam"));
                assertTrue(sentPayload.get("businessExtParam").isObject());
                assertEquals("v", sentPayload.get("businessExtParam").get("k").asText());
        }

        private JsonNode readPublishedMessage(String payload) {
                try {
                        return objectMapper.readTree(payload);
                } catch (Exception e) {
                        throw new AssertionError("Failed to parse published payload", e);
                }
        }
}
