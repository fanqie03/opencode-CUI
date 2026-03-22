package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.service.SkillRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** SkillWebSocketHandler 单元测试：验证握手认证、连接注册、消息处理等逻辑。 */
class SkillWebSocketHandlerTest {

    @Mock
    private SkillRelayService skillRelayService;

    @Mock
    private WebSocketSession session;
    @Mock
    private ServerHttpRequest request;
    @Mock
    private ServerHttpResponse response;
    @Mock
    private WebSocketHandler wsHandler;

    private TestSkillWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TestSkillWebSocketHandler(new ObjectMapper(), skillRelayService, "secret-token");
    }

    @Test
    @DisplayName("valid auth subprotocol passes handshake and echoes selected protocol")
    void validAuthSubprotocolPassesHandshake() {
        HttpHeaders headers = new HttpHeaders();
        String protocol = authProtocol("secret-token", "skill-server");
        headers.set("Sec-WebSocket-Protocol", protocol);
        when(request.getHeaders()).thenReturn(headers);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = handler.beforeHandshake(request, response, wsHandler, attributes);

        org.junit.jupiter.api.Assertions.assertTrue(accepted);
        org.junit.jupiter.api.Assertions.assertEquals(protocol,
                response.getHeaders().getFirst("Sec-WebSocket-Protocol"));
        org.junit.jupiter.api.Assertions.assertEquals("skill-server", attributes.get(SkillRelayService.SOURCE_ATTR));
    }

    @Test
    @DisplayName("invalid auth subprotocol rejects handshake")
    void invalidAuthSubprotocolRejectsHandshake() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Sec-WebSocket-Protocol", authProtocol("wrong-token", "skill-server"));
        when(request.getHeaders()).thenReturn(headers);

        boolean accepted = handler.beforeHandshake(request, response, wsHandler, new HashMap<>());

        org.junit.jupiter.api.Assertions.assertFalse(accepted);
    }

    @Test
    @DisplayName("missing auth subprotocol rejects handshake")
    void missingAuthSubprotocolRejectsHandshake() {
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);

        boolean accepted = handler.beforeHandshake(request, response, wsHandler, new HashMap<>());

        org.junit.jupiter.api.Assertions.assertFalse(accepted);
    }

    @Test
    @DisplayName("connection established registers skill session")
    void connectionEstablishedRegistersSkillSession() throws Exception {
        handler.onOpen(session);

        verify(skillRelayService).registerSourceSession(session);
        verify(session, never()).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("invoke message delegates to skill relay service")
    void invokeDelegatesToSkillRelayService() throws Exception {
        handler.handle(session,
                "{\"type\":\"invoke\",\"source\":\"skill-server\",\"ak\":\"ak_test_001\",\"welinkSessionId\":42,\"action\":\"chat\",\"userId\":\"user-1\"}");

        verify(skillRelayService).handleInvokeFromSkill(eq(session),
                argThat(message -> "invoke".equals(message.getType())
                        && "skill-server".equals(message.getSource())
                        && "ak_test_001".equals(message.getAk())
                        && "42".equals(message.getWelinkSessionId())));
    }

    @Test
    @DisplayName("non invoke message is ignored")
    void nonInvokeMessageIsIgnored() throws Exception {
        handler.handle(session, "{\"type\":\"tool_event\",\"welinkSessionId\":42}");

        verifyNoInteractions(skillRelayService);
    }

    @Test
    @DisplayName("malformed JSON is ignored")
    void malformedJsonIsIgnored() throws Exception {
        handler.handle(session, "not-json");

        verifyNoInteractions(skillRelayService);
    }

    private static final class TestSkillWebSocketHandler extends SkillWebSocketHandler {

        private TestSkillWebSocketHandler(ObjectMapper objectMapper,
                SkillRelayService skillRelayService,
                String internalToken) {
            super(objectMapper, skillRelayService, internalToken);
        }

        private void onOpen(WebSocketSession session) throws Exception {
            super.afterConnectionEstablished(session);
        }

        private void handle(WebSocketSession session, String payload) throws Exception {
            super.handleTextMessage(session, new TextMessage(payload));
        }
    }

    // ==================== v3 新增测试 ====================

    @Test
    @DisplayName("handshake 解析 instanceId 到 session attributes")
    void handshakeExtractsInstanceId() {
        HttpHeaders headers = new HttpHeaders();
        String protocol = authProtocolWithInstanceId("secret-token", "skill-server", "ss-az1-2");
        headers.set("Sec-WebSocket-Protocol", protocol);
        when(request.getHeaders()).thenReturn(headers);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = handler.beforeHandshake(request, response, wsHandler, attributes);

        org.junit.jupiter.api.Assertions.assertTrue(accepted);
        org.junit.jupiter.api.Assertions.assertEquals("ss-az1-2",
                attributes.get(SkillRelayService.INSTANCE_ID_ATTR));
    }

    @Test
    @DisplayName("handshake 无 instanceId 时 attribute 不设置（兼容旧客户端）")
    void handshakeWithoutInstanceIdStillWorks() {
        HttpHeaders headers = new HttpHeaders();
        String protocol = authProtocol("secret-token", "skill-server");
        headers.set("Sec-WebSocket-Protocol", protocol);
        when(request.getHeaders()).thenReturn(headers);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = handler.beforeHandshake(request, response, wsHandler, attributes);

        org.junit.jupiter.api.Assertions.assertTrue(accepted);
        org.junit.jupiter.api.Assertions.assertNull(attributes.get(SkillRelayService.INSTANCE_ID_ATTR));
    }

    @Test
    @DisplayName("invoke 消息经过时调用 learnRoute 提取路由信息")
    void invokeMessageTriggersLearnRoute() throws Exception {
        handler.handle(session,
                "{\"type\":\"invoke\",\"source\":\"skill-server\",\"ak\":\"ak_test_001\","
                        + "\"welinkSessionId\":\"42\",\"action\":\"chat\",\"userId\":\"user-1\","
                        + "\"payload\":{\"toolSessionId\":\"T1\",\"text\":\"hello\"}}");

        verify(skillRelayService).handleInvokeFromSkill(eq(session),
                argThat(message -> "invoke".equals(message.getType())));
        // handleInvokeFromSkill 内部会调用 learnRoute，这里验证 invoke 被正确分发
    }

    private static String authProtocol(String token, String source) {
        String json = "{\"token\":\"" + token + "\",\"source\":\"" + source + "\"}";
        return "auth." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String authProtocolWithInstanceId(String token, String source, String instanceId) {
        String json = "{\"token\":\"" + token + "\",\"source\":\"" + source
                + "\",\"instanceId\":\"" + instanceId + "\"}";
        return "auth." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
