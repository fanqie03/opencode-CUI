package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.AssistantInfoService;
import com.opencode.cui.skill.service.BusinessWhitelistService;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.GatewayMessageRouter;
import com.opencode.cui.skill.service.ImInteractionStateService;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.OpenCodeEventTranslator;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SessionRebuildService;
import com.opencode.cui.skill.service.SessionRouteService;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import com.opencode.cui.skill.service.SkillMessageService;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import com.opencode.cui.skill.service.TranslatorSessionCache;
import com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher;
import com.opencode.cui.skill.service.delivery.StreamMessageEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScopeCloudProtocolIntegration (router-entry)")
class PersonalScopeCloudProtocolIntegrationTest {

    private static final String LOCAL_INSTANCE = "ss-test-local";
    private static final String SESSION_ID = "100";
    private static final String AK = "ak-personal-1";
    private static final String USER_ID = "user-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private SkillMessageService messageService;
    @Mock private SkillSessionService sessionService;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private OpenCodeEventTranslator legacyTranslatorArg;
    @Mock private MessagePersistenceService persistenceService;
    @Mock private StreamBufferService bufferService;
    @Mock private SessionRebuildService rebuildService;
    @Mock private ImInteractionStateService interactionStateService;
    @Mock private ImOutboundService imOutboundService;
    @Mock private SessionRouteService sessionRouteService;
    @Mock private SkillInstanceRegistry skillInstanceRegistry;
    @Mock private AssistantInfoService assistantInfoService;
    @Mock private com.opencode.cui.skill.service.ChannelLookupService channelLookupService;
    @Mock private com.opencode.cui.skill.service.ChannelSuppressReplyWhitelistService channelSuppressReplyWhitelistService;
    @Mock private OutboundDeliveryDispatcher outboundDeliveryDispatcher;
    @Mock private StreamMessageEmitter emitter;
    @Mock private BusinessWhitelistService whitelistService;

