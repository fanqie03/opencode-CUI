package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.MessageHistoryResult;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** SkillMessageService 单元测试：验证消息保存、历史查询等逻辑。 */
class SkillMessageServiceTest {

    @Mock
    private SkillMessageRepository messageRepository;
    @Mock
    private SkillMessagePartRepository partRepository;
    @Mock
    private SkillSessionService sessionService;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private MessageHistoryCacheService messageHistoryCacheService;
    @Mock
    private Executor messageHistoryRefreshExecutor;

    private SkillMessageService service;

    @BeforeEach
    void setUp() {
        lenient().when(snowflakeIdGenerator.nextId()).thenReturn(100L, 101L, 102L, 103L, 104L, 105L);
        lenient().when(messageHistoryCacheService.getWarmSizes()).thenReturn(List.of(50));
        lenient().when(messageRepository.findLatestBySessionId(anyLong(), anyInt())).thenReturn(List.of());
        lenient().when(partRepository.findByMessageIds(anyList())).thenReturn(List.of());
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(messageHistoryRefreshExecutor).execute(any(Runnable.class));
        service = new SkillMessageService(messageRepository, partRepository,
                sessionService, snowflakeIdGenerator, new ObjectMapper(), messageHistoryCacheService,
                messageHistoryRefreshExecutor);
    }

    @Test
    @DisplayName("saveUserMessage creates message with USER role")
    void saveUserMessageCreatesUserRole() {
        when(messageRepository.findMaxSeqBySessionId(eq(1L))).thenReturn(0);

        SkillMessage msg = service.saveUserMessage(1L, "Hello");
        assertNotNull(msg);
        assertEquals(100L, msg.getId());
        assertEquals(SkillMessage.Role.USER, msg.getRole());
        assertEquals("Hello", msg.getContent());
        assertEquals(1, msg.getSeq());
        verify(messageRepository).insert(any(SkillMessage.class));
        // touchSession is deferred to session idle (via MessagePersistenceService)
        verify(sessionService, never()).touchSession(anyLong());
        verify(messageHistoryCacheService).putLatestHistory(eq(1L), eq(50), any());
    }

    @Test
    @DisplayName("saveAssistantMessage creates message with ASSISTANT role")
    void saveAssistantMessageCreatesAssistantRole() {
        when(messageRepository.findMaxSeqBySessionId(eq(1L))).thenReturn(1);

        SkillMessage msg = service.saveAssistantMessage(1L, "Response", "{\"tokens\":10}");
        assertNotNull(msg);
        assertEquals(100L, msg.getId());
        assertEquals(SkillMessage.Role.ASSISTANT, msg.getRole());
        assertEquals(2, msg.getSeq());
        verify(messageRepository).insert(any(SkillMessage.class));
        verify(messageHistoryCacheService).putLatestHistory(eq(1L), eq(50), any());
    }

    @Test
    @DisplayName("saveSystemMessage creates message with SYSTEM role")
    void saveSystemMessageCreatesSystemRole() {
        when(messageRepository.findMaxSeqBySessionId(eq(1L))).thenReturn(2);

        SkillMessage msg = service.saveSystemMessage(1L, "System info");
        assertNotNull(msg);
        assertEquals(100L, msg.getId());
        assertEquals(SkillMessage.Role.SYSTEM, msg.getRole());
        assertEquals(3, msg.getSeq());
        verify(messageRepository).insert(any(SkillMessage.class));
        verify(messageHistoryCacheService).putLatestHistory(eq(1L), eq(50), any());
    }

    @Test
    @DisplayName("saveToolMessage creates message with TOOL role")
    void saveToolMessageCreatesToolRole() {
        when(messageRepository.findMaxSeqBySessionId(eq(1L))).thenReturn(3);

        SkillMessage msg = service.saveToolMessage(1L, "tool output", null);
        assertNotNull(msg);
        assertEquals(100L, msg.getId());
        assertEquals(SkillMessage.Role.TOOL, msg.getRole());
        assertEquals(4, msg.getSeq());
        verify(messageRepository).insert(any(SkillMessage.class));
        verify(messageHistoryCacheService).putLatestHistory(eq(1L), eq(50), any());
    }

