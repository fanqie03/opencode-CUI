package com.opencode.cui.gateway.model;

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
        assertEquals("sess-42", deserialized.getToolSessionId());
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
        assertEquals("sess-42", deserialized.getToolSessionId());
        assertNotNull(deserialized.getUsage());
    }

    @Test
    void testToolErrorSerialization() throws Exception {
        GatewayMessage msg = GatewayMessage.toolError("sess-42", "Connection refused");

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("tool_error", deserialized.getType());
        assertNull(deserialized.getWelinkSessionId());
        assertEquals("sess-42", deserialized.getToolSessionId());
        assertEquals("Connection refused", deserialized.getError());
    }

    @Test
    void testAgentOnlineSerialization() throws Exception {
        GatewayMessage msg = GatewayMessage.agentOnline("ak_test_001", "channel", "1.0.0");

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("agent_online", deserialized.getType());
        assertEquals("ak_test_001", deserialized.getAk());
        assertEquals("channel", deserialized.getToolType());
        assertEquals("1.0.0", deserialized.getToolVersion());
    }

    @Test
    void testAgentOfflineSerialization() throws Exception {
        GatewayMessage msg = GatewayMessage.agentOffline("ak_test_001");

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("agent_offline", deserialized.getType());
        assertEquals("ak_test_001", deserialized.getAk());
    }

    @Test
    void testInvokeSerialization() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"toolSessionId\":\"sess_abc\",\"text\":\"hello\"}");
        GatewayMessage msg = GatewayMessage.invoke("ak_test_001", "42", "chat", payload).withUserId("user-1");

        String json = objectMapper.writeValueAsString(msg);
        GatewayMessage deserialized = objectMapper.readValue(json, GatewayMessage.class);

        assertEquals("invoke", deserialized.getType());
        assertEquals("ak_test_001", deserialized.getAk());
        assertEquals("42", deserialized.getWelinkSessionId());
        assertEquals("user-1", deserialized.getUserId());
        assertEquals("chat", deserialized.getAction());
        assertEquals("hello", deserialized.getPayload().get("text").asText());
    }

    @Test
    void testWithAgentIdCreatesNewInstance() {
        GatewayMessage original = GatewayMessage.toolEvent("sess-42", null);
        GatewayMessage withAgent = original.withAgentId("agent-123");

        assertNull(original.getAgentId());
        assertEquals("agent-123", withAgent.getAgentId());
        assertEquals("sess-42", withAgent.getToolSessionId());
        assertEquals("tool_event", withAgent.getType());
    }

    @Test
    void testWithAkCreatesNewInstance() {
        GatewayMessage original = GatewayMessage.toolEvent("sess-42", null);
        GatewayMessage withAk = original.withAk("ak_test_001");

        assertNull(original.getAk());
        assertEquals("ak_test_001", withAk.getAk());
        assertEquals("sess-42", withAk.getToolSessionId());
        assertEquals("tool_event", withAk.getType());
    }

    @Test
    void testWithSequenceNumberCreatesNewInstance() {
        GatewayMessage original = GatewayMessage.toolEvent("sess-42", null);
        GatewayMessage withSeq = original.withSequenceNumber(5L);

        assertNull(original.getSequenceNumber());
        assertEquals(5L, withSeq.getSequenceNumber());
    }

    @Test
    void testWithoutUserIdCreatesNewInstance() {
        GatewayMessage original = GatewayMessage.toolEvent("sess-42", null).withUserId("user-1");
        GatewayMessage stripped = original.withoutUserId();

        assertEquals("user-1", original.getUserId());
        assertNull(stripped.getUserId());
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
        GatewayMessage msg = GatewayMessage.register("MyPC", "AA:BB:CC:DD:EE:FF", "WINDOWS", "channel", "1.0.0");

        assertEquals("register", msg.getType());
        assertEquals("MyPC", msg.getDeviceName());
        assertEquals("AA:BB:CC:DD:EE:FF", msg.getMacAddress());
        assertEquals("WINDOWS", msg.getOs());
        assertEquals("channel", msg.getToolType());
        assertEquals("1.0.0", msg.getToolVersion());
    }

    @Test
    void testRegisterOkFactory() {
        GatewayMessage msg = GatewayMessage.registerOk();
        assertEquals("register_ok", msg.getType());
    }

    @Test
    void testRegisterRejectedFactory() {
        GatewayMessage msg = GatewayMessage.registerRejected("duplicate_connection");
        assertEquals("register_rejected", msg.getType());
        assertEquals("duplicate_connection", msg.getReason());
    }

    @Test
    void testSessionCreatedFactory() {
        GatewayMessage msg = GatewayMessage.sessionCreated("42", "sess_abc123");

        assertEquals("session_created", msg.getType());
        assertEquals("42", msg.getWelinkSessionId());
        assertEquals("sess_abc123", msg.getToolSessionId());
    }

    @Test
    void testStatusQueryFactory() {
        GatewayMessage msg = GatewayMessage.statusQuery();

        assertEquals("status_query", msg.getType());
        assertNull(msg.getAgentId());
    }
}
