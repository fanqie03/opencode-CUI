package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** MessagePersistenceService 单元测试：验证消息持久化、助手轮次终结等逻辑。 */
class MessagePersistenceServiceTest {

        @Mock
        private SkillMessageService messageService;
        @Mock
        private SkillMessagePartRepository partRepository;
        @Mock
        private SnowflakeIdGenerator snowflakeIdGenerator;
        @Mock
        private MessageHistoryCacheService messageHistoryCacheService;

        private ActiveMessageTracker activeMessageTracker;
        private MessagePersistenceService service;

        @BeforeEach
        void setUp() {
                lenient().when(snowflakeIdGenerator.nextId()).thenReturn(501L, 502L, 503L, 504L);
                activeMessageTracker = new ActiveMessageTracker(messageService);
                service = new MessagePersistenceService(messageService, partRepository, new ObjectMapper(),
                                snowflakeIdGenerator, activeMessageTracker);
        }

        @Test
        @DisplayName("finalizeActiveAssistantTurn closes dangling assistant message")
        void finalizeActiveAssistantTurnClosesDanglingAssistantMessage() {
                when(messageService.saveMessage(
                                any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                                .thenReturn(SkillMessage.builder()
                                                .id(11L)
                                                .messageId("msg_1_1")
                                                .sessionId(1L)
                                                .seq(1)
                                                .build());
                when(partRepository.findMaxSeqByMessageId(11L)).thenReturn(0);
                when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("hello");

                service.persistIfFinal(1L, StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DONE)
                                .partId("part-1")
                                .content("hello")
                                .build());

                service.finalizeActiveAssistantTurn(1L);

                verify(messageService).markMessageFinished(11L);
                verify(messageService, times(2)).scheduleLatestHistoryRefreshAfterCommit(1L);
        }

        @Test
        @DisplayName("text parts refresh skill_message content")
        void textPartsRefreshMessageContent() {
                when(messageService.saveMessage(
                                any(com.opencode.cui.skill.model.SaveMessageCommand.class)))
                                .thenReturn(SkillMessage.builder()
                                                .id(11L)
                                                .messageId("msg_1_1")
                                                .sessionId(1L)
                                                .seq(1)
                                                .build());
                when(partRepository.findMaxSeqByMessageId(11L)).thenReturn(0);
                when(partRepository.findConcatenatedTextByMessageId(11L)).thenReturn("final answer");

                service.persistIfFinal(1L, StreamMessage.builder()
                                .type(StreamMessage.Types.TEXT_DONE)
                                .partId("part-1")
                                .content("final answer")
                                .build());

                verify(messageService).updateMessageContent(11L, "final answer");
                verify(messageService).scheduleLatestHistoryRefreshAfterCommit(1L);
        }

        @Test
        @DisplayName("finalizeActiveAssistantTurn is a no-op when no assistant turn is open")
        void finalizeActiveAssistantTurnNoopWhenNoAssistantTurnOpen() {
                service.finalizeActiveAssistantTurn(1L);

                verify(messageService, never()).markMessageFinished(anyLong());
        }
}
