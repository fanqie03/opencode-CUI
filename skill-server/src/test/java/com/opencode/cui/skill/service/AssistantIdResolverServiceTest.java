package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantIdResolverServiceTest {

    @Mock
    private SkillSessionRepository sessionRepository;
    @Mock
    private GatewayApiClient gatewayApiClient;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ApiCallMetricsService apiCallMetricsService;

    private AssistantIdProperties properties;
    private AssistantIdResolverService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new AssistantIdProperties();
        properties.setEnabled(true);
        properties.setTargetToolType("assistant");
        properties.setPersonaBaseUrl("http://persona-api");
        properties.setCacheTtlMinutes(30);
        properties.setMatchAk(false);

        service = new AssistantIdResolverService(
                properties, sessionRepository, gatewayApiClient,
                restTemplate, redisTemplate, objectMapper, apiCallMetricsService);
    }

    // --- 前置检查 ---

    @Test
    @DisplayName("resolve returns null when disabled")
    void resolveReturnsNullWhenDisabled() {
        properties.setEnabled(false);
        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null for invalid sessionId")
    void resolveReturnsNullForInvalidSessionId() {
        assertNull(service.resolve("ak-001", "not-a-number"));
    }

    @Test
    @DisplayName("resolve returns null for null sessionId")
    void resolveReturnsNullForNullSessionId() {
        assertNull(service.resolve("ak-001", null));
    }

    @Test
    @DisplayName("resolve returns null for null or blank ak")
    void resolveReturnsNullForNullOrBlankAk() {
        assertNull(service.resolve(null, "12345"));
        assertNull(service.resolve("", "12345"));
        assertNull(service.resolve("  ", "12345"));
    }

    // --- session 查询 ---

    @Test
    @DisplayName("resolve returns null when session not found")
    void resolveReturnsNullWhenSessionNotFound() {
        when(sessionRepository.findById(12345L)).thenReturn(null);
        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null when session has no assistantAccount")
    void resolveReturnsNullWhenNoAssistantAccount() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount(null);
        when(sessionRepository.findById(12345L)).thenReturn(session);

        assertNull(service.resolve("ak-001", "12345"));
    }

    // --- toolType 检查 ---

    @Test
    @DisplayName("resolve returns null when toolType does not match")
    void resolveReturnsNullWhenToolTypeMismatch() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("opencode").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null when agent offline")
    void resolveReturnsNullWhenAgentOffline() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(null);

        assertNull(service.resolve("ak-001", "12345"));
    }

    // --- Redis 缓存命中 ---

    @Test
    @DisplayName("resolve returns cached assistantId from Redis")
    void resolveReturnsCachedAssistantId() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn("cached-agent-id");

        String result = service.resolve("ak-001", "12345");

        assertEquals("cached-agent-id", result);
        // 不应调用 persona 接口
        verify(restTemplate, never()).exchange(any(String.class), any(), any(), eq(String.class));
    }

    // --- persona 接口调用 ---

    @Test
    @DisplayName("resolve fetches assistantId from persona API and caches it")
    void resolveFetchesFromPersonaApi() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn(null);

        String personaResponse = "{\"code\":200,\"data\":[{\"id\":\"persona-id-001\",\"ak\":\"ak-001\",\"personaWelinkId\":\"assist-001\"}]}";
        when(restTemplate.exchange(
                eq("http://persona-api/welink-persona-settings/persona-new?personaWelinkId=assist-001"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(personaResponse));

        String result = service.resolve("ak-001", "12345");

        assertEquals("persona-id-001", result);
        verify(valueOperations).set("ss:assistant-id:assist-001", "persona-id-001", Duration.ofMinutes(30));
    }

    // --- match-ak 过滤 ---

    @Test
    @DisplayName("resolve filters by ak when matchAk enabled")
    void resolveFiltersByAkWhenEnabled() {
        properties.setMatchAk(true);

        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-002").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-002")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001:ak-002")).thenReturn(null);

        // data 数组有两条，只有第二条 ak 匹配
        String personaResponse = "{\"code\":200,\"data\":[" +
                "{\"id\":\"wrong-id\",\"ak\":\"ak-other\"}," +
                "{\"id\":\"correct-id\",\"ak\":\"ak-002\"}" +
                "]}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(personaResponse));

        String result = service.resolve("ak-002", "12345");

        assertEquals("correct-id", result);
        verify(valueOperations).set("ss:assistant-id:assist-001:ak-002", "correct-id", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("resolve returns null when matchAk enabled but no ak match in data")
    void resolveReturnsNullWhenNoAkMatch() {
        properties.setMatchAk(true);

        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-999").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-999")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001:ak-999")).thenReturn(null);

        String personaResponse = "{\"code\":200,\"data\":[{\"id\":\"id-1\",\"ak\":\"ak-other\"}]}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(personaResponse));

        assertNull(service.resolve("ak-999", "12345"));
    }

    // --- 降级场景 ---

    @Test
    @DisplayName("resolve returns null on persona API failure (silent degradation)")
    void resolveReturnsNullOnPersonaApiFailure() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn(null);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertNull(service.resolve("ak-001", "12345"));
    }

    @Test
    @DisplayName("resolve returns null when persona API returns empty data array")
    void resolveReturnsNullOnEmptyData() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("assistant").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn(null);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":[]}"));

        assertNull(service.resolve("ak-001", "12345"));
    }

    // --- toolType 大小写不敏感 ---

    @Test
    @DisplayName("resolve matches toolType case-insensitively")
    void resolveMatchesToolTypeCaseInsensitive() {
        SkillSession session = new SkillSession();
        session.setId(12345L);
        session.setAssistantAccount("assist-001");
        when(sessionRepository.findById(12345L)).thenReturn(session);

        // Gateway 返回大写 ASSISTANT
        AgentSummary agent = AgentSummary.builder().ak("ak-001").toolType("ASSISTANT").build();
        when(gatewayApiClient.getAgentByAk("ak-001")).thenReturn(agent);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ss:assistant-id:assist-001")).thenReturn("cached-id");

        assertEquals("cached-id", service.resolve("ak-001", "12345"));
    }
}
