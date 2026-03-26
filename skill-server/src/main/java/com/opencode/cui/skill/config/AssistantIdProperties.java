package com.opencode.cui.skill.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AssistantId 自动注入功能配置。
 * 当 Agent 的 toolType 匹配 targetToolType 且会话有 assistantAccount 时，
 * 自动从 persona 接口获取 assistantId 注入到 payload。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "skill.assistant-id")
public class AssistantIdProperties {

    /** 功能总开关 */
    private boolean enabled = true;

    /** 需匹配的 toolType 值（忽略大小写） */
    private String targetToolType = "assistant";

    /** persona 服务 base URL */
    private String personaBaseUrl;

    /** Redis 缓存 TTL（分钟） */
    private int cacheTtlMinutes = 30;

    /** 是否用 ak 对 persona 返回结果做二次过滤 */
    private boolean matchAk = false;
}
