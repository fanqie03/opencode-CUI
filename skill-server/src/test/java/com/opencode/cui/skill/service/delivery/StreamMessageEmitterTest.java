package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamMessageEmitterTest {

    @Mock OutboundDeliveryDispatcher dispatcher;
    @Mock RedisMessageBroker redisBroker;
    @Mock StreamBufferService bufferService;
    @Mock MessagePersistenceService persistenceService;
    @Mock SkillSessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StreamMessageEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new StreamMessageEmitter(
                dispatcher, redisBroker, bufferService,
                persistenceService, sessionService, objectMapper);
    }

    // --- enrich semantics ---

    @Test
    void enrich1_welinkSessionId_isOverwrittenByCanonicalSessionId() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .welinkSessionId("business-123")
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", "user-a", msg);

        // canonical overwrite: "business-123" 被替换为 "101"
        assertEquals("101", msg.getWelinkSessionId());
    }

    @Test
    void enrich2_welinkSessionId_isFilledWhenBlank() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", "user-a", msg);

        assertEquals("101", msg.getWelinkSessionId());
    }

    @Test
    void enrich3_emittedAt_excludedTypesKeepNull() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.ERROR)      // 在白名单内
                .error("oops")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertNull(msg.getEmittedAt());
    }

    @Test
    void enrich4_emittedAt_nonExcludedAndBlank_filled() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)  // 非白名单
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertNotNull(msg.getEmittedAt());
        assertFalse(msg.getEmittedAt().isBlank());
    }

    @Test
    void enrich5_emittedAt_alreadySet_preserved() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .emittedAt("2026-01-01T00:00:00Z")
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertEquals("2026-01-01T00:00:00Z", msg.getEmittedAt());
    }

    @Test
    void enrich6_userRole_noMessageContextCall() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.MESSAGE_USER)
                .role("user")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        verifyNoInteractions(persistenceService);
    }

    @Test
    void enrich7_assistantRoleNumericSessionId_applyContextIfPresentCalled() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        // Outbound enrich must use the read-only context apply, never trigger finalize.
        verify(persistenceService).applyMessageContextIfPresent(eq(101L), eq(msg));
        verify(persistenceService, never()).prepareMessageContext(anyLong(), any());
    }

    @Test
    void enrich8_assistantRoleNonNumericSessionId_noMessageContextCall() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "not-numeric", null, msg);

        verifyNoInteractions(persistenceService);
    }

    @Test
    void enrich9_repeatedEmit_stableFields() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);
        String firstEmittedAt = msg.getEmittedAt();

        emitter.emitToSession(session, "101", null, msg);

        assertEquals(firstEmittedAt, msg.getEmittedAt(), "emittedAt should not be rewritten");
        assertEquals("101", msg.getWelinkSessionId());
        assertEquals("101", msg.getSessionId());
        // applyMessageContextIfPresent 调 2 次（read-only，重复调对可观察状态无影响）
        verify(persistenceService, times(2)).applyMessageContextIfPresent(eq(101L), eq(msg));
    }

    // --- emitToSession ---

    @Test
    void session1_dispatcherDeliverCalled() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).role("assistant").build();

        emitter.emitToSession(session, "101", "user-a", msg);

        verify(dispatcher).deliver(eq(session), eq("101"), eq("user-a"), eq(msg));
    }

    @Test
    void session2_emitToSession_noRedisInteraction() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).role("assistant").build();

        emitter.emitToSession(session, "101", "user-a", msg);

        verifyNoInteractions(redisBroker);
    }

    @Test
    void session3_dispatcherThrows_emitterDoesNotSwallow() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).role("assistant").build();
        doThrow(new RuntimeException("dispatcher boom"))
                .when(dispatcher).deliver(any(), any(), any(), any());

        // emitter 层不新增 try-catch，异常应冒出（仅行为断言，非合约承诺）
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> emitter.emitToSession(session, "101", "user-a", msg));
        assertEquals("dispatcher boom", ex.getMessage());
    }

    // --- emitToClient ---

    @Test
    void client1_userIdHintPresent_publishToUserCalledOnce() throws Exception {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();

        emitter.emitToClient("101", "user-a", msg);

        org.mockito.ArgumentCaptor<String> payloadCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisBroker).publishToUser(eq("user-a"), payloadCap.capture());
        var envelope = objectMapper.readTree(payloadCap.getValue());
        assertEquals("101", envelope.path("sessionId").asText());
        assertEquals("user-a", envelope.path("userId").asText());
        assertEquals("session.status", envelope.path("message").path("type").asText());
    }

    @Test
    void client2_hintNullAndSessionFound_usesSessionUserId() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();
        SkillSession s = mock(SkillSession.class);
        when(s.getUserId()).thenReturn("user-from-session");
        when(sessionService.findByIdSafe(101L)).thenReturn(s);

        emitter.emitToClient("101", null, msg);

        verify(redisBroker).publishToUser(eq("user-from-session"), anyString());
    }

    @Test
    void client3_hintNullAndSessionNull_publishToUserNotCalled() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();
        when(sessionService.findByIdSafe(any())).thenReturn(null);

        emitter.emitToClient("101", null, msg);

        verifyNoInteractions(redisBroker);
    }

    @Test
    void client4_publishThrows_emitterSwallowsException() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();
        doThrow(new RuntimeException("redis down"))
                .when(redisBroker).publishToUser(anyString(), anyString());

        // 不应抛
        assertDoesNotThrow(() -> emitter.emitToClient("101", "user-a", msg));
    }

    // --- emitToClientWithBuffer ---

    @Test
    void buffer1_normalCase_publishAndAccumulate() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY).build();
        SkillSession s = mock(SkillSession.class);
        when(s.getUserId()).thenReturn("user-a");
        when(sessionService.findByIdSafe(101L)).thenReturn(s);

        emitter.emitToClientWithBuffer("101", msg);

        verify(redisBroker).publishToUser(eq("user-a"), anyString());
        verify(bufferService).accumulate(eq("101"), eq(msg));
    }

    @Test
    void buffer2_publishThrows_bufferStillAccumulates() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY).build();
        SkillSession s = mock(SkillSession.class);
        when(s.getUserId()).thenReturn("user-a");
        when(sessionService.findByIdSafe(101L)).thenReturn(s);
        doThrow(new RuntimeException("redis down"))
                .when(redisBroker).publishToUser(anyString(), anyString());

        emitter.emitToClientWithBuffer("101", msg);

        // 沿袭原 publishProtocolMessage 语义：broadcast 吞异常 → buffer 仍执行
        verify(bufferService).accumulate(eq("101"), eq(msg));
    }

    // --- null-input boundary ---

    @Test
    void boundary1_nullInputs_noOpAllMethods() {
        SkillSession session = mock(SkillSession.class);

        assertDoesNotThrow(() -> {
            emitter.emitToSession(session, null, null, null);
            emitter.emitToClient(null, null, null);
            emitter.emitToClientWithBuffer(null, null);
        });

        verifyNoInteractions(dispatcher);
        verifyNoInteractions(redisBroker);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(persistenceService);
    }
}
