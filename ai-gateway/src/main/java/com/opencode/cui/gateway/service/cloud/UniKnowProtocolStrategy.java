package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * UniKnow 协议策略实现。
 *
 * <p>UniKnow 使用 REST 协议（非流式），同步返回结果。
 *
 * <p>响应格式：
 * <pre>
 * {"data":[{"taskInfo":{"slots":{"result":{"data":"回答内容","requestId":"..."}}}}]}
 * </pre>
 *
 * <p>处理流程：
 * <ol>
 *   <li>发送 HTTP POST 请求</li>
 *   <li>同步等待响应</li>
 *   <li>解析响应并转换为 tool_event（文本内容）+ tool_done（完成）</li>
 * </ol>
 */
@Slf4j
@Component
public class UniKnowProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public UniKnowProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${gateway.cloud.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        this(cloudAuthService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
    }

    public UniKnowProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "uniknow";
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
                    .header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-App-Id", context.getAppId() != null ? context.getAppId() : "")
                    .timeout(Duration.ofSeconds(120));

            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            HttpRequest request = requestBuilder.build();
            log.info("[UNIKNOW] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            if (lifecycle != null) {
                lifecycle.onConnected();
            }

            HttpResponse<String> response = sendRequest(request);

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException("UniKnow connection failed: HTTP " + response.statusCode()));
                return;
            }

            parseUniKnowResponse(response.body(), lifecycle, onEvent, context.getTraceId());

        } catch (Exception e) {
            log.error("[UNIKNOW] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    private void parseUniKnowResponse(String responseBody,
                                     CloudConnectionLifecycle lifecycle,
                                     Consumer<GatewayMessage> onEvent,
                                     String traceId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.path("data");
            
            if (dataArray.isArray() && dataArray.size() > 0) {
                JsonNode firstItem = dataArray.get(0);
                JsonNode taskInfo = firstItem.path("taskInfo");
                JsonNode slots = taskInfo.path("slots");
                JsonNode result = slots.path("result");
                
                String answer = result.path("data").asText("");
                String requestId = result.path("requestId").asText("");
                
                if (!answer.isEmpty()) {
                    // 发送文本事件
                    JsonNode eventData = objectMapper.createObjectNode()
                            .put("type", "text.delta")
                            .set("properties", objectMapper.createObjectNode()
                                    .put("content", answer));
                    GatewayMessage toolEvent = GatewayMessage.toolEvent(requestId, eventData);
                    
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                    onEvent.accept(toolEvent);
                }
                
                // 发送完成事件
                GatewayMessage toolDone = GatewayMessage.toolDone(requestId, null);
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                onEvent.accept(toolDone);
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
                
                log.info("[UNIKNOW] Response processed: traceId={}, requestId={}", traceId, requestId);
            }
        } catch (Exception e) {
            log.warn("[UNIKNOW] Failed to parse response: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    protected HttpResponse<String> sendRequest(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void notifyLifecycle(CloudConnectionLifecycle lifecycle,
                                        Consumer<CloudConnectionLifecycle> action) {
        if (lifecycle != null) {
            action.accept(lifecycle);
        }
    }
}