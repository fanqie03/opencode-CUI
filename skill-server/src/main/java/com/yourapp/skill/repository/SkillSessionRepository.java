package com.yourapp.skill.repository;

import com.yourapp.skill.model.SkillSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SkillSessionRepository extends JpaRepository<SkillSession, Long> {

    Page<SkillSession> findByUserIdAndStatusInOrderByLastActiveAtDesc(
            Long userId, List<SkillSession.Status> statuses, Pageable pageable);

    Page<SkillSession> findByUserIdOrderByLastActiveAtDesc(Long userId, Pageable pageable);

    @Modifying
    @Query("UPDATE SkillSession s SET s.status = :status WHERE s.status = 'ACTIVE' AND s.lastActiveAt < :cutoff")
    int markIdleSessions(@Param("status") SkillSession.Status status,
                         @Param("cutoff") LocalDateTime cutoff);

    List<SkillSession> findByAgentId(Long agentId);
}
