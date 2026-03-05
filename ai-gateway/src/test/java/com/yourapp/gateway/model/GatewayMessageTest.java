package com.yourapp.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GatewayMessage JSON serialization/deserialization tests.
 *
 * Verifies that messages can round-trip through Jackson and that
 * factory methods produce correct output.
 */
class GatewayMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testToolEventSerialization() throws Exception {
        JsonNode event = objectMapper.readTree("{\"type\":\"message.part.updated\",\"delta\":\"hello\"}");
        GatewayMessage msg = GatewayMessage.toolEvent("sess-42", event);

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("tool_event", deserialized.getType());
        assertEquals("sess-42", deserialized.getSessionId());
        assertNotNull(deserialized.getEvent());
        assertEquals("message.part.updated", deserialized.getEvent().get("type").asText());
    }

    @Test
    void testToolDoneSerialization() throws Exception {
        JsonNode usage = objectMapper.readTree("{\"input_tokens\":100,\"output_tokens\":50}");
        GatewayMessage msg = GatewayMessage.toolDone("sess-42", usage);

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("tool_done", deserialized.getType());
        assertEquals("sess-42", deserialized.getSessionId());
        assertNotNull(deserialized.getUsage());
    }

    @Test
    void testToolErrorSerialization() throws Exception {
        GatewayMessage msg = GatewayMessage.toolError("sess-42", "Connection refused");

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("tool_error", deserialized.getType());
        assertEquals("Connection refused", deserialized.getError());
    }

    @Test
    void testAgentOnlineSerialization() throws Exception {
        GatewayMessage msg = GatewayMessage.agentOnline("123", "OPENCODE", "1.0.0");

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("agent_online", deserialized.getType());
        assertEquals("123", deserialized.getAgentId());
        assertEquals("OPENCODE", deserialized.getToolType());
        assertEquals("1.0.0", deserialized.getToolVersion());
    }

    @Test
    void testAgentOfflineSerialization() throws Exception {
        GatewayMessage msg = GatewayMessage.agentOffline("123");

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("agent_offline", deserialized.getType());
        assertEquals("123", deserialized.getAgentId());
    }

    @Test
    void testInvokeSerialization() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"toolSessionId\":\"sess_abc\",\"text\":\"hello\"}");
        GatewayMessage msg = GatewayMessage.invoke("123", "42", "chat", payload);

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("invoke", deserialized.getType());
        assertEquals("123", deserialized.getAgentId());
        assertEquals("42", deserialized.getSessionId());
        assertEquals("chat", deserialized.getAction());
        assertEquals("hello", deserialized.getPayload().get("text").asText());
    }

    @Test
    void testWithAgentIdCreatesNewInstance() {
        GatewayMessage original = GatewayMessage.toolEvent("sess-42", null);
        GatewayMessage withAgent = original.withAgentId("agent-123");

        assertNull(original.getAgentId());
        assertEquals("agent-123", withAgent.getAgentId());
        assertEquals("sess-42", withAgent.getSessionId());
        assertEquals("tool_event", withAgent.getType());
    }

    @Test
    void testWithSequenceNumberCreatesNewInstance() {
        GatewayMessage original = GatewayMessage.toolEvent("sess-42", null);
        GatewayMessage withSeq = original.withSequenceNumber(5L);

        assertNull(original.getSequenceNumber());
        assertEquals(5L, withSeq.getSequenceNumber());
    }

    @Test
    void testNullFieldsNotSerialized() throws Exception {
        GatewayMessage msg = GatewayMessage.heartbeat();
        String json = objectMapper.writeValueAsString(msg);

        // heartbeat should only have "type" field, null fields excluded
        assertFalse(json.contains("\"sessionId\""));
        assertFalse(json.contains("\"agentId\""));
        assertFalse(json.contains("\"event\""));
        assertFalse(json.contains("\"payload\""));
        assertTrue(json.contains("\"heartbeat\""));
    }

    @Test
    void testRegisterFactoryMethod() {
        GatewayMessage msg = GatewayMessage.register("MyPC", "WINDOWS", "OPENCODE", "1.0.0");

        assertEquals("register", msg.getType());
        assertEquals("MyPC", msg.getDeviceName());
        assertEquals("WINDOWS", msg.getOs());
        assertEquals("OPENCODE", msg.getToolType());
        assertEquals("1.0.0", msg.getToolVersion());
    }

    @Test
    void testEnvelopeSerialization() throws Exception {
        MessageEnvelope.EnvelopeMetadata envelope = MessageEnvelope.EnvelopeMetadata.builder()
                .version("1.0.0")
                .messageId("msg-123")
                .timestamp("2026-03-06T00:00:00Z")
                .source("OPENCODE")
                .agentId("agent-1")
                .sessionId("sess-42")
                .sequenceNumber(1L)
                .sequenceScope("session")
                .build();

        GatewayMessage msg = GatewayMessage.builder()
                .envelope(envelope)
                .type("tool_event")
                .sessionId("sess-42")
                .build();

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertTrue(deserialized.hasEnvelope());
        assertEquals("1.0.0", deserialized.getEnvelope().getVersion());
        assertEquals("msg-123", deserialized.getEnvelope().getMessageId());
        assertEquals("OPENCODE", deserialized.getEnvelope().getSource());
    }

    @Test
    void testHasEnvelopeFalseForNull() {
        GatewayMessage msg = GatewayMessage.heartbeat();
        assertFalse(msg.hasEnvelope());
    }

    @Test
    void testGetEnvelopeOrDefaultWhenNoEnvelope() {
        GatewayMessage msg = GatewayMessage.builder()
                .type("tool_event")
                .agentId("123")
                .build();

        MessageEnvelope.EnvelopeMetadata env = msg.getEnvelopeOrDefault();
        assertNotNull(env);
        assertEquals("0.0.0", env.getVersion());
        assertEquals("123", env.getAgentId());
    }

    @Test
    void testSessionCreatedFactory() {
        GatewayMessage msg = GatewayMessage.sessionCreated("sess_abc123");

        assertEquals("session_created", msg.getType());
        assertEquals("sess_abc123", msg.getToolSessionId());
    }

    @Test
    void testStatusQueryFactory() {
        GatewayMessage msg = GatewayMessage.statusQuery();

        assertEquals("status_query", msg.getType());
        assertNull(msg.getAgentId());
    }
}
