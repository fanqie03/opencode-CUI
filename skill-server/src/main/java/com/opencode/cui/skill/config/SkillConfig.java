package com.opencode.cui.skill.config;

import com.opencode.cui.skill.ws.GatewayWSHandler;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class SkillConfig implements WebSocketConfigurer {

    private final GatewayWSHandler gatewayWSHandler;
    private final SkillStreamHandler skillStreamHandler;

    public SkillConfig(GatewayWSHandler gatewayWSHandler, SkillStreamHandler skillStreamHandler) {
        this.gatewayWSHandler = gatewayWSHandler;
        this.skillStreamHandler = skillStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Internal endpoint: AI-Gateway connects here
        registry.addHandler(gatewayWSHandler, "/ws/internal/gateway")
                .setAllowedOrigins("*");

        // Client endpoint: Skill miniapp connects here for streaming
        // Use wildcard "*" because Spring raw WebSocket does not support path variables.
        // The sessionId is extracted from the URI path in SkillStreamHandler.
        registry.addHandler(skillStreamHandler, "/ws/skill/stream/*")
                .setAllowedOrigins("*");
    }
}
