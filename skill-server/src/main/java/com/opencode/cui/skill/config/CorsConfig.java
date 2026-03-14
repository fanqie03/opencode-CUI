package com.opencode.cui.skill.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration for REST API endpoints.
 * Allows cross-origin requests from the Skill Miniapp dev server.
 *
 * 生产环境请通过 skill.cors.allowed-origins 和 skill.cors.allowed-methods
 * 配置具体的域名和方法列表，避免使用通配符 '*'。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${skill.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Value("${skill.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String[] allowedMethods;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(allowedMethods)
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
