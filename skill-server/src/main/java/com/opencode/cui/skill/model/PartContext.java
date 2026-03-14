package com.opencode.cui.skill.model;

/**
 * Encapsulates the common context fields passed between translateXxx methods
 * in OpenCodeEventTranslator, reducing parameter count.
 */
public record PartContext(
        String sessionId,
        String messageId,
        String partId,
        Integer partSeq,
        String role) {
}
