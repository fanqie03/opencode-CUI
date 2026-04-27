package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PayloadBuilderTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("buildPayloadWithObjects: String value 写入")
    void stringValueWritten() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", "v");
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("v", node.get("k").asText());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: JsonNode object 直接 set 不二次序列化")
    void jsonNodeObjectSetDirectly() throws Exception {
        ObjectNode inner = objectMapper.createObjectNode();
        inner.put("a", 1);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", inner);
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertTrue(node.get("k").isObject());
        assertEquals(1, node.get("k").get("a").asInt());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: Map/POJO value 走 valueToTree")
    void mapValueGoesValueToTree() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", Map.of("a", 1, "b", "x"));
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertTrue(node.get("k").isObject());
        assertEquals(1, node.get("k").get("a").asInt());
        assertEquals("x", node.get("k").get("b").asText());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: null value 跳过 key 不出现")
    void nullValueSkipped() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", null);
        fields.put("x", "y");
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertFalse(node.has("k"));
        assertEquals("y", node.get("x").asText());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: NullNode 与 null 等价跳过")
    void nullNodeEquivalentToNull() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", objectMapper.nullNode());
        fields.put("x", "y");
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertFalse(node.has("k"));
        assertEquals("y", node.get("x").asText());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: fields 为 null 时返回 \"{}\"，不抛 NPE")
    void nullFieldsReturnsEmptyJson() {
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, null);
        assertEquals("{}", json);
    }
}
