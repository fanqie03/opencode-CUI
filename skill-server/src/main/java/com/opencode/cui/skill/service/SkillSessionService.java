package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SkillSessionService {

    private final SkillSessionRepository sessionRepository;
    private volatile GatewayRelayService gatewayRelayService;

    @Value("${skill.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    public SkillSessionService(SkillSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Set GatewayRelayService lazily to avoid circular dependency.
     */
    public void setGatewayRelayService(GatewayRelayService gatewayRelayService) {
        this.gatewayRelayService = gatewayRelayService;
    }

    /**
     * Create a new skill session.
     */
    @Transactional
    public SkillSession createSession(String userId, String ak,
            String title, String imGroupId) {
        SkillSession session = SkillSession.builder()
                .userId(userId)
                .ak(ak)
                .title(title)
                .imGroupId(imGroupId)
                .status(SkillSession.Status.ACTIVE)
                .build();

        sessionRepository.insert(session);
        log.info("Created skill session: id={}, userId={}, ak={}", session.getId(), userId, ak);
        return session;
    }

    /**
     * List sessions for a user with pagination and optional filters.
     */
    @Transactional(readOnly = true)
    public PageResult<SkillSession> listSessions(String userId, String ak, String imGroupId,
            String status, int page, int size) {
        int offset = page * size;
        List<String> statusNames = null;
        if (status != null && !status.isBlank()) {
            statusNames = List.of(status);
        }
        boolean hasFilters = (ak != null && !ak.isBlank())
                || (imGroupId != null && !imGroupId.isBlank())
                || statusNames != null;
        if (hasFilters) {
            List<SkillSession> content = sessionRepository.findByUserIdFiltered(
                    userId, ak, imGroupId, statusNames, offset, size);
            long total = sessionRepository.countByUserIdFiltered(
                    userId, ak, imGroupId, statusNames);
            return new PageResult<>(content, total, page, size);
        }
        List<SkillSession> content = sessionRepository.findByUserId(userId, offset, size);
        long total = sessionRepository.countByUserId(userId);
        return new PageResult<>(content, total, page, size);
    }

    /**
     * Get a single session by ID.
     */
    @Transactional(readOnly = true)
    public SkillSession getSession(Long sessionId) {
        SkillSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return session;
    }

    /**
     * Find all ACTIVE sessions for a user. Used by the protocol-level stream
     * endpoint to resume all live sessions on connect.
     */
    @Transactional(readOnly = true)
    public List<SkillSession> findActiveByUserId(String userId) {
        return sessionRepository.findActiveByUserId(userId);
    }

    /**
     * Close a session (set status to CLOSED).
     */
    @Transactional
    public SkillSession closeSession(Long sessionId) {
        sessionRepository.updateStatus(sessionId, SkillSession.Status.CLOSED.name());
        SkillSession session = getSession(sessionId);
        log.info("Closed skill session: id={}", sessionId);
        return session;
    }

    /**
     * Activate an IDLE session (IDLE → ACTIVE).
     * Called when a successful tool_event is received for an IDLE session.
     */
    @Transactional
    public boolean activateSession(Long sessionId) {
        int updated = sessionRepository.activateSession(sessionId);
        if (updated > 0) {
            log.info("Activated session: id={} (IDLE → ACTIVE)", sessionId);
            return true;
        }
        return false;
    }

    /**
     * Clear the toolSessionId for a session (when the tool session becomes
     * invalid).
     */
    @Transactional
    public void clearToolSessionId(Long sessionId) {
        sessionRepository.clearToolSessionId(sessionId);
        log.info("Cleared toolSessionId for session: id={}", sessionId);
    }

    /**
     * Update the last_active_at timestamp for a session.
     */
    @Transactional
    public void touchSession(Long sessionId) {
        sessionRepository.updateLastActiveAt(sessionId, LocalDateTime.now());
    }

    /**
     * Update the tool_session_id on a session (set when OpenCode session is
     * created).
     */
    @Transactional
    public SkillSession updateToolSessionId(Long sessionId, String toolSessionId) {
        sessionRepository.updateToolSessionId(sessionId, toolSessionId, LocalDateTime.now());
        return getSession(sessionId);
    }

    /**
     * Find sessions by AK (used when agent goes offline).
     */
    @Transactional(readOnly = true)
    public List<SkillSession> findByAk(String ak) {
        return sessionRepository.findByAk(ak);
    }

    /**
     * Find a session by its OpenCode tool session ID.
     * Used to route upstream messages from Gateway to the correct welink session.
     */
    @Transactional(readOnly = true)
    public SkillSession findByToolSessionId(String toolSessionId) {
        return sessionRepository.findByToolSessionId(toolSessionId);
    }

    /**
     * Find sessions by status (used at startup for Redis channel recovery).
     */
    @Transactional(readOnly = true)
    public List<SkillSession> findByStatus(SkillSession.Status status) {
        return sessionRepository.findByStatus(status.name());
    }

    /**
     * Scheduled cleanup: mark ACTIVE sessions as IDLE if they have been inactive
     * beyond the configured idle timeout. Also unsubscribes Redis channels.
     */
    @Scheduled(fixedDelayString = "${skill.session.cleanup-interval-minutes:10}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupIdleSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);

        // First, find which sessions will be marked idle
        List<Long> idleSessionIds = sessionRepository.findIdleSessionIds(cutoff);

        if (idleSessionIds.isEmpty()) {
            return;
        }

        // Mark them as IDLE in DB
        int count = sessionRepository.markIdleSessions(SkillSession.Status.IDLE.name(), cutoff);
        log.info("Marked {} sessions as IDLE (inactive since before {})", count, cutoff);

        // Unsubscribe Redis channels for idle sessions
        if (gatewayRelayService != null) {
            for (Long sessionId : idleSessionIds) {
                try {
                    gatewayRelayService.unsubscribeFromSession(sessionId.toString());
                } catch (Exception e) {
                    log.warn("Failed to unsubscribe Redis for idle session {}: {}",
                            sessionId, e.getMessage());
                }
            }
        }
    }
}
