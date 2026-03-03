package com.yourapp.skill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks message sequence numbers per session to detect gaps and trigger recovery.
 *
 * Three-tier recovery strategy:
 * - 1-3 missing: log + continue (acceptable loss for streaming)
 * - 4-10 missing: request recovery (significant gap)
 * - >10 missing: trigger reconnect (major desync)
 */
@Slf4j
@Service
public class SequenceTracker {

    /** Track last received sequence number per session */
    private final Map<String, AtomicLong> lastSequences = new ConcurrentHashMap<>();

    /** Track gap count per session for recovery decisions */
    private final Map<String, AtomicLong> gapCounts = new ConcurrentHashMap<>();

    /**
     * Validate and track sequence number for a session.
     *
     * @param sessionId the session ID
     * @param sequenceNumber the received sequence number (null if not present)
     * @return recovery action: "continue", "request_recovery", or "reconnect"
     */
    public String validateSequence(String sessionId, Long sequenceNumber) {
        if (sequenceNumber == null) {
            log.debug("No sequence number in message for session {}", sessionId);
            return "continue";
        }

        AtomicLong lastSeq = lastSequences.computeIfAbsent(
                sessionId, k -> new AtomicLong(0));
        AtomicLong gapCount = gapCounts.computeIfAbsent(
                sessionId, k -> new AtomicLong(0));

        long last = lastSeq.get();
        long expected = last + 1;

        if (sequenceNumber == expected) {
            // Perfect sequence - reset gap counter
            lastSeq.set(sequenceNumber);
            gapCount.set(0);
            return "continue";
        }

        if (sequenceNumber <= last) {
            // Duplicate or out-of-order (ignore)
            log.debug("Duplicate/out-of-order message for session {}: received={}, last={}",
                    sessionId, sequenceNumber, last);
            return "continue";
        }

        // Gap detected
        long gap = sequenceNumber - expected;
        lastSeq.set(sequenceNumber);
        long totalGaps = gapCount.addAndGet(gap);

        log.warn("Sequence gap detected for session {}: expected={}, received={}, gap={}, totalGaps={}",
                sessionId, expected, sequenceNumber, gap, totalGaps);

        // Three-tier recovery
        if (gap <= 3) {
            log.info("Small gap (1-3) for session {}: continuing", sessionId);
            return "continue";
        } else if (gap <= 10) {
            log.warn("Medium gap (4-10) for session {}: requesting recovery", sessionId);
            return "request_recovery";
        } else {
            log.error("Large gap (>10) for session {}: triggering reconnect", sessionId);
            return "reconnect";
        }
    }

    /**
     * Reset tracking for a session (on reconnect or session close).
     */
    public void resetSession(String sessionId) {
        lastSequences.remove(sessionId);
        gapCounts.remove(sessionId);
        log.debug("Reset sequence tracking for session {}", sessionId);
    }

    /**
     * Get the last sequence number for a session (for monitoring).
     */
    public long getLastSequence(String sessionId) {
        AtomicLong lastSeq = lastSequences.get(sessionId);
        return lastSeq != null ? lastSeq.get() : 0;
    }

    /**
     * Get the total gap count for a session (for monitoring).
     */
    public long getGapCount(String sessionId) {
        AtomicLong gapCount = gapCounts.get(sessionId);
        return gapCount != null ? gapCount.get() : 0;
    }
}
