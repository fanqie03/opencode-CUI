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
 * Dify 协议策略实现。
 *
 * <p>Dify 支持三种模式：chatflow、agent、workflow，均使用 SSE 协议。
 * 通过 HTTP POST 发送请求，读取 SSE 流，解析不同类型的事件。</p>
 *
 * <p>事件类型映射：
 * <ul>
 *   <li>agent_message / message → tool_event（流式文本）</li>
 *   <li>text_chunk → tool_event（流式文本）</li>
 *   <li>message_end → tool_done</li>
 *   <li>workflow_finished → tool_done</li>
 *   <li>agent_thought → tool_event（思考过程）</li>
 * </ul>
 */
@Slf4j
@Component
public class DifyProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public DifyProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${gateway.cloud.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        this(cloudAuthService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
    }

    public DifyProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "dify";
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
            log.info("[DIFY] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            HttpResponse<InputStream> response = sendRequest(request);

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException("Dify connection failed: HTTP " + response.statusCode()));
                return;
            }

            if (lifecycle != null) {
                lifecycle.onConnected();
            }

            readDifyStream(response.body(), lifecycle, onEvent, onError, context.getTraceId());

        } catch (Exception e) {
            log.error("[DIFY] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    private void readDifyStream(InputStream inputStream,
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
                if (line.startsWith("event: ping")) {
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onHeartbeat);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (handleDataLine(line, lifecycle, onEvent, traceId)) {
                        return;
                    }
                }
            }
            log.info("[DIFY] Stream completed: traceId={}", traceId);
        } catch (Exception e) {
            log.error("[DIFY] Stream read error: traceId={}, error={}", traceId, e.getMessage());
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
            String eventType = root.path("event").asText("");
            
            GatewayMessage message = parseDifyEvent(root, eventType);
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
            log.warn("[DIFY] Failed to parse event: traceId={}, data={}, error={}",
                    traceId, jsonData, e.getMessage());
        }
        return false;
    }

    private GatewayMessage parseDifyEvent(JsonNode root, String eventType) {
        String toolSessionId = extractToolSessionId(root);
        
        switch (eventType) {
            case "agent_message":
            case "message": {
                String answer = root.path("answer").asText("");
                if (!answer.isEmpty()) {
                    JsonNode eventData = objectMapper.createObjectNode()
                            .put("type", "text.delta")
                            .set("properties", objectMapper.createObjectNode()
                                    .put("content", answer));
                    return GatewayMessage.toolEvent(toolSessionId, eventData);
                }
                break;
            }
            case "text_chunk": {
                JsonNode data = root.path("data");
                String text = data.path("text").asText("");
                if (!text.isEmpty()) {
                    JsonNode eventData = objectMapper.createObjectNode()
                            .put("type", "text.delta")
                            .set("properties", objectMapper.createObjectNode()
                                    .put("content", text));
                    return GatewayMessage.toolEvent(toolSessionId, eventData);
                }
                break;
            }
            case "agent_thought": {
                String thought = root.path("thought").asText("");
                String observation = root.path("observation").asText("");
                if (!thought.isEmpty() || !observation.isEmpty()) {
                    JsonNode eventData = objectMapper.createObjectNode()
                            .put("type", "thinking")
                            .set("properties", objectMapper.createObjectNode()
                                    .put("thought", thought)
                                    .put("observation", observation));
                    return GatewayMessage.toolEvent(toolSessionId, eventData);
                }
                break;
            }
            case "message_end": {
                JsonNode metadata = root.path("metadata");
                JsonNode usage = metadata.path("usage");
                return GatewayMessage.toolDone(toolSessionId, usage.isMissingNode() ? null : usage);
            }
            case "workflow_finished": {
                JsonNode data = root.path("data");
                JsonNode usage = data.path("total_tokens").isMissingNode() ? null : data;
                return GatewayMessage.toolDone(toolSessionId, usage);
            }
            case "error": {
                String errorMessage = root.path("message").asText("Dify error");
                return GatewayMessage.toolError(toolSessionId, errorMessage);
            }
        }
        return null;
    }

    private String extractToolSessionId(JsonNode root) {
        String conversationId = root.path("conversation_id").asText("");
        String messageId = root.path("message_id").asText("");
        String taskId = root.path("task_id").asText("");
        
        if (!conversationId.isEmpty()) return conversationId;
        if (!messageId.isEmpty()) return messageId;
        if (!taskId.isEmpty()) return taskId;
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