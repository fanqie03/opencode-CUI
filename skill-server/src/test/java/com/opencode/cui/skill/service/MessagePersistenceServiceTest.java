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
    @DisplayName("text.done persists part and message content immediately")
    void textDonePersistsImmediately() {
        setupActiveMessage();
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("final answer");

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1")
                .content("final answer")
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("final answer");
        assertThat(captor.getValue().getPartType()).isEqualTo("text");

        verify(messageService).updateMessageContent(11L, "final answer");
        verify(partBufferService, never()).bufferPart(eq(11L), any());
    }

    @Test
    @DisplayName("session.status=idle triggers flush then sync")
    void sessionIdleFlushesAndSyncs() {
        setupActiveMessage();
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("hello");

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .partId("part-1").content("hello")
                .build());

        SkillMessagePart bufferedPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("part-1").seq(1).partType("text").content("hello")
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, bufferedPart));

        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

        verify(partBufferService).prepareFlush(11L);
        verify(partRepository).batchUpsert(List.of(bufferedPart));
        verify(partRepository, times(2)).findConcatenatedTextByMessageId(11L);
        verify(messageService, times(2)).updateMessageContent(11L, "hello");
        verify(messageService).markMessageFinished(11L);
        verify(partBufferService).commitFlush(any(PartBufferService.FlushBatch.class));
        // idle/completed must invalidate the latest-history cache so the next
        // refresh doesn't return the snapshot warmed before the hook ran.
        verify(messageService, atLeastOnce()).scheduleLatestHistoryRefreshAfterCommit(1L);
    }

    @Test
    @DisplayName("step.done stats are persisted and applied immediately")
    void stepDoneStatsPersistedImmediately() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.STEP_DONE)
                .partId("step-1")
                .usage(StreamMessage.UsageInfo.builder()
                        .tokens(Map.of("input", 100, "output", 200))
                        .cost(0.01).reason("end_turn")
                        .build())
                .build());

        verify(partRepository).upsert(any(SkillMessagePart.class));
        verify(messageService).updateMessageStats(eq(11L), eq(100), eq(200), eq(0.01));

        SkillMessagePart stepPart = SkillMessagePart.builder()
                .id(501L).messageId(11L).sessionId(1L)
                .partId("step-1").seq(1).partType("step-finish")
                .tokensIn(100).tokensOut(200).cost(0.01)
                .build();
        when(partBufferService.prepareFlush(11L)).thenReturn(batchOf(11L, stepPart));
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("");

        service.persistIfFinal(1L, StreamMessage.sessionStatus("idle"));

        verify(messageService, times(2)).updateMessageStats(eq(11L), eq(100), eq(200), eq(0.01));
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
    @DisplayName("messageId switch after a new user turn flushes previous active message")
    void messageIdSwitchAfterNewUserTurnFlushesPreviousActiveMessageParts() {
        when(messageService.saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                .thenReturn(SkillMessage.builder()
                        .id(11L).messageId("msg_old").sessionId(1L).seq(1).build())
                .thenReturn(SkillMessage.builder()
                        .id(22L).messageId("msg_new").sessionId(1L).seq(3).build());

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
        when(messageService.findLastUserMessage(1L)).thenReturn(SkillMessage.builder()
                .id(99L).messageId("user-after-old").sessionId(1L).seq(2)
                .role(SkillMessage.Role.USER).build());

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
    @DisplayName("late older upstream message keeps its own message context while a newer turn is active")
    void lateOlderUpstreamMessageKeepsOriginalMessageContext() {
        SkillMessage oldMessage = SkillMessage.builder()
                .id(11L).messageId("msg_old").sessionId(1L).seq(2).build();
        SkillMessage newMessage = SkillMessage.builder()
                .id(22L).messageId("msg_new").sessionId(1L).seq(4).build();
        when(messageService.saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                .thenReturn(oldMessage)
                .thenReturn(newMessage);
        when(partRepository.findConcatenatedTextByMessageId(11L))
                .thenReturn("old")
                .thenReturn("old")
                .thenReturn("old-tail");
        when(partRepository.findConcatenatedTextByMessageId(22L)).thenReturn("new");

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("msg_old")
                .sourceMessageId("msg_old")
                .partId("part-old-1")
                .content("old")
                .build());

        when(messageService.findLastUserMessage(1L)).thenReturn(SkillMessage.builder()
                .id(99L).messageId("user-after-old").sessionId(1L).seq(3)
                .role(SkillMessage.Role.USER).build());

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("msg_new")
                .sourceMessageId("msg_new")
                .partId("part-new-1")
                .content("new")
                .build());

        when(messageService.findBySessionIdAndMessageId(1L, "msg_old")).thenReturn(oldMessage);

        StreamMessage lateOld = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("msg_old")
                .sourceMessageId("msg_old")
                .partId("part-old-2")
                .content("tail")
                .build();
        service.persistIfFinal(1L, lateOld);

        assertThat(lateOld.getMessageId()).isEqualTo("msg_old");
        assertThat(lateOld.getSourceMessageId()).isEqualTo("msg_old");
        assertThat(activeMessageTracker.getActiveMessage(1L).protocolMessageId()).isEqualTo("msg_new");

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository, times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SkillMessagePart::getMessageId)
                .containsExactly(11L, 22L, 11L);
    }

    @Test
    @DisplayName("tool/text upstream messageId switches stay in the same assistant turn")
    void upstreamMessageIdSwitchInsideAssistantTurnKeepsCanonicalActiveMessage() {
        when(messageService.saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                .thenReturn(SkillMessage.builder()
                        .id(11L).messageId("msg_e5a985").sessionId(1L).seq(2).build());
        when(messageService.findLastUserMessage(1L)).thenReturn(SkillMessage.builder()
                .id(10L).messageId("user-1").sessionId(1L).seq(1)
                .role(SkillMessage.Role.USER).build());
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("intro", "introanswer");

        StreamMessage firstText = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("msg_e5a985")
                .sourceMessageId("msg_e5a985")
                .partId("text-1")
                .content("intro")
                .build();
        StreamMessage secondTool = StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .messageId("msg_e5a988")
                .sourceMessageId("msg_e5a988")
                .partId("tool-2")
                .status("error")
                .tool(StreamMessage.ToolInfo.builder()
                        .toolName("webfetch")
                        .toolCallId("call-2")
                        .build())
                .build();
        StreamMessage finalText = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("msg_e5a98a")
                .sourceMessageId("msg_e5a98a")
                .partId("text-2")
                .content("answer")
                .build();

        service.persistIfFinal(1L, firstText);
        service.persistIfFinal(1L, secondTool);
        service.persistIfFinal(1L, finalText);

        verify(messageService, times(1)).saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class));
        verify(messageService, never()).markMessageFinished(11L);
        assertThat(secondTool.getMessageId()).isEqualTo("msg_e5a985");
        assertThat(secondTool.getSourceMessageId()).isEqualTo("msg_e5a988");
        assertThat(finalText.getMessageId()).isEqualTo("msg_e5a985");
        assertThat(finalText.getSourceMessageId()).isEqualTo("msg_e5a98a");

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository, times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(part -> part.getMessageId().equals(11L));
    }

    @Test
    @DisplayName("temporary generated message id is adopted when upstream message id arrives")
    void generatedMessageIdAdoptsUpstreamMessageId() {
        when(messageService.saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                .thenReturn(SkillMessage.builder()
                        .id(11L).messageId("msg_1_1").sessionId(1L).seq(1).build());
        when(messageService.findBySessionIdAndMessageId(1L, "opencode-msg-1")).thenReturn(null);
        when(messageService.updateProtocolMessageId(11L, "opencode-msg-1")).thenReturn(true);
        when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("answer");

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.QUESTION)
                .partId("question-1")
                .status("running")
                .tool(StreamMessage.ToolInfo.builder()
                        .toolName("question")
                        .toolCallId("call-1")
                        .build())
                .build());

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .messageId("opencode-msg-1")
                .sourceMessageId("opencode-msg-1")
                .partId("text-1")
                .content("answer")
                .build());

        verify(messageService, times(1)).saveMessage(any(com.opencode.cui.skill.model.SaveMessageCommand.class));
        verify(messageService).updateProtocolMessageId(11L, "opencode-msg-1");
        verify(messageService, never()).markMessageFinished(11L);
        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository, times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(part -> part.getMessageId().equals(11L));
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
    @DisplayName("cloud question persists canonical question fields for history recovery")
    void cloudQuestionPersistsCanonicalQuestionFieldsForHistory() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.QUESTION)
                .partId("cloud-question-1")
                .tool(StreamMessage.ToolInfo.builder()
                        .toolCallId("cloud-call-1")
                        .build())
                .questionInfo(StreamMessage.QuestionInfo.builder()
                        .header("Choose")
                        .question("Pick one")
                        .options(List.of("A", "B"))
                        .questions(List.of(StreamMessage.QuestionItem.builder()
                                .header("Choose")
                                .question("Pick one")
                                .options(List.of("A", "B"))
                                .build()))
                        .questionId("cloud-question-request-1")
                        .build())
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        SkillMessagePart persisted = captor.getValue();
        assertThat(persisted.getPartType()).isEqualTo("tool");
        assertThat(persisted.getToolName()).isEqualTo("question");
        assertThat(persisted.getToolCallId()).isEqualTo("cloud-call-1");
        assertThat(persisted.getToolStatus()).isEqualTo("running");
        assertThat(persisted.getToolInput()).contains("Pick one");
        assertThat(persisted.getToolInput()).contains("cloud-question-request-1");

        var historyPart = ProtocolMessageMapper.toProtocolPart(persisted, new ObjectMapper());
        assertThat(historyPart).isNotNull();
        assertThat(historyPart.getType()).isEqualTo("question");
        assertThat(historyPart.getToolName()).isEqualTo("question");
        assertThat(historyPart.getToolCallId()).isEqualTo("cloud-call-1");
        assertThat(historyPart.getStatus()).isEqualTo("running");
        assertThat(historyPart.getQuestionId()).isEqualTo("cloud-question-request-1");
        assertThat(historyPart.getHeader()).isEqualTo("Choose");
        assertThat(historyPart.getQuestion()).isEqualTo("Pick one");
        assertThat(historyPart.getOptions()).containsExactly("A", "B");
    }

    @Test
    @DisplayName("question tool.update merges into existing question part instead of creating duplicate")
    void questionToolUpdateMergesIntoExistingQuestionPart() {
        setupActiveMessage();
        SkillMessagePart otherQuestion = SkillMessagePart.builder()
                .id(20L)
                .messageId(11L)
                .sessionId(1L)
                .partId("other-question-part")
                .seq(0)
                .partType("tool")
                .toolName("question")
                .toolCallId("other-question-id")
                .toolStatus("running")
                .toolInput("{\"question\":\"你想喝什么？\",\"options\":[\"茶\",\"咖啡\"]}")
                .build();
        SkillMessagePart existingQuestion = SkillMessagePart.builder()
                .id(21L)
                .messageId(11L)
                .sessionId(1L)
                .partId("question-part")
                .seq(1)
                .partType("tool")
                .toolName("question")
                .toolCallId("question-id")
                .toolStatus("running")
                .toolInput("""
                        {"question":"你喜欢吃什么类型的食物？","options":["中餐","西餐","重口味"],"questionId":"question-id"}
                        """)
                .build();
        when(partRepository.findByMessageId(11L)).thenReturn(List.of(otherQuestion, existingQuestion));

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .partId("tool-lifecycle-part")
                .status("completed")
                .tool(StreamMessage.ToolInfo.builder()
                        .toolName("question")
                        .toolCallId("tool-call-id")
                        .output("User has answered your questions: \"你喜欢吃什么类型的食物？\"=\"重口味\". You can now continue with the user's answers in mind.")
                        .build())
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        SkillMessagePart updated = captor.getValue();
        assertThat(updated.getPartId()).isEqualTo("question-part");
        assertThat(updated.getToolStatus()).isEqualTo("completed");
        assertThat(updated.getToolOutput()).isEqualTo("重口味");
        assertThat(updated.getToolCallId()).isEqualTo("question-id");
        verify(partRepository, never()).upsert(argThat(part -> "tool-lifecycle-part".equals(part.getPartId())));
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
    @DisplayName("cloud permission.ask defaults to pending status for history recovery")
    void cloudPermissionAskDefaultsPendingStatusForHistory() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_ASK)
                .partId("cloud-perm-1")
                .title("Approve write?")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId("cloud-perm-call-1")
                        .permType("write")
                        .metadata(Map.of("path", "/tmp/a"))
                        .build())
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        SkillMessagePart persisted = captor.getValue();
        assertThat(persisted.getPartType()).isEqualTo("permission");
        assertThat(persisted.getToolCallId()).isEqualTo("cloud-perm-call-1");
        assertThat(persisted.getToolStatus()).isEqualTo("pending");

        var historyPart = ProtocolMessageMapper.toProtocolPart(persisted, new ObjectMapper());
        assertThat(historyPart).isNotNull();
        assertThat(historyPart.getType()).isEqualTo("permission");
        assertThat(historyPart.getPermissionId()).isEqualTo("cloud-perm-call-1");
        assertThat(historyPart.getStatus()).isEqualTo("pending");
        assertThat(historyPart.getContent()).isEqualTo("Approve write?");
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
    @DisplayName("synthesize uses tool.toolCallId to find the pending permission, not the latest one")
    void synthesizePermissionReplyMatchesByToolCallId() {
        SkillMessagePart pending = SkillMessagePart.builder()
                .id(2L).messageId(11L).sessionId(1L)
                .partId("part-A").seq(1).partType("permission")
                .toolCallId("perm-A").toolName("write")
                .content("approve write?")
                .build();
        when(partRepository.findPendingPermissionPartByToolCallId(1L, "perm-A")).thenReturn(pending);
        when(messageService.findById(11L)).thenReturn(SkillMessage.builder()
                .id(11L).messageId("msg_1_1").build());

        StreamMessage toolUpdate = StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .status("completed")
                .tool(StreamMessage.ToolInfo.builder()
                        .toolName("write")
                        .toolCallId("perm-A")
                        .output("ok")
                        .build())
                .build();

        StreamMessage reply = service.synthesizePermissionReplyFromToolOutcome(1L, toolUpdate);

        assertThat(reply).isNotNull();
        assertThat(reply.getType()).isEqualTo(StreamMessage.Types.PERMISSION_REPLY);
        assertThat(reply.getPartId()).isEqualTo("part-A");
        assertThat(reply.getPermission().getPermissionId()).isEqualTo("perm-A");
        assertThat(reply.getPermission().getResponse()).isEqualTo("once");
        // 关键：未来无论 buffer/DB 里还有多少更晚的 pending permission，都不会被命中。
        verify(partRepository).findPendingPermissionPartByToolCallId(1L, "perm-A");
    }

    @Test
    @DisplayName("synthesize returns null when tool.update lacks toolCallId")
    void synthesizeReturnsNullWhenNoToolCallId() {
        StreamMessage toolUpdate = StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .status("completed")
                .tool(StreamMessage.ToolInfo.builder().toolName("write").output("ok").build())
                .build();

        StreamMessage reply = service.synthesizePermissionReplyFromToolOutcome(1L, toolUpdate);

        assertThat(reply).isNull();
        verify(partRepository, never()).findPendingPermissionPartByToolCallId(anyLong(), any());
    }

    @Test
    @DisplayName("synthesize returns null when no permission part matches the toolCallId")
    void synthesizeReturnsNullWhenNoPendingMatchesToolCallId() {
        when(partRepository.findPendingPermissionPartByToolCallId(1L, "perm-A")).thenReturn(null);

        StreamMessage toolUpdate = StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .status("error")
                .error("Error: The user rejected permission to use this specific tool call.")
                .tool(StreamMessage.ToolInfo.builder().toolName("bash").toolCallId("perm-A").build())
                .build();

        StreamMessage reply = service.synthesizePermissionReplyFromToolOutcome(1L, toolUpdate);

        assertThat(reply).isNull();
    }

    @Test
    @DisplayName("synthesize returns null when tool error is not a permission rejection")
    void synthesizePlainToolErrorIgnored() {
        StreamMessage toolUpdate = StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .status("error")
                .error("Error: command failed")
                .tool(StreamMessage.ToolInfo.builder().toolName("bash").toolCallId("perm-A").build())
                .build();

        StreamMessage reply = service.synthesizePermissionReplyFromToolOutcome(1L, toolUpdate);

        assertThat(reply).isNull();
        verify(partRepository, never()).findPendingPermissionPartByToolCallId(anyLong(), any());
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

    @Test
    @DisplayName("cloud permission.reply defaults to completed status for history recovery")
    void cloudPermissionReplyDefaultsCompletedStatusForHistory() {
        setupActiveMessage();

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .partId("cloud-perm-1")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId("cloud-perm-call-1")
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

        var historyPart = ProtocolMessageMapper.toProtocolPart(persisted, new ObjectMapper());
        assertThat(historyPart).isNotNull();
        assertThat(historyPart.getType()).isEqualTo("permission");
        assertThat(historyPart.getPermissionId()).isEqualTo("cloud-perm-call-1");
        assertThat(historyPart.getStatus()).isEqualTo("completed");
        assertThat(historyPart.getResponse()).isEqualTo("once");
    }

    @Test
    @DisplayName("recordQuestionReply updates the pending question by toolCallId when partId is absent")
    void recordQuestionReplyUpdatesPendingQuestionByToolCallIdWhenPartIdAbsent() {
        SkillMessagePart pending = SkillMessagePart.builder()
                .id(21L)
                .messageId(11L)
                .sessionId(1L)
                .partId("cloud-question-part")
                .seq(2)
                .partType("tool")
                .toolName("question")
                .toolCallId("call-q")
                .toolStatus("running")
                .toolInput("{\"question\":\"Pick one\"}")
                .build();
        when(partRepository.findPendingQuestionPartByToolCallId(1L, "call-q")).thenReturn(pending);

        boolean updated = service.recordQuestionReply(1L, "call-q", "A", null);

        assertThat(updated).isTrue();
        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        assertThat(captor.getValue().getPartId()).isEqualTo("cloud-question-part");
        assertThat(captor.getValue().getToolStatus()).isEqualTo("completed");
        assertThat(captor.getValue().getToolOutput()).isEqualTo("A");
        verify(messageService).scheduleLatestHistoryRefreshAfterCommit(1L);
    }

    @Test
    @DisplayName("recordPermissionReply updates a pending permission by toolCallId when partId differs")
    void recordPermissionReplyUpdatesPendingPermissionByToolCallIdWhenPartIdDiffers() {
        SkillMessagePart pending = SkillMessagePart.builder()
                .id(22L)
                .messageId(11L)
                .sessionId(1L)
                .partId("cloud-permission-part")
                .seq(3)
                .partType("permission")
                .toolCallId("perm-123")
                .toolName("write")
                .toolStatus("pending")
                .build();
        when(partRepository.findByPartId(1L, "perm-123")).thenReturn(null);
        when(partRepository.findPendingPermissionPartByToolCallId(1L, "perm-123")).thenReturn(pending);

        boolean updated = service.recordPermissionReply(1L, "perm-123", "once");

        assertThat(updated).isTrue();
        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        assertThat(captor.getValue().getPartId()).isEqualTo("cloud-permission-part");
        assertThat(captor.getValue().getToolStatus()).isEqualTo("completed");
        assertThat(captor.getValue().getToolOutput()).isEqualTo("once");
        verify(messageService).scheduleLatestHistoryRefreshAfterCommit(1L);
    }

    @Test
    @DisplayName("completed question event updates the original question part by toolCallId")
    void completedQuestionEventUpdatesOriginalQuestionPartByToolCallId() {
        setupActiveMessage();
        SkillMessagePart pending = SkillMessagePart.builder()
                .id(23L)
                .messageId(11L)
                .sessionId(1L)
                .partId("original-question-part")
                .seq(4)
                .partType("tool")
                .toolName("question")
                .toolCallId("call-q")
                .toolStatus("running")
                .toolInput("{\"question\":\"Pick one\"}")
                .build();
        when(partRepository.findByPartId(1L, "completed-question-part")).thenReturn(null);
        when(partRepository.findPendingQuestionPartByToolCallId(1L, "call-q")).thenReturn(pending);

        service.persistIfFinal(1L, StreamMessage.builder()
                .type(StreamMessage.Types.QUESTION)
                .partId("completed-question-part")
                .status("completed")
                .tool(StreamMessage.ToolInfo.builder()
                        .toolCallId("call-q")
                        .output("B")
                        .build())
                .build());

        ArgumentCaptor<SkillMessagePart> captor = ArgumentCaptor.forClass(SkillMessagePart.class);
        verify(partRepository).upsert(captor.capture());
        assertThat(captor.getValue().getPartId()).isEqualTo("original-question-part");
        assertThat(captor.getValue().getToolStatus()).isEqualTo("completed");
        assertThat(captor.getValue().getToolOutput()).isEqualTo("B");
    }
}
