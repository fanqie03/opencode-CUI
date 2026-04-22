package com.opencode.cui.skill.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 助理离线提示文案供给组件。
 * 从 sys_config (assistant_offline:message) 读取，缺失/禁用/空串时回退到 DEFAULT_OFFLINE_MESSAGE。
 */
@Component
@RequiredArgsConstructor
public class AssistantOfflineMessageProvider {

    static final String DEFAULT_OFFLINE_MESSAGE =
            "任务下发失败，请检查助理是否离线，确保助理在线后重试";
    static final String CONFIG_TYPE = "assistant_offline";
    static final String CONFIG_KEY  = "message";

    private final SysConfigService sysConfigService;

    public String get() {
        String v = sysConfigService.getValue(CONFIG_TYPE, CONFIG_KEY);
        return (v == null || v.isBlank()) ? DEFAULT_OFFLINE_MESSAGE : v;
    }
}
