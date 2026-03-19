package com.opencode.cui.skill.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ImTokenAuthInterceptor implements HandlerInterceptor {

    private final String inboundToken;

    public ImTokenAuthInterceptor(
            @org.springframework.beans.factory.annotation.Value("${skill.im.inbound-token:}") String inboundToken) {
        this.inboundToken = inboundToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (inboundToken == null || inboundToken.isBlank()) {
            writeUnauthorized(response, "Inbound token is not configured");
            return false;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, "Missing token");
            return false;
        }

        String token = auth.substring(7);
        if (!inboundToken.equals(token)) {
            writeUnauthorized(response, "Invalid token");
            return false;
        }

        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":401,\"errormsg\":\"" + message + "\"}");
    }
}
