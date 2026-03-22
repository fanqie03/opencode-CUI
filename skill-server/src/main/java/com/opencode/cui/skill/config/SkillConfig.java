package com.opencode.cui.skill.config;

import com.opencode.cui.skill.ws.SkillStreamHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Skill Server 核心配置类。
 * 启用 WebSocket 和定时任务调度，注册 Skill 流式消息推送端点。
 *
 * <p>
 * WebSocket 端点：{@code /ws/skill/stream} — 前端 MiniApp 实时消息推送入口。
 * </p>
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class SkillConfig implements WebSocketConfigurer {

    private final SkillStreamHandler skillStreamHandler;

    public SkillConfig(SkillStreamHandler skillStreamHandler) {
        this.skillStreamHandler = skillStreamHandler;
    }

    /**
     * 注册 WebSocket 处理器。
     * 前端通过 {@code /ws/skill/stream} 连接获取流式消息推送。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(skillStreamHandler, "/ws/skill/stream")
                .setAllowedOrigins("*");
    }
}
