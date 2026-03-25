package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SessionRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 会话路由记录的 MyBatis Mapper。
 * 对应数据库 session_route 表，管理会话与 Source 实例之间的路由映射。
 */
@Mapper
public interface SessionRouteRepository {

    /** 插入路由记录 */
    int insert(SessionRoute route);

    /** 按 Skill 侧会话 ID 查询路由 */
    SessionRoute findByWelinkSessionId(@Param("welinkSessionId") Long welinkSessionId);

    /** 按 OpenCode 工具会话 ID 查询路由 */
    SessionRoute findByToolSessionId(@Param("toolSessionId") String toolSessionId);

    /** 更新指定会话和来源类型的工具会话 ID */
    int updateToolSessionId(@Param("welinkSessionId") Long welinkSessionId,
            @Param("sourceType") String sourceType,
            @Param("toolSessionId") String toolSessionId);

    /** 更新指定会话和来源类型的路由状态 */
    int updateStatus(@Param("welinkSessionId") Long welinkSessionId,
            @Param("sourceType") String sourceType,
            @Param("status") String status);

    /** 启动接管：将指定 AK 下所有 ACTIVE 路由的 sourceInstance 更新为新实例 */
    int takeoverByAk(@Param("ak") String ak,
            @Param("newSourceInstance") String newSourceInstance);

    /** 优雅关闭：关闭指定实例下所有 ACTIVE 路由 */
    int closeAllBySourceInstance(@Param("sourceInstance") String sourceInstance);

    /**
     * Optimistic-lock takeover: atomically update sourceInstance only when current owner matches.
     * Returns 1 on success (ownership transferred), 0 on conflict (someone else already took over).
     */
    int tryTakeover(@Param("welinkSessionId") Long welinkSessionId,
            @Param("deadInstanceId") String deadInstanceId,
            @Param("newInstanceId") String newInstanceId);

    /** 僵尸清理：关闭 updated_at 早于指定时间且仍为 ACTIVE 的记录 */
    int closeStaleActiveRoutes(@Param("cutoffTime") LocalDateTime cutoffTime);

    /** 历史清理：删除 CLOSED 且 updated_at 早于指定时间的记录 */
    int purgeClosedBefore(@Param("cutoffTime") LocalDateTime cutoffTime);
}
