package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** ImOutboundService 单元测试：验证向 IM 发送消息的出站逻辑。 */
class ImOutboundServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private ImOutboundService service;

    @BeforeEach
    void setUp() {
        service = new ImOutboundService(restTemplate, "http://localhost:8080", "token-123");
    }

    @Test
    @DisplayName("group chat uses group endpoint")
    void groupChatUsesGroupEndpoint() throws Exception {
        when(restTemplate.postForEntity(eq("http://localhost:8080/v1/welinkim/im-service/chat/app-group-chat"),
                any(HttpEntity.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"msgId\":1}")));

        boolean result = service.sendTextToIm("group", "grp-001", "hello", "assist-001");

        assertTrue(result);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://localhost:8080/v1/welinkim/im-service/chat/app-group-chat"),
                captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
        Map<?, ?> body = (Map<?, ?>) captor.getValue().getBody();
        assertEquals("grp-001", body.get("sessionId"));
        assertEquals("assist-001", body.get("senderAccount"));
    }
}
