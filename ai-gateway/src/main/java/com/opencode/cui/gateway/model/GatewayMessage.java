package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gateway WebSocket message protocol.
 *
 * Protocol-aligned fields:
 * - welinkSessionId: skill-side session id
 * - toolSessionId: OpenCode session id
 * - ak: routing key for the connected agent
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayMessage {

    /** Message type discriminator */
    private String type;

    /** Legacy/internal DB identifier; protocol routing uses ak instead */
    private String agentId;

    /** Agent AK identifier used for routing */
    private String ak;

    /** Skill session identifier from Layer2/3 protocol (String to prevent JS precision loss) */
    private String welinkSessionId;

    /** User identifier trusted by server-side routing */
    private String userId;

    /** Upstream source service identifier */
    private String source;

    /** Trace identifier for cross-service routing observability */
    private String traceId;

    /** Action for invoke messages: chat, create_session, close_session, ... */
    private String action;

    /** Payload for invoke or register messages */
    private JsonNode payload;

    /** OpenCode raw event (transparent relay) */
    private JsonNode event;

    /** Error description */
    private String error;

    /** Token usage information */
    private JsonNode usage;

    /** Sequence number for message ordering (multi-instance coordination) */
    private Long sequenceNumber;

    // --- Register message fields ---
    private String deviceName;
    private String macAddress;
    private String os;
    private String toolType;
    private String toolVersion;

    // --- Register response fields ---
    private String reason;

    // --- Tool/session fields ---
    private String toolSessionId;
    private JsonNode session;

    // --- Status response fields ---
    private Boolean opencodeOnline;

    public static GatewayMessage register(String deviceName, String macAddress,
            String os, String toolType, String toolVersion) {
        return GatewayMessage.builder()
                .type("register")
                .deviceName(deviceName)
                .macAddress(macAddress)
                .os(os)
                .toolType(toolType)
                .toolVersion(toolVersion)
                .build();
    }

    public static GatewayMessage registerOk() {
        return GatewayMessage.builder()
                .type("register_ok")
                .build();
    }

    public static GatewayMessage registerRejected(String reason) {
        return GatewayMessage.builder()
                .type("register_rejected")
                .reason(reason)
                .build();
    }

    public static GatewayMessage heartbeat() {
        return GatewayMessage.builder()
                .type("heartbeat")
                .build();
    }

    public static GatewayMessage toolEvent(String toolSessionId, JsonNode event) {
        return GatewayMessage.builder()
                .type("tool_event")
                .toolSessionId(toolSessionId)
                .event(event)
                .build();
    }

    public static GatewayMessage toolDone(String toolSessionId, JsonNode usage) {
        return GatewayMessage.builder()
                .type("tool_done")
                .toolSessionId(toolSessionId)
                .usage(usage)
                .build();
    }

    public static GatewayMessage toolError(String toolSessionId, String error) {
        return GatewayMessage.builder()
                .type("tool_error")
                .toolSessionId(toolSessionId)
                .error(error)
                .build();
    }

    public static GatewayMessage sessionCreated(String welinkSessionId, String toolSessionId) {
        return GatewayMessage.builder()
                .type("session_created")
                .welinkSessionId(welinkSessionId)
                .toolSessionId(toolSessionId)
                .build();
    }

    public static GatewayMessage agentOnline(String ak, String toolType, String toolVersion) {
        return GatewayMessage.builder()
                .type("agent_online")
                .ak(ak)
                .toolType(toolType)
                .toolVersion(toolVersion)
                .build();
    }

    public static GatewayMessage agentOffline(String ak) {
        return GatewayMessage.builder()
                .type("agent_offline")
                .ak(ak)
                .build();
    }

    public static GatewayMessage invoke(String ak, String welinkSessionId,
            String action, JsonNode payload) {
        return GatewayMessage.builder()
                .type("invoke")
                .ak(ak)
                .welinkSessionId(welinkSessionId)
                .action(action)
                .payload(payload)
                .build();
    }

    public static GatewayMessage statusQuery() {
        return GatewayMessage.builder()
                .type("status_query")
                .build();
    }

    public GatewayMessage withAgentId(String agentId) {
        return this.toBuilder()
                .agentId(agentId)
                .build();
    }

    public GatewayMessage withAk(String ak) {
        return this.toBuilder()
                .ak(ak)
                .build();
    }

    public GatewayMessage withSequenceNumber(Long sequenceNumber) {
        return this.toBuilder()
                .sequenceNumber(sequenceNumber)
                .build();
    }

    public GatewayMessage withUserId(String userId) {
        return this.toBuilder()
                .userId(userId)
                .build();
    }

    public GatewayMessage withSource(String source) {
        return this.toBuilder()
                .source(source)
                .build();
    }

    public GatewayMessage withTraceId(String traceId) {
        return this.toBuilder()
                .traceId(traceId)
                .build();
    }

    public GatewayMessage withoutUserId() {
        return this.toBuilder()
                .userId(null)
                .build();
    }

    public GatewayMessage withoutSource() {
        return this.toBuilder()
                .source(null)
                .build();
    }

    public GatewayMessage withoutRoutingContext() {
        return this.toBuilder()
                .userId(null)
                .source(null)
                .build();
    }
}
