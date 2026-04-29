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
 * 标准协议策略实现。
 *
 * <p>标准协议使用 SSE（Server-Sent Events）协议，支持多种消息类型：
 * - text: 文本内容
 * - planning: 规划中
 * - searching: 搜索中
 * - searchResult: 搜索结果
 * - reference: 引用
 * - think: 深度思考
 * - askMore: 追问
 *
 * <p>响应格式：
 * <pre>
 * {"code":"0","message":"","error":"","isFinish":false,"data":{"type":"text","content":"..."}}
 * </pre>
 *
 * <p>处理流程：
 * <ol>
 *   <li>发送 HTTP POST 请求，Content-Type: application/json</li>
 *   <li>建立 SSE 连接接收流式响应</li>
 *   <li>解析每个 data 事件并转换为 GatewayMessage</li>
 *   <li>当 isFinish=true 时发送 tool_done 并结束连接</li>
 * </ol>
 */
@Slf4j
@Component
public class StandardProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public StandardProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${gateway.cloud.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        this(cloudAuthService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
    }

    public StandardProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "standard";
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
                    .header("X-App-Id", context.getAppId() != null ? context.getAppId() : "")
                    .timeout(Duration.ofSeconds(120));

            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            HttpRequest request = requestBuilder.build();
            log.info("[STANDARD] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException("Standard connection failed: HTTP " + response.statusCode()));
                return;
            }

            if (lifecycle != null) {
                lifecycle.onConnected();
            }

            String messageId = context.getCloudRequest().path("messageId").asText("");
            String topicId = context.getCloudRequest().path("topicId").asText("");
            String toolSessionId = messageId.isEmpty() ? topicId : messageId;

            readStandardStream(response.body(), lifecycle, onEvent, onError, context.getTraceId(), toolSessionId);

        } catch (Exception e) {
            log.error("[STANDARD] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    private void readStandardStream(InputStream inputStream,
                                   CloudConnectionLifecycle lifecycle,
                                   Consumer<GatewayMessage> onEvent,
                                   Consumer<Throwable> onError,
                                   String traceId,
                                   String toolSessionId) {
        boolean finished = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith(":")) {
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onHeartbeat);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (handleDataLine(line, lifecycle, onEvent, onError, traceId, toolSessionId)) {
                        finished = true;
                        return;
                    }
                }
            }
            log.info("[STANDARD] Stream completed: traceId={}", traceId);
        } catch (Exception e) {
            log.error("[STANDARD] Stream read error: traceId={}, error={}", traceId, e.getMessage());
            onError.accept(e);
            return;
        }
        
        if (!finished) {
            log.info("[STANDARD] Stream ended without isFinish=true, sending tool_done: traceId={}", traceId);
            GatewayMessage toolDone = GatewayMessage.toolDone(toolSessionId, null);
            notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
            onEvent.accept(toolDone);
            notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
        }
    }

    private boolean handleDataLine(String line, CloudConnectionLifecycle lifecycle,
                                   Consumer<GatewayMessage> onEvent,
                                   Consumer<Throwable> onError,
                                   String traceId,
                                   String toolSessionId) {
        String jsonData = line.substring(5).trim();
        if (jsonData.isEmpty() || "[DONE]".equals(jsonData)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(jsonData);
            String code = root.path("code").asText("");
            
            if (!"0".equals(code)) {
                String error = root.path("error").asText("Standard protocol error");
                GatewayMessage errorMsg = GatewayMessage.toolError(toolSessionId, error);
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                onEvent.accept(errorMsg);
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
                return true;
            }
            
            boolean isFinish = root.path("isFinish").asBoolean(false);
            JsonNode data = root.path("data");
            
            GatewayMessage message = parseStandardData(data, toolSessionId);
            if (message != null) {
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                onEvent.accept(message);
                
                if (isFinish) {
                    GatewayMessage toolDone = GatewayMessage.toolDone(toolSessionId, null);
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                    onEvent.accept(toolDone);
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("[STANDARD] Failed to parse event: traceId={}, data={}, error={}",
                    traceId, jsonData, e.getMessage());
        }
        return false;
    }

    private GatewayMessage parseStandardData(JsonNode data, String toolSessionId) {
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
        
        return GatewayMessage.toolEvent(toolSessionId, eventData);
    }

    private static void notifyLifecycle(CloudConnectionLifecycle lifecycle,
                                        Consumer<CloudConnectionLifecycle> action) {
        if (lifecycle != null) {
            action.accept(lifecycle);
        }
    }
}