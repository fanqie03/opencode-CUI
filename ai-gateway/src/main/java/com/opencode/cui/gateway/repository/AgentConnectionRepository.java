package com.opencode.cui.gateway.repository;

import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentConnection.AgentStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentConnectionRepository {

    /** Find all agents with a given status */
    List<AgentConnection> findByStatus(@Param("status") AgentStatus status);

    /** Find agents by user ID */
    List<AgentConnection> findByUserId(@Param("userId") Long userId);

    /**
     * Find agents by user ID and status (e.g. ONLINE agents for a specific user)
     */
    List<AgentConnection> findByUserIdAndStatus(
            @Param("userId") Long userId,
            @Param("status") AgentStatus status);

    /**
     * Find an existing online connection for the same AK and tool type (for
     * kick-old logic)
     */
    AgentConnection findByAkIdAndToolTypeAndStatus(
            @Param("akId") String akId,
            @Param("toolType") String toolType,
            @Param("status") AgentStatus status);

    /** Find stale agents: online but last_seen_at older than the given threshold */
    List<AgentConnection> findStaleAgents(@Param("threshold") LocalDateTime threshold);

    /** Bulk mark stale agents offline */
    int markStaleAgentsOffline(@Param("threshold") LocalDateTime threshold);

    /** Insert a new agent connection record */
    int insert(AgentConnection agent);

    /** Find by primary key */
    AgentConnection findById(@Param("id") Long id);

    /** Update status for an agent */
    int updateStatus(@Param("id") Long id, @Param("status") AgentStatus status);

    /** Update last_seen_at for an agent */
    int updateLastSeenAt(@Param("id") Long id, @Param("lastSeenAt") LocalDateTime lastSeenAt);

    /** Delete by primary key */
    int deleteById(@Param("id") Long id);
}
