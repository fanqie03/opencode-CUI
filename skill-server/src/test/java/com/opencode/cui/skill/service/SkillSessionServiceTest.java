package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SessionListQuery;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
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
/** SkillSessionService 单元测试：验证会话创建、查询、关闭等核心逻辑。 */
class SkillSessionServiceTest {

    @Mock
    private SkillSessionRepository sessionRepository;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private SessionRouteService sessionRouteService;

    private SkillSessionService service;

    @BeforeEach
    void setUp() {
        lenient().when(snowflakeIdGenerator.nextId()).thenReturn(42L);
        service = new SkillSessionService(sessionRepository, snowflakeIdGenerator, sessionRouteService);
    }

    @Test
    @DisplayName("createSession inserts and returns session")
    void createSessionInsertsAndReturns() {
        SkillSession result = service.createSession("1", "ak-3", "Test", "miniapp", null, "chat-1", null);
        assertNotNull(result);
        assertEquals(42L, result.getId());
        assertEquals(SkillSession.Status.ACTIVE, result.getStatus());
        assertEquals("miniapp", result.getBusinessSessionDomain());
        assertEquals("chat-1", result.getBusinessSessionId());
        verify(sessionRepository).insert(any(SkillSession.class));
    }

    @Test
    @DisplayName("getSession returns existing session")
    void getSessionReturnsExisting() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        when(sessionRepository.findById(42L)).thenReturn(session);

        SkillSession result = service.getSession(42L);
        assertEquals(42L, result.getId());
    }

    @Test
    @DisplayName("getSession throws for non-existent session")
    void getSessionThrowsForNonExistent() {
        when(sessionRepository.findById(999L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.getSession(999L));
    }

    @Test
    @DisplayName("closeSession updates status to CLOSED")
    void closeSessionUpdatesStatus() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        session.setStatus(SkillSession.Status.CLOSED);
        when(sessionRepository.findById(42L)).thenReturn(session);

        SkillSession result = service.closeSession(42L);
        verify(sessionRepository).updateStatus(42L, "CLOSED");
        assertEquals(SkillSession.Status.CLOSED, result.getStatus());
    }

    @Test
    @DisplayName("touchSession updates last_active_at")
    void touchSessionUpdatesTimestamp() {
        service.touchSession(42L);
        verify(sessionRepository).updateLastActiveAt(eq(42L), any());
    }

    @Test
    @DisplayName("updateToolSessionId updates and returns session")
    void updateToolSessionIdUpdatesAndReturns() {
        SkillSession session = new SkillSession();
        session.setId(42L);
        when(sessionRepository.findById(42L)).thenReturn(session);

        SkillSession result = service.updateToolSessionId(42L, "ts-abc");
        verify(sessionRepository).updateToolSessionId(eq(42L), eq("ts-abc"), any());
        assertEquals(42L, result.getId());
    }

    @Test
    @DisplayName("findByAk delegates to repository")
    void findByAkDelegates() {
        SkillSession s1 = new SkillSession();
        s1.setId(1L);
        when(sessionRepository.findByAk("99")).thenReturn(List.of(s1));

        List<SkillSession> result = service.findByAk("99");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("listSessions without status filter")
    void listSessionsWithoutFilter() {
        when(sessionRepository.findByUserId("1", 0, 10)).thenReturn(List.of());
        when(sessionRepository.countByUserId("1")).thenReturn(0L);

        PageResult<SkillSession> result = service.listSessions(
                new SessionListQuery("1", null, null, null, null, null, null, 0, 10));
        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
    }

    @Test
    @DisplayName("listSessions with status filter")
    void listSessionsWithFilter() {
        when(sessionRepository.findByUserIdFiltered(eq("1"), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(), eq(0), eq(10)))
                .thenReturn(List.of());
        when(sessionRepository.countByUserIdFiltered(eq("1"), isNull(), isNull(), isNull(), isNull(), isNull(), anyList())).thenReturn(0L);

        PageResult<SkillSession> result = service.listSessions(
                new SessionListQuery("1", null, null, null, null, null, "ACTIVE", 0, 10));
        assertNotNull(result);
    }

    @Test
    @DisplayName("findByBusinessSession delegates to repository")
    void findByBusinessSessionDelegates() {
        SkillSession session = new SkillSession();
        session.setId(55L);
        when(sessionRepository.findByBusinessSession("im", "group", "chat-1", "ak-1")).thenReturn(session);

        SkillSession result = service.findByBusinessSession("im", "group", "chat-1", "ak-1");

        assertNotNull(result);
        assertEquals(55L, result.getId());
    }
}
