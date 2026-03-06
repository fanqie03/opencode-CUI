package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SkillSessionRepository {

    SkillSession findById(@Param("id") Long id);

    List<SkillSession> findByUserId(@Param("userId") Long userId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    List<SkillSession> findByUserIdAndStatusIn(@Param("userId") Long userId,
            @Param("statuses") List<String> statuses,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId);

    long countByUserIdAndStatusIn(@Param("userId") Long userId,
            @Param("statuses") List<String> statuses);

    List<SkillSession> findByAgentId(@Param("agentId") Long agentId);

    List<SkillSession> findByStatus(@Param("status") String status);

    int insert(SkillSession session);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int updateLastActiveAt(@Param("id") Long id, @Param("lastActiveAt") LocalDateTime lastActiveAt);

    int updateToolSessionId(@Param("id") Long id, @Param("toolSessionId") String toolSessionId,
            @Param("lastActiveAt") LocalDateTime lastActiveAt);

    int updateAgentId(@Param("id") Long id, @Param("agentId") Long agentId);

    int markIdleSessions(@Param("status") String status, @Param("cutoff") LocalDateTime cutoff);
}
