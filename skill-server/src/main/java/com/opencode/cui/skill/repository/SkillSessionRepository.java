package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SkillSessionRepository {

        SkillSession findById(@Param("id") Long id);

        List<SkillSession> findByUserId(@Param("userId") String userId,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        List<SkillSession> findByUserIdAndStatusIn(@Param("userId") String userId,
                        @Param("statuses") List<String> statuses,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        List<SkillSession> findActiveByUserId(@Param("userId") String userId);

        long countByUserId(@Param("userId") String userId);

        long countByUserIdAndStatusIn(@Param("userId") String userId,
                        @Param("statuses") List<String> statuses);

        List<SkillSession> findByUserIdFiltered(@Param("userId") String userId,
                        @Param("ak") String ak,
                        @Param("imGroupId") String imGroupId,
                        @Param("statuses") List<String> statuses,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        long countByUserIdFiltered(@Param("userId") String userId,
                        @Param("ak") String ak,
                        @Param("imGroupId") String imGroupId,
                        @Param("statuses") List<String> statuses);

        List<SkillSession> findByAk(@Param("ak") String ak);

        SkillSession findByToolSessionId(@Param("toolSessionId") String toolSessionId);

        List<SkillSession> findByStatus(@Param("status") String status);

        int insert(SkillSession session);

        int updateStatus(@Param("id") Long id, @Param("status") String status);

        int updateLastActiveAt(@Param("id") Long id, @Param("lastActiveAt") LocalDateTime lastActiveAt);

        int updateToolSessionId(@Param("id") Long id, @Param("toolSessionId") String toolSessionId,
                        @Param("lastActiveAt") LocalDateTime lastActiveAt);

        int updateAk(@Param("id") Long id, @Param("ak") String ak);

        /**
         * Activate a session (IDLE → ACTIVE). Only transitions IDLE sessions.
         */
        int activateSession(@Param("id") Long id);

        /**
         * Clear the tool_session_id for a session (when toolSession becomes invalid).
         */
        int clearToolSessionId(@Param("id") Long id);

        /**
         * Mark ACTIVE sessions as IDLE if inactive beyond cutoff.
         * Returns the IDs of affected sessions for cleanup.
         */
        List<Long> findIdleSessionIds(@Param("cutoff") LocalDateTime cutoff);

        int markIdleSessions(@Param("status") String status, @Param("cutoff") LocalDateTime cutoff);
}
