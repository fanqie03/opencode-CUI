package com.opencode.cui.gateway.repository;

import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentConnection.AgentStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 连接记录的 MyBatis Mapper。
 * 对应数据库 agent_connection 表，管理 Agent 的注册、状态更新和查询。
 */
@Mapper
public interface AgentConnectionRepository {

        /** 按状态查询 Agent 列表 */
        List<AgentConnection> findByStatus(@Param("status") AgentStatus status);

        /** 按用户 ID 查询 Agent 列表 */
        List<AgentConnection> findByUserId(@Param("userId") String userId);

        /**
         * 按用户 ID 和状态查询 Agent 列表（如：某用户的在线 Agent）。
         */
        List<AgentConnection> findByUserIdAndStatus(
                        @Param("userId") String userId,
                        @Param("status") AgentStatus status);

        /**
         * 按 AK + 工具类型 + 状态查询已有连接（用于踢旧逻辑）。
         */
        AgentConnection findByAkIdAndToolTypeAndStatus(
                        @Param("akId") String akId,
                        @Param("toolType") String toolType,
                        @Param("status") AgentStatus status);

        /** 查询过期 Agent：在线但 last_seen_at 早于指定阈值 */
        List<AgentConnection> findStaleAgents(@Param("threshold") LocalDateTime threshold);

        /** 批量将过期 Agent 标记为离线 */
        int markStaleAgentsOffline(@Param("threshold") LocalDateTime threshold);

        /** 插入新的 Agent 连接记录 */
        int insert(AgentConnection agent);

        /** 按主键查询 */
        AgentConnection findById(@Param("id") Long id);

        /** 查询指定 AK 的最新连接记录 */
        AgentConnection findLatestByAkId(@Param("akId") String akId);

        /** 按 AK + 工具类型查询（不限状态，用于身份复用） */
        AgentConnection findByAkIdAndToolType(
                        @Param("akId") String akId,
                        @Param("toolType") String toolType);

        /**
         * 更新 Agent 信息（状态、设备信息、版本、时间戳），用于身份复用。
         */
        int updateAgentInfo(AgentConnection agent);

        /** 更新 Agent 状态 */
        int updateStatus(@Param("id") Long id, @Param("status") AgentStatus status);

        /** 更新 Agent 最后活跃时间 */
        int updateLastSeenAt(@Param("id") Long id, @Param("lastSeenAt") LocalDateTime lastSeenAt);

        /** 按主键删除 */
        int deleteById(@Param("id") Long id);
}
