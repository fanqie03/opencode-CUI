package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.SnapshotService;
import com.opencode.cui.skill.telemetry.metrics.WsConnectionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SkillStreamHandler 单元测试：验证 WebSocket 握手、消息推送和连接管理。 */
class SkillStreamHandlerTest {

        private SkillStreamHandler handler;
        private SnapshotService snapshotService;
        private SkillSessionService sessionService;
        private RedisMessageBroker redisMessageBroker;
        private ObjectMapper objectMapper = new ObjectMapper();

        @BeforeEach
        void setUp() {
                snapshotService = mock(SnapshotService.class);
                sessionService = mock(SkillSessionService.class);
                redisMessageBroker = mock(RedisMessageBroker.class);
                // nextStreamSeq 改为 Redis INCR 实现，mock 需显式 stub 以返回合法序号
                when(redisMessageBroker.nextStreamSeq(any())).thenAnswer(inv -> 1L);
                handler = new SkillStreamHandler(
                                objectMapper,
                                sessionService,
                                snapshotService,
                                redisMessageBroker,
                                new WsConnectionMetrics(new SimpleMeterRegistry()));
        }

        @Test
        @DisplayName("protocol /ws/skill/stream connection sends streaming state and receives live updates")
        void protocolPathRegistersUserSubscriber() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("idle").build());

                handler.afterConnectionEstablished(session);
                handler.pushStreamMessage("42",
                                StreamMessage.builder().type("delta").sessionId("42").content("hello").build());

                verify(session, times(2)).sendMessage(any(TextMessage.class));
                verify(redisMessageBroker).subscribeToUser(eq("10001"), any());
        }

        @Test
        @DisplayName("resume action replays streaming state")
        void resumeReplaysCurrentState() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("idle").build());

                handler.afterConnectionEstablished(session);
                reset(session);
                when(session.getAttributes()).thenReturn(new HashMap<>(java.util.Map.of("userId", "10001")));
                when(session.isOpen()).thenReturn(true);

                handler.handleMessage(session, new TextMessage("{\"action\":\"resume\"}"));

                verify(session).sendMessage(any(TextMessage.class));

                ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
                verify(session).sendMessage(captor.capture());
                var json = objectMapper.readTree(captor.getValue().getPayload());
                Assertions.assertEquals("streaming", json.path("type").asText());
        }

        @Test
        @DisplayName("resume action with sessionId replays only that owned session")
        void resumeWithSessionIdReplaysOnlyRequestedSession() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(1L)
                                                .sessionId("42").welinkSessionId("42").sessionStatus("busy").build());

                handler.afterConnectionEstablished(session);
                clearInvocations(snapshotService);
                reset(session);
                when(session.getAttributes()).thenReturn(new HashMap<>(java.util.Map.of("userId", "10001")));
                when(session.isOpen()).thenReturn(true);

                SkillSession requested = new SkillSession();
                requested.setId(43L);
                requested.setUserId("10001");
                when(sessionService.findByIdSafe(43L)).thenReturn(requested);
                when(snapshotService.buildStreamingState(eq("43"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("43").welinkSessionId("43").sessionStatus("busy").build());

                handler.handleMessage(session, new TextMessage("{\"action\":\"resume\",\"sessionId\":\"43\"}"));

                verify(snapshotService, never()).getActiveSessionsForUser("10001");
                verify(snapshotService).buildStreamingState(eq("43"), any(Long.class));

                ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
                verify(session).sendMessage(captor.capture());
                var json = objectMapper.readTree(captor.getValue().getPayload());
                Assertions.assertEquals("streaming", json.path("type").asText());
                Assertions.assertEquals("43", json.path("welinkSessionId").asText());
        }

        @Test
        @DisplayName("resume action rejects sessions owned by another user")
        void resumeWithSessionIdRejectsWrongOwner() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of());
                handler.afterConnectionEstablished(session);
                reset(session);
                when(session.getAttributes()).thenReturn(new HashMap<>(java.util.Map.of("userId", "10001")));
                when(session.isOpen()).thenReturn(true);

                SkillSession requested = new SkillSession();
                requested.setId(43L);
                requested.setUserId("20002");
                when(sessionService.findByIdSafe(43L)).thenReturn(requested);

                handler.handleMessage(session, new TextMessage("{\"action\":\"resume\",\"sessionId\":\"43\"}"));

                verify(session, never()).sendMessage(any(TextMessage.class));
                verify(snapshotService, never()).buildStreamingState(eq("43"), any(Long.class));
        }

        @Test
        @DisplayName("ping action acts as keepalive without replaying state")
        void pingDoesNotReplayCurrentState() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("idle").build());

                handler.afterConnectionEstablished(session);
                reset(session);
                when(session.getAttributes()).thenReturn(new HashMap<>(java.util.Map.of("userId", "10001")));
                when(session.isOpen()).thenReturn(true);

                handler.handleMessage(session, new TextMessage("{\"action\":\"ping\"}"));

                verify(session, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("streaming payload exposes aggregated protocol parts instead of raw event types")
        void streamingPayloadUsesAggregatedProtocolParts() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");

                // 构建 streaming 状态中的 protocol parts
                var textPart = objectMapper.createObjectNode();
                textPart.put("type", "text");
                textPart.put("content", "hello");
                var toolPart = objectMapper.createObjectNode();
                toolPart.put("type", "tool");
                toolPart.put("toolName", "bash");
                toolPart.set("input", objectMapper.readTree("{\"command\":\"pwd\"}"));

                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("busy")
                                                .parts(List.of(textPart, toolPart)).build());

                handler.afterConnectionEstablished(session);

                ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
                verify(session, atLeastOnce()).sendMessage(captor.capture());

                String payload = captor.getAllValues().get(0).getPayload();
                var json = objectMapper.readTree(payload);
                Assertions.assertEquals("streaming", json.path("type").asText());
                Assertions.assertEquals("busy", json.path("sessionStatus").asText());
                Assertions.assertEquals("text", json.path("parts").get(0).path("type").asText());
                Assertions.assertEquals("hello", json.path("parts").get(0).path("content").asText());
                Assertions.assertEquals("tool", json.path("parts").get(1).path("type").asText());
                Assertions.assertEquals("bash", json.path("parts").get(1).path("toolName").asText());
                Assertions.assertEquals("pwd", json.path("parts").get(1).path("input").path("command").asText());
                Assertions.assertTrue(json.path("parts").get(0).path("type").asText().indexOf('.') < 0);
        }

        @Test
        @DisplayName("streaming sessionStatus stays within busy idle domain")
        void streamingSessionStatusStaysWithinBusyIdleDomain() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("idle").build());

                handler.afterConnectionEstablished(session);

                ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
                verify(session, atLeastOnce()).sendMessage(captor.capture());
                String payload = captor.getAllValues().get(0).getPayload();
                var json = objectMapper.readTree(payload);
                Assertions.assertEquals("streaming", json.path("type").asText());
                Assertions.assertEquals("idle", json.path("sessionStatus").asText());
                Assertions.assertNotEquals("retry", json.path("sessionStatus").asText());
        }

        @Test
        @DisplayName("base stream path without userId cookie is rejected")
        void missingUserCookieIsRejected() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", null);

                handler.afterConnectionEstablished(session);

                verify(session).close(any());
        }

        @Test
        @DisplayName("closed subscriber is skipped gracefully")
        void closedSubscriberIsSkipped() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession skillSession = new SkillSession();
                skillSession.setId(42L);
                skillSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(skillSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("idle").build());
                when(session.isOpen()).thenReturn(false);

                handler.afterConnectionEstablished(session);
                handler.pushStreamMessage("42",
                                StreamMessage.builder().type("delta").sessionId("42").content("content").build());

                verify(session, never()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("transport error followed by close does not unsubscribe remaining user stream connection")
        void duplicateCleanupDoesNotDropActiveUserSubscription() throws Exception {
                WebSocketSession first = mockSession("/ws/skill/stream", "userId=10001");
                WebSocketSession second = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("idle").build());

                handler.afterConnectionEstablished(first);
                handler.afterConnectionEstablished(second);

                handler.handleTransportError(first, new RuntimeException("boom"));
                handler.afterConnectionClosed(first, org.springframework.web.socket.CloseStatus.SERVER_ERROR);

                StreamMessage msg = StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DELTA)
                                .sessionId("42")
                                .content("hello")
                                .build();
                handler.pushStreamMessageToUser("10001", msg);

                verify(redisMessageBroker, times(1)).subscribeToUser(eq("10001"), any());
                verify(redisMessageBroker, never()).unsubscribeFromUser("10001");
                verify(second, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("redis user broadcast preserves welinkSessionId when relaying to websocket clients")
        @SuppressWarnings("unchecked")
        void redisBroadcastPreservesWelinkSessionId() throws Exception {
                WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
                SkillSession activeSession = new SkillSession();
                activeSession.setId(42L);
                activeSession.setUserId("10001");
                when(snapshotService.getActiveSessionsForUser("10001")).thenReturn(List.of(activeSession));
                when(snapshotService.buildStreamingState(eq("42"), any(Long.class))).thenReturn(
                                StreamMessage.builder().type(StreamMessage.Types.STREAMING).seq(2L)
                                                .sessionId("42").sessionStatus("idle").build());

                ArgumentCaptor<Consumer<String>> consumerCaptor = ArgumentCaptor
                                .forClass((Class<Consumer<String>>) (Class<?>) Consumer.class);

                handler.afterConnectionEstablished(session);
                verify(redisMessageBroker).subscribeToUser(eq("10001"), consumerCaptor.capture());

                reset(session);
                when(session.isOpen()).thenReturn(true);

                consumerCaptor.getValue()
                                .accept("""
                                                {"sessionId":"42","userId":"10001","message":{"type":"text.delta","content":"hello","welinkSessionId":42}}
                                                """);

                ArgumentCaptor<TextMessage> textCaptor = ArgumentCaptor.forClass(TextMessage.class);
                verify(session).sendMessage(textCaptor.capture());
                var json = objectMapper.readTree(textCaptor.getValue().getPayload());
                Assertions.assertEquals("text.delta", json.path("type").asText());
                Assertions.assertEquals("42", json.path("welinkSessionId").asText());
                Assertions.assertTrue(json.path("seq").asLong() > 0);
        }

        private WebSocketSession mockSession(String uri, String cookieHeader) throws Exception {
                WebSocketSession session = mock(WebSocketSession.class);
                when(session.getUri()).thenReturn(new URI(uri));
                when(session.isOpen()).thenReturn(true);
                when(session.getAttributes()).thenReturn(new HashMap<>());

                HttpHeaders headers = new HttpHeaders();
                if (cookieHeader != null) {
                        headers.put(HttpHeaders.COOKIE, List.of(cookieHeader));
                }
                when(session.getHandshakeHeaders()).thenReturn(headers);
                return session;
        }
}
