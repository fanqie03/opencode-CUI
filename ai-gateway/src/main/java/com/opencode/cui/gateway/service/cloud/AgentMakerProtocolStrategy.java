package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AgentMaker 协议策略实现。
 *
 * <p>AgentMaker 使用 SSE 协议，响应格式为 JSON API 风格：
 * <pre>
 * {"errors":"","meta":null,"data":{"id":"1","type":"AgentDialogueVO","attributes":{...}}}
 * </pre>
 *
 * <p>事件类型映射：
 * <ul>
 *   <li>PROCESSING → tool_event（处理中）</li>
 *   <li>TOOL_EXEC → tool_event（工具执行）</li>
 *   <li>ANSWER → tool_event（流式文本）</li>
 *   <li>DONE → tool_done（完成）</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentMakerProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentMakerProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${gateway.cloud.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        this(cloudAuthService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
    }

    public AgentMakerProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "agentmaker";
    }

    @Override
    public void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        try {
            String requestBody = objectMapper.writeValueAsString(context.getCloudRequest());

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(context.getEndpoint()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-App-Id", context.getAppId() != null ? context.getAppId() : "");

            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            HttpRequest request = requestBuilder.build();
            log.info("[AGENTMAKER] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            HttpResponse<InputStream> response = sendRequest(request);

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException("AgentMaker connection failed: HTTP " + response.statusCode()));
                return;
            }

            if (lifecycle != null) {
                lifecycle.onConnected();
            }

            readAgentMakerStream(response.body(), lifecycle, onEvent, onError, context.getTraceId());

        } catch (Exception e) {
            log.error("[AGENTMAKER] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    private void readAgentMakerStream(InputStream inputStream,
                                     CloudConnectionLifecycle lifecycle,
                                     Consumer<GatewayMessage> onEvent,
                                     Consumer<Throwable> onError,
                                     String traceId) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(":")) {
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onHeartbeat);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (handleDataLine(line, lifecycle, onEvent, traceId)) {
                        return;
                    }
                }
            }
            log.info("[AGENTMAKER] Stream completed: traceId={}", traceId);
        } catch (Exception e) {
            log.error("[AGENTMAKER] Stream read error: traceId={}, error={}", traceId, e.getMessage());
            onError.accept(e);
        }
    }

    private boolean handleDataLine(String line, CloudConnectionLifecycle lifecycle,
                                   Consumer<GatewayMessage> onEvent, String traceId) {
        String jsonData = line.substring(5).trim();
        if (jsonData.isEmpty()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(jsonData);
            JsonNode data = root.path("data");
            JsonNode attributes = data.path("attributes");
            
            if (attributes.isMissingNode()) {
                return false;
            }
            
            String agentStatus = attributes.path("agentStatus").asText("");
            String content = attributes.path("content").asText("");
            String sessionId = attributes.path("sessionId").asText("");
            String requestId = attributes.path("requestId").asText("");
            
            String toolSessionId = !sessionId.isEmpty() ? sessionId : requestId;
            
            GatewayMessage message = parseAgentMakerEvent(agentStatus, content, toolSessionId, attributes);
            if (message != null) {
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                onEvent.accept(message);
                
                if (message.isType(GatewayMessage.Type.TOOL_DONE)
                        || message.isType(GatewayMessage.Type.TOOL_ERROR)) {
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("[AGENTMAKER] Failed to parse event: traceId={}, data={}, error={}",
                    traceId, jsonData, e.getMessage());
        }
        return false;
    }

    private GatewayMessage parseAgentMakerEvent(String agentStatus, String content, 
                                               String toolSessionId, JsonNode attributes) {
        switch (agentStatus) {
            case "PROCESSING": {
                JsonNode eventData = objectMapper.createObjectNode()
                        .put("type", "thinking")
                        .set("properties", objectMapper.createObjectNode()
                                .put("content", content));
                return GatewayMessage.toolEvent(toolSessionId, eventData);
            }
            case "TOOL_EXEC": {
                JsonNode toolResult = attributes.path("toolResult");
                JsonNode eventData = objectMapper.createObjectNode()
                        .put("type", "tool_exec")
                        .set("properties", objectMapper.createObjectNode()
                                .put("content", content)
                                .set("toolResult", toolResult));
                return GatewayMessage.toolEvent(toolSessionId, eventData);
            }
            case "ANSWER": {
                JsonNode eventData = objectMapper.createObjectNode()
                        .put("type", "text.delta")
                        .set("properties", objectMapper.createObjectNode()
                                .put("content", content));
                return GatewayMessage.toolEvent(toolSessionId, eventData);
            }
            case "DONE": {
                return GatewayMessage.toolDone(toolSessionId, null);
            }
        }
        return null;
    }

    protected HttpResponse<InputStream> sendRequest(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static void notifyLifecycle(CloudConnectionLifecycle lifecycle,
                                        Consumer<CloudConnectionLifecycle> action) {
        if (lifecycle != null) {
            action.accept(lifecycle);
        }
    }
}