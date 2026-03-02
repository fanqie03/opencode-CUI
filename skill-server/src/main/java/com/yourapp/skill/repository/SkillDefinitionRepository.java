package com.yourapp.skill.repository;

import com.yourapp.skill.model.SkillDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillDefinitionRepository extends JpaRepository<SkillDefinition, Long> {

    Optional<SkillDefinition> findBySkillCode(String skillCode);

    List<SkillDefinition> findByStatusOrderBySortOrderAsc(SkillDefinition.Status status);
}
