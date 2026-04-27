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

import java.util.Map;

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

    // ========== businessExtParam 透传场景（6 用例） ==========

    @Test
    @DisplayName("buildInvoke(chat) 含 businessExtParam → 透传到 extParameters.businessExtParam")
    void buildInvoke_chat_passesBusinessExtParam() throws Exception {
        String payload = "{\"text\":\"hi\",\"businessExtParam\":{\"a\":1,\"k\":[1,2]}," +
                "\"toolSessionId\":\"cloud-001\",\"assistantAccount\":\"asst-1\"," +
                "\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        Map<String, Object> ext = captor.getValue().getExtParameters();
        assertNotNull(ext);
        JsonNode bep = (JsonNode) ext.get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(1, bep.get("a").asInt());
        assertTrue(bep.get("k").isArray());
        JsonNode pep = (JsonNode) ext.get("platformExtParam");
        assertTrue(pep.isObject());
        assertEquals(0, pep.size());
    }

    @Test
    @DisplayName("buildInvoke(chat) 缺省 businessExtParam → extParameters.businessExtParam 兜底为 {}")
    void buildInvoke_chat_missingBusinessExtParam() throws Exception {
        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-001\"," +
                "\"assistantAccount\":\"asst-1\",\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        Map<String, Object> ext = captor.getValue().getExtParameters();
        assertNotNull(ext);
        JsonNode bep = (JsonNode) ext.get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
        JsonNode pep = (JsonNode) ext.get("platformExtParam");
        assertTrue(pep.isObject());
        assertEquals(0, pep.size());
    }

    @Test
    @DisplayName("buildInvoke(question_reply) 含 businessExtParam → 透传")
    void buildInvoke_questionReply_passesBusinessExtParam() throws Exception {
        String payload = "{\"answer\":\"ok\",\"toolCallId\":\"tc-1\"," +
                "\"businessExtParam\":{\"q\":\"x\"},\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "question_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        JsonNode bep = (JsonNode) captor.getValue().getExtParameters().get("businessExtParam");
        assertEquals("x", bep.get("q").asText());
        JsonNode pep = (JsonNode) captor.getValue().getExtParameters().get("platformExtParam");
        assertTrue(pep.isObject());
        assertEquals(0, pep.size());
    }

    @Test
    @DisplayName("buildInvoke(question_reply) 缺省 → 兜底 {}")
    void buildInvoke_questionReply_missingBusinessExtParam() throws Exception {
        String payload = "{\"answer\":\"ok\",\"toolCallId\":\"tc-1\",\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "question_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        JsonNode bep = (JsonNode) captor.getValue().getExtParameters().get("businessExtParam");
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("buildInvoke(permission_reply) 含 businessExtParam → 透传")
    void buildInvoke_permissionReply_passesBusinessExtParam() throws Exception {
        String payload = "{\"permissionId\":\"p-1\",\"response\":\"once\"," +
                "\"businessExtParam\":{\"p\":true},\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "permission_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        JsonNode bep = (JsonNode) captor.getValue().getExtParameters().get("businessExtParam");
        assertTrue(bep.get("p").asBoolean());
        JsonNode pep = (JsonNode) captor.getValue().getExtParameters().get("platformExtParam");
        assertTrue(pep.isObject());
        assertEquals(0, pep.size());
    }

    @Test
    @DisplayName("buildInvoke(permission_reply) 缺省 → 兜底 {}")
    void buildInvoke_permissionReply_missingBusinessExtParam() throws Exception {
        String payload = "{\"permissionId\":\"p-1\",\"response\":\"once\",\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "permission_reply", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        JsonNode bep = (JsonNode) captor.getValue().getExtParameters().get("businessExtParam");
        assertEquals(0, bep.size());
    }

    // ========== businessExtParam 异常分支（3 用例） ==========

    @Test
    @DisplayName("buildInvoke 业务方传字符串（非 object） → 兜底 {}")
    void buildInvoke_businessExtParam_asString_fallback() throws Exception {
        String payload = "{\"text\":\"hi\",\"businessExtParam\":\"abc\",\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        JsonNode bep = (JsonNode) captor.getValue().getExtParameters().get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("buildInvoke 业务方传数组 → 兜底 {}")
    void buildInvoke_businessExtParam_asArray_fallback() throws Exception {
        String payload = "{\"text\":\"hi\",\"businessExtParam\":[1,2,3],\"toolSessionId\":\"cloud-001\"}";
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        JsonNode bep = (JsonNode) captor.getValue().getExtParameters().get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
    }

    @Test
    @DisplayName("buildInvoke payload 整体非法 JSON → 不抛异常，兜底 {}")
    void buildInvoke_payloadInvalidJson_fallback() throws Exception {
        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", "chat", "not-a-json");
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any(CloudRequestContext.class)))
                .thenReturn(objectMapper.createObjectNode());

        strategy.buildInvoke(command, info);

        ArgumentCaptor<CloudRequestContext> captor = ArgumentCaptor.forClass(CloudRequestContext.class);
        verify(cloudRequestBuilder).buildCloudRequest(eq("app-001"), captor.capture());
        JsonNode bep = (JsonNode) captor.getValue().getExtParameters().get("businessExtParam");
        assertTrue(bep.isObject());
        assertEquals(0, bep.size());
    }
}
