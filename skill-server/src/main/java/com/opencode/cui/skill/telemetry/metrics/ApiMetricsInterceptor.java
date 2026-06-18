package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.concurrent.TimeUnit;

@Component
public class ApiMetricsInterceptor implements HandlerInterceptor {

    private final MeterRegistry meterRegistry;

    public ApiMetricsInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute("metrics.startTime", System.currentTimeMillis());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        Object startObj = request.getAttribute("metrics.startTime");
        if (startObj == null) {
            return;
        }
        long startTime = (long) startObj;
        long cost = System.currentTimeMillis() - startTime;
        String url = request.getRequestURI();

        meterRegistry.timer("common_interface_duration_seconds",
                "common_interface_url", url)
            .record(cost, TimeUnit.MILLISECONDS);
    }
}