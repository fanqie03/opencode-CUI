package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.service.scope.PersonalScopeStrategy;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * PR2: 验证 {@code GatewayRelayService.buildInvokeMessage}（personal scope wire 升级）。
 *
 * <p>关键覆盖：
 * <ul>
 *   <li>payload 顶层 businessExtParam 搬到 extParameters.businessExtParam（P2a）</li>
 *   <li>extParameters.platformExtParam 含 PR2 三字段（domain / domainType / businessSessionId）</li>
 *   <li>payload 已有 extParameters 时幂等保护：不二次注入</li>
 *   <li>三字段 null 时序列化为 JSON null（key 保留）</li>
 * </ul>
 */
@DisplayName("GatewayRelayService.buildInvokeMessage (PR2 personal wire 升级)")
class GatewayRelayServiceBuildInvokeMessageTest {

    private ObjectMapper objectMapper;
    private GatewayMessageRouter messageRouter;
    private SessionRebuildService rebuildService;
    private RedisMessageBroker redisMessageBroker;
    private AssistantIdResolverService assistantIdResolverService;
    private AssistantInfoService assistantInfoService;
    private AssistantScopeDispatcher scopeDispatcher;
    private StreamMessageEmitter emitter;
    private PersonalScopeStrategy personalStrategy;
    private GatewayRelayService service;
    private GatewayRelayService.GatewayRelayTarget relayTarget;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        messageRouter = mock(GatewayMessageRouter.class);
        rebuildService = mock(SessionRebuildService.class);
        redisMessageBroker = mock(RedisMessageBroker.class);
        assistantIdResolverService = mock(AssistantIdResolverService.class);
        assistantInfoService = mock(AssistantInfoService.class);
        scopeDispatcher = mock(AssistantScopeDispatcher.class);
        emitter = mock(StreamMessageEmitter.class);
        personalStrategy = mock(PersonalScopeStrategy.class);

        // dispatcher 永远返 personal strategy（buildInvokeMessage 仅服务于 personal 出站路径）
        when(personalStrategy.getScope()).thenReturn("personal");
        when(scopeDispatcher.getStrategy(any(), any(), any(AssistantInfo.class))).thenReturn(personalStrategy);
        when(assistantInfoService.getAssistantInfo(anyString())).thenReturn(new AssistantInfo());
        lenient().when(assistantInfoService.getAssistantInfo(anyString(), anyString())).thenReturn(new AssistantInfo());
        when(assistantIdResolverService.resolve(anyString(), anyString())).thenReturn(null);

        relayTarget = mock(GatewayRelayService.GatewayRelayTarget.class);
        when(relayTarget.hasActiveConnection()).thenReturn(true);
        when(relayTarget.sendToGateway(anyString())).thenReturn(true);

