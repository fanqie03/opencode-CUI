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
 * Athena 协议策略实现。
 *
 * <p>Athena 使用两步协议：
 * <ol>
 *   <li>POST /mock/athena/tasks 创建任务，获取 taskId</li>
 *   <li>GET /mock/athena/stream?id={taskId} 通过 SSE 获取流式结果</li>
 * </ol>
 *
 * <p>标准协议响应格式：
 * <pre>
 * {"code":"0","message":"","error":"","isFinish":false,"data":{"type":"text","content":"..."}}
 * </pre>
 *
 * <p>事件类型映射：
 * <ul>
 *   <li>type=text → tool_event（流式文本）</li>
 *   <li>type=planning → tool_event（规划中）</li>
 *   <li>type=searching → tool_event（搜索中）</li>
 *   <li>type=searchResult → tool_event（搜索结果）</li>
 *   <li>type=reference → tool_event（引用）</li>
 *   <li>type=think → tool_event（思考）</li>
 *   <li>type=askMore → tool_event（追问）</li>
 *   <li>isFinish=true → tool_done（完成）</li>
 * </ul>
 */
@Slf4j
@Component
public class AthenaProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public AthenaProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${gateway.cloud.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        this(cloudAuthService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
    }

    public AthenaProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "athena";
    }

    @Override
    public void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        try {
            String requestBody = objectMapper.writeValueAsString(context.getCloudRequest());
            
            // Step 1: 创建任务
            String taskId = createTask(context, requestBody);
            if (taskId == null) {
                onError.accept(new RuntimeException("Athena create task failed"));
                return;
            }
            
            log.info("[ATHENA] Task created: taskId={}, traceId={}", taskId, context.getTraceId());

            // Step 2: 建立 SSE 连接获取结果
            String streamUrl = context.getEndpoint() + "/stream?id=" + taskId;
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl))
                    .GET()
                    .header("Accept", "text/event-stream")
                    .header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-App-Id", context.getAppId() != null ? context.getAppId() : "");

            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            HttpRequest request = requestBuilder.build();
            log.info("[ATHENA] Connecting to stream: url={}, traceId={}", streamUrl, context.getTraceId());

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException("Athena stream connection failed: HTTP " + response.statusCode()));
                return;
            }

            if (lifecycle != null) {
                lifecycle.onConnected();
            }

            readAthenaStream(response.body(), lifecycle, onEvent, onError, taskId, context.getTraceId());

        } catch (Exception e) {
            log.error("[ATHENA] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    private String createTask(CloudConnectionContext context, String requestBody) throws Exception {
        String createUrl = context.getEndpoint() + "/tasks";
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(createUrl))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "")
                .timeout(Duration.ofSeconds(30));

        cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("[ATHENA] Create task failed: HTTP {}", response.statusCode());
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.path("data");
        return data.asText(null);
    }

    private void readAthenaStream(InputStream inputStream,
                                  CloudConnectionLifecycle lifecycle,
                                  Consumer<GatewayMessage> onEvent,
                                  Consumer<Throwable> onError,
                                  String taskId,
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
                    if (handleDataLine(line, lifecycle, onEvent, taskId, traceId)) {
                        return;
                    }
                }
            }
            log.info("[ATHENA] Stream completed: taskId={}, traceId={}", taskId, traceId);
        } catch (Exception e) {
            log.error("[ATHENA] Stream read error: taskId={}, traceId={}, error={}", taskId, traceId, e.getMessage());
            onError.accept(e);
        }
    }

    private boolean handleDataLine(String line, CloudConnectionLifecycle lifecycle,
                                   Consumer<GatewayMessage> onEvent, String taskId, String traceId) {
        String jsonData = line.substring(5).trim();
        if (jsonData.isEmpty() || "[DONE]".equals(jsonData)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(jsonData);
            String code = root.path("code").asText("");
            
            if (!"0".equals(code)) {
                String error = root.path("error").asText("Athena error");
                GatewayMessage errorMsg = GatewayMessage.toolError(taskId, error);
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                onEvent.accept(errorMsg);
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
                return true;
            }
            
            boolean isFinish = root.path("isFinish").asBoolean(false);
            JsonNode data = root.path("data");
            
            GatewayMessage message = parseAthenaData(data, taskId);
            if (message != null) {
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                onEvent.accept(message);
                
                if (isFinish) {
                    GatewayMessage toolDone = GatewayMessage.toolDone(taskId, null);
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                    onEvent.accept(toolDone);
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("[ATHENA] Failed to parse event: traceId={}, data={}, error={}",
                    traceId, jsonData, e.getMessage());
        }
        return false;
    }

    private GatewayMessage parseAthenaData(JsonNode data, String taskId) {
        if (data.isMissingNode()) {
            return null;
        }
        
        String type = data.path("type").asText("");
        String content = data.path("content").asText("");
        String planning = data.path("planning").asText("");
        
        com.fasterxml.jackson.databind.node.ObjectNode eventData = objectMapper.createObjectNode();
        
        switch (type) {
            case "text":
                eventData.put("type", "text.delta");
                eventData.set("properties", objectMapper.createObjectNode().put("content", content));
                break;
            case "planning":
                eventData.put("type", "thinking");
                eventData.set("properties", objectMapper.createObjectNode().put("content", planning));
                break;
            case "searching":
                eventData.put("type", "searching");
                eventData.set("properties", data);
                break;
            case "searchResult":
                eventData.put("type", "search_result");
                eventData.set("properties", data);
                break;
            case "reference":
                eventData.put("type", "reference");
                eventData.set("properties", data);
                break;
            case "think":
                eventData.put("type", "thinking");
                eventData.set("properties", objectMapper.createObjectNode().put("content", content));
                break;
            case "askMore":
                eventData.put("type", "ask_more");
                eventData.set("properties", data);
                break;
            default:
                return null;
        }
        
        return GatewayMessage.toolEvent(taskId, eventData);
    }

    private static void notifyLifecycle(CloudConnectionLifecycle lifecycle,
                                        Consumer<CloudConnectionLifecycle> action) {
        if (lifecycle != null) {
            action.accept(lifecycle);
        }
    }
}