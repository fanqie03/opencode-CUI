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
class AgentMakerProtocolStrategyTest {

    @Mock
    private CloudAuthService cloudAuthService;

    @Mock
    private HttpClient httpClient;

    private ObjectMapper objectMapper;
    private AgentMakerProtocolStrategy strategy;

    private List<GatewayMessage> receivedEvents;
    private List<Throwable> receivedErrors;
    private Consumer<GatewayMessage> onEvent;
    private Consumer<Throwable> onError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        strategy = spy(new AgentMakerProtocolStrategy(cloudAuthService, objectMapper, httpClient));
        
        receivedEvents = new ArrayList<>();
        receivedErrors = new ArrayList<>();
        onEvent = receivedEvents::add;
        onError = receivedErrors::add;
    }

    private CloudConnectionContext buildContext() {
        return CloudConnectionContext.builder()
                .endpoint("https://cloud.example.com/agentmaker/sse")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("userInput", "hello")))
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
    @DisplayName("AgentMaker PROCESSING 事件解析")
    void shouldParseProcessingEvent() throws Exception {
        String sseStream = "data: {\"errors\":\"\",\"meta\":null,\"data\":{\"id\":\"1\",\"type\":\"AgentDialogueVO\",\"attributes\":{\"requestId\":\"req1\",\"agentStatus\":\"PROCESSING\",\"status\":\"\",\"content\":\"正在分析\",\"sessionId\":\"sess1\"}}}\n";

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertEquals(1, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
        assertEquals("thinking", receivedEvents.get(0).getEvent().path("type").asText());
    }

    @Test
    @DisplayName("AgentMaker ANSWER 事件解析")
    void shouldParseAnswerEvent() throws Exception {
        String sseStream = String.join("\n",
                "data: {\"errors\":\"\",\"meta\":null,\"data\":{\"id\":\"1\",\"type\":\"AgentDialogueVO\",\"attributes\":{\"requestId\":\"req1\",\"agentStatus\":\"ANSWER\",\"content\":\"hello\"}}}",
                "data: {\"errors\":\"\",\"meta\":null,\"data\":{\"id\":\"1\",\"type\":\"AgentDialogueVO\",\"attributes\":{\"requestId\":\"req1\",\"agentStatus\":\"DONE\",\"content\":\"hello\"}}}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertEquals(2, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
        assertEquals("text.delta", receivedEvents.get(0).getEvent().path("type").asText());
        assertEquals(GatewayMessage.Type.TOOL_DONE, receivedEvents.get(1).getType());
    }

    @Test
    @DisplayName("AgentMaker TOOL_EXEC 事件解析")
    void shouldParseToolExecEvent() throws Exception {
        String sseStream = "data: {\"errors\":\"\",\"meta\":null,\"data\":{\"id\":\"1\",\"type\":\"AgentDialogueVO\",\"attributes\":{\"requestId\":\"req1\",\"agentStatus\":\"TOOL_EXEC\",\"content\":\"执行工具\",\"toolResult\":[{\"toolName\":\"Search\"}]}}}\n";

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertEquals(1, receivedEvents.size());
        assertEquals("tool_exec", receivedEvents.get(0).getEvent().path("type").asText());
    }

    @Test
    @DisplayName("getProtocol 返回 agentmaker")
    void shouldReturnAgentmakerProtocol() {
        assertEquals("agentmaker", strategy.getProtocol());
    }
}