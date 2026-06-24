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
import org.springframework.context.ApplicationEventPublisher;

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

    @Mock
    private RedisMessageBroker redisMessageBroker;

    @Mock
    private UnreadRedisService unreadRedisService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private SkillSessionService service;

    @BeforeEach
    void setUp() {
        lenient().when(snowflakeIdGenerator.nextId()).thenReturn(42L);
        service = new SkillSessionService(sessionRepository, snowflakeIdGenerator, sessionRouteService,
                redisMessageBroker, unreadRedisService, applicationEventPublisher);
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
    @DisplayName("markSessionIdle updates open session to IDLE")
    void markSessionIdleUpdatesOpenSession() {
        when(sessionRepository.markIdle(42L)).thenReturn(1);

        assertTrue(service.markSessionIdle(42L));
        verify(sessionRepository).markIdle(42L);
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
    @DisplayName("updateToolSessionId: oldToolSessionId is null → no delete, only set new mapping")
    void updateToolSessionIdNoOldValueOnlySetsNew() {
        SkillSession existing = new SkillSession();
        existing.setId(42L);
        existing.setToolSessionId(null); // no old value
        when(sessionRepository.findById(42L)).thenReturn(existing);

        service.updateToolSessionId(42L, "ts-new");

        verify(sessionRepository).updateToolSessionId(eq(42L), eq("ts-new"), any());
        verify(redisMessageBroker, never()).deleteToolSessionMapping(any());
        verify(redisMessageBroker).setToolSessionMapping("ts-new", "42");
    }

    @Test
    @DisplayName("updateToolSessionId: oldToolSessionId equals newToolSessionId → no delete, idempotent set")
    void updateToolSessionIdSameValueIdempotent() {
        SkillSession existing = new SkillSession();
        existing.setId(42L);
        existing.setToolSessionId("ts-same");
        when(sessionRepository.findById(42L)).thenReturn(existing);

        service.updateToolSessionId(42L, "ts-same");

        verify(sessionRepository).updateToolSessionId(eq(42L), eq("ts-same"), any());
        verify(redisMessageBroker, never()).deleteToolSessionMapping(any());
        verify(redisMessageBroker).setToolSessionMapping("ts-same", "42");
    }

    @Test
    @DisplayName("updateToolSessionId: remap (old != new, both non-null) → delete(old) + set(new)")
    void updateToolSessionIdRemapDeletesOldAndSetsNew() {
        SkillSession existing = new SkillSession();
        existing.setId(42L);
        existing.setToolSessionId("ts-old");
        when(sessionRepository.findById(42L)).thenReturn(existing);

        service.updateToolSessionId(42L, "ts-new");

        verify(sessionRepository).updateToolSessionId(eq(42L), eq("ts-new"), any());
        verify(redisMessageBroker).deleteToolSessionMapping("ts-old");
        verify(redisMessageBroker).setToolSessionMapping("ts-new", "42");
    }

    @Test
    @DisplayName("updateToolSessionId: newToolSessionId is null → delete(old), no set")
    void updateToolSessionIdNullNewDeletesOldOnly() {
        SkillSession existing = new SkillSession();
        existing.setId(42L);
        existing.setToolSessionId("ts-old");
        when(sessionRepository.findById(42L)).thenReturn(existing);

        service.updateToolSessionId(42L, null);

        verify(sessionRepository).updateToolSessionId(eq(42L), isNull(), any());
        verify(redisMessageBroker).deleteToolSessionMapping("ts-old");
        verify(redisMessageBroker, never()).setToolSessionMapping(any(), any());
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
    @DisplayName("listSessions without status filter defaults to ACTIVE+IDLE")
    void listSessionsWithoutFilter() {
        List<String> defaultStatuses = List.of("ACTIVE", "IDLE");
        when(sessionRepository.findByUserIdAndStatusIn("1", defaultStatuses, 0, 10)).thenReturn(List.of());
        when(sessionRepository.countByUserIdAndStatusIn("1", defaultStatuses)).thenReturn(0L);

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

    @Test
    @DisplayName("findByBusinessSession with AK and assistantAccount uses combined lookup key")
    void findByBusinessSessionWithAkAndAssistantAccountDelegatesToCombinedKey() {
        SkillSession session = new SkillSession();
        session.setId(56L);
        when(sessionRepository.findByBusinessSessionAndAkAndAssistantAccount(
                "im", "group", "chat-1", "ak-1", "assist-1"))
                .thenReturn(session);

        SkillSession result = service.findByBusinessSession(
                "im", "group", "chat-1", "ak-1", "assist-1");

        assertNotNull(result);
        assertEquals(56L, result.getId());
        verify(sessionRepository).findByBusinessSessionAndAkAndAssistantAccount(
                "im", "group", "chat-1", "ak-1", "assist-1");
        verify(sessionRepository, never()).findByBusinessSession(any(), any(), any(), any());
        verify(sessionRepository, never()).findByBusinessSessionAndAssistantAccount(
                any(), any(), any(), any());
    }

    // ==================== PR3: createSessionWithDefaultAssistant ====================

    @Test
    @DisplayName("createSessionWithDefaultAssistant: 单事务一次写入 (INSERT + createRoute + Redis 都就绪)")
    void createSessionWithDefaultAssistantHappyPath() {
        SkillSession reloaded = new SkillSession();
        reloaded.setId(42L);
        reloaded.setAk("AK_V");
        reloaded.setAssistantAccount("ACC_V");
        reloaded.setToolSessionId("ts-1");
        reloaded.setUserId("u-1");
        reloaded.setBusinessSessionDomain("helpdesk");
        reloaded.setBusinessSessionType("direct");
        reloaded.setBusinessSessionId("biz-1");
        reloaded.setStatus(SkillSession.Status.ACTIVE);
        when(sessionRepository.findById(42L)).thenReturn(reloaded);

        SkillSession result = service.createSessionWithDefaultAssistant(
                "u-1", "AK_V", "ACC_V", "Title",
                "helpdesk", "direct", "biz-1", "ts-1");

        // INSERT skill_session
        verify(sessionRepository).insert(any(SkillSession.class));
        // createRoute 写 session ownership
        verify(sessionRouteService).createRoute(eq("AK_V"), eq(42L), eq("skill-server"), eq("u-1"));
        // Redis 写 toolSessionId → sessionId 映射
        verify(redisMessageBroker).setToolSessionMapping("ts-1", "42");
        // 返回从 DB 重读的最新 session 对象
        assertNotNull(result);
        assertEquals(42L, result.getId());
        assertEquals("AK_V", result.getAk());
        assertEquals("ACC_V", result.getAssistantAccount());
        assertEquals("ts-1", result.getToolSessionId());
    }

    @Test
    @DisplayName("createSessionWithDefaultAssistant: Redis 写失败宽松 (异常 → log warn, DB 事务仍提交)")
    void createSessionWithDefaultAssistantRedisFailureIsLenient() {
        SkillSession reloaded = new SkillSession();
        reloaded.setId(42L);
        reloaded.setAk("AK_V");
        reloaded.setAssistantAccount("ACC_V");
        reloaded.setToolSessionId("ts-1");
        reloaded.setStatus(SkillSession.Status.ACTIVE);
        when(sessionRepository.findById(42L)).thenReturn(reloaded);
        // mock Redis 抛异常
        doThrow(new RuntimeException("Redis 拖头"))
                .when(redisMessageBroker).setToolSessionMapping(any(), any());

        // 不抛异常
        SkillSession result = service.createSessionWithDefaultAssistant(
                "u-1", "AK_V", "ACC_V", "Title",
                "helpdesk", "direct", "biz-1", "ts-1");

        // DB INSERT 仍然成功
        verify(sessionRepository).insert(any(SkillSession.class));
        // createRoute 仍然成功
        verify(sessionRouteService).createRoute(any(), any(), any(), any());
        // 接口仍返 200（session 非 null）
        assertNotNull(result);
        assertEquals(42L, result.getId());
    }

    @Test
    @DisplayName("createSessionWithDefaultAssistant: 返回 from-DB 重读的最新 session")
    void createSessionWithDefaultAssistantReturnsReloaded() {
        // mock findById 返回带最新字段的对象（不是 INSERT 时构造的对象）
        SkillSession reloaded = new SkillSession();
        reloaded.setId(42L);
        reloaded.setAk("AK_V");
        reloaded.setAssistantAccount("ACC_V");
        reloaded.setToolSessionId("ts-1");
        // 仅 reloaded 对象才带 createdAt
        reloaded.setCreatedAt(java.time.LocalDateTime.now());
        when(sessionRepository.findById(42L)).thenReturn(reloaded);

        SkillSession result = service.createSessionWithDefaultAssistant(
                "u-1", "AK_V", "ACC_V", "Title", "d", "t", "bid", "ts-1");

        verify(sessionRepository).findById(42L);
        assertNotNull(result.getCreatedAt(), "Should return from-DB reloaded session");
    }
}
