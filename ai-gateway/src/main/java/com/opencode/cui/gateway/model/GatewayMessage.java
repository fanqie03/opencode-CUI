package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Gateway WebSocket message protocol.
 *
 * Upstream (PCAgent -> Gateway):
 * register, heartbeat, tool_event, tool_done, tool_error, session_created
 *
 * Downstream (Gateway -> PCAgent):
 * invoke, status_query
 *
 * Internal (Gateway <-> Skill Server):
 * tool_event, tool_done, tool_error, agent_online, agent_offline,
 * session_created, invoke
 *
 * Protocol Evolution:
 * - Legacy format: flat fields (type, sessionId, event, etc.)
 * - Envelope format: envelope + type + payload (unified protocol)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayMessage {

    /** Unified protocol envelope (optional, for envelope-aware clients) */
    private MessageEnvelope.EnvelopeMetadata envelope;

    /** Message type discriminator */
    private String type;

    /** Agent connection ID (used for DB operations) */
    private String agentId;

    /** Agent AK identifier (used for routing — agent:{ak} Redis channel) */
    private String ak;

    /** Session identifier */
    private String sessionId;

    /** Action for invoke messages: chat, create_session, close_session */
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
    private String os;
    private String toolType;
    private String toolVersion;

    // --- Session created field ---
    private String toolSessionId;

    // --- Session created: full session details (transparent relay to Skill Server)
    // ---
    private JsonNode session;

    // --- Status response fields ---
    private Boolean opencodeOnline;

    // ========== Static factory methods ==========

    /** PCAgent -> Gateway: register device */
    public static GatewayMessage register(String deviceName, String os,
            String toolType, String toolVersion) {
        return GatewayMessage.builder()
                .type("register")
                .deviceName(deviceName)
                .os(os)
                .toolType(toolType)
                .toolVersion(toolVersion)
                .build();
    }

    /** PCAgent -> Gateway: heartbeat */
    public static GatewayMessage heartbeat() {
        return GatewayMessage.builder()
                .type("heartbeat")
                .build();
    }

    /** PCAgent -> Gateway: OpenCode tool event (transparent relay) */
    public static GatewayMessage toolEvent(String sessionId, JsonNode event) {
        return GatewayMessage.builder()
                .type("tool_event")
                .sessionId(sessionId)
                .event(event)
                .build();
    }

    /** PCAgent -> Gateway: tool execution completed */
    public static GatewayMessage toolDone(String sessionId, JsonNode usage) {
        return GatewayMessage.builder()
                .type("tool_done")
                .sessionId(sessionId)
                .usage(usage)
                .build();
    }

    /** PCAgent -> Gateway: tool execution error */
    public static GatewayMessage toolError(String sessionId, String error) {
        return GatewayMessage.builder()
                .type("tool_error")
                .sessionId(sessionId)
                .error(error)
                .build();
    }

    /** PCAgent -> Gateway: session created on OpenCode side */
    public static GatewayMessage sessionCreated(String toolSessionId) {
        return GatewayMessage.builder()
                .type("session_created")
                .toolSessionId(toolSessionId)
                .build();
    }

    /** Gateway -> Skill Server: agent came online */
    public static GatewayMessage agentOnline(String ak, String toolType, String toolVersion) {
        return GatewayMessage.builder()
                .type("agent_online")
                .ak(ak)
                .toolType(toolType)
                .toolVersion(toolVersion)
                .build();
    }

    /** Gateway -> Skill Server: agent went offline */
    public static GatewayMessage agentOffline(String ak) {
        return GatewayMessage.builder()
                .type("agent_offline")
                .ak(ak)
                .build();
    }

    /** Skill Server -> Gateway -> PCAgent: invoke an action */
    public static GatewayMessage invoke(String agentId, String sessionId,
            String action, JsonNode payload) {
        return GatewayMessage.builder()
                .type("invoke")
                .agentId(agentId)
                .sessionId(sessionId)
                .action(action)
                .payload(payload)
                .build();
    }

    /** Gateway -> PCAgent: query agent status */
    public static GatewayMessage statusQuery() {
        return GatewayMessage.builder()
                .type("status_query")
                .build();
    }

    /**
     * Attach agentId to an existing message (for relay to Skill Server).
     * Returns a new instance with agentId set.
     */
    public GatewayMessage withAgentId(String agentId) {
        GatewayMessage copy = GatewayMessage.builder()
                .envelope(this.envelope)
                .type(this.type)
                .agentId(agentId)
                .ak(this.ak)
                .sessionId(this.sessionId)
                .action(this.action)
                .payload(this.payload)
                .event(this.event)
                .error(this.error)
                .usage(this.usage)
                .sequenceNumber(this.sequenceNumber)
                .deviceName(this.deviceName)
                .os(this.os)
                .toolType(this.toolType)
                .toolVersion(this.toolVersion)
                .toolSessionId(this.toolSessionId)
                .session(this.session)
                .opencodeOnline(this.opencodeOnline)
                .build();
        return copy;
    }

    /**
     * Attach ak to an existing message (for relay to Skill Server).
     * Returns a new instance with ak set.
     */
    public GatewayMessage withAk(String ak) {
        GatewayMessage copy = GatewayMessage.builder()
                .envelope(this.envelope)
                .type(this.type)
                .agentId(this.agentId)
                .ak(ak)
                .sessionId(this.sessionId)
                .action(this.action)
                .payload(this.payload)
                .event(this.event)
                .error(this.error)
                .usage(this.usage)
                .sequenceNumber(this.sequenceNumber)
                .deviceName(this.deviceName)
                .os(this.os)
                .toolType(this.toolType)
                .toolVersion(this.toolVersion)
                .toolSessionId(this.toolSessionId)
                .session(this.session)
                .opencodeOnline(this.opencodeOnline)
                .build();
        return copy;
    }

    /**
     * Attach sequence number to an existing message (for multi-instance
     * coordination).
     * Returns a new instance with sequenceNumber set.
     */
    public GatewayMessage withSequenceNumber(Long sequenceNumber) {
        GatewayMessage copy = GatewayMessage.builder()
                .envelope(this.envelope)
                .type(this.type)
                .agentId(this.agentId)
                .ak(this.ak)
                .sessionId(this.sessionId)
                .action(this.action)
                .payload(this.payload)
                .event(this.event)
                .error(this.error)
                .usage(this.usage)
                .sequenceNumber(sequenceNumber)
                .deviceName(this.deviceName)
                .os(this.os)
                .toolType(this.toolType)
                .toolVersion(this.toolVersion)
                .toolSessionId(this.toolSessionId)
                .session(this.session)
                .opencodeOnline(this.opencodeOnline)
                .build();
        return copy;
    }

    /**
     * Check if this message has a valid envelope.
     */
    @JsonIgnore
    public boolean hasEnvelope() {
        return envelope != null && envelope.getVersion() != null;
    }

    /**
     * Get envelope or return a default with minimal metadata.
     */
    @JsonIgnore
    public MessageEnvelope.EnvelopeMetadata getEnvelopeOrDefault() {
        if (hasEnvelope()) {
            return envelope;
        }
        return MessageEnvelope.EnvelopeMetadata.builder()
                .version("0.0.0")
                .messageId("unknown")
                .timestamp("")
                .source("UNKNOWN")
                .agentId(agentId != null ? agentId : "unknown")
                .sessionId(sessionId)
                .sequenceNumber(sequenceNumber != null ? sequenceNumber : 0L)
                .sequenceScope("agent")
                .build();
    }
}
