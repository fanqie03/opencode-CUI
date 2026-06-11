package com.opencode.cui.gateway.ws;

/**
 * Local-only metadata used to distinguish outbound WebSocket sender ownership.
 */
public record AsyncSenderIdentity(String channel, String peerType, String peerId) {

    private static final String UNKNOWN = "unknown";
    private static final AsyncSenderIdentity UNKNOWN_IDENTITY =
            new AsyncSenderIdentity(UNKNOWN, UNKNOWN, UNKNOWN);

    public static AsyncSenderIdentity unknown() {
        return UNKNOWN_IDENTITY;
    }

    public static AsyncSenderIdentity agent(String ak) {
        return new AsyncSenderIdentity("agent", "agent", normalize(ak));
    }

    public static AsyncSenderIdentity source(String sourceType, String sourceInstanceId) {
        return new AsyncSenderIdentity("source", normalize(sourceType), normalize(sourceInstanceId));
    }

    public static AsyncSenderIdentity orUnknown(AsyncSenderIdentity identity) {
        return identity == null ? UNKNOWN_IDENTITY : identity;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
