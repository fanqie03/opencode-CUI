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

    @Value("${skill.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    public SkillSessionService(SkillSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Create a new skill session.
     */
    @Transactional
    public SkillSession createSession(Long userId, String ak,
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
     * List sessions for a user with pagination.
     * If statuses is null or empty, returns all sessions for the user.
     */
    @Transactional(readOnly = true)
    public PageResult<SkillSession> listSessions(Long userId, List<SkillSession.Status> statuses,
            int page, int size) {
        int offset = page * size;
        if (statuses != null && !statuses.isEmpty()) {
            List<String> statusNames = statuses.stream()
                    .map(SkillSession.Status::name)
                    .collect(Collectors.toList());
            List<SkillSession> content = sessionRepository.findByUserIdAndStatusIn(userId, statusNames, offset, size);
            long total = sessionRepository.countByUserIdAndStatusIn(userId, statusNames);
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
     * beyond the configured idle timeout.
     */
    @Scheduled(fixedDelayString = "${skill.session.cleanup-interval-minutes:10}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupIdleSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);
        int count = sessionRepository.markIdleSessions(SkillSession.Status.IDLE.name(), cutoff);
        if (count > 0) {
            log.info("Marked {} sessions as IDLE (inactive since before {})", count, cutoff);
        }
    }
}
