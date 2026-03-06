package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Unified protocol envelope for cross-IDE event streaming.
 *
 * Wraps all messages exchanged between PC Agent, AI-Gateway, and Skill Server
 * to provide consistent metadata, versioning, and sequence tracking.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageEnvelope {

    /**
     * Envelope metadata attached to every protocol message.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EnvelopeMetadata {
        /** Protocol version (semver format, e.g., "1.0.0") */
        private String version;

        /** Unique message identifier (UUID v4) */
        private String messageId;

        /** ISO 8601 timestamp of message creation */
        private String timestamp;

        /** Source IDE/tool that generated this message */
        private String source;

        /** Agent connection ID (assigned by gateway on registration) */
        private String agentId;

        /** Session identifier (optional, for session-scoped messages) */
        private String sessionId;

        /** Sequence number for ordering within a scope */
        private Long sequenceNumber;

        /** Scope for sequence numbering: 'session' or 'agent' */
        private String sequenceScope;
    }

    /** Envelope metadata */
    private EnvelopeMetadata envelope;

    /** Message type discriminator (e.g., 'tool_event', 'invoke', 'tool_done') */
    private String type;

    /** Message payload (type-specific content) */
    private Object payload;

    /**
     * Check if this message has a valid envelope.
     */
    public boolean hasEnvelope() {
        return envelope != null && envelope.getVersion() != null;
    }

    /**
     * Get envelope or return a default with minimal metadata.
     */
    public EnvelopeMetadata getEnvelopeOrDefault() {
        if (hasEnvelope()) {
            return envelope;
        }
        return EnvelopeMetadata.builder()
                .version("0.0.0")
                .messageId("unknown")
                .timestamp("")
                .source("UNKNOWN")
                .agentId("unknown")
                .sequenceNumber(0L)
                .sequenceScope("agent")
                .build();
    }
}
