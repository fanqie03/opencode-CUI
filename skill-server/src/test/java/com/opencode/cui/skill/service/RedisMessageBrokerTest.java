package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * RedisMessageBroker 单元测试：聚焦 toolSessionId → welinkSessionId 反查缓存的失效行为。
 * 覆盖 PRD 04-24-skill-server-tool-session-mapping-invalidation 中关于 broker 的 2 个用例。
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerTest {

    private static final String TOOL_SESSION_PREFIX = "ss:tool-session:";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        broker = new RedisMessageBroker(redisTemplate, listenerContainer);
    }

    @Test
    @DisplayName("deleteToolSessionMapping 正常 key 调用 redisTemplate.delete(prefix+key) 一次")
    void deleteToolSessionMappingDeletesPrefixedKey() {
        broker.deleteToolSessionMapping("T1");

        verify(redisTemplate).delete(TOOL_SESSION_PREFIX + "T1");
    }

    @Test
    @DisplayName("deleteToolSessionMapping null/blank → no-op，不触达 redisTemplate")
    void deleteToolSessionMappingNullOrBlankIsNoOp() {
        broker.deleteToolSessionMapping(null);
        broker.deleteToolSessionMapping("");
        broker.deleteToolSessionMapping("   ");

        verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(listenerContainer);
    }
}
