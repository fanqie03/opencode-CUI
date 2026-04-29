package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DifyProtocolStrategyTest {

    @Mock
    private CloudAuthService cloudAuthService;

    @Mock
    private HttpClient httpClient;

    private ObjectMapper objectMapper;
    private DifyProtocolStrategy strategy;

    private List<GatewayMessage> receivedEvents;
    private List<Throwable> receivedErrors;
    private Consumer<GatewayMessage> onEvent;
    private Consumer<Throwable> onError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        strategy = spy(new DifyProtocolStrategy(cloudAuthService, objectMapper, httpClient));
        
        receivedEvents = new ArrayList<>();
        receivedErrors = new ArrayList<>();
        onEvent = receivedEvents::add;
        onError = receivedErrors::add;
    }

    private CloudConnectionContext buildContext() {
        return CloudConnectionContext.builder()
                .endpoint("https://cloud.example.com/dify")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("query", "hello")))
                .appId("app_test")
                .authType("soa")
                .traceId("trace_001")
                .build();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> mockResponse(int statusCode, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (statusCode == 200) {
            when(response.body()).thenReturn(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    @Test
    @DisplayName("Dify agent_message 事件解析")
    void shouldParseAgentMessageEvent() throws Exception {
        String sseStream = String.join("\n",
                "event: agent_message",
                "data: {\"event\":\"agent_message\",\"conversation_id\":\"conv1\",\"message_id\":\"msg1\",\"answer\":\"hello\"}",
                "event: message_end",
                "data: {\"event\":\"message_end\",\"conversation_id\":\"conv1\",\"message_id\":\"msg1\",\"metadata\":{\"usage\":{\"total_tokens\":42}}}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertEquals(2, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
        assertEquals("hello", receivedEvents.get(0).getEvent().path("properties").path("content").asText());
        assertEquals(GatewayMessage.Type.TOOL_DONE, receivedEvents.get(1).getType());
    }

    @Test
    @DisplayName("Dify text_chunk 事件解析")
    void shouldParseTextChunkEvent() throws Exception {
        String sseStream = String.join("\n",
                "event: text_chunk",
                "data: {\"event\":\"text_chunk\",\"task_id\":\"task1\",\"workflow_run_id\":\"run1\",\"data\":{\"text\":\"world\"}}",
                "event: workflow_finished",
                "data: {\"event\":\"workflow_finished\",\"task_id\":\"task1\",\"workflow_run_id\":\"run1\",\"data\":{\"status\":\"succeeded\"}}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertEquals(2, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
        assertEquals("world", receivedEvents.get(0).getEvent().path("properties").path("content").asText());
        assertEquals(GatewayMessage.Type.TOOL_DONE, receivedEvents.get(1).getType());
    }

    @Test
    @DisplayName("Dify ping 事件被忽略")
    void shouldIgnorePingEvent() throws Exception {
        String sseStream = String.join("\n",
                "event: ping",
                "",
                "event: agent_message",
                "data: {\"event\":\"agent_message\",\"answer\":\"hi\"}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertEquals(1, receivedEvents.size());
        assertTrue(receivedErrors.isEmpty());
    }

    @Test
    @DisplayName("Dify HTTP 非 200 触发错误")
    void shouldCallOnErrorWhenHttpNon200() throws Exception {
        HttpResponse<InputStream> response = mockResponse(500, "Internal Server Error");
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertTrue(receivedEvents.isEmpty());
        assertEquals(1, receivedErrors.size());
    }

    @Test
    @DisplayName("getProtocol 返回 dify")
    void shouldReturnDifyProtocol() {
        assertEquals("dify", strategy.getProtocol());
    }
}