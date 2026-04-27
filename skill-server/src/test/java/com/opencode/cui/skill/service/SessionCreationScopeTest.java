package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImSessionManager scope 分支测试：验证 business/personal 会话创建中 toolSessionId 的处理方式。
 */
@ExtendWith(MockitoExtension.class)
class SessionCreationScopeTest {

    @Mock
    private SkillSessionService sessionService;
    @Mock
    private GatewayRelayService gatewayRelayService;
    @Mock
    private SessionRebuildService rebuildService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private AssistantInfoService assistantInfoService;
    @Mock
    private AssistantScopeDispatcher scopeDispatcher;
    @Mock
    private AssistantScopeStrategy businessStrategy;
    @Mock
    private AssistantScopeStrategy personalStrategy;

    private ImSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 模拟获取 Redis 锁成功
        lenient().when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);
        // 模拟 findByBusinessSession 返回 null（确保走创建路径）
        lenient().when(sessionService.findByBusinessSession(any(), any(), any(), any())).thenReturn(null);

        sessionManager = new ImSessionManager(
                sessionService,
                gatewayRelayService,
                rebuildService,
                redisTemplate,
                new ObjectMapper(),
                assistantInfoService,
                scopeDispatcher,
                30);
    }

    @Test
    @DisplayName("S57: business session creation - toolSessionId generated locally as cloud-*")
    void s57_businessSessionToolSessionIdGeneratedLocally() {
        // arrange
        SkillSession created = new SkillSession();
        created.setId(100L);
        created.setAk("ak-biz");
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(assistantInfoService.getCachedScope("ak-biz")).thenReturn("business");
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-abc123def456");

        // act
        sessionManager.createSessionAsync("im", "direct", "dm-001", "ak-biz",
                "owner-001", "assist-001", null, "hello", null);

        // assert: toolSessionId was updated locally with cloud-* prefix
        ArgumentCaptor<String> toolSessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionService).updateToolSessionId(eq(100L), toolSessionCaptor.capture());
        String toolSessionId = toolSessionCaptor.getValue();
        assertNotNull(toolSessionId);
        assertTrue(toolSessionId.startsWith("cloud-"),
                "business toolSessionId should start with 'cloud-', got: " + toolSessionId);

        // should NOT request tool session from Gateway
        verify(gatewayRelayService, never()).rebuildToolSession(any(), any(), any());
    }

    @Test
    @DisplayName("S58: personal session creation - toolSessionId is null (delegated to Gateway)")
    void s58_personalSessionToolSessionIdIsNull() {
        // arrange
        SkillSession created = new SkillSession();
        created.setId(200L);
        created.setAk("ak-personal");
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(assistantInfoService.getCachedScope("ak-personal")).thenReturn("personal");
        when(scopeDispatcher.getStrategy("personal")).thenReturn(personalStrategy);
        when(personalStrategy.generateToolSessionId()).thenReturn(null);

        // act
        sessionManager.createSessionAsync("im", "direct", "dm-002", "ak-personal",
                "owner-002", "assist-002", null, "hello", null);

        // assert: toolSessionId NOT updated locally
        verify(sessionService, never()).updateToolSessionId(any(), any());

        // should request tool session from Gateway via rebuildToolSession
        verify(gatewayRelayService).rebuildToolSession(any(), eq(created), eq("hello"));
    }
}
