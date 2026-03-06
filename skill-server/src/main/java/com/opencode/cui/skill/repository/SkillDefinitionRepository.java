package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SkillDefinitionRepository {

    List<SkillDefinition> findAll();

    SkillDefinition findById(@Param("id") Long id);

    SkillDefinition findBySkillCode(@Param("skillCode") String skillCode);

    List<SkillDefinition> findByStatus(@Param("status") String status);

    List<SkillDefinition> findByStatusOrderBySortOrderAsc(@Param("status") String status);

    int insert(SkillDefinition definition);

    int update(SkillDefinition definition);
}
