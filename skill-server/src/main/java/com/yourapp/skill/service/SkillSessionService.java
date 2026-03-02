package com.yourapp.skill.service;

import com.yourapp.skill.model.SkillSession;
import com.yourapp.skill.repository.SkillSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    public SkillSession createSession(Long userId, Long skillDefinitionId, Long agentId,
                                      String title, String imChatId) {
        SkillSession session = SkillSession.builder()
                .userId(userId)
                .skillDefinitionId(skillDefinitionId)
                .agentId(agentId)
                .title(title)
                .imChatId(imChatId)
                .status(SkillSession.Status.ACTIVE)
                .build();

        SkillSession saved = sessionRepository.save(session);
        log.info("Created skill session: id={}, userId={}, skillDefId={}", saved.getId(), userId, skillDefinitionId);
        return saved;
    }

    /**
     * List sessions for a user with pagination.
     * If statuses is null or empty, returns all sessions for the user.
     */
    @Transactional(readOnly = true)
    public Page<SkillSession> listSessions(Long userId, List<SkillSession.Status> statuses, Pageable pageable) {
        if (statuses != null && !statuses.isEmpty()) {
            return sessionRepository.findByUserIdAndStatusInOrderByLastActiveAtDesc(userId, statuses, pageable);
        }
        return sessionRepository.findByUserIdOrderByLastActiveAtDesc(userId, pageable);
    }

    /**
     * Get a single session by ID.
     */
    @Transactional(readOnly = true)
    public SkillSession getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    /**
     * Close a session (set status to CLOSED).
     */
    @Transactional
    public SkillSession closeSession(Long sessionId) {
        SkillSession session = getSession(sessionId);
        session.setStatus(SkillSession.Status.CLOSED);
        session.touch();
        SkillSession saved = sessionRepository.save(session);
        log.info("Closed skill session: id={}", sessionId);
        return saved;
    }

    /**
     * Update the last_active_at timestamp for a session.
     */
    @Transactional
    public void touchSession(Long sessionId) {
        SkillSession session = getSession(sessionId);
        session.touch();
        sessionRepository.save(session);
    }

    /**
     * Update the tool_session_id on a session (set when OpenCode session is created).
     */
    @Transactional
    public SkillSession updateToolSessionId(Long sessionId, String toolSessionId) {
        SkillSession session = getSession(sessionId);
        session.setToolSessionId(toolSessionId);
        session.touch();
        return sessionRepository.save(session);
    }

    /**
     * Find sessions by agent ID (used when agent goes offline).
     */
    @Transactional(readOnly = true)
    public List<SkillSession> findByAgentId(Long agentId) {
        return sessionRepository.findByAgentId(agentId);
    }

    /**
     * Scheduled cleanup: mark ACTIVE sessions as IDLE if they have been inactive
     * beyond the configured idle timeout.
     */
    @Scheduled(fixedDelayString = "${skill.session.cleanup-interval-minutes:10}",
               timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupIdleSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);
        int count = sessionRepository.markIdleSessions(SkillSession.Status.IDLE, cutoff);
        if (count > 0) {
            log.info("Marked {} sessions as IDLE (inactive since before {})", count, cutoff);
        }
    }
}