    @Test
    @DisplayName("getMessageHistory returns latest page first")
    void getMessageHistoryReturnsLatestPageFirst() {
        when(messageRepository.countBySessionId(1L)).thenReturn(45L);
        when(messageRepository.findBySessionId(1L, 25, 20)).thenReturn(List.of());

        PageResult<SkillMessage> result = service.getMessageHistory(1L, 0, 20);
        assertNotNull(result);
        assertEquals(45, result.getTotalElements());
        assertEquals(0, result.getNumber());
        assertEquals(20, result.getSize());
        verify(messageRepository).findBySessionId(1L, 25, 20);
    }

    @Test
    @DisplayName("getMessageHistory loads older page when page increases")
    void getMessageHistoryLoadsOlderPage() {
        when(messageRepository.countBySessionId(1L)).thenReturn(45L);
        when(messageRepository.findBySessionId(1L, 5, 20)).thenReturn(List.of());

        PageResult<SkillMessage> result = service.getMessageHistory(1L, 1, 20);
        assertNotNull(result);
        assertEquals(1, result.getNumber());
        verify(messageRepository).findBySessionId(1L, 5, 20);
    }

    @Test
    @DisplayName("getMessageHistory clamps to earliest page when offset underflows")
    void getMessageHistoryClampsToEarliestPage() {
        when(messageRepository.countBySessionId(1L)).thenReturn(45L);
        when(messageRepository.findBySessionId(1L, 0, 20)).thenReturn(List.of());

        PageResult<SkillMessage> result = service.getMessageHistory(1L, 9, 20);
        assertNotNull(result);
        assertEquals(9, result.getNumber());
        verify(messageRepository).findBySessionId(1L, 0, 20);
    }

    @Test
    @DisplayName("getMessageHistory uses fallback size when size is invalid")
    void getMessageHistoryUsesFallbackSizeWhenInvalid() {
        when(messageRepository.countBySessionId(1L)).thenReturn(0L);
        when(messageRepository.findBySessionId(1L, 0, 50)).thenReturn(List.of());

        PageResult<SkillMessage> result = service.getMessageHistory(1L, 0, 0);
        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        assertEquals(0, result.getNumber());
        assertEquals(50, result.getSize());
        verify(messageRepository).findBySessionId(1L, 0, 50);
    }

    @Test
    @DisplayName("getMessageHistoryWithParts batches part queries")
    void getMessageHistoryWithPartsBatchesPartQueries() {
        SkillMessage message1 = SkillMessage.builder()
                .id(101L)
                .sessionId(1L)
                .seq(10)
                .role(SkillMessage.Role.USER)
                .content("hello")
                .contentType(SkillMessage.ContentType.PLAIN)
                .build();
        SkillMessage message2 = SkillMessage.builder()
                .id(102L)
                .sessionId(1L)
                .seq(11)
                .role(SkillMessage.Role.ASSISTANT)
                .content("world")
                .contentType(SkillMessage.ContentType.MARKDOWN)
                .build();
        SkillMessagePart part1 = SkillMessagePart.builder()
                .id(201L)
                .messageId(101L)
                .seq(1)
                .partType("text")
                .content("p1")
                .build();
        SkillMessagePart part2 = SkillMessagePart.builder()
                .id(202L)
                .messageId(102L)
                .seq(1)
                .partType("text")
                .content("p2")
                .build();

        when(messageRepository.findLatestBySessionId(1L, 21)).thenReturn(List.of(message2, message1));
        when(partRepository.findByMessageIds(argThat(ids -> ids != null && Set.copyOf(ids).equals(Set.of(101L, 102L)))))
                .thenReturn(List.of(part1, part2));

        MessageHistoryResult<ProtocolMessageView> result = service.getCursorMessageHistoryWithParts(1L, null, 20);

        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertFalse(Boolean.TRUE.equals(result.getHasMore()));
        assertNull(result.getNextBeforeSeq());
        assertEquals(1, result.getContent().get(0).getParts().size());
        assertEquals("p1", result.getContent().get(0).getParts().get(0).getContent());
        assertEquals("p2", result.getContent().get(1).getParts().get(0).getContent());
        verify(partRepository).findByMessageIds(anyList());
        verify(partRepository, never()).findByMessageId(anyLong());
        verify(messageHistoryCacheService).putLatestHistory(eq(1L), eq(20), any());
    }

