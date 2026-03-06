package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillMessagePart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SkillMessagePartRepository {

    int insert(SkillMessagePart part);

    /**
     * Upsert: insert or update by (session_id, part_id).
     * Used to update tool status/output when final state arrives.
     */
    int upsert(SkillMessagePart part);

    SkillMessagePart findByPartId(@Param("sessionId") Long sessionId,
            @Param("partId") String partId);

    List<SkillMessagePart> findByMessageId(@Param("messageId") Long messageId);

    int findMaxSeqByMessageId(@Param("messageId") Long messageId);

    int deleteByMessageId(@Param("messageId") Long messageId);
}
