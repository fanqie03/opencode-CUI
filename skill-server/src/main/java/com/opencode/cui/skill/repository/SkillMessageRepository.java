package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 消息的 MyBatis Mapper。
 * 对应数据库 skill_message 表，管理会话中消息的 CRUD 和统计查询。
 */
@Mapper
public interface SkillMessageRepository {

        /** 按主键查询消息 */
        SkillMessage findById(@Param("id") Long id);

        /** 按会话 ID + 消息 UUID 查询 */
        SkillMessage findBySessionIdAndMessageId(@Param("sessionId") Long sessionId,
                        @Param("messageId") String messageId);

        /** 按会话 ID 分页查询消息列表 */
        List<SkillMessage> findBySessionId(@Param("sessionId") Long sessionId,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        List<SkillMessage> findLatestBySessionId(@Param("sessionId") Long sessionId,
                        @Param("limit") int limit);

        List<SkillMessage> findBySessionIdBeforeSeq(@Param("sessionId") Long sessionId,
                        @Param("beforeSeq") Integer beforeSeq,
                        @Param("limit") int limit);

        /** 按会话 ID 查询全部消息（不分页） */
        List<SkillMessage> findAllBySessionId(@Param("sessionId") Long sessionId);

        /** 统计指定会话的消息总数 */
        long countBySessionId(@Param("sessionId") Long sessionId);

        /** 查询指定会话下的最大序号 */
        int findMaxSeqBySessionId(@Param("sessionId") Long sessionId);

        /** 插入新消息 */
        int insert(SkillMessage message);

        /** 查询指定会话中最后一条用户消息 */
        SkillMessage findLastUserMessage(@Param("sessionId") Long sessionId);

        /** 更新消息的 token 统计和费用 */
        int updateStats(@Param("id") Long id,
                        @Param("tokensIn") Integer tokensIn,
                        @Param("tokensOut") Integer tokensOut,
                        @Param("cost") Double cost);

        /** 更新消息文本内容 */
        int updateContent(@Param("id") Long id,
                        @Param("content") String content);

        /** 标记消息为已完成 */
        int markFinished(@Param("id") Long id);
}
