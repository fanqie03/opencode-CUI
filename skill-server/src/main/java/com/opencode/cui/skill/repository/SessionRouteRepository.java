package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SessionRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