    private AssistantScopeDispatcher scopeDispatcher;
    private PersonalScopeStrategy personalStrategy;
    private CloudEventTranslator cloudEventTranslator;
    private OpenCodeEventTranslator openCodeEventTranslator;

    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() throws Exception {
        cloudEventTranslator = new CloudEventTranslator();
        java.lang.reflect.Method init = CloudEventTranslator.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(cloudEventTranslator);

        openCodeEventTranslator = new OpenCodeEventTranslator(objectMapper, new TranslatorSessionCache());
        personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);
        // PR2: AssistantScopeDispatcher 新增 ruleService + defaultStrategy 依赖。本集成测试不走默认助手分支：
        // 传 mock ruleService（默认 lookup 返 empty） + null defaultStrategy 即可。
        scopeDispatcher = new AssistantScopeDispatcher(
                List.of(personalStrategy), whitelistService,
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.DefaultAssistantRuleService.class),
                null);

        lenient().when(skillInstanceRegistry.getInstanceId()).thenReturn(LOCAL_INSTANCE);
        lenient().when(sessionRouteService.getOwnerInstance(any())).thenReturn(LOCAL_INSTANCE);
        lenient().when(assistantInfoService.getCachedScope(anyString())).thenReturn("personal");
        lenient().when(sessionService.activateSession(anyLong())).thenReturn(false);
        SkillSession fakeSession = new SkillSession();
        fakeSession.setId(Long.valueOf(SESSION_ID));
        lenient().when(sessionService.findByIdSafe(anyLong())).thenReturn(fakeSession);

        router = new GatewayMessageRouter(
                objectMapper, messageService, sessionService, redisMessageBroker,
                legacyTranslatorArg, persistenceService, bufferService, rebuildService,
                interactionStateService, imOutboundService, sessionRouteService,
                skillInstanceRegistry, assistantInfoService,
                channelLookupService, channelSuppressReplyWhitelistService,
                scopeDispatcher,
                outboundDeliveryDispatcher, emitter, 120);
    }

    @Test
    @DisplayName("cloud text.delta through router: same partId keeps partSeq, new partId increments")
    void routerCloudTextDelta_partSeqIncrements() {
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "hello ")));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "world")));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p2", "think")));

        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).emitToSession(any(), anyString(), anyString(), captor.capture());

        List<StreamMessage> captured = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.TEXT_DELTA.equals(m.getType()))
                .toList();
        assertEquals(3, captured.size(), "expected three TEXT_DELTA emitted by emitter");
        assertEquals("hello ", captured.get(0).getContent());
        assertEquals("world", captured.get(1).getContent());
        assertEquals("think", captured.get(2).getContent());
        assertEquals(Integer.valueOf(1), captured.get(0).getPartSeq());
        assertEquals(Integer.valueOf(1), captured.get(1).getPartSeq());
        assertEquals(Integer.valueOf(2), captured.get(2).getPartSeq());
    }

    @Test
    @DisplayName("cloud tool.update through router: emits TOOL_UPDATE with status/toolName/toolCallId/output")
    void routerCloudToolUpdate_emitsToolUpdate() {
        router.route("tool_event", AK, USER_ID, buildToolEventNode(
                buildCloudToolUpdate("m1", "p-tool-1", "call-abc", "web_search", "running", null)));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(
                buildCloudToolUpdate("m1", "p-tool-1", "call-abc", "web_search", "completed", "result-text")));

        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).emitToSession(any(), anyString(), anyString(), captor.capture());

        List<StreamMessage> toolUpdates = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.TOOL_UPDATE.equals(m.getType()))
                .toList();
        assertEquals(2, toolUpdates.size());
        StreamMessage first = toolUpdates.get(0);
        assertEquals("running", first.getStatus());
        assertNotNull(first.getTool());
        assertEquals("web_search", first.getTool().getToolName());
        assertEquals("call-abc", first.getTool().getToolCallId());
        StreamMessage second = toolUpdates.get(1);
        assertEquals("completed", second.getStatus());
        assertNotNull(second.getTool());
        assertEquals("result-text", second.getTool().getOutput());
    }

    @Test
    @DisplayName("session.status=idle through router clears partSeq counter; next text.delta restarts at 1")
    void routerSessionIdle_clearsPartSeqCounter() {
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "a")));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudSessionStatusIdle()));
        router.route("tool_event", AK, USER_ID, buildToolEventNode(buildCloudTextDelta("m1", "p1", "b")));

        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).emitToSession(any(), anyString(), anyString(), captor.capture());

        List<StreamMessage> textDeltas = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.TEXT_DELTA.equals(m.getType()))
                .toList();
        List<StreamMessage> statusMsgs = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.SESSION_STATUS.equals(m.getType()))
                .toList();

        assertEquals(2, textDeltas.size());
        assertEquals(Integer.valueOf(1), textDeltas.get(0).getPartSeq());
        assertEquals(Integer.valueOf(1), textDeltas.get(1).getPartSeq(),
                "after idle the counter is cleared; next delta for same partId restarts at 1");

        StreamMessage idleMsg = statusMsgs.stream()
                .filter(m -> "idle".equals(m.getSessionStatus()))
                .findFirst().orElse(null);
        assertNotNull(idleMsg);
    }

    @Test
    @DisplayName("opencode event (no protocol field) still routes to OpenCodeEventTranslator path")
    void routerOpencodeEvent_stillWorks() {
        ObjectNode part = objectMapper.createObjectNode();
        part.put("id", "p-oc-1");
        part.put("type", "text");
        part.put("sessionID", "s-oc");
        part.put("messageID", "m-oc");
        part.put("text", "oc hello");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("part", part);
        props.put("sessionID", "s-oc");
        props.put("messageID", "m-oc");
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message.part.updated");
        event.set("properties", props);

        router.route("tool_event", AK, USER_ID, buildToolEventNode(event));

        ArgumentCaptor<StreamMessage> captor = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter, atLeastOnce()).emitToSession(any(), anyString(), anyString(), captor.capture());
        StreamMessage out = captor.getAllValues().stream()
                .filter(m -> StreamMessage.Types.TEXT_DONE.equals(m.getType()))
                .findFirst().orElse(null);
        assertNotNull(out);
        assertEquals("oc hello", out.getContent());
    }

    // ---------------- helpers ----------------

    private ObjectNode buildToolEventNode(JsonNode eventNode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_event");
        node.put("welinkSessionId", SESSION_ID);
        node.put("ak", AK);
        node.set("event", eventNode);
        return node;
    }

    private JsonNode buildCloudTextDelta(String messageId, String partId, String content) {
        ObjectNode props = objectMapper.createObjectNode();
        props.put("messageId", messageId);
        props.put("partId", partId);
        props.put("content", content);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", "text.delta");
        event.set("properties", props);
        return event;
    }

    private JsonNode buildCloudToolUpdate(String messageId, String partId, String toolCallId,
                                          String toolName, String status, String output) {
        ObjectNode props = objectMapper.createObjectNode();
        props.put("messageId", messageId);
        props.put("partId", partId);
        props.put("toolCallId", toolCallId);
        props.put("toolName", toolName);
        props.put("status", status);
        if (output != null) {
            props.put("output", output);
        }
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", "tool.update");
        event.set("properties", props);
        return event;
    }

    private JsonNode buildCloudSessionStatusIdle() {
        // 严格只发 sessionStatus，匹配 spec §5.1 支持子集契约
        ObjectNode props = objectMapper.createObjectNode();
        props.put("sessionStatus", "idle");
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", "session.status");
        event.set("properties", props);
        return event;
    }
}
