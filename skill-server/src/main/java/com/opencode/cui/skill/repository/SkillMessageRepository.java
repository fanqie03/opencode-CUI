package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SkillMessageRepository {

        SkillMessage findById(@Param("id") Long id);

        SkillMessage findBySessionIdAndMessageId(@Param("sessionId") Long sessionId,
                        @Param("messageId") String messageId);

        List<SkillMessage> findBySessionId(@Param("sessionId") Long sessionId,
                        @Param("offset") int offset,
                        @Param("limit") int limit);

        List<SkillMessage> findAllBySessionId(@Param("sessionId") Long sessionId);

        long countBySessionId(@Param("sessionId") Long sessionId);

        int findMaxSeqBySessionId(@Param("sessionId") Long sessionId);

        int insert(SkillMessage message);

        SkillMessage findLastUserMessage(@Param("sessionId") Long sessionId);

        int updateStats(@Param("id") Long id,
                        @Param("tokensIn") Integer tokensIn,
                        @Param("tokensOut") Integer tokensOut,
                        @Param("cost") Double cost);

        int updateContent(@Param("id") Long id,
                        @Param("content") String content);

        int markFinished(@Param("id") Long id);
}
