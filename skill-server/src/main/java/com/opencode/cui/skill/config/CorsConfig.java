package com.opencode.cui.skill.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 跨域配置。
 * 允许来自 Skill MiniApp 开发服务器的跨域请求。
 *
 * <p>
 * 生产环境请通过 {@code skill.cors.allowed-origins} 和
 * {@code skill.cors.allowed-methods}
 * 配置具体的域名和方法列表，避免使用通配符 {@code *}。
 * </p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 允许的跨域来源，默认 * */
    @Value("${skill.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    /** 允许的 HTTP 方法 */
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
