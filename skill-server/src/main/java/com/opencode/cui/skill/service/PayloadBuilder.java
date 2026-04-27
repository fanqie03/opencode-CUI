package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 通用 JSON payload 构建工具。
 * 从 SkillMessageController / SkillSessionController 提取的共享逻辑。
 */
@Slf4j
public final class PayloadBuilder {

    private PayloadBuilder() {
        // utility class
    }

    /**
     * 将 key-value 映射序列化为 JSON 字符串，跳过 null 值。
     */
    public static String buildPayload(ObjectMapper objectMapper, Map<String, String> fields) {
        ObjectNode node = objectMapper.createObjectNode();
        fields.forEach((k, v) -> {
            if (v != null) {
                node.put(k, v);
            }
        });
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 支持嵌套对象 value 的 payload 构建工具。
     * 处理规则：
     * - fields 为 null → 返回 "{}"
     * - null 跳过（key 不写入）
     * - JsonNode.isNull() 跳过（NullNode 与 null 等价）
     * - JsonNode 其他形态（含 ObjectNode/ArrayNode/TextNode 等）→ node.set
     * - String → node.put
     * - 其他对象 → objectMapper.valueToTree
     *
     * <p>本重载用于 chat / question_reply / permission_reply 三个 action 的 payload 组装，
     * 透传 businessExtParam（自由 JSON 对象）所需。
     */
    public static String buildPayloadWithObjects(ObjectMapper objectMapper, Map<String, Object> fields) {
        if (fields == null) {
            return "{}";
        }
        ObjectNode node = objectMapper.createObjectNode();
        fields.forEach((k, v) -> {
            if (v == null) return;
            if (v instanceof JsonNode jn) {
                if (jn.isNull()) return;
                node.set(k, jn);
            } else if (v instanceof String s) {
                node.put(k, s);
            } else {
                node.set(k, objectMapper.valueToTree(v));
            }
        });
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload with objects: {}", e.getMessage());
            return "{}";
        }
    }
}
