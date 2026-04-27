package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagePersistenceServiceTest {

    @Mock
    private SkillMessageService messageService;
    @Mock
    private SkillMessagePartRepository partRepository;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock
    private SkillSessionService skillSessionService;
    @Mock
    private PartBufferService partBufferService;

    private ActiveMessageTracker activeMessageTracker;
    private MessagePersistenceService service;

    @BeforeEach
    void setUp() {
        lenient().when(snowflakeIdGenerator.nextId()).thenReturn(501L, 502L, 503L, 504L);
        lenient().when(partBufferService.nextSeq(anyLong())).thenReturn(1, 2, 3, 4);
        // Default: prepareFlush returns an empty batch — tests with buffered parts override.
        lenient().when(partBufferService.prepareFlush(anyLong()))
                .thenAnswer(inv -> new PartBufferService.FlushBatch(
                        inv.getArgument(0), null, java.util.Collections.emptyList()));
        activeMessageTracker = new ActiveMessageTracker(messageService);
        service = new MessagePersistenceService(messageService, partRepository, new ObjectMapper(),
                snowflakeIdGenerator, activeMessageTracker, skillSessionService, partBufferService);
    }

    private PartBufferService.FlushBatch batchOf(long dbId, SkillMessagePart... parts) {
        return new PartBufferService.FlushBatch(dbId,
                "ss:part-buf:" + dbId + ":flush:test", List.of(parts));
    }

    private void setupActiveMessage() {
        when(messageService.saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                .thenReturn(SkillMessage.builder()
                        .id(11L).messageId("msg_1_1").sessionId(1L).seq(1)
                        .build());
    }

    @Test
    @DisplayName("text.done buffers part to Redis instead of direct DB upsert")
    void textDoneBuffersToRedis() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1")
                .content("final answer")
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partBufferService).bufferPart(eq(11L), captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("final answer");
        assertThat(captor.getValue().getPartType()).isEqualTo("text");

        verify(partRepository, never()).upsert(any());
    }

    @Test
    @DisplayName("session.status=idle triggers flush then sync")
    void sessionIdleFlushesAndSyncs() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-1").seq(1).partType("text").content("hello")
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, bufferedPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("hello");

        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

        verify(partBufferService).prepareFlush(11L);
        verify(partRepository).batchUpsert(List.of(bufferedPart));
        verify(partRepository).findConcatenatedTextByMessageId(11L);
        verify(messageService).updateMessageContent(11L, "hello");
        verify(messageService).markMessageFinished(11L);
        verify(partBufferService).commitFlush(any(PartBufferService.FlushBatch.class));
        // idle/completed must invalidate the latest-history cache so the next
        // refresh doesn't return the snapshot warmed before the hook ran.
        verify(messageService, atLeastOnce()).scheduleLatestHistoryRefreshAfterCommit(1L);
    }

    @Test
    @DisplayName("step.done stats are accumulated and applied during flush")
    void stepDoneStatsAccumulatedDuringFlush() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.STEP_DONE)
                .partId("step-1")
                .usage(StreamMessage.UsageInfo.builder()
                        .tokens(Map.of("input", 100, "output", 200))
                        .cost(0.01).reason("end_turn")
                        .build())
                .build());

        verify(messageService, never()).updateMessageStats(anyLong(), any(), any(), any());

        SkillMessagePart stepPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("step-1").seq(1).partType("step-finish")
                .tokensIn(100).tokensOut(200).cost(0.01)
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, stepPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("");

        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

        verify(messageService).updateMessageStats(eq(11L), eq(100), eq(200), eq(0.01));
    }

    @Test
    @DisplayName("finalizeActiveAssistantTurn closes dangling assistant message")
    void finalizeActiveAssistantTurnClosesDanglingAssistantMessage() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        service.finalizeActiveAssistantTurn(1L);

        verify(messageService).markMessageFinished(11L);
        verify(messageService, times(2)).scheduleLatestHistoryRefreshAfterCommit(1L);
    }

    @Test
    @DisplayName("finalizeActiveAssistantTurn flushes buffered parts before marking finished")
    void finalizeActiveAssistantTurnFlushesBufferedParts() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-1").seq(1).partType("text").content("hello")
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, bufferedPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("hello");

        service.finalizeActiveAssistantTurn(1L);

        verify(partBufferService).prepareFlush(11L);
        verify(partRepository).batchUpsert(List.of(bufferedPart));
        verify(messageService).updateMessageContent(11L, "hello");
        verify(messageService).markMessageFinished(11L);
        verify(partBufferService).commitFlush(any(PartBufferService.FlushBatch.class));
    }

    @Test
    @DisplayName("messageId switch flushes buffered parts of previous active message")
    void messageIdSwitchFlushesPreviousActiveMessageParts() {
        when(messageService.saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                .thenReturn(SkillMessage.builder()
                        .id(11L).messageId("msg_old").sessionId(1L).seq(1).build())
                .thenReturn(SkillMessage.builder()
                        .id(22L).messageId("msg_new").sessionId(1L).seq(2).build());

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("msg_old")
                .partId("part-old-1").content("old")
                .build());

        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-old-1").seq(1).partType("text").content("old")
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, bufferedPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("old");

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("msg_new")
                .partId("part-new-1").content("new")
                .build());

        verify(partBufferService).prepareFlush(11L);
        verify(partRepository).batchUpsert(List.of(bufferedPart));
        verify(messageService).updateMessageContent(11L, "old");
        verify(messageService).markMessageFinished(11L);
        verify(partBufferService).commitFlush(any(PartBufferService.FlushBatch.class));
    }

    @Test
    @DisplayName("finalizeActiveAssistantTurn is a no-op when no assistant turn is open")
    void finalizeActiveAssistantTurnNoopWhenNoAssistantTurnOpen() {
        service.finalizeActiveAssistantTurn(1L);
        verify(messageService, never()).markMessageFinished(anyLong());
    }

    @Test
    @DisplayName("question parts persist immediately so a refresh during pending interaction can recover them")
    void questionPartsPersistImmediately() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.QUESTION)
                .partId("question-1")
                .status("running")
                .tool(StreamMessage.ToolInfo.builder()
                        .toolName("question")
                        .toolCallId("tool-call-1")
                        .input(Map.of(
                                "questions", List.of(Map.of(
                                        "header", "Implementation",
                                        "question", "Use option A?",
                                        "options", List.of("A", "B")))))
                        .build())
                .questionInfo(StreamMessage.QuestionInfo.builder()
                        .header("Implementation")
                        .question("Use option A?")
                        .build())
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        SkillMessagePart persisted = captor.getValue();
        assertThat(persisted.getPartType()).isEqualTo("tool");
        assertThat(persisted.getToolName()).isEqualTo("question");
        assertThat(persisted.getToolCallId()).isEqualTo("tool-call-1");
        assertThat(persisted.getToolStatus()).isEqualTo("running");
        assertThat(persisted.getToolInput()).contains("Use option A?");
        verify(partBufferService, never()).bufferPart(anyLong(), any());
    }

    @Test
    @DisplayName("permission_ask persists immediately so a refresh during pending interaction can recover it")
    void permissionAskPersistsImmediately() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_ASK)
                .partId("perm-1")
                .status("pending")
                .title("Approve writing to /etc/hosts?")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId("perm-call-1")
                        .permType("write")
                        .build())
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        SkillMessagePart persisted = captor.getValue();
        assertThat(persisted.getPartType()).isEqualTo("permission");
        assertThat(persisted.getToolCallId()).isEqualTo("perm-call-1");
        assertThat(persisted.getToolStatus()).isEqualTo("pending");
        verify(partBufferService, never()).bufferPart(anyLong(), any());
    }

    @Test
    @DisplayName("finalize: batchUpsert failure rolls back the buffer and restores active so retry can pick it up")
    void finalizeBatchUpsertFailureRollsBackAndRestoresActive() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-1").seq(1).partType("text").content("hello")
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, bufferedPart));
        doThrow(new RuntimeException("mysql down")).when(partRepository).batchUpsert(List.of(bufferedPart));

        try {
            service.finalizeActiveAssistantTurn(1L);
            org.assertj.core.api.Assertions.fail("expected RuntimeException to propagate");
        } catch (RuntimeException expected) {
            assertThat(expected).hasMessageContaining("mysql down");
        }

        verify(partBufferService).rollbackFlush(any(PartBufferService.FlushBatch.class));
        verify(partBufferService, never()).commitFlush(any());
        verify(messageService, never()).markMessageFinished(11L);
        // Active ref must be restored so a retry sees the same in-flight message.
        assertThat(activeMessageTracker.getActiveMessage(1L)).isNotNull();
        assertThat(activeMessageTracker.getActiveMessage(1L).dbId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("finalize inside a tx: afterCompletion rollback restores buffer and active ref")
    void finalizeAfterCompletionRollbackRestoresBufferAndActive() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-1").seq(1).partType("text").content("hello")
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, bufferedPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("hello");

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.finalizeActiveAssistantTurn(1L);

            // Hook ran inside synchronized tx, registered an afterCommit/afterCompletion.
            verify(partBufferService, never()).commitFlush(any());
            verify(partBufferService, never()).rollbackFlush(any());

            // Simulate tx rollback.
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(partBufferService).rollbackFlush(any(PartBufferService.FlushBatch.class));
        // Active ref must be restored so a retry sees the same in-flight message.
        assertThat(activeMessageTracker.getActiveMessage(1L)).isNotNull();
        assertThat(activeMessageTracker.getActiveMessage(1L).dbId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("applyMessageContextIfPresent never creates a placeholder or finalizes")
    void applyContextIfPresentIsReadOnlyWhenNoActive() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        service.applyMessageContextIfPresent(1L, msg);

        // No active in tracker → must NOT save a new placeholder, must NOT touch DB.
        verify(messageService, never()).saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class));
        verify(partBufferService, never()).prepareFlush(anyLong());
        verify(partRepository, never()).batchUpsert(any());
    }

    @Test
    @DisplayName("applyMessageContextIfPresent applies messageId/seq/role from existing active")
    void applyContextIfPresentAppliesActiveContext() {
        setupActiveMessage();
        // Seed an active by triggering a buffered persist first.
        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("seed").content("seed")
                .build());
        clearInvocations(messageService, partBufferService, partRepository);

        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        service.applyMessageContextIfPresent(1L, msg);

        assertThat(msg.getMessageId()).isEqualTo("msg_1_1");
        assertThat(msg.getMessageSeq()).isEqualTo(1);
        // Critical: read-only path — no save, no flush, no upsert.
        verify(messageService, never()).saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class));
        verify(partBufferService, never()).prepareFlush(anyLong());
    }

    @Test
    @DisplayName("permission_reply with active context persists immediately to overwrite status/response")
    void permissionReplyPersistsImmediatelyWhenActiveExists() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .partId("perm-1")
                .status("completed")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId("perm-call-1")
                        .permType("write")
                        .response("once")
                        .build())
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        SkillMessagePart persisted = captor.getValue();
        assertThat(persisted.getPartType()).isEqualTo("permission");
        assertThat(persisted.getToolStatus()).isEqualTo("completed");
        assertThat(persisted.getToolOutput()).isEqualTo("once");
        verify(partBufferService, never()).bufferPart(anyLong(), any());
    }
}
