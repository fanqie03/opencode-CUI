package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.AssistantInstanceInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudConnectionHandle;
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import com.opencode.cui.gateway.service.cloud.CloudAuthService;
import com.opencode.cui.gateway.service.cloud.WebHookExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * CloudAgentService 单元测试（TDD）。
 *
 * <p>覆盖 assistantAccount remoteProperty + businessTag SysConfig 驱动的分叉逻辑：
 * <ul>
 *   <li>action → scope 映射（chat / question_reply / permission_reply / unknown）</li>
 *   <li>channelType vs action 双向校验</li>
 *   <li>SSE/WebSocket 走 CloudProtocolClient；webhook 走 WebHookExecutor</li>
 *   <li>assistantAccount 未命中 remoteProperty 后按 businessTag 读取 SysConfig</li>
 *   <li>原 chat 路径的 fallback messageId / fallback partId / errorSent 单次回流逻辑保留</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CloudAgentServiceTest {

    private static final String TEST_AK = "ak-test-001";
    private static final String CHAT_SCOPE = "callback:weagent:chat";
    private static final String QR_SCOPE = "callback:weagent:question_reply";
    private static final String PR_SCOPE = "callback:weagent:permission_reply";

    @Mock
    private SysConfigFallbackProviderV2 sysConfigRouteProvider;
    @Mock
    private CloudRouteSwitchService cloudRouteSwitchService;
    @Mock
    private AssistantInstanceInfoService assistantInstanceInfoService;
    @Mock
    private CloudProtocolClient cloudProtocolClient;
    @Mock
    private WebHookExecutor webHookExecutor;
    @Mock
    private CloudTimeoutProperties cloudTimeoutProperties;
    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private Consumer<GatewayMessage> onRelay;
    @Mock
    private HttpClient httpClient;
    @Mock
    private CloudAuthService cloudAuthService;
    @Mock
    private Executor abortExecutor;

    @Captor
    private ArgumentCaptor<Consumer<GatewayMessage>> onEventCaptor;
    @Captor
    private ArgumentCaptor<Consumer<Throwable>> onErrorCaptor;
    @Captor
    private ArgumentCaptor<CloudConnectionContext> contextCaptor;
    @Captor
    private ArgumentCaptor<GatewayMessage> messageCaptor;

    private CloudAgentService cloudAgentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(cloudTimeoutProperties.getFirstEventTimeoutSeconds()).thenReturn(120);
        lenient().when(cloudTimeoutProperties.getEffectiveIdleTimeoutSeconds(anyString())).thenReturn(90);
        lenient().when(cloudTimeoutProperties.getMaxDurationSeconds()).thenReturn(600);
        lenient().when(cloudRouteSwitchService.remotePropertyEnabled()).thenReturn(true);
        // 默认 SysConfig 兜底返回 null（各测试按需覆盖）
        lenient().when(sysConfigRouteProvider.load(any(), any(), any())).thenReturn(null);

        cloudAgentService = new CloudAgentService(
                sysConfigRouteProvider, cloudRouteSwitchService, assistantInstanceInfoService,
                cloudProtocolClient, webHookExecutor, cloudTimeoutProperties,
                redisMessageBroker, objectMapper, cloudAuthService, abortExecutor, "gw-local");

        // 让 abortExecutor 同步执行提交的任务（便于测试验证）
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(abortExecutor).execute(any(Runnable.class));

        // 注入 mock httpClient（构造函数中内联创建了真实实例）
        org.springframework.test.util.ReflectionTestUtils.setField(
                cloudAgentService, "httpClient", httpClient);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private GatewayMessage buildInvoke(String action, String ak) {
        return buildInvoke(action, ak, "tool-session-001", "welink-session-001");
    }

    private GatewayMessage buildInvoke(String action, String ak, String toolSessionId, String welinkSessionId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("cloudRequest", objectMapper.createObjectNode().put("prompt", "hello"));
        if (toolSessionId != null) {
            payload.put("toolSessionId", toolSessionId);
        }

        return GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .ak(ak)
                .action(action)
                .userId("user-001")
                .welinkSessionId(welinkSessionId)
                .traceId("trace-001")
                .businessTag("biz-tag")
                .payload(payload)
                .build();
    }

    private CallbackConfig buildCfg(String channelType, String channelAddress, String authType, String appId) {
        CallbackConfig c = new CallbackConfig();
        c.setAk(TEST_AK);
        c.setScope("callback:weagent:chat");
        c.setChannelType(channelType);
        c.setChannelAddress(channelAddress);
        c.setAuthType(authType);
        c.setAppId(appId);
        return c;
    }

    private GatewayMessage buildRemoteInvoke(String action) {
        GatewayMessage invoke = buildInvoke(action, null);
        return invoke.toBuilder()
                .assistantAccount("bot-001")
                .build();
    }

    private AssistantInstanceInfo buildInstance(String type, String protocol, String url) {
        return buildInstance(type, protocol, url, "soa");
    }

    private AssistantInstanceInfo buildInstance(String type, String protocol, String url,
                                                String firstHeaderType, String... extraHeaderTypes) {
        AssistantInstanceInfo.RemoteProperty property = new AssistantInstanceInfo.RemoteProperty();
        property.setType(type);
        property.setCommProtocol(protocol);
        property.setUrl(url);
        property.setDataProtocol("wrong_data_protocol");

        java.util.List<AssistantInstanceInfo.RemoteHeader> headers = new java.util.ArrayList<>();
        headers.add(buildHeader(firstHeaderType));
        for (String extraHeaderType : extraHeaderTypes) {
            headers.add(buildHeader(extraHeaderType));
        }
        property.setHeaders(headers);

        AssistantInstanceInfo info = new AssistantInstanceInfo();
        info.setPartnerAccount("bot-001");
        info.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_ASSISTANT_SQUARE);
        info.setBizRobotTag("assistant_square");
        info.setRemoteProperty(java.util.List.of(property));
        return info;
    }

    private AssistantInstanceInfo.RemoteHeader buildHeader(String type) {
        AssistantInstanceInfo.RemoteHeader header = new AssistantInstanceInfo.RemoteHeader();
        header.setType(type);
        header.setCustomKey("X-Bot-Token");
        header.setCustomValue("secret-token");
        return header;
    }

    // ---------------------------------------------------------------------
    // abort_session cancels current cloud stream
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("abort_session 取消云端流")
    class AbortSessionTests {

        @Test
        @DisplayName("abort_session cancels the active streaming connection by toolSessionId")
        void handleInvoke_abortSessionCancelsActiveStreamingConnection() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();
                assertNotNull(handle);
                assertFalse(handle.isCancelled());

                cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);

                assertTrue(handle.isCancelled());
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("cancelled stream suppresses late connection errors")
        void handleInvoke_cancelledStreamSuppressesLateErrors() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();
                Consumer<Throwable> onErrorCallback = invocation.getArgument(4);

                cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);
                assertTrue(handle.isCancelled());

                onErrorCallback.accept(new RuntimeException("stream closed"));
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session without active stream is a no-op")
        void handleInvoke_abortSessionWithoutActiveStreamIsNoOp() {
            cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);

            verifyNoInteractions(assistantInstanceInfoService,
                    cloudProtocolClient, webHookExecutor, onRelay);
        }

        @Test
        @DisplayName("abort_session action is normalized before action validation")
        void handleInvoke_abortSessionActionIsNormalized() {
            cloudAgentService.handleInvoke(buildInvoke(" abort_session ", TEST_AK), onRelay);

            verifyNoInteractions(assistantInstanceInfoService,
                    cloudProtocolClient, webHookExecutor, onRelay);
        }

        @Test
        @DisplayName("streaming chat registers and removes the cloud stream owner route")
        void handleInvoke_streamingChatRegistersCloudStreamOwnerRoute() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(redisMessageBroker).setCloudStreamRoute(
                    "tool-session-001", "gw-local", Duration.ofSeconds(660));
            verify(redisMessageBroker).removeCloudStreamRoute("tool-session-001", "gw-local");
        }

        @Test
        @DisplayName("overlapping chats in the same welink session do not cancel each other")
        void handleInvoke_overlappingChatsWithSameWelinkSessionDoNotCancelFirstStream() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            GatewayMessage first = buildInvoke("chat", TEST_AK, "tool-session-001", "welink-session-001");
            GatewayMessage second = buildInvoke("chat", TEST_AK, "tool-session-002", "welink-session-001");
            CloudConnectionHandle[] firstHandle = new CloudConnectionHandle[1];

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();
                assertNotNull(handle);

                if (firstHandle[0] == null) {
                    firstHandle[0] = handle;
                    cloudAgentService.handleInvoke(second, onRelay);
                    assertFalse(firstHandle[0].isCancelled(),
                            "second chat with a different toolSessionId must not cancel the first stream");
                } else {
                    assertFalse(handle.isCancelled());
                }
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(first, onRelay);

            verify(cloudProtocolClient, times(2)).connect(eq("sse"), any(), any(), any(), any());
            verify(redisMessageBroker).setCloudStreamRoute(
                    "tool-session-001", "gw-local", Duration.ofSeconds(660));
            verify(redisMessageBroker).setCloudStreamRoute(
                    "tool-session-002", "gw-local", Duration.ofSeconds(660));
            verify(redisMessageBroker).removeCloudStreamRoute("tool-session-001", "gw-local");
            verify(redisMessageBroker).removeCloudStreamRoute("tool-session-002", "gw-local");
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("overlapping chats with the same toolSessionId do not cancel each other")
        void handleInvoke_overlappingChatsWithSameToolSessionDoNotCancelEachOther() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            GatewayMessage first = buildInvoke("chat", TEST_AK, "tool-session-001", "welink-session-001");
            GatewayMessage second = buildInvoke("chat", TEST_AK, "tool-session-001", "welink-session-001");
            List<CloudConnectionHandle> handles = new ArrayList<>();

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();
                assertNotNull(handle);
                handles.add(handle);

                if (handles.size() == 1) {
                    cloudAgentService.handleInvoke(second, onRelay);
                    assertFalse(handles.get(0).isCancelled(),
                            "second chat with the same toolSessionId must not cancel the first stream");
                } else {
                    assertFalse(handles.get(0).isCancelled());
                    assertFalse(handles.get(1).isCancelled());
                }
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(first, onRelay);

            verify(cloudProtocolClient, times(2)).connect(eq("sse"), any(), any(), any(), any());
            verify(redisMessageBroker, times(2)).setCloudStreamRoute(
                    "tool-session-001", "gw-local", Duration.ofSeconds(660));
            verify(redisMessageBroker, times(1)).removeCloudStreamRoute("tool-session-001", "gw-local");
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session cancels all local active streams for the same session")
        void handleInvoke_abortSessionCancelsAllLocalActiveStreamsForSession() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            GatewayMessage first = buildInvoke("chat", TEST_AK, "tool-session-001", "welink-session-001");
            GatewayMessage second = buildInvoke("chat", TEST_AK, "tool-session-001", "welink-session-001");
            List<CloudConnectionHandle> handles = new ArrayList<>();

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();
                assertNotNull(handle);
                handles.add(handle);

                if (handles.size() == 1) {
                    cloudAgentService.handleInvoke(second, onRelay);
                } else {
                    cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);
                    assertEquals(2, handles.size());
                    assertTrue(handles.get(0).isCancelled());
                    assertTrue(handles.get(1).isCancelled());
                }
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(first, onRelay);

            verify(cloudProtocolClient, times(2)).connect(eq("sse"), any(), any(), any(), any());
            verify(redisMessageBroker, atLeastOnce()).removeCloudStreamRoute("tool-session-001", "gw-local");
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session without local stream relays to all remote owner gateways")
        void handleInvoke_abortSessionNoLocalActiveStreamRelaysToAllOwnerGateways() throws Exception {
            when(redisMessageBroker.getCloudStreamOwners("tool-session-001"))
                    .thenReturn(Set.of("gw-owner-a", "gw-local", "gw-owner-b"));
            ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> relayCaptor = ArgumentCaptor.forClass(String.class);

            cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);

            verify(redisMessageBroker, times(2)).publishToGwRelay(ownerCaptor.capture(), relayCaptor.capture());
            assertEquals(Set.of("gw-owner-a", "gw-owner-b"), Set.copyOf(ownerCaptor.getAllValues()));
            RelayMessage relay = objectMapper.readValue(relayCaptor.getAllValues().get(0), RelayMessage.class);
            assertEquals(RelayMessage.RELAY_TO_CLOUD_CONTROL, relay.relayType());
            GatewayMessage relayedMessage = objectMapper.readValue(relay.originalMessage(), GatewayMessage.class);
            assertEquals("abort_session", relayedMessage.getAction());
            assertEquals("tool-session-001", relayedMessage.getPayload().path("toolSessionId").asText());
            assertTrue(relayedMessage.getPayload().path("_cloudControlRelayed").asBoolean());
            verifyNoInteractions(assistantInstanceInfoService,
                    cloudProtocolClient, webHookExecutor, onRelay);
        }

        @Test
        @DisplayName("relayed abort_session cancels locally only and does not relay again")
        void handleInvoke_relayedAbortSessionDoesNotRelayAgain() {
            GatewayMessage abort = buildInvoke("abort_session", TEST_AK);
            ((ObjectNode) abort.getPayload()).put("_cloudControlRelayed", true);

            cloudAgentService.handleInvoke(abort, onRelay);

            verify(redisMessageBroker, never()).publishToGwRelay(anyString(), anyString());
            verifyNoInteractions(assistantInstanceInfoService,
                    cloudProtocolClient, webHookExecutor, onRelay);
        }

        @Test
        @DisplayName("relayed abort_session 带 assistantAccount 仍跳过第三方 stop，避免重复调用")
        void handleInvoke_relayedAbortSessionWithAssistantAccount_skipsThirdPartyStop() throws Exception {
            // 第三方 abort route 已配置（remoteProperty type=abort），但 relayed abort 应跳过
            lenient().when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));
            // relayed abort：带 assistantAccount 且 _cloudControlRelayed=true
            GatewayMessage abort = buildRemoteInvoke("abort_session");
            ((ObjectNode) abort.getPayload()).put("_cloudControlRelayed", true);

            cloudAgentService.handleInvoke(abort, onRelay);

            // 即使配置了 abort route，relayed abort 也不应发起第三方 HTTP stop
            verifyNoInteractions(httpClient);
            // 不应二次 relay
            verify(redisMessageBroker, never()).publishToGwRelay(anyString(), anyString());
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session without local active stream relays to the owning GW")
        void handleInvoke_abortSessionNoLocalActiveStreamRelaysToOwnerGateway() throws Exception {
            when(redisMessageBroker.getCloudStreamRoute("tool-session-001")).thenReturn("gw-owner");
            ArgumentCaptor<String> relayCaptor = ArgumentCaptor.forClass(String.class);

            cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);

            verify(redisMessageBroker).publishToGwRelay(eq("gw-owner"), relayCaptor.capture());
            RelayMessage relay = objectMapper.readValue(relayCaptor.getValue(), RelayMessage.class);
            assertEquals(RelayMessage.RELAY_TO_CLOUD_CONTROL, relay.relayType());
            GatewayMessage relayedMessage = objectMapper.readValue(relay.originalMessage(), GatewayMessage.class);
            assertEquals("abort_session", relayedMessage.getAction());
            assertEquals("tool-session-001", relayedMessage.getPayload().path("toolSessionId").asText());
            verifyNoInteractions(assistantInstanceInfoService,
                    cloudProtocolClient, webHookExecutor, onRelay);
        }

        @Test
        @DisplayName("abilityType(abort_session) returns abort")
        void abilityType_abortSession_returnsAbort() throws Exception {
            // abilityType is package-private static, tested via resolveRemoteRoute behavior
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

            GatewayMessage invoke = buildRemoteInvoke("abort_session");
            cloudAgentService.handleInvoke(invoke, onRelay);

            // resolveRemoteRoute matched type=abort -> route found -> sendAbortRequest called
            // (verify via httpClient.send was invoked)
            verify(httpClient, atLeastOnce()).send(any(HttpRequest.class),
                    eq(HttpResponse.BodyHandlers.ofString()));
        }

        @Test
        @DisplayName("abort_session without abort remoteProperty skips third-party call")
        void handleInvoke_abortSessionWithoutAbortRemoteProperty_skipsThirdPartyCall() {
            // remoteProperty has chat but NOT abort
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("chat", "sse", "https://remote.example.com/chat"));

            GatewayMessage invoke = buildRemoteInvoke("abort_session");
            cloudAgentService.handleInvoke(invoke, onRelay);

            // No HTTP call made
            verifyNoInteractions(httpClient);
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session builds correct request body matching question interface")
        void handleInvoke_abortSession_buildsCorrectRequestBody() throws Exception {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

            @SuppressWarnings("unchecked")
            HttpResponse<String> mockResp = mock(HttpResponse.class);
            when(mockResp.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResp);

            GatewayMessage invoke = buildRemoteInvoke("abort_session");
            cloudAgentService.handleInvoke(invoke, onRelay);

            ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(reqCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));

            HttpRequest req = reqCaptor.getValue();
            assertEquals("POST", req.method());
            assertTrue(req.uri().toString().contains("https://remote.example.com/stop"));

            // Parse body via bodyPublisher
            String body = req.bodyPublisher()
                    .map(p -> {
                        try {
                            var baos = new java.io.ByteArrayOutputStream();
                            var channel = java.nio.channels.Channels.newChannel(baos);
                            var subscriber = new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                                private java.util.concurrent.Flow.Subscription subscription;
                                @Override
                                public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                    this.subscription = s;
                                    s.request(Long.MAX_VALUE);
                                }
                                @Override
                                public void onNext(java.nio.ByteBuffer buf) {
                                    try { channel.write(buf); } catch (Exception e) { throw new RuntimeException(e); }
                                }
                                @Override
                                public void onError(Throwable t) {}
                                @Override
                                public void onComplete() {
                                    try { channel.close(); } catch (Exception e) { throw new RuntimeException(e); }
                                }
                            };
                            p.subscribe(subscriber);
                            return baos.toString();
                        } catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .orElse("{}");
            ObjectNode bodyJson = (ObjectNode) objectMapper.readTree(body);

            assertEquals("abort", bodyJson.path("type").asText());
            assertEquals("tool-session-001", bodyJson.path("topicId").asText());
            assertEquals("bot-001", bodyJson.path("assistantAccount").asText());
            assertEquals("user-001", bodyJson.path("sendUserAccount").asText());
            assertEquals("zh", bodyJson.path("clientLang").asText());
            assertTrue(bodyJson.has("extParameters"));
            assertTrue(bodyJson.path("extParameters").has("businessExtParam"));
            assertTrue(bodyJson.path("extParameters").has("platformExtParam"));
        }

        @Test
        @DisplayName("abort_session third-party returns 500 does not affect local cancel")
        void handleInvoke_abortSession_thirdParty500_doesNotAffectLocalCancel() throws Exception {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

            @SuppressWarnings("unchecked")
            HttpResponse<String> mockResp = mock(HttpResponse.class);
            when(mockResp.statusCode()).thenReturn(500);
            when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResp);

            // Also set up an active streaming connection to verify it gets cancelled
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();

                // Send abort_session (with assistantAccount so remoteProperty is checked)
                GatewayMessage abort = buildRemoteInvoke("abort_session");
                cloudAgentService.handleInvoke(abort, onRelay);

                // Local stream should still be cancelled despite third-party 500
                assertTrue(handle.isCancelled());
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            // Verify HTTP was attempted
            verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
            // No tool_error relayed
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session third-party call is async and does not block local cancel")
        void handleInvoke_abortSession_thirdPartyCallIsAsync() throws Exception {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

            @SuppressWarnings("unchecked")
            HttpResponse<String> mockResp = mock(HttpResponse.class);
            when(mockResp.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResp);

            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();

                GatewayMessage abort = buildRemoteInvoke("abort_session");
                cloudAgentService.handleInvoke(abort, onRelay);

                // After handleInvoke returns, both cancel AND HTTP should have happened
                assertTrue(handle.isCancelled());
                verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session third-party HTTP exception does not affect local cancel")
        void handleInvoke_abortSession_httpException_doesNotAffectLocalCancel() throws Exception {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("abort", "http", "https://remote.example.com/stop"));

            when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenThrow(new java.io.IOException("Connection refused"));

            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();

                GatewayMessage abort = buildRemoteInvoke("abort_session");
                cloudAgentService.handleInvoke(abort, onRelay);

                // Local stream still cancelled despite HTTP exception
                assertTrue(handle.isCancelled());
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session without assistantAccount skips third-party call")
        void handleInvoke_abortSession_withoutAssistantAccount_skipsThirdPartyCall() {
            // buildInvoke (not buildRemoteInvoke) has no assistantAccount
            cloudAgentService.handleInvoke(buildInvoke("abort_session", TEST_AK), onRelay);

            verifyNoInteractions(httpClient, assistantInstanceInfoService);
        }

        @Test
        @DisplayName("abort_session falls back to SysConfig when remoteProperty has no abort")
        void handleInvoke_abortSession_fallsBackToSysConfig() throws Exception {
            // remoteProperty has chat but NOT abort
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("chat", "sse", "https://remote.example.com/chat"));
            // SysConfig has abort fallback
            CallbackConfig abortCfg = buildCfg("webhook", "https://sysconfig.example.com/stop", "soa", null);
            when(sysConfigRouteProvider.load(null, "callback:weagent:abort", "biz-tag"))
                    .thenReturn(abortCfg);

            @SuppressWarnings("unchecked")
            HttpResponse<String> mockResp = mock(HttpResponse.class);
            when(mockResp.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                    .thenReturn(mockResp);

            GatewayMessage invoke = buildRemoteInvoke("abort_session");
            cloudAgentService.handleInvoke(invoke, onRelay);

            // Verify HTTP was sent to SysConfig URL
            ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(reqCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
            assertTrue(reqCaptor.getValue().uri().toString().contains("https://sysconfig.example.com/stop"));
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session route channelType=sse 跳过第三方 POST，仅本地 cancel")
        void handleInvoke_abortSession_nonWebhookChannel_skipsThirdPartyPost() throws Exception {
            // remoteProperty type=abort 但 commProtocol=sse → channelType=sse，不是 webhook/http
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("abort", "sse", "https://remote.example.com/stop"));
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();

                GatewayMessage abort = buildRemoteInvoke("abort_session");
                cloudAgentService.handleInvoke(abort, onRelay);

                // 本地 cancel 仍执行
                assertTrue(handle.isCancelled());
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            // 第三方 HTTP POST 未发起（避免向 SSE 地址发 POST）
            verifyNoInteractions(httpClient);
            verifyNoInteractions(onRelay);
        }

        @Test
        @DisplayName("abort_session executor 拒绝任务时本地 cancel 仍执行，不抛异常")
        void handleInvoke_abortSession_executorRejected_localCancelStillRuns() throws Exception {
            // 模拟线程池队列满，execute 同步抛 RejectedExecutionException
            // （abort route 配置无关紧要：任务被拒绝后 lambda 体不会执行）
            doThrow(new java.util.concurrent.RejectedExecutionException("queue full"))
                    .when(abortExecutor).execute(any(Runnable.class));
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionHandle handle = context.getConnectionHandle();

                GatewayMessage abort = buildRemoteInvoke("abort_session");
                // 不应抛异常
                cloudAgentService.handleInvoke(abort, onRelay);

                // 本地 cancel 仍执行
                assertTrue(handle.isCancelled());
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            // 任务被拒绝，HTTP 未发起
            verifyNoInteractions(httpClient);
            verifyNoInteractions(onRelay);
        }
    }

    // ---------------------------------------------------------------------
    // action → scope 路由
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Action 路由分叉")
    class ActionRoutingTests {

        @Test
        @DisplayName("chat action + assistantAccount → 优先使用 instance remoteProperty，不触发 SysConfig fallback")
        void handleInvoke_chatAction_usesInstanceRemoteProperty() {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("chat", "sse", "https://remote.example.com/chat"));

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            CloudConnectionContext ctx = contextCaptor.getValue();
            assertEquals("https://remote.example.com/chat", ctx.getChannelAddress());
            assertEquals("assistant_square", ctx.getCloudProfile());
            assertEquals("soa", ctx.getAuthType());
            verifyNoInteractions(sysConfigRouteProvider, webHookExecutor);
        }

        @Test
        @DisplayName("remoteProperty headers 只取第一个 header.type 作为 authType")
        void handleInvoke_remotePropertyUsesFirstHeaderTypeOnly() {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("chat", "sse", "https://remote.example.com/chat", "apig", "soa"));

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            assertEquals("apig", contextCaptor.getValue().getAuthType());
            verifyNoInteractions(sysConfigRouteProvider, webHookExecutor);
        }

        @Test
        @DisplayName("remoteProperty header.type=integration 映射到 integration_token authType")
        void handleInvoke_remotePropertyIntegrationHeader_mapsToIntegrationToken() {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("chat", "sse", "https://remote.example.com/chat", "integration"));

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            assertEquals("integration_token", contextCaptor.getValue().getAuthType());
            verifyNoInteractions(sysConfigRouteProvider, webHookExecutor);
        }

        @Test
        @DisplayName("remoteType=2 overrides bizRobotTag for cloudProfile")
        void handleInvoke_remoteTypeDefaultProtocol_setsDefaultCloudProfile() {
            AssistantInstanceInfo info = buildInstance("chat", "sse", "https://remote.example.com/chat");
            info.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_DEFAULT);
            info.setBizRobotTag("assistant_square");
            when(assistantInstanceInfoService.getInstanceInfo("bot-001")).thenReturn(info);

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            assertEquals("default", contextCaptor.getValue().getCloudProfile());
            verifyNoInteractions(sysConfigRouteProvider, webHookExecutor);
        }

        @Test
        @DisplayName("remoteType=0 ignores legacy remoteProperty and falls back to SysConfig")
        void handleInvoke_localRemoteTypeIgnoresRemoteProperty() {
            AssistantInstanceInfo info = buildInstance("chat", "sse", "https://remote.example.com/chat");
            info.setRemoteType(AssistantInstanceInfo.REMOTE_TYPE_LOCAL);
            when(assistantInstanceInfoService.getInstanceInfo("bot-001")).thenReturn(info);
            when(sysConfigRouteProvider.load(null, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://sysconfig.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            assertEquals("https://sysconfig.example.com/chat", contextCaptor.getValue().getChannelAddress());
            verifyNoInteractions(webHookExecutor);
        }

        @Test
        @DisplayName("remote_property_enabled=false skips remoteProperty and routes by SysConfig")
        void handleInvoke_remotePropertyDisabled_routesDirectlyToSysConfig() {
            when(cloudRouteSwitchService.remotePropertyEnabled()).thenReturn(false);
            when(sysConfigRouteProvider.load(null, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://sysconfig.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verifyNoInteractions(assistantInstanceInfoService, webHookExecutor);
            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            assertEquals("https://sysconfig.example.com/chat", contextCaptor.getValue().getChannelAddress());
        }

        @Test
        @DisplayName("remoteProperty first matching entry invalid -> continue to next valid entry")
        void handleInvoke_remotePropertySkipsInvalidMatchingProperty() {
            AssistantInstanceInfo.RemoteProperty invalid = new AssistantInstanceInfo.RemoteProperty();
            invalid.setType("chat");
            invalid.setCommProtocol("sse");

            AssistantInstanceInfo.RemoteProperty valid = new AssistantInstanceInfo.RemoteProperty();
            valid.setType("chat");
            valid.setCommProtocol("sse");
            valid.setUrl("https://remote.example.com/chat-valid");

            AssistantInstanceInfo info = new AssistantInstanceInfo();
            info.setPartnerAccount("bot-001");
            info.setIsRemote(true);
            info.setRemoteProperty(java.util.List.of(invalid, valid));
            when(assistantInstanceInfoService.getInstanceInfo("bot-001")).thenReturn(info);

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            assertEquals("https://remote.example.com/chat-valid", contextCaptor.getValue().getChannelAddress());
            verifyNoInteractions(sysConfigRouteProvider, webHookExecutor);
        }

        @Test
        @DisplayName("question_reply action → 选择 type=question 的 remoteProperty 并走 WebHook")
        void handleInvoke_questionReplyAction_usesQuestionRemoteProperty() {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("question", "http", "https://remote.example.com/question"));

            GatewayMessage invoke = buildRemoteInvoke("question_reply");
            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(webHookExecutor).execute(contextCaptor.capture(), eq(onRelay), eq(invoke), eq("tool-session-001"));
            assertEquals("webhook", contextCaptor.getValue().getChannelType());
            assertEquals("https://remote.example.com/question", contextCaptor.getValue().getChannelAddress());
            verifyNoInteractions(sysConfigRouteProvider, cloudProtocolClient);
        }

        @Test
        @DisplayName("assistantAccount 未命中 remoteProperty → 按 businessTag 回退 SysConfig")
        void handleInvoke_remoteAssistantWithoutMatchingRemoteProperty_fallsBackToSysConfig() {
            when(assistantInstanceInfoService.getInstanceInfo("bot-001"))
                    .thenReturn(buildInstance("question", "http", "https://remote.example.com/question"));
            when(sysConfigRouteProvider.load(null, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://sysconfig.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildRemoteInvoke("chat"), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any(), any());
            assertEquals("https://sysconfig.example.com/chat", contextCaptor.getValue().getChannelAddress());
            verifyNoInteractions(webHookExecutor);
        }

        @Test
        @DisplayName("chat action → 走 CloudProtocolClient (SSE)")
        void handleInvoke_chatAction_routesToSseProtocol() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());
            verifyNoInteractions(webHookExecutor);
        }

        @Test
        @DisplayName("chat action → 走 CloudProtocolClient (WebSocket)")
        void handleInvoke_chatAction_routesToWebSocketProtocol() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("websocket", "wss://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(eq("websocket"), any(), any(), any(), any());
            verifyNoInteractions(webHookExecutor);
        }

        @Test
        @DisplayName("question_reply action → 走 WebHookExecutor")
        void handleInvoke_questionReplyAction_routesToWebHook() {
            when(sysConfigRouteProvider.load(TEST_AK, QR_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("webhook", "https://cloud.example.com/qr", "soa", null));

            GatewayMessage invoke = buildInvoke("question_reply", TEST_AK);
            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(webHookExecutor).execute(any(CloudConnectionContext.class),
                    eq(onRelay), eq(invoke), eq("tool-session-001"));
            verifyNoInteractions(cloudProtocolClient);
        }

        @Test
        @DisplayName("permission_reply action → 走 WebHookExecutor")
        void handleInvoke_permissionReplyAction_routesToWebHook() {
            when(sysConfigRouteProvider.load(TEST_AK, PR_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("webhook", "https://cloud.example.com/pr", "soa", null));

            GatewayMessage invoke = buildInvoke("permission_reply", TEST_AK);
            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(webHookExecutor).execute(any(CloudConnectionContext.class),
                    eq(onRelay), eq(invoke), eq("tool-session-001"));
            verifyNoInteractions(cloudProtocolClient);
        }

        @Test
        @DisplayName("未知 action → 回流 tool_error")
        void handleInvoke_unknownAction_emitsToolError() {
            cloudAgentService.handleInvoke(buildInvoke("unknown_action", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Unknown action"),
                    "Expected 'Unknown action' in error: " + err.getError());
            assertNull(err.getReason(),
                    "Unknown action should not carry a structured reason; got: " + err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }
    }

    // ---------------------------------------------------------------------
    // channelType vs action 校验
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("ChannelType 校验")
    class ChannelTypeValidationTests {

        @Test
        @DisplayName("chat 收到 webhook channel → tool_error")
        void handleInvoke_chatWithWebhookChannel_emitsToolError() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("webhook", "https://x", "soa", null));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Invalid channel type for chat"),
                    "Expected 'Invalid channel type for chat' in error: " + err.getError());
            assertNull(err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }

        @Test
        @DisplayName("question_reply 收到 sse channel → tool_error")
        void handleInvoke_questionReplyWithSseChannel_emitsToolError() {
            when(sysConfigRouteProvider.load(TEST_AK, QR_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://x", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("question_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Invalid channel type for reply"),
                    "Expected 'Invalid channel type for reply' in error: " + err.getError());
            assertNull(err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }

        @Test
        @DisplayName("permission_reply 收到 websocket channel → tool_error")
        void handleInvoke_permissionReplyWithWebSocketChannel_emitsToolError() {
            when(sysConfigRouteProvider.load(TEST_AK, PR_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("websocket", "wss://x", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("permission_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("Invalid channel type for reply"));
            assertNull(err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }
    }

    // ---------------------------------------------------------------------
    // SysConfig route missing
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("配置缺失")
    class MissingConfigTests {

        @Test
        @DisplayName("chat 配置缺失 → tool_error，错误消息保留 'Cloud route info not found'")
        void handleInvoke_chatConfigMissing_emitsLegacyErrorMessage() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag")).thenReturn(null);

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient, never()).connect(any(), any(), any(), any(), any());
            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertEquals(TEST_AK, err.getAk());
            assertEquals("user-001", err.getUserId());
            assertEquals("welink-session-001", err.getWelinkSessionId());
            assertEquals("tool-session-001", err.getToolSessionId());
            assertNotNull(err.getError());
            assertTrue(err.getError().contains("Cloud route SysConfig not found for businessTag: biz-tag"),
                    "Expected SysConfig route error message, got: " + err.getError());
            assertEquals("callback_config_missing", err.getReason(),
                    "missing route should carry structured reason for SS routing");
        }

        @Test
        @DisplayName("question_reply SysConfig 配置缺失 → tool_error")
        void handleInvoke_questionReplyReturnsNullConfig_emitsToolError() {
            when(sysConfigRouteProvider.load(TEST_AK, QR_SCOPE, "biz-tag")).thenReturn(null);

            cloudAgentService.handleInvoke(buildInvoke("question_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("question_reply"));
            assertTrue(err.getError().contains("Cloud route SysConfig not found for businessTag: biz-tag"),
                    "Expected SysConfig route error message, got: " + err.getError());
            assertEquals("callback_config_missing", err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }

        @Test
        @DisplayName("permission_reply SysConfig 配置缺失 → tool_error")
        void handleInvoke_permissionReplyReturnsNullConfig_emitsToolError() {
            when(sysConfigRouteProvider.load(TEST_AK, PR_SCOPE, "biz-tag")).thenReturn(null);

            cloudAgentService.handleInvoke(buildInvoke("permission_reply", TEST_AK), onRelay);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("permission_reply"));
            assertTrue(err.getError().contains("Cloud route SysConfig not found for businessTag: biz-tag"));
            assertEquals("callback_config_missing", err.getReason());
            verifyNoInteractions(cloudProtocolClient, webHookExecutor);
        }
    }

    // ---------------------------------------------------------------------
    // chat 路径正常流程（保留原有 fallback 与 errorSent 行为）
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("chat 路径正常流程")
    class ChatHappyPathTests {

        @Test
        @DisplayName("构建正确的 CloudConnectionContext (含 channelType / scope)")
        void shouldBuildCorrectConnectionContext() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app_36209"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    any(),
                    any()
            );

            CloudConnectionContext ctx = contextCaptor.getValue();
            assertEquals("https://cloud.example.com/chat", ctx.getChannelAddress());
            assertEquals("sse", ctx.getChannelType());
            assertEquals(CHAT_SCOPE, ctx.getScope());
            assertEquals("app_36209", ctx.getAppId());
            assertEquals("soa", ctx.getAuthType());
            assertEquals("trace-001", ctx.getTraceId());
            assertNotNull(ctx.getCloudRequest());
        }

        @Test
        @DisplayName("云端事件注入路由上下文后通过 onRelay 转发")
        void shouldInjectRoutingContextAndRelayEvents() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    onEventCaptor.capture(),
                    any()
            );

            GatewayMessage cloudEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .event(objectMapper.createObjectNode().put("text", "response"))
                    .build();

            onEventCaptor.getValue().accept(cloudEvent);

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage relayed = messageCaptor.getValue();

            assertEquals(TEST_AK, relayed.getAk());
            assertEquals("user-001", relayed.getUserId());
            assertEquals("welink-session-001", relayed.getWelinkSessionId());
            assertEquals("trace-001", relayed.getTraceId());
            assertEquals("tool-session-001", relayed.getToolSessionId());
        }

        @Test
        @DisplayName("云端事件已有 toolSessionId 时不覆盖")
        void shouldNotOverrideExistingToolSessionId() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    onEventCaptor.capture(),
                    any()
            );

            GatewayMessage cloudEvent = GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .toolSessionId("cloud-tool-session-999")
                    .event(objectMapper.createObjectNode().put("text", "response"))
                    .build();

            onEventCaptor.getValue().accept(cloudEvent);

            verify(onRelay).accept(messageCaptor.capture());
            assertEquals("cloud-tool-session-999", messageCaptor.getValue().getToolSessionId());
        }
    }

    // ---------------------------------------------------------------------
    // chat 路径异常流程（保留原有 onError / 超时行为）
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("chat 路径异常流程")
    class ChatErrorTests {

        @Test
        @DisplayName("云端连接异常时 onError 通过 onRelay 构建 tool_error 转发")
        void shouldRelayToolErrorOnCloudConnectionFailure() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    any(),
                    onErrorCaptor.capture()
            );

            onErrorCaptor.getValue().accept(new RuntimeException("Connection timeout"));

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertEquals(TEST_AK, err.getAk());
            assertTrue(err.getError().contains("Connection timeout"));
            assertNull(err.getReason());
        }

        @Test
        @DisplayName("云端超时时通过 onRelay 返回包含超时信息的 tool_error")
        void shouldRelayToolErrorWithTimeoutInfoOnTimeout() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    any(),
                    onErrorCaptor.capture()
            );

            onErrorCaptor.getValue().accept(
                    new RuntimeException("Cloud agent error: idle_timeout (elapsed: 90s)"));

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("idle_timeout"));
            assertNull(err.getReason());
        }

        @Test
        @DisplayName("云端生命周期超时时取消 active stream handle 并只回流一次 tool_error")
        void shouldCancelActiveStreamHandleOnLifecycleTimeout() throws Exception {
            when(cloudTimeoutProperties.getFirstEventTimeoutSeconds()).thenReturn(1);
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));
            CountDownLatch timeoutRelayed = new CountDownLatch(1);
            CountDownLatch transportCancelled = new CountDownLatch(1);

            doAnswer(invocation -> {
                timeoutRelayed.countDown();
                return null;
            }).when(onRelay).accept(any(GatewayMessage.class));

            doAnswer(invocation -> {
                CloudConnectionContext context = invocation.getArgument(1);
                CloudConnectionLifecycle lifecycle = invocation.getArgument(2);
                Consumer<Throwable> onErrorCallback = invocation.getArgument(4);
                CloudConnectionHandle handle = context.getConnectionHandle();
                assertNotNull(handle);
                handle.onCancel(transportCancelled::countDown);

                lifecycle.onConnected();

                assertTrue(timeoutRelayed.await(3, TimeUnit.SECONDS),
                        "timeout should relay tool_error");
                assertTrue(transportCancelled.await(1, TimeUnit.SECONDS),
                        "timeout should cancel transport close action");
                assertTrue(handle.isCancelled(), "timeout should mark active handle cancelled");

                onErrorCallback.accept(new RuntimeException("stream closed after timeout"));
                return null;
            }).when(cloudProtocolClient).connect(eq("sse"), any(), any(), any(), any());

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(onRelay, times(1)).accept(messageCaptor.capture());
            GatewayMessage err = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, err.getType());
            assertTrue(err.getError().contains("first_event_timeout"));
            assertNull(err.getReason());
        }
    }

    // ---------------------------------------------------------------------
    // businessTag / legacy cloudProfile to SysConfig route provider
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("businessTag 透传到 SysConfig route provider")
    class CloudProfilePropagationTests {

        @Test
        @DisplayName("顶层 businessTag 优先于 payload cloudProfile 缺失")
        void handleInvoke_payloadWithoutCloudProfile_defaultsToDefault() {
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(buildInvoke("chat", TEST_AK), onRelay);

            verify(sysConfigRouteProvider).load(TEST_AK, CHAT_SCOPE, "biz-tag");
        }

        @Test
        @DisplayName("顶层 businessTag 优先于 payload cloudProfile=null")
        void handleInvoke_payloadCloudProfileNull_defaultsToDefault() {
            GatewayMessage invoke = buildInvoke("chat", TEST_AK);
            ((ObjectNode) invoke.getPayload()).putNull("cloudProfile");
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(sysConfigRouteProvider).load(TEST_AK, CHAT_SCOPE, "biz-tag");
        }

        @Test
        @DisplayName("顶层 businessTag 优先于 payload cloudProfile 空字符串")
        void handleInvoke_payloadCloudProfileBlank_defaultsToDefault() {
            GatewayMessage invoke = buildInvoke("chat", TEST_AK);
            ((ObjectNode) invoke.getPayload()).put("cloudProfile", "");
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "biz-tag"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/chat", "soa", "app-1"));

            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(sysConfigRouteProvider).load(TEST_AK, CHAT_SCOPE, "biz-tag");
        }

        @Test
        @DisplayName("旧 payload cloudProfile 在顶层 businessTag 缺失时兼容透传")
        void handleInvoke_payloadCloudProfileSpecific_propagatesValue() {
            GatewayMessage invoke = buildInvoke("chat", TEST_AK);
            ((ObjectNode) invoke.getPayload()).put("cloudProfile", "assistant_square");
            invoke = invoke.toBuilder().businessTag(null).build();
            when(sysConfigRouteProvider.load(TEST_AK, CHAT_SCOPE, "assistant_square"))
                    .thenReturn(buildCfg("sse", "https://cloud.example.com/as", "soa", null));

            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(sysConfigRouteProvider).load(TEST_AK, CHAT_SCOPE, "assistant_square");
        }

        @Test
        @DisplayName("旧 payload cloudProfile 在 webhook action 上同样兼容透传")
        void handleInvoke_payloadCloudProfileSpecific_propagatesOnWebhookActions() {
            GatewayMessage invoke = buildInvoke("question_reply", TEST_AK);
            ((ObjectNode) invoke.getPayload()).put("cloudProfile", "assistant_square");
            invoke = invoke.toBuilder().businessTag(null).build();
            when(sysConfigRouteProvider.load(TEST_AK, QR_SCOPE, "assistant_square"))
                    .thenReturn(buildCfg("webhook", "https://x/q", "soa", null));

            cloudAgentService.handleInvoke(invoke, onRelay);

            verify(sysConfigRouteProvider).load(TEST_AK, QR_SCOPE, "assistant_square");
        }
    }
}
