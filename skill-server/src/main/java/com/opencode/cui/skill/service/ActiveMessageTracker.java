package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SaveMessageCommand;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active (in-flight) streamed assistant messages per session.
 *
 * Responsibilities:
 * - Maps each session to its current active DB message (identity tracking)
 * - Creates / resolves DB messages to establish stable message identity
 * - Applies message context (messageId, messageSeq, role) onto StreamMessages
 * - No longer manages user message dedup (removed: user messages are only saved at inbound entry points)
 *
 * Extracted from MessagePersistenceService to separate tracking concerns
 * from persistence logic.
 */
@Slf4j
@Component
public class ActiveMessageTracker {

    private final SkillMessageService messageService;

    private final ConcurrentHashMap<Long, ActiveMessageRef> activeMessages = new ConcurrentHashMap<>();

    public ActiveMessageTracker(SkillMessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Resolve (or create) the active DB message for a given session and
     * StreamMessage.
     * Also applies message context (messageId, messageSeq, role) onto the
     * StreamMessage.
     *
     * @return the active message reference, or null if msg is null/no type
     */
    public ActiveMessageRef resolveActiveMessage(Long sessionId, StreamMessage msg) {
        String requestedMessageId = ProtocolUtils.firstNonBlank(msg.getMessageId(), msg.getSourceMessageId());
        String role = ProtocolUtils.normalizeRole(msg.getRole());

        ActiveMessageRef active = activeMessages.get(sessionId);
        if (active != null) {
            if (requestedMessageId == null || requestedMessageId.equals(active.protocolMessageId())) {
                applyMessageContext(msg, active, role);
                return active;
            }

            finalizeActiveMessage(sessionId, active, "message_id_changed");
        }

        if (requestedMessageId != null) {
            SkillMessage existing = messageService.findBySessionIdAndMessageId(sessionId, requestedMessageId);
            if (existing != null) {
                ActiveMessageRef existingRef = new ActiveMessageRef(
                        existing.getId(),
                        existing.getMessageId(),
                        existing.getSeq());
                activeMessages.put(sessionId, existingRef);
                applyMessageContext(msg, existingRef, role);
                return existingRef;
            }
        }

        SkillMessage created = messageService.saveMessage(
                new SaveMessageCommand(
                        sessionId,
                        requestedMessageId,
                        toRoleEnum(role),
                        "",
                        toContentType(role),
                        null));
        ActiveMessageRef createdRef = new ActiveMessageRef(
                created.getId(),
                created.getMessageId(),
                created.getSeq());
        activeMessages.put(sessionId, createdRef);
        applyMessageContext(msg, createdRef, role);
        log.debug("Created active streamed message: sessionId={}, dbId={}, protocolId={}, seq={}",
                sessionId, createdRef.dbId(), createdRef.protocolMessageId(), createdRef.messageSeq());
        return createdRef;
    }

    /**
     * Clear active tracking for a session.
     */
    public void clearSession(Long sessionId) {
        activeMessages.remove(sessionId);
    }

    /**
     * Finalize the current assistant turn before a new user turn starts.
     */
    public void finalizeActiveAssistantTurn(Long sessionId) {
        ActiveMessageRef active = activeMessages.remove(sessionId);
        if (active == null) {
            return;
        }

        messageService.markMessageFinished(active.dbId());
        log.debug("Finalized dangling assistant message before user turn: sessionId={}, messageId={}, protocolId={}",
                sessionId, active.dbId(), active.protocolMessageId());
    }

    /**
     * Remove and finalize the current active message for a session (on session
     * idle/completed).
     *
     * @return the removed ActiveMessageRef, or null if none was active
     */
    public ActiveMessageRef removeAndFinalize(Long sessionId) {
        ActiveMessageRef active = activeMessages.remove(sessionId);
        if (active != null) {
            messageService.markMessageFinished(active.dbId());
            log.debug("Finalized assistant message on session status: sessionId={}, messageId={}, protocolId={}",
                    sessionId, active.dbId(), active.protocolMessageId());
        }
        return active;
    }

    // ==================== Internal Helpers ====================

    private void applyMessageContext(StreamMessage msg, ActiveMessageRef active, String role) {
        msg.setMessageId(active.protocolMessageId());
        msg.setMessageSeq(active.messageSeq());
        msg.setRole(role);
    }

    private void finalizeActiveMessage(Long sessionId, ActiveMessageRef active, String reason) {
        activeMessages.remove(sessionId, active);
        messageService.markMessageFinished(active.dbId());
        log.debug("Finalized active streamed message: sessionId={}, messageId={}, protocolId={}, reason={}",
                sessionId, active.dbId(), active.protocolMessageId(), reason);
    }

    private SkillMessage.Role toRoleEnum(String role) {
        return switch (role) {
            case "user" -> SkillMessage.Role.USER;
            case "system" -> SkillMessage.Role.SYSTEM;
            case "tool" -> SkillMessage.Role.TOOL;
            default -> SkillMessage.Role.ASSISTANT;
        };
    }

    private SkillMessage.ContentType toContentType(String role) {
        return switch (role) {
            case "user", "system" -> SkillMessage.ContentType.PLAIN;
            case "tool" -> SkillMessage.ContentType.CODE;
            default -> SkillMessage.ContentType.MARKDOWN;
        };
    }

    /**
     * Immutable reference to an active in-flight message.
     */
    public record ActiveMessageRef(Long dbId, String protocolMessageId, Integer messageSeq) {
    }
}
