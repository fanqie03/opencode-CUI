package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 会话的 MyBatis Mapper。
 * 对应数据库 skill_session 表，管理会话的创建、状态变更、多条件查询等。
 */
@Mapper
public interface SkillSessionRepository {

        /** 按主键查询会话 */
        SkillSession findById(@Param("id") Long id);

        /** 按用户 ID 分页查询会话列表 */
        List<SkillSession> findByUserId(@Param("userId") String userId,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        /** 按用户 ID 和状态列表分页查询 */
        List<SkillSession> findByUserIdAndStatusIn(@Param("userId") String userId,
                        @Param("statuses") List<String> statuses,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        /** 查询指定用户的所有活跃会话 */
        List<SkillSession> findActiveByUserId(@Param("userId") String userId);

        /** 统计指定用户的会话总数 */
        long countByUserId(@Param("userId") String userId);

        /** 按用户 ID 和状态列表统计会话数 */
        long countByUserIdAndStatusIn(@Param("userId") String userId,
                        @Param("statuses") List<String> statuses);

        /** 按用户 ID + AK + 业务会话域/类型/ID + 助手账号 + 状态列表组合过滤查询 */
        List<SkillSession> findByUserIdFiltered(@Param("userId") String userId,
                        @Param("ak") String ak,
                        @Param("businessSessionDomain") String businessSessionDomain,
                        @Param("businessSessionType") String businessSessionType,
                        @Param("businessSessionId") String businessSessionId,
                        @Param("assistantAccount") String assistantAccount,
                        @Param("statuses") List<String> statuses,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        /** 按用户 ID + AK + 业务会话域/类型/ID + 助手账号 + 状态列表组合过滤统计 */
        long countByUserIdFiltered(@Param("userId") String userId,
                        @Param("ak") String ak,
                        @Param("businessSessionDomain") String businessSessionDomain,
                        @Param("businessSessionType") String businessSessionType,
                        @Param("businessSessionId") String businessSessionId,
                        @Param("assistantAccount") String assistantAccount,
                        @Param("statuses") List<String> statuses);

        /** 按 AK 查询所有会话 */
        List<SkillSession> findByAk(@Param("ak") String ak);

        /** 按 OpenCode 工具会话 ID 查询 */
        SkillSession findByToolSessionId(@Param("toolSessionId") String toolSessionId);

        /** 按业务域 + 业务类型 + 业务会话 ID + AK 查询（IM 场景复用会话） */
        SkillSession findByBusinessSession(@Param("businessSessionDomain") String businessSessionDomain,
                        @Param("businessSessionType") String businessSessionType,
                        @Param("businessSessionId") String businessSessionId,
                        @Param("ak") String ak);

        /** 按状态查询会话列表 */
        List<SkillSession> findByStatus(@Param("status") String status);

        /** 插入新会话 */
        int insert(SkillSession session);

        /** 更新会话状态 */
        int updateStatus(@Param("id") Long id, @Param("status") String status);

        /** 更新最后活跃时间 */
        int updateLastActiveAt(@Param("id") Long id, @Param("lastActiveAt") LocalDateTime lastActiveAt);

        /** 更新工具会话 ID 和最后活跃时间 */
        int updateToolSessionId(@Param("id") Long id, @Param("toolSessionId") String toolSessionId,
                        @Param("lastActiveAt") LocalDateTime lastActiveAt);

        /** 更新会话绑定的 AK */
        int updateAk(@Param("id") Long id, @Param("ak") String ak);

        /**
         * 激活会话（IDLE → ACTIVE）。仅对 IDLE 状态的会话生效。
         */
        int activateSession(@Param("id") Long id);

        /** 当标题发生变化时更新会话标题 */
        int updateTitle(@Param("id") Long id, @Param("title") String title);

        /**
         * 清除会话的工具会话 ID（工具会话失效时调用）。
         */
        int clearToolSessionId(@Param("id") Long id);

        /**
         * 查询超过截止时间仍未活跃的 ACTIVE 会话 ID 列表（用于空闲清理）。
         */
        List<Long> findIdleSessionIds(@Param("cutoff") LocalDateTime cutoff);

        /** 将超过截止时间未活跃的 ACTIVE 会话批量标记为指定状态 */
        int markIdleSessions(@Param("status") String status, @Param("cutoff") LocalDateTime cutoff);
}
