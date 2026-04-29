package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UniKnowProtocolStrategyTest {

    @Mock
    private CloudAuthService cloudAuthService;

    @Mock
    private HttpClient httpClient;

    private ObjectMapper objectMapper;
    private UniKnowProtocolStrategy strategy;

    private List<GatewayMessage> receivedEvents;
    private List<Throwable> receivedErrors;
    private Consumer<GatewayMessage> onEvent;
    private Consumer<Throwable> onError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        strategy = spy(new UniKnowProtocolStrategy(cloudAuthService, objectMapper, httpClient));
        
        receivedEvents = new ArrayList<>();
        receivedErrors = new ArrayList<>();
        onEvent = receivedEvents::add;
        onError = receivedErrors::add;
    }

    private CloudConnectionContext buildContext() {
        return CloudConnectionContext.builder()
                .endpoint("https://cloud.example.com/uniknow/chat")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("query", "hello")))
                .appId("app_test")
                .authType("soa")
                .traceId("trace_001")
                .build();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockStringResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        if (statusCode == 200) {
            when(response.body()).thenReturn(body);
        }
        return response;
    }

    @Test
    @DisplayName("UniKnow REST 响应解析")
    void shouldParseRestResponse() throws Exception {
        String responseBody = "{\"data\":[{\"taskInfo\":{\"slots\":{\"result\":{\"data\":\"hello world\",\"requestId\":\"req123\"}}}}]}";

        HttpResponse<String> response = mockStringResponse(200, responseBody);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertEquals(2, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
        assertEquals("hello world", receivedEvents.get(0).getEvent().path("properties").path("content").asText());
        assertEquals(GatewayMessage.Type.TOOL_DONE, receivedEvents.get(1).getType());
        assertEquals("req123", receivedEvents.get(1).getToolSessionId());
    }

    @Test
    @DisplayName("UniKnow HTTP 非 200 触发错误")
    void shouldCallOnErrorWhenHttpNon200() throws Exception {
        HttpResponse<String> response = mockStringResponse(500, "Internal Server Error");
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        strategy.connect(buildContext(), mock(CloudConnectionLifecycle.class), onEvent, onError);

        assertTrue(receivedEvents.isEmpty());
        assertEquals(1, receivedErrors.size());
    }

    @Test
    @DisplayName("getProtocol 返回 uniknow")
    void shouldReturnUniknowProtocol() {
        assertEquals("uniknow", strategy.getProtocol());
    }
}