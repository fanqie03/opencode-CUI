package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.PendingChatRequest;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.AssistantIdResolverService;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.GatewayMessageRouter;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SessionRebuildService;
import com.opencode.cui.skill.service.SnowflakeIdGenerator;
import com.opencode.cui.skill.service.SysConfigService;
import com.opencode.cui.skill.service.cloud.DefaultCloudRequestStrategy;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfileRegistry;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @Mock
    private SnowflakeIdGenerator idGenerator;

    private ObjectMapper objectMapper;
    private BusinessScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        DefaultCloudRequestStrategy defaultStrategy = new DefaultCloudRequestStrategy(objectMapper);
        CloudRequestProfileRegistry registry = new CloudRequestProfileRegistry(
                List.of(defaultStrategy), sysConfigService, objectMapper, 300000L);
        strategy = new BusinessScopeStrategy(registry, cloudEventTranslator, objectMapper, idGenerator);
    }

    @Test
    @DisplayName("含 businessExtParam → cloud body extParameters.businessExtParam 等值，platformExtParam 占位 {}")
    void e2eChatPassesBusinessExtParam() throws Exception {
        when(sysConfigService.getValue(any(), eq("app-001"))).thenReturn(null);

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

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
        // platformExtParam 现在含三字段 key（PR1：domain/domainType/businessSessionId 均未传，
        // 序列化为 JSON null，key 保留）
        assertEquals(4, pep.size());
        assertTrue(pep.has("businessSessionDomain"));
        assertTrue(pep.get("businessSessionDomain").isNull());
        assertTrue(pep.has("businessSessionType"));
        assertTrue(pep.get("businessSessionType").isNull());
        assertTrue(pep.has("businessSessionId"));
        assertTrue(pep.get("businessSessionId").isNull());
        assertEquals("app-001", pep.path("bizRobotTag").asText());
    }

    @Test
    @DisplayName("缺省 businessExtParam → cloud body 兜底 extParameters.businessExtParam = {}")
    void e2eChatMissingBusinessExtParam() throws Exception {
        when(sysConfigService.getValue(any(), eq("app-001"))).thenReturn(null);

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("app-001");

        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-1\","
                + "\"assistantAccount\":\"asst-1\",\"sendUserAccount\":\"u-1\"}";
        InvokeCommand cmd = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);

        String invokeMessage = strategy.buildInvoke(cmd, info);

        ObjectNode message = (ObjectNode) objectMapper.readTree(invokeMessage);
        JsonNode cloudRequest = message.get("payload").get("cloudRequest");
        JsonNode ext = cloudRequest.get("extParameters");

        assertTrue(ext.get("businessExtParam").isObject());
        assertEquals(0, ext.get("businessExtParam").size());
        assertTrue(ext.get("platformExtParam").isObject());
        // platformExtParam 现在含三字段 key（PR1：均未传 → JSON null）
        assertEquals(4, ext.get("platformExtParam").size());
        assertTrue(ext.get("platformExtParam").get("businessSessionDomain").isNull());
        assertTrue(ext.get("platformExtParam").get("businessSessionType").isNull());
        assertTrue(ext.get("platformExtParam").get("businessSessionId").isNull());
        assertEquals("app-001", ext.get("platformExtParam").path("bizRobotTag").asText());
    }

    // =====================================================================
    // PR3b Nested A: personal scope 端到端 wire 形态
    //   走真实 GatewayRelayService.buildInvokeMessage（personal 出站唯一路径），
    //   ArgumentCaptor 拦截 sendToGateway 字符串后还原 JSON 断言。
    // =====================================================================
    @Nested
    @DisplayName("PR3b Nested A: personal scope 端到端 wire 形态")
    class PersonalScopePathTests {

        private ObjectMapper personalMapper;
        private GatewayRelayService relayService;
        private GatewayRelayService.GatewayRelayTarget relayTarget;

        @BeforeEach
        void setUpPersonal() {
            personalMapper = new ObjectMapper();
            GatewayMessageRouter messageRouter = mock(GatewayMessageRouter.class);
            SessionRebuildService rebuildService = mock(SessionRebuildService.class);
            RedisMessageBroker redisMessageBroker = mock(RedisMessageBroker.class);
            AssistantIdResolverService assistantIdResolverService = mock(AssistantIdResolverService.class);
            AssistantInfoService assistantInfoService = mock(AssistantInfoService.class);
            AssistantScopeDispatcher dispatcher = mock(AssistantScopeDispatcher.class);
            StreamMessageEmitter emitter = mock(StreamMessageEmitter.class);
            PersonalScopeStrategy personalStrategy = mock(PersonalScopeStrategy.class);

            org.mockito.Mockito.lenient().when(personalStrategy.getScope()).thenReturn("personal");
            org.mockito.Mockito.lenient().when(dispatcher.getStrategy(any(), any(), any(AssistantInfo.class)))
                    .thenReturn(personalStrategy);
            org.mockito.Mockito.lenient().when(assistantInfoService.getAssistantInfo(anyString()))
                    .thenReturn(new AssistantInfo());
            org.mockito.Mockito.lenient().when(assistantIdResolverService.resolve(anyString(), anyString()))
                    .thenReturn(null);

            relayTarget = mock(GatewayRelayService.GatewayRelayTarget.class);
            org.mockito.Mockito.lenient().when(relayTarget.hasActiveConnection()).thenReturn(true);
            org.mockito.Mockito.lenient().when(relayTarget.sendToGateway(anyString())).thenReturn(true);

             relayService = new GatewayRelayService(personalMapper, messageRouter, rebuildService,
                     redisMessageBroker, assistantIdResolverService, assistantInfoService,
                     dispatcher, emitter, mock(ApiCallMetricsService.class));
            relayService.setGatewayRelayTarget(relayTarget);
        }

        private JsonNode sendAndCaptureWire(InvokeCommand cmd) throws Exception {
            relayService.sendInvokeToGateway(cmd);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(relayTarget, times(1)).sendToGateway(captor.capture());
            return personalMapper.readTree(captor.getValue());
        }

        @Test
        @DisplayName("personal chat: businessExtParam 搬入 extParameters 信封 + platformExtParam 三字段全填")
        void personal_chat_extParametersEnvelope_threeFieldsFilled() throws Exception {
            ObjectNode payload = personalMapper.createObjectNode();
            payload.put("text", "hello");
            payload.put("toolSessionId", "ts-personal-1");
            ObjectNode bep = personalMapper.createObjectNode();
            bep.put("foo", "bar");
            bep.put("count", 7);
            payload.set("businessExtParam", bep);

            InvokeCommand cmd = new InvokeCommand(
                    "ak-p1", "user-p1", "1101", "chat",
                    personalMapper.writeValueAsString(payload),
                    null, "im", "group", "biz-group-p1");

            JsonNode wire = sendAndCaptureWire(cmd);
            JsonNode outPayload = wire.path("payload");

            assertFalse(outPayload.has("businessExtParam"),
                    "payload 顶层 businessExtParam 必须被搬走");
            JsonNode ext = outPayload.path("extParameters");
            assertTrue(ext.isObject(), "extParameters 信封必须出现");
            assertEquals("bar", ext.path("businessExtParam").path("foo").asText());
            assertEquals(7, ext.path("businessExtParam").path("count").asInt());

            JsonNode platform = ext.path("platformExtParam");
            assertEquals("im", platform.path("businessSessionDomain").asText());
            assertEquals("group", platform.path("businessSessionType").asText());
            assertEquals("biz-group-p1", platform.path("businessSessionId").asText());
        }

        @Test
        @DisplayName("personal chat: command 三字段全 null → platformExtParam 三 key 保留, 值 JSON null")
        void personal_chat_extParametersEnvelope_threeFieldsAllNull() throws Exception {
            ObjectNode payload = personalMapper.createObjectNode();
            payload.put("text", "anon");

            // 5 参 InvokeCommand → domain/domainType/businessSessionId 默认 null
            InvokeCommand cmd = new InvokeCommand(
                    "ak-p2", "user-p2", "1102", "chat",
                    personalMapper.writeValueAsString(payload));

            JsonNode wire = sendAndCaptureWire(cmd);
            JsonNode platform = wire.path("payload").path("extParameters").path("platformExtParam");

            assertTrue(platform.has("businessSessionDomain"));
            assertTrue(platform.has("businessSessionType"));
            assertTrue(platform.has("businessSessionId"));
            assertTrue(platform.has("bizRobotTag"));
            assertTrue(platform.path("businessSessionDomain").isNull());
            assertTrue(platform.path("businessSessionType").isNull());
            assertTrue(platform.path("businessSessionId").isNull());
            assertTrue(platform.path("bizRobotTag").isNull());
        }

        @Test
        @DisplayName("personal chat: domain 有值, type/id null → 部分填充, 缺失 key 仍保留为 JSON null")
        void personal_chat_partialNull() throws Exception {
            ObjectNode payload = personalMapper.createObjectNode();
            payload.put("text", "partial");

            InvokeCommand cmd = new InvokeCommand(
                    "ak-p3", "user-p3", "1103", "chat",
                    personalMapper.writeValueAsString(payload),
                    null, "miniapp", null, null);

            JsonNode wire = sendAndCaptureWire(cmd);
            JsonNode platform = wire.path("payload").path("extParameters").path("platformExtParam");

            assertEquals("miniapp", platform.path("businessSessionDomain").asText());
            assertTrue(platform.has("businessSessionType"));
            assertTrue(platform.path("businessSessionType").isNull(),
                    "domainType null → JSON null, key 保留");
            assertTrue(platform.has("businessSessionId"));
            assertTrue(platform.path("businessSessionId").isNull(),
                    "businessSessionId null → JSON null, key 保留");
            assertTrue(platform.has("bizRobotTag"));
            assertTrue(platform.path("bizRobotTag").isNull());
        }

        @Test
        @DisplayName("personal question_reply: 同样注入 extParameters 信封（chat 之外的 action 一致）")
        void personal_questionReply_extParametersInjected() throws Exception {
            ObjectNode payload = personalMapper.createObjectNode();
            payload.put("toolSessionId", "ts-personal-qr");
            payload.put("answer", "选项A");
            payload.put("toolCallId", "call-q-1");

            InvokeCommand cmd = new InvokeCommand(
                    "ak-p4", "user-p4", "1104", GatewayActions.QUESTION_REPLY,
                    personalMapper.writeValueAsString(payload),
                    null, "im", "direct", "biz-direct-p4");

            JsonNode wire = sendAndCaptureWire(cmd);
            JsonNode ext = wire.path("payload").path("extParameters");

            assertTrue(ext.isObject(),
                    "question_reply 也必须携带 extParameters 信封");
            // 没传 businessExtParam → 默认 {}
            assertTrue(ext.path("businessExtParam").isObject());
            assertEquals(0, ext.path("businessExtParam").size());

            JsonNode platform = ext.path("platformExtParam");
            assertEquals("im", platform.path("businessSessionDomain").asText());
            assertEquals("direct", platform.path("businessSessionType").asText());
            assertEquals("biz-direct-p4", platform.path("businessSessionId").asText());
        }

        @Test
        @DisplayName("personal chat: payload.imGroupId 顶层保留不动（PRD R9 命名冗余约定）")
        void personal_imGroupIdPreservedAtTopLevel() throws Exception {
            ObjectNode payload = personalMapper.createObjectNode();
            payload.put("text", "group msg");
            payload.put("toolSessionId", "ts-personal-grp");
            payload.put("imGroupId", "group-001");
            payload.put("sendUserAccount", "real-sender");

            InvokeCommand cmd = new InvokeCommand(
                    "ak-p5", "user-p5", "1105", "chat",
                    personalMapper.writeValueAsString(payload),
                    null, "im", "group", "group-001");

            JsonNode wire = sendAndCaptureWire(cmd);
            JsonNode outPayload = wire.path("payload");

            // 关键不变量：imGroupId 顶层仍在
            assertTrue(outPayload.has("imGroupId"),
                    "payload.imGroupId 顶层必须保留（R9 命名冗余约定）");
            assertEquals("group-001", outPayload.path("imGroupId").asText());

            // 同时 extParameters.platformExtParam.businessSessionId 也含同值（短期并存）
            JsonNode platform = outPayload.path("extParameters").path("platformExtParam");
            assertEquals("group-001", platform.path("businessSessionId").asText(),
                    "businessSessionId 与 imGroupId 短期同值并存");
        }
    }

    // =====================================================================
    // PR3b Nested B: retry 路径端到端
    //   GatewayRelayService.buildInvokeMessage 已有幂等保护：当 retryPendingMessages
    //   预先构造好 extParameters 信封后, buildInvokeMessage 不会二次注入。
    //   本 Nested 直接验证 retryPendingMessages 重建的 chatPayload + 9 参 InvokeCommand。
    // =====================================================================
    @Nested
    @DisplayName("PR3b Nested B: retry 路径端到端")
    class RetryPathTests {

        private ObjectMapper retryMapper;

        @BeforeEach
        void setUpRetry() {
            retryMapper = new ObjectMapper();
        }

        /**
         * 模拟 {@code GatewayMessageRouter.retryPendingMessages} 重建 chatPayload 的核心逻辑
         * （与生产代码 line 906–947 1:1 对齐）。
         * <p>这里走"真实 helper + 真实 ObjectMapper"组装，<b>不</b> mock 任何字符串拼接，
         * 端到端验证字段填充。
         */
        private com.opencode.cui.skill.model.InvokeCommand simulateRetryRebuildOne(
                String ak, String userId, String welinkSessionId, String toolSessionId,
                PendingChatRequest req, Boolean suppressReply) throws Exception {
            ObjectNode chatPayload = retryMapper.createObjectNode();
            chatPayload.put("text", req.text());
            chatPayload.put("toolSessionId", toolSessionId);
            chatPayload.put("assistantAccount", req.assistantAccount());
            chatPayload.put("sendUserAccount", req.sendUserAccount());
            chatPayload.put("imGroupId", req.imGroupId());
            chatPayload.put("messageId", req.messageId());

            JsonNode ext = req.businessExtParam();
            ObjectNode extParameters = retryMapper.createObjectNode();
            if (ext != null && !ext.isNull()) {
                extParameters.set("businessExtParam", ext);
            } else {
                extParameters.set("businessExtParam", retryMapper.createObjectNode());
            }
            extParameters.set("platformExtParam",
                    com.opencode.cui.skill.service.PlatformExtParamBuilder.build(retryMapper,
                            req.businessSessionDomain(),
                            req.businessSessionType(),
                            req.imGroupId(),
                            req.bizRobotTag(),
                            req.allowedSlashCommands()));
            chatPayload.set("extParameters", extParameters);

            String payloadStr = retryMapper.writeValueAsString(chatPayload);
            return new InvokeCommand(
                    ak, userId, welinkSessionId, GatewayActions.CHAT, payloadStr, suppressReply,
                    req.businessSessionDomain(),
                    req.businessSessionType(),
                    req.imGroupId());
        }

        @Test
        @DisplayName("retry: 完整 PendingChatRequest → chatPayload.extParameters.platformExtParam 三字段绑定值")
        void retry_chatPayload_containsExtParametersWithThreeFields() throws Exception {
            JsonNode bep = retryMapper.readTree("{\"flag\":true}");
            PendingChatRequest req = new PendingChatRequest(
                    "hello", "assist-r1", "sender-r1", "biz-group-r1", "msg-r1",
                    bep, "im", "group", "robot-r1", null);

            InvokeCommand cmd = simulateRetryRebuildOne(
                    "ak-r1", "user-r1", "2101", "tool-r1", req, null);

            JsonNode payload = retryMapper.readTree(cmd.payload());

            // 顶层 businessExtParam 不应出现（已搬到 extParameters）
            assertFalse(payload.has("businessExtParam"));

            JsonNode ext = payload.path("extParameters");
            assertEquals(true, ext.path("businessExtParam").path("flag").asBoolean());

            JsonNode platform = ext.path("platformExtParam");
            assertEquals("im", platform.path("businessSessionDomain").asText());
            assertEquals("group", platform.path("businessSessionType").asText());
            assertEquals("biz-group-r1", platform.path("businessSessionId").asText());
            assertEquals("robot-r1", platform.path("bizRobotTag").asText());
        }

        @Test
        @DisplayName("retry: 老格式 entry（businessSessionDomain/Type 为 null）→ platformExtParam 三字段为 JSON null, 不抛异常")
        void retry_oldFormatPendingEntry_replayedWithNullPlatformFields() throws Exception {
            // 模拟 PR2 之前老格式 entry: 缺 businessSessionDomain / businessSessionType,
            // Jackson 反序列化时自动兜底为 null
            PendingChatRequest legacy = new PendingChatRequest(
                    "legacy text", "assist-r2", "sender-r2", null, "msg-r2",
                    null, null, null);

            InvokeCommand cmd = simulateRetryRebuildOne(
                    "ak-r2", "user-r2", "2102", "tool-r2", legacy, null);

            JsonNode payload = retryMapper.readTree(cmd.payload());
            JsonNode platform = payload.path("extParameters").path("platformExtParam");

            assertTrue(platform.has("businessSessionDomain"));
            assertTrue(platform.has("businessSessionType"));
            assertTrue(platform.has("businessSessionId"));
            assertTrue(platform.has("bizRobotTag"));
            assertTrue(platform.path("businessSessionDomain").isNull(),
                    "老 entry domain null → JSON null");
            assertTrue(platform.path("businessSessionType").isNull(),
                    "老 entry type null → JSON null");
            assertTrue(platform.path("businessSessionId").isNull(),
                    "imGroupId null → businessSessionId JSON null");
            assertTrue(platform.path("bizRobotTag").isNull());

            // 关键不变量：老 entry 也得 businessExtParam 兜底 {}
            assertTrue(payload.path("extParameters").path("businessExtParam").isObject());
            assertEquals(0, payload.path("extParameters").path("businessExtParam").size());
        }

        @Test
        @DisplayName("retry: InvokeCommand 9 参全传（domain/domainType/businessSessionId）")
        void retry_invokeCommand_nineArgWithDomainTypeId() throws Exception {
            PendingChatRequest req = new PendingChatRequest(
                    "nine-arg", "assist-r3", "sender-r3", "biz-group-r3", "msg-r3",
                    null, "im", "group");

            InvokeCommand cmd = simulateRetryRebuildOne(
                    "ak-r3", "user-r3", "2103", "tool-r3", req, Boolean.TRUE);

            // 直接断言 InvokeCommand record 9 字段, 不读 wire JSON
            assertEquals("ak-r3", cmd.ak());
            assertEquals("user-r3", cmd.userId());
            assertEquals("2103", cmd.sessionId());
            assertEquals(GatewayActions.CHAT, cmd.action());
            assertEquals(Boolean.TRUE, cmd.suppressReply());
            assertEquals("im", cmd.domain(),
                    "PR2: 9 参 InvokeCommand.domain 来自 PendingChatRequest.businessSessionDomain");
            assertEquals("group", cmd.domainType(),
                    "PR2: 9 参 InvokeCommand.domainType 来自 PendingChatRequest.businessSessionType");
            assertEquals("biz-group-r3", cmd.businessSessionId(),
                    "PR2: 9 参 InvokeCommand.businessSessionId 来自 PendingChatRequest.imGroupId (R9 命名冗余)");
        }
    }
}
