package com.yourapp.gateway.repository;

import com.yourapp.gateway.model.AgentConnection;
import com.yourapp.gateway.model.AgentConnection.AgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentConnectionRepository extends JpaRepository<AgentConnection, Long> {

    /** Find all agents with a given status */
    List<AgentConnection> findByStatus(AgentStatus status);

    /** Find agents by user ID */
    List<AgentConnection> findByUserId(Long userId);

    /** Find an existing online connection for the same AK and tool type (for kick-old logic) */
    Optional<AgentConnection> findByAkIdAndToolTypeAndStatus(String akId, String toolType, AgentStatus status);

    /** Find stale agents: online but last_seen_at older than the given threshold */
    @Query("SELECT a FROM AgentConnection a WHERE a.status = 'ONLINE' AND a.lastSeenAt < :threshold")
    List<AgentConnection> findStaleAgents(@Param("threshold") LocalDateTime threshold);

    /** Bulk mark stale agents offline */
    @Modifying
    @Query("UPDATE AgentConnection a SET a.status = 'OFFLINE' WHERE a.status = 'ONLINE' AND a.lastSeenAt < :threshold")
    int markStaleAgentsOffline(@Param("threshold") LocalDateTime threshold);
}
