package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillStreamHandlerTest {

    private SkillStreamHandler handler;
    private StreamBufferService bufferService;
    private SkillSessionService sessionService;
    private SkillMessageService messageService;
    private SkillMessagePartRepository partRepository;
    private RedisMessageBroker redisMessageBroker;

    @BeforeEach
    void setUp() {
        bufferService = mock(StreamBufferService.class);
        sessionService = mock(SkillSessionService.class);
        messageService = mock(SkillMessageService.class);
        partRepository = mock(SkillMessagePartRepository.class);
        redisMessageBroker = mock(RedisMessageBroker.class);
        handler = new SkillStreamHandler(
                new ObjectMapper(),
                bufferService,
                sessionService,
                messageService,
                partRepository,
                redisMessageBroker);
    }

    @Test
    @DisplayName("protocol /ws/skill/stream connection sends snapshot and receives live updates")
    void protocolPathRegistersUserSubscriber() throws Exception {
        WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
        SkillSession activeSession = new SkillSession();
        activeSession.setId(42L);
        activeSession.setUserId("10001");
        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of());
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());

        handler.afterConnectionEstablished(session);
        handler.pushToSession("42", "delta", "hello");

        verify(session, times(3)).sendMessage(any(TextMessage.class));
        verify(redisMessageBroker).subscribeToUser(eq("10001"), any());
    }

    @Test
    @DisplayName("snapshot payload uses protocol-shaped history messages")
    void snapshotPayloadUsesProtocolShape() throws Exception {
        WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
        SkillSession activeSession = new SkillSession();
        activeSession.setId(42L);
        activeSession.setUserId("10001");
        SkillMessage message = SkillMessage.builder()
                .id(10L)
                .sessionId(42L)
                .messageId("msg_42_1")
                .messageSeq(1)
                .role(SkillMessage.Role.ASSISTANT)
                .content("Done")
                .contentType(SkillMessage.ContentType.MARKDOWN)
                .createdAt(LocalDateTime.of(2026, 3, 8, 12, 0))
                .build();
        SkillMessagePart part = SkillMessagePart.builder()
                .messageId(10L)
                .partId("part-1")
                .seq(1)
                .partType("tool")
                .toolName("bash")
                .toolCallId("call-1")
                .toolStatus("completed")
                .toolInput("{\"command\":\"pwd\"}")
                .toolOutput("D:/work")
                .toolTitle("Run pwd")
                .build();

        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of(message));
        when(partRepository.findByMessageId(10L)).thenReturn(List.of(part));
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        String payload = captor.getAllValues().get(0).getPayload();
        var json = new ObjectMapper().readTree(payload);
        Assertions.assertEquals("snapshot", json.path("type").asText());
        Assertions.assertTrue(json.path("seq").asLong() > 0);
        Assertions.assertTrue(json.path("welinkSessionId").isTextual());
        Assertions.assertEquals("42", json.path("welinkSessionId").asText());
        Assertions.assertEquals("msg_42_1", json.path("messages").get(0).path("id").asText());
        Assertions.assertTrue(json.path("messages").get(0).path("welinkSessionId").isTextual());
        Assertions.assertEquals("42", json.path("messages").get(0).path("welinkSessionId").asText());
        Assertions.assertEquals("tool", json.path("messages").get(0).path("parts").get(0).path("type").asText());
        Assertions.assertEquals("completed", json.path("messages").get(0).path("parts").get(0).path("status").asText());
        Assertions.assertEquals("pwd",
                json.path("messages").get(0).path("parts").get(0).path("input").path("command").asText());
    }

    @Test
    @DisplayName("snapshot payload extracts nested question fields from tool input")
    void snapshotPayloadExtractsNestedQuestionFields() throws Exception {
        WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
        SkillSession activeSession = new SkillSession();
        activeSession.setId(42L);
        activeSession.setUserId("10001");
        SkillMessage message = SkillMessage.builder()
                .id(11L)
                .sessionId(42L)
                .messageId("msg_42_2")
                .messageSeq(2)
                .role(SkillMessage.Role.ASSISTANT)
                .content("")
                .contentType(SkillMessage.ContentType.MARKDOWN)
                .createdAt(LocalDateTime.of(2026, 3, 8, 12, 1))
                .build();
        SkillMessagePart part = SkillMessagePart.builder()
                .messageId(11L)
                .partId("part-question-1")
                .seq(1)
                .partType("tool")
                .toolName("question")
                .toolCallId("call-question-1")
                .toolStatus("running")
                .toolInput(
                        """
                                {"questions":[{"header":"实现方案","question":"选 A 还是 B？","options":[{"label":"A","description":"只改最小范围"},{"label":"B","description":"做完整重构"}]}]}
                                """)
                .build();

        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of(message));
        when(partRepository.findByMessageId(11L)).thenReturn(List.of(part));
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        String payload = captor.getAllValues().get(0).getPayload();
        var json = new ObjectMapper().readTree(payload);
        var partJson = json.path("messages").get(0).path("parts").get(0);
        Assertions.assertEquals("question", partJson.path("type").asText());
        Assertions.assertEquals("实现方案", partJson.path("header").asText());
        Assertions.assertEquals("选 A 还是 B？", partJson.path("question").asText());
        Assertions.assertEquals("A", partJson.path("options").get(0).asText());
        Assertions.assertEquals("B", partJson.path("options").get(1).asText());
    }

    @Test
    @DisplayName("resume action replays snapshot and streaming state")
    void resumeReplaysCurrentState() throws Exception {
        WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
        SkillSession activeSession = new SkillSession();
        activeSession.setId(42L);
        activeSession.setUserId("10001");
        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of());
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());

        handler.afterConnectionEstablished(session);
        reset(session);
        when(session.getAttributes()).thenReturn(new HashMap<>(java.util.Map.of("userId", "10001")));
        when(session.isOpen()).thenReturn(true);

        handler.handleMessage(session, new TextMessage("{\"action\":\"resume\"}"));

        verify(session, times(2)).sendMessage(any(TextMessage.class));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(captor.capture());
        var objectMapper = new ObjectMapper();
        var first = objectMapper.readTree(captor.getAllValues().get(0).getPayload());
        var second = objectMapper.readTree(captor.getAllValues().get(1).getPayload());
        Assertions.assertEquals("snapshot", first.path("type").asText());
        Assertions.assertEquals("streaming", second.path("type").asText());
    }

    @Test
    @DisplayName("streaming payload exposes aggregated protocol parts instead of raw event types")
    void streamingPayloadUsesAggregatedProtocolParts() throws Exception {
        WebSocketSession session = mockSession("/ws/skill/stream", "userId=10001");
        SkillSession activeSession = new SkillSession();
        activeSession.setId(42L);
        activeSession.setUserId("10001");
        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of());
        when(bufferService.isSessionStreaming("42")).thenReturn(true);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of(
                StreamMessage.builder()
                        .type(StreamMessage.Types.TEXT_DELTA)
                        .sessionId("42")
                        .messageId("msg-stream")
                        .messageSeq(5)
                        .role("assistant")
                        .partId("part-text-1")
                        .partSeq(1)
                        .content("hello")
                        .build(),
                StreamMessage.builder()
                        .type(StreamMessage.Types.TOOL_UPDATE)
                        .sessionId("42")
                        .messageId("msg-stream")
                        .messageSeq(5)
                        .role("assistant")
                        .partId("part-tool-1")
                        .partSeq(2)
                        .toolName("bash")
                        .toolCallId("call-1")
                        .status("running")
                        .input(new ObjectMapper().readTree("{\"command\":\"pwd\"}"))
                        .output("")
                        .build()));

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        String payload = captor.getAllValues().get(1).getPayload();
        var json = new ObjectMapper().readTree(payload);
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
        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of());
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getAllValues().get(1).getPayload();
        var json = new ObjectMapper().readTree(payload);
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
        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(skillSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of());
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());
        when(session.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(session);
        handler.pushToSession("42", "delta", "content");

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
        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of());
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());

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
        when(sessionService.findActiveByUserId("10001")).thenReturn(List.of(activeSession));
        when(messageService.getAllMessages(42L)).thenReturn(List.of());
        when(bufferService.isSessionStreaming("42")).thenReturn(false);
        when(bufferService.getStreamingParts("42")).thenReturn(List.of());

        ArgumentCaptor<Consumer<String>> consumerCaptor = ArgumentCaptor.forClass((Class<Consumer<String>>) (Class<?>) Consumer.class);

        handler.afterConnectionEstablished(session);
        verify(redisMessageBroker).subscribeToUser(eq("10001"), consumerCaptor.capture());

        reset(session);
        when(session.isOpen()).thenReturn(true);

        consumerCaptor.getValue().accept("""
                {"sessionId":"42","userId":"10001","message":{"type":"text.delta","content":"hello","welinkSessionId":42}}
                """);

        ArgumentCaptor<TextMessage> textCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(textCaptor.capture());
        var json = new ObjectMapper().readTree(textCaptor.getValue().getPayload());
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
