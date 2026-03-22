package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SessionRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SessionRouteRepository {

    int insert(SessionRoute route);

    SessionRoute findByWelinkSessionId(@Param("welinkSessionId") Long welinkSessionId);

    SessionRoute findByToolSessionId(@Param("toolSessionId") String toolSessionId);

    int updateToolSessionId(@Param("welinkSessionId") Long welinkSessionId,
                            @Param("sourceType") String sourceType,
                            @Param("toolSessionId") String toolSessionId);

    int updateStatus(@Param("welinkSessionId") Long welinkSessionId,
                     @Param("sourceType") String sourceType,
                     @Param("status") String status);
}
