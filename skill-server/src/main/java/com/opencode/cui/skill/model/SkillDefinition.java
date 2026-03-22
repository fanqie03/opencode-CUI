package com.opencode.cui.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Skill 定义实体。
 * 对应数据库 skill_definition 表，记录系统支持的 Skill 类型及其元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    /** 数据库主键 */
    private Long id;

    /** Skill 编码（唯一标识） */
    private String skillCode;

    /** Skill 显示名称 */
    private String skillName;

    /** 工具类型（如 opencode） */
    private String toolType;

    /** Skill 描述 */
    private String description;

    /** 图标 URL */
    private String iconUrl;

    /** Skill 状态，默认 ACTIVE */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 排序权重，默认 0 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;

    /**
     * Skill 状态枚举。
     */
    public enum Status {
        /** 启用 */
        ACTIVE,
        /** 禁用 */
        DISABLED
    }
}
