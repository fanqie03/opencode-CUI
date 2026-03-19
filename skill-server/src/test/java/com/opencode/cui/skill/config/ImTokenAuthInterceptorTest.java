package com.opencode.cui.skill.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImTokenAuthInterceptorTest {

    @Test
    @DisplayName("blank configured token rejects inbound requests")
    void blankConfiguredTokenRejectsInboundRequests() throws Exception {
        ImTokenAuthInterceptor interceptor = new ImTokenAuthInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer ");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("matching bearer token is allowed")
    void matchingBearerTokenIsAllowed() throws Exception {
        ImTokenAuthInterceptor interceptor = new ImTokenAuthInterceptor("token-123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer token-123");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
    }
}
