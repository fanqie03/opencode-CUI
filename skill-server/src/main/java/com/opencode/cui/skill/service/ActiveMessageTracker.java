package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SaveMessageCommand;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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

    /**
     * Per-session reentrant lock used to serialize finalize ↔ resolveActiveMessage on
     * the same session. Prevents the rollback-window race where a concurrent inbound
     * thread could swap in a new active ref before the failing finalize's
     * {@code restoreIfAbsent} runs (medium finding from Codex review v2).
     *
     * <p>Lock is reentrant so {@code resolveActiveMessage} can call into the private
     * {@code finalizeActiveMessage} on messageId switch without self-deadlocking.</p>
     */
    private final ConcurrentHashMap<Long, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /*
     * Known limitation (Codex review v3.1, residual MEDIUM race):
     *
     *   T1 (finalize) acquires the session lock, detaches active, releases the lock,
     *   then the surrounding @Transactional method returns and Spring is committing
     *   the tx (~50–200ms window).
     *   T2 (inbound persist) arrives in that window with a *different* messageId,
     *   acquires the lock, sees no active, creates a new placeholder.
     *   T1's tx then rolls back → afterCompletion's restoreIfAbsent fails because
     *   T2 already occupies the slot → the old buffered parts get rolled back into
     *   Redis but no active ref points at them anymore.
     *
     * Closing this race fully would require holding the session lock until tx
     * commit (cross-callback lock ownership) or a finalize state machine that
     * blocks new resolves on the same session — both significantly larger refactors.
     * For now we surface the failure via an ERROR log on restoreIfAbsent miss so
     * operations can detect it; concrete trigger requires three concurrent threads
     * + tx rollback + messageId mismatch within the same ~50ms window, which is
     * extremely rare in the OpenCode stream order.
     */

    private volatile BeforeFinalizeHook beforeFinalizeHook = (sid, ref) -> {};

    private <T> T withSessionLock(Long sessionId, Supplier<T> action) {
        if (sessionId == null) {
            return action.get();
        }
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void withSessionLock(Long sessionId, Runnable action) {
        withSessionLock(sessionId, () -> {
            action.run();
            return null;
        });
    }

    public ActiveMessageTracker(SkillMessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Hook invoked just before an active message is marked finished. Used by
     * {@link MessagePersistenceService} to durably persist any buffered parts
     * (Redis → MySQL) so they survive a placeholder/real-message swap or a
     * user-turn boundary.
     *
     * <p>Contract:
     * <ul>
     *   <li>If the hook throws, the caller MUST {@link #restoreIfAbsent} the
     *       detached active ref and propagate the exception. {@code markFinished}
     *       MUST NOT run, because the message is not yet durable.</li>
     *   <li>If the hook returns normally, {@code markFinished} runs.</li>
     * </ul>
     * </p>
     */
    @FunctionalInterface
    public interface BeforeFinalizeHook {
        void beforeFinalize(Long sessionId, ActiveMessageRef active);
    }

    public void setBeforeFinalizeHook(BeforeFinalizeHook hook) {
        this.beforeFinalizeHook = hook != null ? hook : (sid, ref) -> {};
    }

    /**
     * Atomically remove and return the current active ref for a session.
     */
    public ActiveMessageRef detachActive(Long sessionId) {
        return activeMessages.remove(sessionId);
    }

    /**
     * Read-only counterpart to {@link #resolveActiveMessage}: if a current
     * active ref already matches (or no messageId was specified) just apply its
     * messageId/seq/role onto the StreamMessage and return it. NEVER triggers a
     * finalize, NEVER creates a placeholder message. Used by outbound enrich so
     * the WS delivery path doesn't drag DB/Redis I/O along.
     *
     * @return the active ref if context was applied, or null when no usable
     *         active is on file (caller should not block delivery).
     */
    public ActiveMessageRef applyContextIfPresent(Long sessionId, StreamMessage msg) {
        if (sessionId == null || msg == null) {
            return null;
        }
        ActiveMessageRef active = activeMessages.get(sessionId);
        if (active == null) {
            return null;
        }
        String requestedMessageId = ProtocolUtils.firstNonBlank(msg.getMessageId(), msg.getSourceMessageId());
        if (requestedMessageId != null && !requestedMessageId.equals(active.protocolMessageId())) {
            // Outbound emit must not force a finalize on messageId mismatch — that's the
            // inbound persist path's job. Just leave the msg as-is so the front-end uses
            // whatever messageId the upstream event already carries.
            return null;
        }
        applyMessageContext(msg, active, ProtocolUtils.normalizeRole(msg.getRole()));
        return active;
    }

    /**
     * Restore a previously detached active ref, but only if no newer active has
     * appeared in the meantime. Used by finalize failure paths so a retry can
     * pick up the same in-flight message instead of orphaning it.
     */
    public boolean restoreIfAbsent(Long sessionId, ActiveMessageRef ref) {
        if (ref == null) {
            return false;
        }
        boolean restored = activeMessages.putIfAbsent(sessionId, ref) == null;
        if (!restored) {
            // See class-level "Known limitation" note: a concurrent resolve already
            // took the slot during the finalize → tx rollback window. Old buffer was
            // restored to Redis by rollbackFlush but the ref is now unreachable —
            // operations should monitor this signal.
            log.error("restoreIfAbsent lost the slot to a concurrent resolve: sessionId={}, dbId={}, protocolId={} — buffered parts may be orphaned in Redis",
                    sessionId, ref.dbId(), ref.protocolMessageId());
        }
        return restored;
    }

    /**
     * Run a critical section under the per-session lock. Used by tx
     * {@code afterCompletion} callbacks (which fire outside the original
     * finalize call stack) so a concurrent inbound resolveActiveMessage can't
     * slip in between rollback and restore on the same session.
     */
    public void runUnderSessionLock(Long sessionId, Runnable action) {
        withSessionLock(sessionId, action);
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
        return withSessionLock(sessionId, () -> doResolveActiveMessage(sessionId, msg));
    }

    private ActiveMessageRef doResolveActiveMessage(Long sessionId, StreamMessage msg) {
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
     * Clear active tracking for a session, running the finalize hook so any
     * buffered parts are persisted before the ref is dropped. Same hook
     * contract as {@link #finalizeActiveAssistantTurn(Long)}: hook failure
     * restores the ref and rethrows.
     */
    public void clearSession(Long sessionId) {
        withSessionLock(sessionId, () -> {
            ActiveMessageRef active = activeMessages.remove(sessionId);
            if (active == null) {
                return;
            }
            try {
                beforeFinalizeHook.beforeFinalize(sessionId, active);
            } catch (RuntimeException e) {
                restoreIfAbsent(sessionId, active);
                throw e;
            }
        });
    }

    /**
     * Finalize the current assistant turn before a new user turn starts.
     *
     * <p>Hook failure semantics: if {@link BeforeFinalizeHook#beforeFinalize}
     * throws, the active ref is restored and the exception is rethrown so the
     * caller can retry; {@code markMessageFinished} is NOT called because the
     * message has not been durably persisted.</p>
     */
    public void finalizeActiveAssistantTurn(Long sessionId) {
        withSessionLock(sessionId, () -> {
            ActiveMessageRef active = activeMessages.remove(sessionId);
            if (active == null) {
                return;
            }

            try {
                beforeFinalizeHook.beforeFinalize(sessionId, active);
            } catch (RuntimeException e) {
                restoreIfAbsent(sessionId, active);
                throw e;
            }
            messageService.markMessageFinished(active.dbId());
            log.debug("Finalized dangling assistant message before user turn: sessionId={}, messageId={}, protocolId={}",
                    sessionId, active.dbId(), active.protocolMessageId());
        });
    }

    /**
     * Remove and finalize the current active message for a session (on session
     * idle/completed). Same hook contract as
     * {@link #finalizeActiveAssistantTurn(Long)}.
     *
     * @return the removed ActiveMessageRef, or null if none was active
     */
    public ActiveMessageRef removeAndFinalize(Long sessionId) {
        return withSessionLock(sessionId, () -> {
            ActiveMessageRef active = activeMessages.remove(sessionId);
            if (active == null) {
                return null;
            }
            try {
                beforeFinalizeHook.beforeFinalize(sessionId, active);
            } catch (RuntimeException e) {
                restoreIfAbsent(sessionId, active);
                throw e;
            }
            messageService.markMessageFinished(active.dbId());
            log.debug("Finalized assistant message on session status: sessionId={}, messageId={}, protocolId={}",
                    sessionId, active.dbId(), active.protocolMessageId());
            return active;
        });
    }

    /**
     * Get the current active message reference for a session (without creating one).
     */
    public ActiveMessageRef getActiveMessage(Long sessionId) {
        return activeMessages.get(sessionId);
    }

    // ==================== Internal Helpers ====================

    private void applyMessageContext(StreamMessage msg, ActiveMessageRef active, String role) {
        msg.setMessageId(active.protocolMessageId());
        msg.setMessageSeq(active.messageSeq());
        msg.setRole(role);
    }

    private void finalizeActiveMessage(Long sessionId, ActiveMessageRef active, String reason) {
        if (!activeMessages.remove(sessionId, active)) {
            // A concurrent thread already swapped the active ref; the other thread
            // owns the finalize and we must not flush a stale snapshot.
            log.debug("Skip finalize: stale ref, sessionId={}, dbId={}, reason={}",
                    sessionId, active.dbId(), reason);
            return;
        }
        try {
            beforeFinalizeHook.beforeFinalize(sessionId, active);
        } catch (RuntimeException e) {
            restoreIfAbsent(sessionId, active);
            throw e;
        }
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
