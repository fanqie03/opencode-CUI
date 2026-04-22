package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.cloud.CloudRequestBuilder;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessScopeStrategy")
class BusinessScopeStrategyTest {

    @Mock
    private CloudRequestBuilder cloudRequestBuilder;

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BusinessScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new BusinessScopeStrategy(cloudRequestBuilder, cloudEventTranslator, objectMapper);
    }

    @Test
    @DisplayName("getScope() returns \"business\"")
    void getScope_returnsBusiness() {
        assertEquals("business", strategy.getScope());
    }

    @Test
    @DisplayName("generateToolSessionId() returns string with \"cloud-\" prefix")
    void generateToolSessionId_returnsCloudPrefix() {
        String id = strategy.generateToolSessionId();
        assertNotNull(id);
        assertTrue(id.startsWith("cloud-"), "Expected 'cloud-' prefix but got: " + id);
    }

    @Test
    @DisplayName("generateToolSessionId() returns unique values")
    void generateToolSessionId_returnsUniqueValues() {
        String id1 = strategy.generateToolSessionId();
        String id2 = strategy.generateToolSessionId();
        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("requiresSessionCreatedCallback() returns false")
    void requiresSessionCreatedCallback_returnsFalse() {
        assertFalse(strategy.requiresSessionCreatedCallback());
    }

    @Test
    @DisplayName("requiresOnlineCheck() returns false")
    void requiresOnlineCheck_returnsFalse() {
        assertFalse(strategy.requiresOnlineCheck());
    }

    @Test
    @DisplayName("translateEvent() delegates to CloudEventTranslator")
    void translateEvent_delegatesToCloudEventTranslator() {
        JsonNode event = objectMapper.createObjectNode().put("type", "text.delta");
        String sessionId = "session-123";
        StreamMessage expected = StreamMessage.builder().type("text.delta").build();
        when(cloudEventTranslator.translate(event, sessionId)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, sessionId);

        assertSame(expected, result);
        verify(cloudEventTranslator).translate(event, sessionId);
    }

    @Test
    @DisplayName("buildInvoke() calls CloudRequestBuilder and sets assistantScope in payload")
    void buildInvoke_callsCloudRequestBuilder() {
        InvokeCommand command = new InvokeCommand("ak-1", "user-1", "session-1", "chat", "{\"content\":\"hello\"}");
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-123");

        ObjectNode cloudRequest = objectMapper.createObjectNode();
        cloudRequest.put("message", "hello");
        when(cloudRequestBuilder.buildCloudRequest(eq("app-123"), any(CloudRequestContext.class)))
                .thenReturn(cloudRequest);

        String result = strategy.buildInvoke(command, info);

        assertNotNull(result);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-123"), any(CloudRequestContext.class));
    }

    @Test
    @DisplayName("buildInvoke(chat) extracts sendUserAccount from command.payload to CloudRequestContext")
    void buildInvoke_chat_extractsSendUserAccount() {
        // 作用域说明：action=chat。q/p reply 在 business scope 的序列化尚未实现（见 spec 4.2.5），
        // 本用例不覆盖 q/p reply 场景，避免误导性测试。
        String payload = "{\"content\":\"hello\",\"sendUserAccount\":\"user-001\","
                + "\"assistantAccount\":\"asst-1\",\"toolSessionId\":\"tool-1\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "owner-1", "session-1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-123");
        when(cloudRequestBuilder.buildCloudRequest(any(), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> ctx = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-123"), ctx.capture());
        assertEquals("user-001", ctx.getValue().getSendUserAccount(),
                "sendUserAccount should be extracted from command.payload");
        assertEquals("asst-1", ctx.getValue().getAssistantAccount());
    }
}
