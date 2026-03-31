package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wrapper message used for GW-to-GW relay over Redis pub/sub.
 *
 * <p>Channel pattern: {@code gw:relay:{instanceId}}
 *
 * <p>When an {@code invoke} arrives at GW-A but the target Agent is connected to GW-B,
 * GW-A publishes a {@code RelayMessage} to {@code gw:relay:{gwB-instanceId}}.
 * GW-B receives it, extracts {@code originalMessage}, and delivers to the local Agent.
 *
 * <p>Supports two relay types:
 * <ul>
 *   <li><b>to-agent</b> (default): delivers {@code originalMessage} to a local Agent session.</li>
 *   <li><b>to-source</b>: delivers {@code originalMessage} to a local Source WebSocket connection
 *       identified by {@code targetSourceType} and {@code targetSourceInstanceId}.</li>
 * </ul>
 *
 * <p>Note: the legacy upstream path (Agent->SS) uses a separate channel
 * {@code gw:legacy-relay:{instanceId}} with raw {@code GatewayMessage} JSON,
 * and does not use this wrapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayMessage(
        /** Always {@code "relay"} -- used to distinguish from legacy raw GatewayMessage JSON. */
        String type,

        /**
         * Originating service type, e.g. {@code "skill-server"}.
         * Used for routing-learning propagation; may be {@code null} for direct GW-to-GW relay.
         */
        String sourceType,

        /**
         * Routing keys carried for upstream-routing-table learning,
         * e.g. {@code ["ts-abc", "w:42"]}. May be {@code null} when not applicable.
         */
        List<String> routingKeys,

        /** The original {@link GatewayMessage} serialized as JSON string. */
        String originalMessage,

        /**
         * Relay direction: {@code "to-agent"} (default/null) or {@code "to-source"}.
         * When null or absent, treated as {@code "to-agent"} for backward compatibility.
         */
        String relayType,

        /**
         * Target source type for {@code "to-source"} relay, e.g. {@code "skill-server"}.
         * Only used when {@code relayType} is {@code "to-source"}.
         */
        String targetSourceType,

        /**
         * Target source instance ID for {@code "to-source"} relay.
         * Only used when {@code relayType} is {@code "to-source"}.
         */
        String targetSourceInstanceId
) {

    /** Canonical type discriminator value. */
    public static final String TYPE = "relay";

    /** Relay type: deliver to Agent (default). */
    public static final String RELAY_TO_AGENT = "to-agent";

    /** Relay type: deliver to Source WebSocket connection. */
    public static final String RELAY_TO_SOURCE = "to-source";

    /**
     * Factory: creates a minimal relay message (no routing-learning metadata).
     *
     * @param originalMessageJson JSON string of the GatewayMessage to be relayed
     * @return a new RelayMessage with {@code type="relay"}
     */
    public static RelayMessage of(String originalMessageJson) {
        return new RelayMessage(TYPE, null, null, originalMessageJson, null, null, null);
    }

    /**
     * Factory: creates a relay message with routing-learning metadata (to-agent).
     *
     * @param sourceType          originating service type
     * @param routingKeys         routing keys for upstream-routing-table learning
     * @param originalMessageJson JSON string of the GatewayMessage to be relayed
     * @return a new RelayMessage
     */
    public static RelayMessage of(String sourceType, List<String> routingKeys, String originalMessageJson) {
        return new RelayMessage(TYPE, sourceType, routingKeys, originalMessageJson, null, null, null);
    }

    /**
     * Factory: creates a to-source relay message.
     *
     * @param targetSourceType       target source type, e.g. "skill-server"
     * @param targetSourceInstanceId target source instance ID
     * @param payload                the message payload to deliver to the source
     * @return a new RelayMessage with {@code relayType="to-source"}
     */
    public static RelayMessage toSource(String targetSourceType, String targetSourceInstanceId, String payload) {
        return new RelayMessage(TYPE, null, null, payload, RELAY_TO_SOURCE, targetSourceType, targetSourceInstanceId);
    }
}