    @Test
    @DisplayName("getMessageHistoryWithParts uses cursor and exposes nextBeforeSeq")
    void getMessageHistoryWithPartsUsesCursor() {
        SkillMessage message1 = SkillMessage.builder().id(101L).sessionId(1L).seq(8).build();
        SkillMessage message2 = SkillMessage.builder().id(102L).sessionId(1L).seq(9).build();
        SkillMessage message3 = SkillMessage.builder().id(103L).sessionId(1L).seq(10).build();

        when(messageRepository.findLatestBySessionId(1L, 3)).thenReturn(List.of(message3, message2, message1));
        when(partRepository.findByMessageIds(anyList())).thenReturn(List.of());

        MessageHistoryResult<ProtocolMessageView> result = service.getCursorMessageHistoryWithParts(1L, null, 2);

        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertTrue(Boolean.TRUE.equals(result.getHasMore()));
        assertEquals(9, result.getContent().get(0).getSeq());
        assertEquals(10, result.getContent().get(1).getSeq());
        assertEquals(9, result.getNextBeforeSeq());
    }

    @Test
    @DisplayName("getCursorMessageHistoryWithParts loads messages before specified seq")
    void getCursorMessageHistoryWithPartsLoadsMessagesBeforeSpecifiedSeq() {
        SkillMessage message1 = SkillMessage.builder().id(101L).sessionId(1L).seq(4).build();
        SkillMessage message2 = SkillMessage.builder().id(102L).sessionId(1L).seq(5).build();

        when(messageRepository.findBySessionIdBeforeSeq(1L, 6, 3)).thenReturn(List.of(message2, message1));
        when(partRepository.findByMessageIds(anyList())).thenReturn(List.of());

        MessageHistoryResult<ProtocolMessageView> result = service.getCursorMessageHistoryWithParts(1L, 6, 2);

        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertEquals(4, result.getContent().get(0).getSeq());
        assertEquals(5, result.getContent().get(1).getSeq());
        assertFalse(Boolean.TRUE.equals(result.getHasMore()));
        verify(messageHistoryCacheService, never()).getLatestHistory(anyLong(), anyInt());
        verify(messageHistoryCacheService, never()).putLatestHistory(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("getCursorMessageHistoryWithParts returns empty result for empty session")
    void getCursorMessageHistoryWithPartsReturnsEmptyResultForEmptySession() {
        when(messageRepository.findLatestBySessionId(1L, 3)).thenReturn(List.of());

        MessageHistoryResult<ProtocolMessageView> result = service.getCursorMessageHistoryWithParts(1L, null, 2);

        assertNotNull(result);
        assertTrue(result.getContent().isEmpty());
        assertFalse(Boolean.TRUE.equals(result.getHasMore()));
        assertNull(result.getNextBeforeSeq());
    }

    @Test
    @DisplayName("getCursorMessageHistoryWithParts supports size one")
    void getCursorMessageHistoryWithPartsSupportsSizeOne() {
        SkillMessage message1 = SkillMessage.builder().id(101L).sessionId(1L).seq(10).build();
        SkillMessage message2 = SkillMessage.builder().id(102L).sessionId(1L).seq(11).build();

        when(messageRepository.findLatestBySessionId(1L, 2)).thenReturn(List.of(message2, message1));
        when(partRepository.findByMessageIds(anyList())).thenReturn(List.of());

        MessageHistoryResult<ProtocolMessageView> result = service.getCursorMessageHistoryWithParts(1L, null, 1);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(11, result.getContent().get(0).getSeq());
        assertTrue(Boolean.TRUE.equals(result.getHasMore()));
        assertEquals(11, result.getNextBeforeSeq());
    }

    @Test
    @DisplayName("getCursorMessageHistoryWithParts returns cached latest history when available")
    void getCursorMessageHistoryWithPartsReturnsCachedLatestHistoryWhenAvailable() {
        ProtocolMessageView view = ProtocolMessageView.builder().seq(10).build();
        MessageHistoryResult<ProtocolMessageView> cached = MessageHistoryResult.<ProtocolMessageView>builder()
                .content(List.of(view))
                .size(20)
                .hasMore(false)
                .build();
        when(messageHistoryCacheService.getLatestHistory(1L, 20)).thenReturn(cached);

        MessageHistoryResult<ProtocolMessageView> result = service.getCursorMessageHistoryWithParts(1L, null, 20);

        assertSame(cached, result);
        verify(messageRepository, never()).findLatestBySessionId(anyLong(), anyInt());
        verify(partRepository, never()).findByMessageIds(anyList());
    }

    @Test
    @DisplayName("refreshLatestHistoryCaches warms configured sizes")
    void refreshLatestHistoryCachesWarmsConfiguredSizes() {
        SkillMessage message = SkillMessage.builder()
                .id(101L)
                .sessionId(1L)
                .seq(10)
                .role(SkillMessage.Role.USER)
                .content("hello")
                .contentType(SkillMessage.ContentType.PLAIN)
                .build();
        when(messageHistoryCacheService.getWarmSizes()).thenReturn(List.of(20, 50));
        when(messageRepository.findLatestBySessionId(1L, 21)).thenReturn(List.of(message));
        when(messageRepository.findLatestBySessionId(1L, 51)).thenReturn(List.of(message));
        when(partRepository.findByMessageIds(anyList())).thenReturn(List.of());

        service.refreshLatestHistoryCaches(1L);

        verify(messageHistoryCacheService).putLatestHistory(eq(1L), eq(20), any());
        verify(messageHistoryCacheService).putLatestHistory(eq(1L), eq(50), any());
    }

    @Test
    @DisplayName("scheduleLatestHistoryRefreshAfterCommit dispatches to executor")
    void scheduleLatestHistoryRefreshAfterCommitDispatchesToExecutor() {
        when(messageHistoryCacheService.getWarmSizes()).thenReturn(List.of());

        service.scheduleLatestHistoryRefreshAfterCommit(1L);

        verify(messageHistoryRefreshExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("scheduleLatestHistoryRefreshAfterCommit invalidates stale latest-history cache before refresh")
    void scheduleLatestHistoryRefreshAfterCommitInvalidatesCacheBeforeRefresh() {
        when(messageHistoryCacheService.getWarmSizes()).thenReturn(List.of());

        service.scheduleLatestHistoryRefreshAfterCommit(1L);

        verify(messageHistoryCacheService).invalidateLatestHistory(1L);
        verify(messageHistoryRefreshExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("sequence numbers auto-increment per session")
    void sequenceNumbersAutoIncrement() {
        when(messageRepository.findMaxSeqBySessionId(1L))
                .thenReturn(0)
                .thenReturn(1);

        SkillMessage msg1 = service.saveUserMessage(1L, "First");
        SkillMessage msg2 = service.saveUserMessage(1L, "Second");
        assertEquals(1, msg1.getSeq());
        assertEquals(2, msg2.getSeq());
    }
}
