package com.opencode.cui.skill.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ImTokenAuthInterceptor imTokenAuthInterceptor;

    public WebMvcConfig(ImTokenAuthInterceptor imTokenAuthInterceptor) {
        this.imTokenAuthInterceptor = imTokenAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(imTokenAuthInterceptor)
                .addPathPatterns("/api/inbound/**");
    }
}
