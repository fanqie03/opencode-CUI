package com.opencode.cui.skill.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.opencode.cui.skill.telemetry.metrics.ApiMetricsInterceptor;

/**
 * Spring MVC 拦截器注册配置。
 * 注册 MDC 日志上下文拦截器和 IM Token 认证拦截器。
 *
 * <p>
 * 拦截器执行顺序：MDC 拦截器先于认证拦截器，确保认证日志也带有 traceId。
 * </p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ImTokenAuthInterceptor imTokenAuthInterceptor;
    private final MdcRequestInterceptor mdcRequestInterceptor;
    private final ApiMetricsInterceptor apiMetricsInterceptor;

    public WebMvcConfig(ImTokenAuthInterceptor imTokenAuthInterceptor,
            MdcRequestInterceptor mdcRequestInterceptor,
            ApiMetricsInterceptor apiMetricsInterceptor) {
        this.imTokenAuthInterceptor = imTokenAuthInterceptor;
        this.mdcRequestInterceptor = mdcRequestInterceptor;
        this.apiMetricsInterceptor = apiMetricsInterceptor;
    }

    /**
     * 注册拦截器链。
     * MDC 拦截器拦截所有 /api/** 请求，IM Token 拦截器仅拦截 /api/inbound/** 请求。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // MDC 拦截器在认证之前执行，确保认证日志也带有 traceId
        registry.addInterceptor(mdcRequestInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(apiMetricsInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(imTokenAuthInterceptor)
                .addPathPatterns("/api/inbound/**", "/api/external/**");
    }
}
