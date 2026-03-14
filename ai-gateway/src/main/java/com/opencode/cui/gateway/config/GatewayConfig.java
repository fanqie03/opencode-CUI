package com.opencode.cui.gateway.config;

import com.opencode.cui.gateway.ws.AgentWebSocketHandler;
import com.opencode.cui.gateway.ws.SkillWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class GatewayConfig implements WebSocketConfigurer {

    @Value("${gateway.websocket.max-text-message-buffer-size-bytes:1048576}")
    private int maxTextMessageBufferSizeBytes;

    @Value("${gateway.websocket.allowed-origins:*}")
    private String[] allowedOrigins;

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final SkillWebSocketHandler skillWebSocketHandler;

    public GatewayConfig(AgentWebSocketHandler agentWebSocketHandler,
            SkillWebSocketHandler skillWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.skillWebSocketHandler = skillWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .addInterceptors(agentWebSocketHandler)
                .setAllowedOrigins(allowedOrigins);

        registry.addHandler(skillWebSocketHandler, "/ws/skill")
                .addInterceptors(skillWebSocketHandler)
                .setAllowedOrigins(allowedOrigins);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxTextMessageBufferSizeBytes);
        return container;
    }
}
