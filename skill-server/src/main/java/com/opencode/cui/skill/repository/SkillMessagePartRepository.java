package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillMessagePart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息分片的 MyBatis Mapper。
 * 对应数据库 skill_message_part 表，管理消息内各结构化分片（text/tool/file 等）。
 */
@Mapper
public interface SkillMessagePartRepository {

    /** 插入新分片 */
    int insert(SkillMessagePart part);

    /**
     * Upsert：按 (session_id, part_id) 插入或更新分片。
     * 用于工具执行到最终状态时更新 status/output 等字段。
     */
    int upsert(SkillMessagePart part);

    /** 按会话 ID + 分片 ID 查询分片 */
    SkillMessagePart findByPartId(@Param("sessionId") Long sessionId,
            @Param("partId") String partId);

    /**
     * 按 toolCallId 精确查找仍待处理的权限分片。
     * permission part 的 tool_call_id 字段存的就是 opencode 的 callID（即协议层 permissionId）。
     */
    SkillMessagePart findPendingPermissionPartByToolCallId(@Param("sessionId") Long sessionId,
            @Param("toolCallId") String toolCallId);

    SkillMessagePart findPendingQuestionPartByToolCallId(@Param("sessionId") Long sessionId,
            @Param("toolCallId") String toolCallId);

    /** 按所属消息 ID 查询所有分片 */
    List<SkillMessagePart> findByMessageId(@Param("messageId") Long messageId);

    List<SkillMessagePart> findByMessageIds(@Param("messageIds") List<Long> messageIds);

    /** 查询指定消息下的最大分片序号 */
    int findMaxSeqByMessageId(@Param("messageId") Long messageId);

    /**
     * 在 SQL 层拼接指定消息所有 text 类型 part 的内容，避免 N+1 查询。
     */
    String findConcatenatedTextByMessageId(@Param("messageId") Long messageId);

    /** 批量 upsert：一次性写入多个分片 */
    int batchUpsert(@Param("parts") List<SkillMessagePart> parts);

    /** 按所属消息 ID 删除所有分片 */
    int deleteByMessageId(@Param("messageId") Long messageId);
}
