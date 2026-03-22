package com.opencode.cui.skill.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * IM 入站接口的 Token 认证拦截器。
 * 拦截 {@code /api/inbound/**} 请求，校验 Authorization header 中的 Bearer Token。
 *
 * <p>
 * Token 通过 {@code skill.im.inbound-token} 配置，未配置时所有请求将被拒绝。
 * </p>
 */
@Slf4j
@Component
public class ImTokenAuthInterceptor implements HandlerInterceptor {

    /** 预配置的合法 Token */
    private final String inboundToken;
    private final ObjectMapper objectMapper;

    public ImTokenAuthInterceptor(
            @org.springframework.beans.factory.annotation.Value("${skill.im.inbound-token:}") String inboundToken,
            ObjectMapper objectMapper) {
        this.inboundToken = inboundToken;
        this.objectMapper = objectMapper;
    }

    /**
     * 请求预处理：校验 Bearer Token。
     * 校验失败时返回 401 JSON 响应并中断请求链。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        String remoteAddr = request.getRemoteAddr();

        // Token 未配置：拒绝所有请求
        if (inboundToken == null || inboundToken.isBlank()) {
            log.warn("[AUTH_FAIL] IM token auth: reason=token_not_configured, path={}, remoteAddr={}",
                    path, remoteAddr);
            writeUnauthorized(response, "Inbound token is not configured");
            return false;
        }

        // 缺少 Authorization header
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            log.warn("[AUTH_FAIL] IM token auth: reason=missing_token, path={}, remoteAddr={}",
                    path, remoteAddr);
            writeUnauthorized(response, "Missing token");
            return false;
        }

        // Token 不匹配
        String token = auth.substring(7);
        if (!inboundToken.equals(token)) {
            log.warn("[AUTH_FAIL] IM token auth: reason=invalid_token, path={}, remoteAddr={}",
                    path, remoteAddr);
            writeUnauthorized(response, "Invalid token");
            return false;
        }

        log.info("[AUTH_OK] IM token auth: path={}, remoteAddr={}", path, remoteAddr);
        return true;
    }

    /** 写入 401 Unauthorized JSON 响应 */
    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("code", 401, "errormsg", message)));
    }
}
