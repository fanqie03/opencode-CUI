package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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

    private SkillMessageService service;

    @BeforeEach
    void setUp() {
        lenient().when(snowflakeIdGenerator.nextId()).thenReturn(100L, 101L, 102L, 103L, 104L, 105L);
        service = new SkillMessageService(messageRepository, partRepository,
                sessionService, snowflakeIdGenerator, new ObjectMapper());
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
        verify(sessionService).touchSession(1L);
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
    }

    @Test
    @DisplayName("getMessageHistory returns paginated results")
    void getMessageHistoryReturnsPaginated() {
        when(messageRepository.findBySessionId(1L, 0, 20)).thenReturn(List.of());
        when(messageRepository.countBySessionId(1L)).thenReturn(0L);

        PageResult<SkillMessage> result = service.getMessageHistory(1L, 0, 20);
        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        assertEquals(0, result.getNumber());
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
