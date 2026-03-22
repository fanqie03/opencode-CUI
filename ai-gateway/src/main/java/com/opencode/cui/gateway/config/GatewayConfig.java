package com.opencode.cui.gateway.config;

import com.opencode.cui.gateway.ws.AgentWebSocketHandler;
import com.opencode.cui.gateway.ws.SkillWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * AI Gateway 核心配置类。
 * 负责 WebSocket 端点注册、MVC 拦截器配置和 WebSocket 容器参数设置。
 *
 * <p>
 * WebSocket 端点：
 * <ul>
 * <li>{@code /ws/agent} — Agent 客户端连接入口</li>
 * <li>{@code /ws/skill} — Skill Server 连接入口</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
public class GatewayConfig implements WebSocketConfigurer, WebMvcConfigurer {

    /** WebSocket 文本消息最大缓冲区（字节），默认 1MB */
    @Value("${gateway.websocket.max-text-message-buffer-size-bytes:1048576}")
    private int maxTextMessageBufferSizeBytes;

    /** 允许的跨域来源，默认 * */
    @Value("${gateway.websocket.allowed-origins:*}")
    private String[] allowedOrigins;

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final SkillWebSocketHandler skillWebSocketHandler;
    private final MdcRequestInterceptor mdcRequestInterceptor;

    public GatewayConfig(AgentWebSocketHandler agentWebSocketHandler,
            SkillWebSocketHandler skillWebSocketHandler,
            MdcRequestInterceptor mdcRequestInterceptor) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.skillWebSocketHandler = skillWebSocketHandler;
        this.mdcRequestInterceptor = mdcRequestInterceptor;
    }

    /**
     * 注册 MVC 拦截器。
     * MdcRequestInterceptor 拦截所有 /api/** 请求，自动设置 MDC 上下文。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(mdcRequestInterceptor)
                .addPathPatterns("/api/**");
    }

    /**
     * 注册 WebSocket 处理器。
     * Agent 端连接到 /ws/agent，Skill Server 连接到 /ws/skill。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .addInterceptors(agentWebSocketHandler)
                .setAllowedOrigins(allowedOrigins);

        registry.addHandler(skillWebSocketHandler, "/ws/skill")
                .addInterceptors(skillWebSocketHandler)
                .setAllowedOrigins(allowedOrigins);
    }

    /**
     * 配置 WebSocket 容器参数。
     * 设置文本消息最大缓冲区大小。
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxTextMessageBufferSizeBytes);
        return container;
    }
}
