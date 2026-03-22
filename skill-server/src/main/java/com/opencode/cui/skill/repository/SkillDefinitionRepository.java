package com.opencode.cui.skill.repository;

import com.opencode.cui.skill.model.SkillDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Skill 定义的 MyBatis Mapper。
 * 对应数据库 skill_definition 表，管理系统支持的 Skill 类型元数据。
 */
@Mapper
public interface SkillDefinitionRepository {

    /** 查询所有 Skill 定义 */
    List<SkillDefinition> findAll();

    /** 按主键查询 */
    SkillDefinition findById(@Param("id") Long id);

    /** 按 Skill 编码查询 */
    SkillDefinition findBySkillCode(@Param("skillCode") String skillCode);

    /** 按状态查询 */
    List<SkillDefinition> findByStatus(@Param("status") String status);

    /** 按状态查询并按排序权重升序排列 */
    List<SkillDefinition> findByStatusOrderBySortOrderAsc(@Param("status") String status);

    /** 插入新 Skill 定义 */
    int insert(SkillDefinition definition);

    /** 更新 Skill 定义 */
    int update(SkillDefinition definition);
}
