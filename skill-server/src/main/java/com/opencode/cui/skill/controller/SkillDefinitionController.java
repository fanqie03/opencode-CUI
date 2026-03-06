package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.SkillDefinition;
import com.opencode.cui.skill.repository.SkillDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/skill/definitions")
public class SkillDefinitionController {

    private final SkillDefinitionRepository definitionRepository;

    public SkillDefinitionController(SkillDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    /**
     * GET /api/skill/definitions
     * Query all active skill definitions, ordered by sort_order ascending.
     */
    @GetMapping
    public ResponseEntity<List<SkillDefinition>> listDefinitions() {
        List<SkillDefinition> definitions = definitionRepository
                .findByStatusOrderBySortOrderAsc(SkillDefinition.Status.ACTIVE.name());
        log.debug("Listed {} active skill definitions", definitions.size());
        return ResponseEntity.ok(definitions);
    }
}
