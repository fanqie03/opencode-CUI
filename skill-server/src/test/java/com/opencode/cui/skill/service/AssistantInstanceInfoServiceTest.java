package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInstanceInfo;
import com.opencode.cui.skill.model.ExistenceStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantInstanceInfoServiceTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ApiCallMetricsService apiCallMetricsService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssistantInstanceInfoService service;

    @BeforeEach
    void setUp() {
         lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
         service = new AssistantInstanceInfoService(restTemplate, redisTemplate, objectMapper,
                 "https://example.com/instance/query", "token-1", 300, apiCallMetricsService);
    }

    @Test
    @DisplayName("缓存命中时返回 EXISTS，不调用远端")
    void lookup_cacheHit_returnsExists() throws Exception {
        AssistantInstanceInfo cached = new AssistantInstanceInfo();
        cached.setPartnerAccount("assist-001");
        cached.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_ASSISTANT_SQUARE);
        when(valueOps.get("ss:assistant:instance:assist-001"))
                .thenReturn(objectMapper.writeValueAsString(cached));

        AssistantInstanceInfoService.LookupResult result = service.lookup("assist-001");

        assertEquals(ExistenceStatus.EXISTS, result.status());
        assertEquals("assist-001", result.info().getPartnerAccount());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("缓存未命中时调用 instance/query，解析 remoteProperty 并写缓存")
    void lookup_cacheMiss_fetchesAndCachesRemoteProperty() {
        when(valueOps.get("ss:assistant:instance:assist-001")).thenReturn(null);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", 200);
        ObjectNode data = body.putObject("data");
        data.put("id", "robot-001");
        data.put("partnerAccount", "assist-001");
        data.put("ownerWelinkId", "owner-001");
        data.put("remoteType", AssistantInstanceInfo.REMOTE_TYPE_ASSISTANT_SQUARE);
        ObjectNode remote = data.putArray("remoteProperty").addObject();
        remote.put("type", "chat");
        remote.put("commProtocol", "sse");
        remote.put("url", "https://remote.example.com/chat");
        remote.putArray("headers").addObject()
                .put("type", "custom")
                .put("customKey", "X-Token")
                .put("customValue", "secret");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        AssistantInstanceInfoService.LookupResult result = service.lookup("assist-001");

        assertEquals(ExistenceStatus.EXISTS, result.status());
        assertTrue(result.info().remoteAssistant());
        assertEquals("robot-001", result.info().getId());
        assertEquals("assistant_square", result.info().protocolProfile());
        assertEquals("chat", result.info().getRemoteProperty().get(0).getType());
        verify(valueOps).set(eq("ss:assistant:instance:assist-001"), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("data 为空时返回 NOT_EXISTS 且不写实例缓存")
    void lookup_emptyData_returnsNotExistsWithoutInstanceCacheWrite() {
        when(valueOps.get("ss:assistant:instance:missing")).thenReturn(null);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", 200);
        body.putNull("data");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        AssistantInstanceInfoService.LookupResult result = service.lookup("missing");

        assertEquals(ExistenceStatus.NOT_EXISTS, result.status());
        verify(valueOps, never()).set(eq("ss:assistant:instance:missing"), anyString(), any(Duration.class));
    }
}
