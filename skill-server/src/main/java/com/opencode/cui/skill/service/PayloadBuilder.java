package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
}
