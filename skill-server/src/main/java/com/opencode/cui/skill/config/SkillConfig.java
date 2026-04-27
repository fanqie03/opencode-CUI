package com.opencode.cui.skill.config;

import com.github.benmanes.caffeine.cache.Ticker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.time.Clock;

/**
 * Skill Server 核心配置类。
 * 启用 WebSocket 和定时任务调度，注册 Skill 流式消息推送端点。
 *
 * <p>
 * WebSocket 端点：
 * <ul>
 *   <li>{@code /ws/skill/stream} — 前端 MiniApp 实时消息推送入口。</li>
 *   <li>{@code /ws/external/stream} — 外部模块（IM、CRM 等）实时消息推送入口，需 Sec-WebSocket-Protocol 认证。</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class SkillConfig implements WebSocketConfigurer {

    private final SkillStreamHandler skillStreamHandler;
    private final ExternalStreamHandler externalStreamHandler;

    public SkillConfig(SkillStreamHandler skillStreamHandler,
                       ExternalStreamHandler externalStreamHandler) {
        this.skillStreamHandler = skillStreamHandler;
        this.externalStreamHandler = externalStreamHandler;
    }

    /**
     * 注册 WebSocket 处理器。
     * 前端通过 {@code /ws/skill/stream} 连接获取流式消息推送。
     * 外部模块通过 {@code /ws/external/stream} 连接，需携带 auth subprotocol 认证。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(skillStreamHandler, "/ws/skill/stream")
                .setAllowedOrigins("*");
        registry.addHandler(externalStreamHandler, "/ws/external/stream")
                .addInterceptors(externalStreamHandler)
                .setAllowedOrigins("*");
    }

    /**
     * 系统 Clock，用于 {@link com.opencode.cui.skill.service.GatewayMessageRouter}
     * 计算 route_confirm 去重 cache 的时间戳。测试可通过 {@link ConditionalOnMissingBean}
     * 注入 fake clock 控制虚拟时间。
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * 系统 Ticker，用于 Caffeine cache 过期判定（route_confirm 去重 cache）。
     * 测试可注入 FakeTicker 推进虚拟时间，跳过真实墙钟等待。
     */
    @Bean
    @ConditionalOnMissingBean
    public Ticker systemTicker() {
        return Ticker.systemTicker();
    }
}
