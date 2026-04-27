package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.cloud.CloudRequestBuilder;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import com.opencode.cui.skill.logging.MdcHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 业务助手策略。
 * <ul>
 *   <li>invoke 通过 CloudRequestBuilder 构建云端请求体</li>
 *   <li>toolSessionId 使用 "cloud-" + UUID 预生成</li>
 *   <li>不需要 session_created 回调</li>
 *   <li>不需要 Agent 在线检查</li>
 *   <li>事件翻译使用 CloudEventTranslator</li>
 * </ul>
 */
@Slf4j
@Component
public class BusinessScopeStrategy implements AssistantScopeStrategy {

    private final CloudRequestBuilder cloudRequestBuilder;
    private final CloudEventTranslator cloudEventTranslator;
    private final ObjectMapper objectMapper;

    public BusinessScopeStrategy(CloudRequestBuilder cloudRequestBuilder,
                                 CloudEventTranslator cloudEventTranslator,
                                 ObjectMapper objectMapper) {
        this.cloudRequestBuilder = cloudRequestBuilder;
        this.cloudEventTranslator = cloudEventTranslator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getScope() {
        return "business";
    }

    @Override
    public String buildInvoke(InvokeCommand command, AssistantInfo info) {
        String appId = info.getAppId();

        // 从 command payload 提取 content
        String content = extractContent(command.payload());

        // 从 command payload 提取 toolSessionId 作为 topicId
        String toolSessionId = extractField(command.payload(), "toolSessionId");

        // 取业务方扩展参数（缺省 / 非 object → null，由下方兜底为 {}）
        JsonNode businessExtParam = extractObjectField(command.payload(), "businessExtParam");

        // 用 LinkedHashMap 保证 businessExtParam 序列化在 platformExtParam 之前（与协议文档示例一致）
        Map<String, Object> extParameters = new LinkedHashMap<>();
        extParameters.put("businessExtParam",
                businessExtParam != null ? businessExtParam : objectMapper.createObjectNode());
        extParameters.put("platformExtParam", objectMapper.createObjectNode());

        CloudRequestContext context = CloudRequestContext.builder()
                .content(content)
                .contentType("text")
                .topicId(toolSessionId)
                .assistantAccount(extractField(command.payload(), "assistantAccount"))
                .sendUserAccount(extractField(command.payload(), "sendUserAccount"))
                .imGroupId(extractField(command.payload(), "imGroupId"))
                .messageId(extractField(command.payload(), "messageId"))
                .clientLang("zh")
                .extParameters(extParameters)
                .build();

        ObjectNode cloudRequest = cloudRequestBuilder.buildCloudRequest(appId, context);

        // 构建 payload：GW 的 CloudAgentService 期望 payload.cloudRequest 和 payload.toolSessionId
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("cloudRequest", cloudRequest);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            payload.put("toolSessionId", toolSessionId);
        }

        // 包装为 Gateway invoke 消息格式
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "invoke");
        message.put("ak", command.ak());
        message.put("source", "skill-server");
        message.put("action", command.action());
        message.put("assistantScope", "business");
        if (command.userId() != null && !command.userId().isBlank()) {
            message.put("userId", command.userId());
        }

        // 注入 traceId：从 MDC 获取或自动生成，确保跨服务链路可追踪
        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        message.set("payload", payload);

        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize business invoke message: ak={}, appId={}",
                    command.ak(), appId, e);
            return null;
        }
    }

    @Override
    public String generateToolSessionId() {
        return "cloud-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public boolean requiresSessionCreatedCallback() {
        return false;
    }

    @Override
    public boolean requiresOnlineCheck() {
        return false;
    }

    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        return cloudEventTranslator.translate(event, sessionId);
    }

    // ------------------------------------------------------------------ private

    private String extractField(String payload, String fieldName) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode field = node.path(fieldName);
            return field.isMissingNode() || field.isNull() ? null : field.asText();
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse payload for field extraction: field={}, error={}", fieldName, e.getMessage());
            return null;
        }
    }

    /**
     * 从 payload string 中安全提取嵌套 JSON object 字段。
     * 返回 null 触发上层兜底为 {}：
     * - payload null/blank → null
     * - readTree 失败 → null + DEBUG 日志
     * - 字段缺失 / NullNode → null
     * - 字段非 object（string/array/number/bool）→ null + WARN 日志
     */
    private JsonNode extractObjectField(String payload, String fieldName) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode field = node.path(fieldName);
            if (field.isMissingNode() || field.isNull()) {
                return null;
            }
            if (!field.isObject()) {
                log.warn("{} is not a JSON object, treating as empty: actualType={}, value={}",
                        fieldName, field.getNodeType(), field);
                return null;
            }
            return field;
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse payload for object field extraction: field={}, error={}",
                    fieldName, e.getMessage());
            return null;
        }
    }

    private String extractContent(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("text").asText(node.path("content").asText(node.path("message").asText("")));
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse payload for content extraction, using raw: {}", e.getMessage());
            return payload;
        }
    }
}