         service = new GatewayRelayService(objectMapper, messageRouter, rebuildService,
                 redisMessageBroker, assistantIdResolverService, assistantInfoService,
                 scopeDispatcher, emitter, mock(ApiCallMetricsService.class));
        service.setGatewayRelayTarget(relayTarget);
    }

    @Test
    @DisplayName("personal 路径：顶层透传 assistantAccount，并从 AssistantInfo 透传 businessTag")
    void personal_writesTopLevelAssistantAccountAndBusinessTag() throws Exception {
        AssistantInfo info = new AssistantInfo();
        info.setBusinessTag("biz-tag-local");
        when(assistantInfoService.getAssistantInfo("ak-1", "asst-1")).thenReturn(info);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", "hello");
        payload.put("toolSessionId", "ts-1");
        payload.put("assistantAccount", "asst-1");

        InvokeCommand cmd = new InvokeCommand(
                "ak-1", "user-1", "1001", "chat", objectMapper.writeValueAsString(payload),
                null, "im", "direct", "biz-dm-1");

        JsonNode message = captureSentMessage(cmd);

        assertEquals("asst-1", message.path("assistantAccount").asText());
        assertEquals("biz-tag-local", message.path("businessTag").asText());
        assertEquals("biz-tag-local",
                message.path("payload").path("extParameters").path("platformExtParam")
                        .path("bizRobotTag").asText());
    }

    /** Sends invoke and captures the serialized JSON sent to gateway. */
    private JsonNode captureSentMessage(InvokeCommand cmd) throws Exception {
        service.sendInvokeToGateway(cmd);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(relayTarget, times(1)).sendToGateway(captor.capture());
        return objectMapper.readTree(captor.getValue());
    }

    @Test
    @DisplayName("personal 路径：payload 顶层 businessExtParam 搬到 extParameters.businessExtParam + 注入 platformExtParam 三字段")
    void personal_movesBusinessExtParam_andInjectsPlatformExtParam() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", "hello");
        payload.put("toolSessionId", "ts-1");
        ObjectNode bep = objectMapper.createObjectNode();
        bep.put("k", "v");
        payload.set("businessExtParam", bep);

        InvokeCommand cmd = new InvokeCommand(
                "ak-1", "user-1", "1001", "chat", objectMapper.writeValueAsString(payload),
                null, "im", "group", "biz-group-1");

        JsonNode message = captureSentMessage(cmd);
        JsonNode outPayload = message.path("payload");

        // 顶层 businessExtParam 应已被移除
        assertFalse(outPayload.has("businessExtParam"),
                "payload top-level businessExtParam must be removed (moved into extParameters)");

        // extParameters 信封存在
        JsonNode extParameters = outPayload.path("extParameters");
        assertTrue(extParameters.isObject(), "extParameters must be present");

        // extParameters.businessExtParam == 原 bep
        JsonNode movedExt = extParameters.path("businessExtParam");
        assertEquals("v", movedExt.path("k").asText());

        // platformExtParam 三字段
        JsonNode platform = extParameters.path("platformExtParam");
        assertEquals("im", platform.path("businessSessionDomain").asText());
        assertEquals("group", platform.path("businessSessionType").asText());
        assertEquals("biz-group-1", platform.path("businessSessionId").asText());
    }

    @Test
    @DisplayName("personal 路径：payload 无 businessExtParam 顶层 → extParameters.businessExtParam = {}")
    void personal_noBusinessExtParam_defaultEmptyObject() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", "hi");
        payload.put("toolSessionId", "ts-2");

        InvokeCommand cmd = new InvokeCommand(
                "ak-2", "user-2", "1002", "chat", objectMapper.writeValueAsString(payload),
                null, "miniapp", "single", "biz-2");

        JsonNode message = captureSentMessage(cmd);
        JsonNode extParameters = message.path("payload").path("extParameters");

        JsonNode bep = extParameters.path("businessExtParam");
        assertTrue(bep.isObject(), "businessExtParam must default to empty object");
        assertEquals(0, bep.size(), "default businessExtParam should be empty {}");

        JsonNode platform = extParameters.path("platformExtParam");
        assertEquals("miniapp", platform.path("businessSessionDomain").asText());
        assertEquals("single", platform.path("businessSessionType").asText());
        assertEquals("biz-2", platform.path("businessSessionId").asText());
    }

    @Test
    @DisplayName("personal create_session：allowedSlashCommands 写入 platformExtParam")
    void personal_createSession_injectsAllowedSlashCommands() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", "im-direct-biz-3");

        InvokeCommand cmd = new InvokeCommand(
                "ak-3", "owner-3", "1003", "create_session", objectMapper.writeValueAsString(payload),
                null, "im", "direct", "biz-direct-3",
                java.util.List.of("new", "sessions", "session", "models"));

        JsonNode message = captureSentMessage(cmd);
        JsonNode platform = message.path("payload").path("extParameters").path("platformExtParam");

        assertEquals("im", platform.path("businessSessionDomain").asText());
        assertEquals("direct", platform.path("businessSessionType").asText());
        assertEquals("biz-direct-3", platform.path("businessSessionId").asText());
        assertTrue(platform.path("allowedSlashCommands").isArray());
        assertEquals(4, platform.path("allowedSlashCommands").size());
        assertEquals("new", platform.path("allowedSlashCommands").get(0).asText());
        assertEquals("sessions", platform.path("allowedSlashCommands").get(1).asText());
        assertEquals("session", platform.path("allowedSlashCommands").get(2).asText());
        assertEquals("models", platform.path("allowedSlashCommands").get(3).asText());
    }

    @Test
    @DisplayName("幂等保护：payload 已有 extParameters → 不二次注入 / 不覆盖")
    void idempotency_existingExtParameters_notOverwritten() throws Exception {
        // 模拟 retryPendingMessages 提前构造 extParameters 的场景
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", "preexisting");
        ObjectNode preBuiltExt = objectMapper.createObjectNode();
        ObjectNode preBuiltBep = objectMapper.createObjectNode();
        preBuiltBep.put("preexisting", true);
        preBuiltExt.set("businessExtParam", preBuiltBep);
        ObjectNode preBuiltPlatform = objectMapper.createObjectNode();
        preBuiltPlatform.put("businessSessionDomain", "preset-domain");
        preBuiltPlatform.put("businessSessionType", "preset-type");
        preBuiltPlatform.put("businessSessionId", "preset-id");
        preBuiltExt.set("platformExtParam", preBuiltPlatform);
        payload.set("extParameters", preBuiltExt);

        InvokeCommand cmd = new InvokeCommand(
                "ak-3", "user-3", "1003", "chat", objectMapper.writeValueAsString(payload),
                null, "should-be-ignored", "should-be-ignored", "should-be-ignored");

        JsonNode message = captureSentMessage(cmd);
        JsonNode extParameters = message.path("payload").path("extParameters");

        // 保留预置值, 不被 command 三字段覆盖
        assertEquals(true, extParameters.path("businessExtParam").path("preexisting").asBoolean());
        assertEquals("preset-domain", extParameters.path("platformExtParam").path("businessSessionDomain").asText());
        assertEquals("preset-type", extParameters.path("platformExtParam").path("businessSessionType").asText());
        assertEquals("preset-id", extParameters.path("platformExtParam").path("businessSessionId").asText());
    }

    @Test
    @DisplayName("command 三字段 null → platformExtParam 三字段序列化为 JSON null（key 保留）")
    void nullCommandFields_serializeAsJsonNull() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", "anonymous");

        // 5 参 InvokeCommand 默认 domain/domainType/businessSessionId 全 null
        InvokeCommand cmd = new InvokeCommand(
                "ak-4", "user-4", "1004", "chat", objectMapper.writeValueAsString(payload));

        JsonNode message = captureSentMessage(cmd);
        JsonNode platform = message.path("payload").path("extParameters").path("platformExtParam");

        assertNotNull(platform);
        assertTrue(platform.has("businessSessionDomain"), "key must be retained");
        assertTrue(platform.has("businessSessionType"), "key must be retained");
        assertTrue(platform.has("businessSessionId"), "key must be retained");
        assertTrue(platform.has("bizRobotTag"), "key must be retained");
        assertTrue(platform.path("businessSessionDomain").isNull(), "null value preserved as JSON null");
        assertTrue(platform.path("businessSessionType").isNull(), "null value preserved as JSON null");
        assertTrue(platform.path("businessSessionId").isNull(), "null value preserved as JSON null");
        assertTrue(platform.path("bizRobotTag").isNull(), "null value preserved as JSON null");
    }

    @Test
    @DisplayName("非 chat action（permission_reply）：personal 路径同样补 extParameters 信封")
    void permissionReply_alsoInjectsExtParameters() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("permissionId", "perm-1");
        payload.put("response", "once");

        InvokeCommand cmd = new InvokeCommand(
                "ak-5", "user-5", "1005", "permission_reply", objectMapper.writeValueAsString(payload),
                null, "im", "direct", "biz-d");

        JsonNode message = captureSentMessage(cmd);
        JsonNode extParameters = message.path("payload").path("extParameters");

        assertTrue(extParameters.isObject(), "permission_reply must also carry extParameters envelope");
        assertEquals("biz-d",
                extParameters.path("platformExtParam").path("businessSessionId").asText());
    }
}
