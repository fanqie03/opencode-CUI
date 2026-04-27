package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.SysConfigService;
import com.opencode.cui.skill.service.cloud.CloudRequestBuilder;
import com.opencode.cui.skill.service.cloud.DefaultCloudRequestStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ⓕ-1 集成测试：验证 InvokeCommand.payload 含 businessExtParam 时，
 * BusinessScopeStrategy.buildInvoke 经由真实 CloudRequestBuilder + DefaultCloudRequestStrategy
 * 端到端组装出含嵌套 extParameters 的云端报文（非 mock cloudRequestBuilder）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ⓕ-1: business chat 端到端 extParameters 透传")
class ExtParametersIntegrationTest {

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    @Mock
    private SysConfigService sysConfigService;

    private ObjectMapper objectMapper;
    private BusinessScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        DefaultCloudRequestStrategy defaultStrategy = new DefaultCloudRequestStrategy(objectMapper);
        CloudRequestBuilder builder = new CloudRequestBuilder(List.of(defaultStrategy), sysConfigService);
        strategy = new BusinessScopeStrategy(builder, cloudEventTranslator, objectMapper);
    }

    @Test
    @DisplayName("含 businessExtParam → cloud body extParameters.businessExtParam 等值，platformExtParam 占位 {}")
    void e2eChatPassesBusinessExtParam() throws Exception {
        when(sysConfigService.getValue(any(), eq("app-001"))).thenReturn(null);

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-1\","
                + "\"assistantAccount\":\"asst-1\",\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\","
                + "\"businessExtParam\":{\"isHwEmployee\":false,\"knowledgeId\":[\"kb-1\"]}}";
        InvokeCommand cmd = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);

        String invokeMessage = strategy.buildInvoke(cmd, info);

        ObjectNode message = (ObjectNode) objectMapper.readTree(invokeMessage);
        JsonNode cloudRequest = message.get("payload").get("cloudRequest");
        assertNotNull(cloudRequest);

        JsonNode ext = cloudRequest.get("extParameters");
        assertNotNull(ext);
        assertTrue(ext.isObject());

        JsonNode bep = ext.get("businessExtParam");
        assertNotNull(bep);
        assertTrue(bep.isObject());
        assertEquals(false, bep.get("isHwEmployee").asBoolean());
        assertTrue(bep.get("knowledgeId").isArray());
        assertEquals("kb-1", bep.get("knowledgeId").get(0).asText());

        JsonNode pep = ext.get("platformExtParam");
        assertNotNull(pep);
        assertTrue(pep.isObject());
        assertEquals(0, pep.size());
    }

    @Test
    @DisplayName("缺省 businessExtParam → cloud body 兜底 extParameters.businessExtParam = {}")
    void e2eChatMissingBusinessExtParam() throws Exception {
        when(sysConfigService.getValue(any(), eq("app-001"))).thenReturn(null);

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setAppId("app-001");

        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-1\"}";
        InvokeCommand cmd = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);

        String invokeMessage = strategy.buildInvoke(cmd, info);

        ObjectNode message = (ObjectNode) objectMapper.readTree(invokeMessage);
        JsonNode cloudRequest = message.get("payload").get("cloudRequest");
        JsonNode ext = cloudRequest.get("extParameters");

        assertTrue(ext.get("businessExtParam").isObject());
        assertEquals(0, ext.get("businessExtParam").size());
        assertTrue(ext.get("platformExtParam").isObject());
        assertEquals(0, ext.get("platformExtParam").size());
    }
}
