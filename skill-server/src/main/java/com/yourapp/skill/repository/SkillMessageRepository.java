package com.yourapp.skill.repository;

import com.yourapp.skill.model.SkillMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SkillMessageRepository extends JpaRepository<SkillMessage, Long> {

    Page<SkillMessage> findBySessionIdOrderBySeqAsc(Long sessionId, Pageable pageable);

    @Query("SELECT COALESCE(MAX(m.seq), 0) FROM SkillMessage m WHERE m.sessionId = :sessionId")
    int findMaxSeqBySessionId(@Param("sessionId") Long sessionId);

    Optional<SkillMessage> findBySessionIdAndSeq(Long sessionId, Integer seq);

    long countBySessionId(Long sessionId);
}
