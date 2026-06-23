package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.logging.GatewayStreamEventLogHelper;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.AssistantInstanceInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import com.opencode.cui.gateway.service.cloud.CloudAuthService;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudConnectionHandle;
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import com.opencode.cui.gateway.service.cloud.CloudRemoteRequestLogHelper;
import com.opencode.cui.gateway.service.cloud.WebHookExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 云端 Agent 服务编排器。
 *
 * <p>负责编排云端 AI 调用的完整流程：
 * <ol>
 *   <li>按 {@code action} 映射到 callback {@code scope}</li>
 *   <li>按 assistantAccount 查询远端 remoteProperty，未命中则直接读取 SS SysConfig</li>
 *   <li>校验 {@code channelType} 与 {@code action} 匹配关系</li>
 *   <li>构建 {@link CloudConnectionContext}</li>
 *   <li>分叉到 {@link WebHookExecutor}（webhook）或 {@link CloudProtocolClient}（sse/websocket）</li>
 * </ol>
 * </p>
 *
 * <p>不再直接依赖 {@link SkillRelayService}，改由调用方传入 {@code onRelay} 回调，
 * 以打破循环依赖：SkillRelayService → BusinessInvokeRouteStrategy → CloudAgentService。</p>
 */
@Slf4j
@Service
public class CloudAgentService {

    private static final String ACTION_ABORT_SESSION = "abort_session";
    private static final String CLOUD_CONTROL_RELAYED_FLAG = "_cloudControlRelayed";

    /** action → callback scope 硬编码映射。 */
    private static final Map<String, String> ACTION_TO_SCOPE = Map.of(
            "chat",             "callback:weagent:chat",
            "question_reply",   "callback:weagent:question_reply",
            "permission_reply", "callback:weagent:permission_reply",
            "abort_session",    "callback:weagent:abort"
    );

    /** tool_error reason 枚举：让 SS 能精确区分失败类型，不再依赖 error 文案启发式。 */
    static final String REASON_CALLBACK_CONFIG_MISSING = "callback_config_missing";

    private final SysConfigFallbackProviderV2 sysConfigRouteProvider;
    private final CloudRouteSwitchService cloudRouteSwitchService;
    private final AssistantInstanceInfoService assistantInstanceInfoService;
    private final CloudProtocolClient cloudProtocolClient;
    private final WebHookExecutor webHookExecutor;
    private final CloudTimeoutProperties timeoutProperties;
    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final String gatewayInstanceId;
    private final ConcurrentHashMap<String, Set<ActiveCloudConnection>> activeStreamingConnections =
            new ConcurrentHashMap<>();

    private final HttpClient httpClient;
    private final Executor abortExecutor;
    private final CloudAuthService cloudAuthService;

    @Autowired
    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudRouteSwitchService cloudRouteSwitchService,
                             AssistantInstanceInfoService assistantInstanceInfoService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties,
                             RedisMessageBroker redisMessageBroker,
                             ObjectMapper objectMapper,
                             CloudAuthService cloudAuthService,
                             @Qualifier("cloudAbortExecutor") Executor abortExecutor,
                             @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId) {
        this.sysConfigRouteProvider = sysConfigRouteProvider;
        this.cloudRouteSwitchService = cloudRouteSwitchService;
        this.assistantInstanceInfoService = assistantInstanceInfoService;
        this.cloudProtocolClient = cloudProtocolClient;
        this.webHookExecutor = webHookExecutor;
        this.timeoutProperties = timeoutProperties;
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.cloudAuthService = cloudAuthService;
        this.abortExecutor = abortExecutor;
        this.gatewayInstanceId = gatewayInstanceId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudRouteSwitchService cloudRouteSwitchService,
                             AssistantInstanceInfoService assistantInstanceInfoService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this(sysConfigRouteProvider, cloudRouteSwitchService, assistantInstanceInfoService,
                cloudProtocolClient, webHookExecutor, timeoutProperties, null, new ObjectMapper(),
                null, null, "gateway-local");
    }

    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudRouteSwitchService cloudRouteSwitchService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this(sysConfigRouteProvider, cloudRouteSwitchService, null, cloudProtocolClient, webHookExecutor, timeoutProperties);
    }

    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this(sysConfigRouteProvider, null, null, cloudProtocolClient, webHookExecutor, timeoutProperties);
    }

    /**
     * 处理 invoke 消息，编排云端 AI 调用流程。
     *
     * @param invokeMessage invoke 消息
     * @param onRelay       回调：将需要转发的消息回传给调用方（通常是 SkillRelayService::relayToSkill）
     */
    public void handleInvoke(GatewayMessage invokeMessage, Consumer<GatewayMessage> onRelay) {
        String ak = invokeMessage.getAk();
        String action = normalizeAction(invokeMessage.getAction());
        if (action != null && !action.equals(invokeMessage.getAction())) {
            invokeMessage = invokeMessage.toBuilder().action(action).build();
        }
        JsonNode payload = invokeMessage.getPayload();
        JsonNode cloudRequest = payload == null ? null : payload.path("cloudRequest");
        String toolSessionId = firstNonBlank(
                invokeMessage.getToolSessionId(),
                textAt(payload, "toolSessionId"));
        String assistantAccount = firstNonBlank(
                invokeMessage.getAssistantAccount(),
                textAt(payload, "assistantAccount"),
                textAt(payload, "partnerAccount"));
        String businessTag = firstNonBlank(
                invokeMessage.getBusinessTag(),
                textAt(payload, "businessTag"),
                textAt(payload, "cloudProfile"));

        log.info("[CLOUD_AGENT] handleInvoke: ak={}, action={}, assistantAccount={}, businessTag={}, toolSessionId={}, traceId={}",
                ak, action, mask(assistantAccount), businessTag, toolSessionId, invokeMessage.getTraceId());

        if (ACTION_ABORT_SESSION.equals(action)) {
            // 主路径：优先取消本地活跃 SSE/WS 连接，保证用户侧即时终止不被第三方调用拖慢
            cancelStreamingConnection(invokeMessage, toolSessionId);
            // 旁路：异步通知第三方终止接口（fire-and-forget），不得阻塞或影响本地 cancel。
            // 仅入口 GW 执行第三方 stop：relayed abort（_cloudControlRelayed=true）来自其它 GW 的转发，
            // 若再次调用第三方 stop 会导致一次用户 abort 触发多次第三方终止请求。
            if (!isCloudControlRelayed(invokeMessage)) {
                invokeRemoteAbortIfConfigured(invokeMessage, toolSessionId, assistantAccount, businessTag);
            }
            return;
        }

        // 1. action → scope 映射
        String scope = ACTION_TO_SCOPE.get(action);
        if (scope == null) {
            log.warn("[CLOUD_AGENT] unknown action: ak={}, action={}", ak, action);
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException("Unknown action: " + action), null));
            return;
        }

        RemoteRoute remoteRoute = null;
        if (assistantAccount != null && !assistantAccount.isBlank()) {
            if (remotePropertyEnabled()) {
                remoteRoute = resolveRemoteRoute(assistantAccount, action, businessTag);
            } else {
                log.info("[CLOUD_AGENT] remoteProperty lookup disabled by SysConfig: assistantAccount={}, businessTag={}, action={}",
                        mask(assistantAccount), businessTag, action);
            }
        }
        if (remoteRoute != null) {
            invokeRemoteRoute(invokeMessage, onRelay, cloudRequest, toolSessionId, scope, remoteRoute);
            return;
        }

        CallbackConfig cfg = sysConfigRouteProvider.load(ak, scope, businessTag);
        if (cfg == null) {
            String reason = "Cloud route SysConfig not found for businessTag: " + businessTag
                    + ", action: " + action;
            log.warn("[CLOUD_AGENT] sysconfig route missing: ak={}, scope={}, action={}, assistantAccount={}, businessTag={}",
                    ak, scope, action, mask(assistantAccount), businessTag);
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException(reason), REASON_CALLBACK_CONFIG_MISSING));
            return;
        }

        // 4. channelType vs action 校验：
        //    - chat 必须 sse/websocket
        //    - question_reply / permission_reply 必须 webhook
        boolean expectsWebhook = !"chat".equals(action);
        boolean isWebhook = "webhook".equals(cfg.getChannelType());
        if (expectsWebhook != isWebhook) {
            String msg = "chat".equals(action)
                    ? "Invalid channel type for chat: " + cfg.getChannelType()
                    : "Invalid channel type for reply: " + cfg.getChannelType();
            log.warn("[CLOUD_AGENT] channel type mismatch: ak={}, action={}, channelType={}",
                    ak, action, cfg.getChannelType());
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId, new RuntimeException(msg), null));
            return;
        }

        // 5. 构建连接上下文
        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress(cfg.getChannelAddress())
                .channelType(cfg.getChannelType())
                .scope(scope)
                .appId(cfg.getAppId())
                .authType(cfg.getAuthType())
                .cloudRequest(cloudRequest)
                .traceId(invokeMessage.getTraceId())
                .cloudProfile(businessTag)
                .build();

        // 6. 分叉
        if (isWebhook) {
            webHookExecutor.execute(context, onRelay, invokeMessage, toolSessionId);
            return;
        }

        // 7. chat：SSE / WebSocket 走原 CloudProtocolClient 逻辑（保留 lifecycle / fallback messageId / fallback partId）
        invokeStreaming(invokeMessage, onRelay, context, cfg.getChannelType(), ak, toolSessionId);
    }

    private void invokeRemoteRoute(GatewayMessage invokeMessage,
                                   Consumer<GatewayMessage> onRelay,
                                   JsonNode cloudRequest,
                                   String toolSessionId,
                                   String scope,
                                   RemoteRoute remoteRoute) {
        boolean expectsWebhook = !"chat".equals(invokeMessage.getAction());
        boolean isWebhook = "webhook".equals(remoteRoute.channelType());
        if (expectsWebhook != isWebhook) {
            String msg = "chat".equals(invokeMessage.getAction())
                    ? "Invalid channel type for chat: " + remoteRoute.channelType()
                    : "Invalid channel type for reply: " + remoteRoute.channelType();
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId, new RuntimeException(msg), null));
            return;
        }

        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress(remoteRoute.channelAddress())
                .channelType(remoteRoute.channelType())
                .scope(scope)
                .appId(remoteRoute.appId())
                .authType(remoteRoute.authType())
                .cloudRequest(cloudRequest)
                .traceId(invokeMessage.getTraceId())
                .cloudProfile(remoteRoute.cloudProfile())
                .build();

        if (isWebhook) {
            webHookExecutor.execute(context, onRelay, invokeMessage, toolSessionId);
            return;
        }
        invokeStreaming(invokeMessage, onRelay, context, remoteRoute.channelType(),
                invokeMessage.getAk(), toolSessionId);
    }

    private RemoteRoute resolveRemoteRoute(String assistantAccount, String action, String fallbackBusinessTag) {
        if (assistantInstanceInfoService == null
                || assistantAccount == null || assistantAccount.isBlank()) {
            return null;
        }
        AssistantInstanceInfo info = assistantInstanceInfoService.getInstanceInfo(assistantAccount);
        if (info == null || !info.remoteAssistant()
                || info.getRemoteProperty() == null || info.getRemoteProperty().isEmpty()) {
            return null;
        }
        String abilityType = abilityType(action);
        for (AssistantInstanceInfo.RemoteProperty property : info.getRemoteProperty()) {
            if (property == null || !abilityType.equalsIgnoreCase(blankToEmpty(property.getType()))) {
                continue;
            }
            String channelAddress = firstNonBlank(property.getUrl());
            String channelType = mapChannelType(property.getCommProtocol());
            if (channelAddress == null || channelType == null) {
                continue;
            }
            String authType = resolveAuthType(property.getHeaders());
            return new RemoteRoute(channelAddress, channelType, null,
                    firstNonBlank(info.protocolProfile(), info.getBizRobotTag(), fallbackBusinessTag),
                    authType);
        }
        return null;
    }

    private boolean remotePropertyEnabled() {
        return cloudRouteSwitchService == null || cloudRouteSwitchService.remotePropertyEnabled();
    }

    private static String abilityType(String action) {
        return switch (action) {
            case "chat" -> "chat";
            case "abort_session" -> "abort";
            default -> "question"; // question_reply, permission_reply
        };
    }

    private static String normalizeAction(String action) {
        if (action == null) {
            return null;
        }
        return action.trim().toLowerCase(Locale.ROOT);
    }

    private static String mapChannelType(String commProtocol) {
        String value = blankToEmpty(commProtocol).toLowerCase();
        return switch (value) {
            case "sse" -> "sse";
            case "ws", "websocket" -> "websocket";
            case "http", "webhook" -> "webhook";
            default -> null;
        };
    }

    /**
     * 判断 channelType 是否为 webhook/http（POST 语义）。
     *
     * <p>abort 第三方通知始终按 HTTP POST 发送，route 误配成 sse/websocket 时
     * 会向流式地址发 POST，必须显式拒绝。</p>
     */
    private static boolean isWebhookChannel(String channelType) {
        return "webhook".equalsIgnoreCase(channelType) || "http".equalsIgnoreCase(channelType);
    }

    private static String resolveAuthType(List<AssistantInstanceInfo.RemoteHeader> headers) {
        if (headers == null || headers.isEmpty()) {
            return "none";
        }
        AssistantInstanceInfo.RemoteHeader first = headers.get(0);
        return mapRemoteHeaderTypeToAuthType(first == null ? null : first.getType());
    }

    private static String mapRemoteHeaderTypeToAuthType(String type) {
        String value = blankToEmpty(type).trim().toLowerCase();
        return switch (value) {
            case "" -> null;
            case "0", "none", "noauth", "no_auth" -> "none";
            case "1", "soa" -> "soa";
            case "2", "apig" -> "apig";
            case "3", "integration", "integration_token" -> "integration_token";
            default -> value;
        };
    }

    /**
     * 调用流式协议（SSE / WebSocket）。保留原实现的全部行为：
     * <ul>
     *   <li>{@link CloudConnectionLifecycle} 三段超时（首事件 / 空闲 / 最大时长）</li>
     *   <li>fallback messageId：优先沿用云端首个事件携带的 messageId，缺失则生成</li>
     *   <li>fallback partId：按归一化后的事件类型分组，每类共享同一个 partId</li>
     *   <li>errorSent CAS：超时或 onError 任一方触发后只回流一次 tool_error</li>
     * </ul>
     */
    private void invokeStreaming(GatewayMessage invokeMessage,
                                 Consumer<GatewayMessage> onRelay,
                                 CloudConnectionContext context,
                                 String protocol,
                                 String ak,
                                 String toolSessionId) {
        AtomicReference<String> fallbackMessageIdRef = new AtomicReference<>(null);
        ConcurrentHashMap<String, String> fallbackPartIds = new ConcurrentHashMap<>();
        AtomicBoolean errorSent = new AtomicBoolean(false);
        CloudConnectionHandle connectionHandle = new CloudConnectionHandle();
        context.setConnectionHandle(connectionHandle);
        ActiveCloudConnection activeConnection = registerActiveConnection(
                toolSessionId, invokeMessage.getWelinkSessionId(), connectionHandle);
        registerCloudStreamRoute(toolSessionId);

        CloudConnectionLifecycle lifecycle = new CloudConnectionLifecycle(
                timeoutProperties.getFirstEventTimeoutSeconds(),
                timeoutProperties.getEffectiveIdleTimeoutSeconds(protocol),
                timeoutProperties.getMaxDurationSeconds(),
                (timeoutType, elapsedSeconds) -> {
                    handleStreamingTimeout(invokeMessage, onRelay, ak, toolSessionId,
                            connectionHandle, errorSent, timeoutType, elapsedSeconds);
                },
                () -> log.info("[CLOUD_AGENT] Connection closed by lifecycle: ak={}, traceId={}",
                        ak, invokeMessage.getTraceId())
        );

        try {
            cloudProtocolClient.connect(protocol, context, lifecycle,
                    event -> {
                        if (connectionHandle.isCancelled()) {
                            return;
                        }
                        String rawPayload = rawCloudPayload(event);
                        // 注入路由上下文
                        event.setAk(ak);
                        event.setUserId(invokeMessage.getUserId());
                        event.setWelinkSessionId(invokeMessage.getWelinkSessionId());
                        event.setTraceId(invokeMessage.getTraceId());
                        if (event.getToolSessionId() == null) {
                            event.setToolSessionId(toolSessionId);
                        }

                        // 兜底：云端未传 messageId/partId 时 GW 自动补充
                        // GatewayMessage.event 结构: {"type":"text.delta","properties":{"content":"..."}}
                        // SS 的 CloudEventTranslator handler 从 event.properties 中读取字段
                        // 所以注入到 properties 和 event 顶层都需要（properties 给 handler 读，顶层给 translate 方法读）
                        JsonNode eventNode = event.getEvent();
                        if (eventNode != null && !eventNode.isMissingNode() && eventNode.isObject()) {
                            String eventType = eventNode.path("type").asText("");
                            ObjectNode eventObj = (ObjectNode) eventNode;
                            JsonNode props = eventObj.path("properties");
                            ObjectNode propsObj = (props != null && props.isObject())
                                    ? (ObjectNode) props : null;

                            // messageId 兜底：优先从云端事件学习，不存在时生成
                            String eventMsgId = (propsObj != null && propsObj.has("messageId"))
                                    ? propsObj.path("messageId").asText("") : "";
                            if (!eventMsgId.isBlank()) {
                                // 学习云端首个携带 messageId 的事件，作为后续的 fallback
                                fallbackMessageIdRef.compareAndSet(null, eventMsgId);
                            } else if (propsObj != null) {
                                // 云端没传 messageId，使用已学习的或生成兜底
                                String fallback = fallbackMessageIdRef.updateAndGet(current ->
                                        current != null ? current
                                                : "cloud-msg-" + UUID.randomUUID().toString().replace("-", ""));
                                propsObj.put("messageId", fallback);
                            }

                            // partId 兜底：注入到 properties 中
                            boolean needPartId = propsObj == null
                                    || !propsObj.has("partId")
                                    || propsObj.path("partId").asText("").isBlank();
                            if (needPartId && propsObj != null) {
                                String normalizedType = normalizeEventType(eventType);
                                String fbPartId = fallbackPartIds.computeIfAbsent(normalizedType,
                                        t -> "cloud-part-" + t + "-" + UUID.randomUUID().toString().substring(0, 8));
                                propsObj.put("partId", fbPartId);
                            }
                        }

                        if (errorSent.get()) return;
                        var previousMdc = MdcHelper.snapshot();
                        try {
                            MdcHelper.fromGatewayMessage(event);
                            MdcHelper.putScenario("cloud-agent-stream-rx");
                            if (!isSseProtocol(protocol)) {
                                GatewayStreamEventLogHelper.inbound(log, "gw.cloud_agent", "received", rawPayload);
                            }
                            onRelay.accept(event);
                        } finally {
                            MdcHelper.restore(previousMdc);
                        }
                    },
                    error -> {
                        if (connectionHandle.isCancelled()) {
                            log.info("[CLOUD_AGENT] Cloud connection cancelled: ak={}, traceId={}, toolSessionId={}",
                                    ak, invokeMessage.getTraceId(), toolSessionId);
                            return;
                        }
                        log.error("[CLOUD_AGENT] Cloud connection error: ak={}, traceId={}, error={}",
                                ak, invokeMessage.getTraceId(), error.getMessage());
                        GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId, error, null);
                        if (errorSent.compareAndSet(false, true)) { onRelay.accept(errorMsg); }
                    }
            );
        } finally {
            removeActiveConnection(activeConnection);
            removeCloudStreamRouteIfNoLocalActive(toolSessionId);
            lifecycle.close();
        }
    }

    private void handleStreamingTimeout(GatewayMessage invokeMessage,
                                        Consumer<GatewayMessage> onRelay,
                                        String ak,
                                        String toolSessionId,
                                        CloudConnectionHandle connectionHandle,
                                        AtomicBoolean errorSent,
                                        String timeoutType,
                                        long elapsedSeconds) {
        if (connectionHandle.isCancelled()) {
            log.info("[CLOUD_AGENT] Ignore timeout after cancellation: ak={}, traceId={}, type={}",
                    ak, invokeMessage.getTraceId(), timeoutType);
            return;
        }
        log.warn("[CLOUD_AGENT] Connection timeout: ak={}, traceId={}, type={}, elapsed={}s",
                ak, invokeMessage.getTraceId(), timeoutType, elapsedSeconds);
        connectionHandle.cancel();
        GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId,
                new RuntimeException(timeoutType + " (elapsed: " + elapsedSeconds + "s)"), null);
        if (errorSent.compareAndSet(false, true)) {
            onRelay.accept(errorMsg);
        }
    }

    private void cancelStreamingConnection(GatewayMessage invokeMessage, String toolSessionId) {
        List<String> keys = activeConnectionKeys(toolSessionId, invokeMessage.getWelinkSessionId());
        if (keys.isEmpty()) {
            log.info("[CLOUD_AGENT] abort_session ignored: reason=no_connection_key, traceId={}",
                    invokeMessage.getTraceId());
            return;
        }

        List<ActiveCloudConnection> activeConnections = activeConnections(keys);
        int cancelled = 0;
        for (ActiveCloudConnection activeConnection : activeConnections) {
            removeActiveConnection(activeConnection);
            if (activeConnection.handle().cancel()) {
                cancelled++;
            }
        }
        if (!activeConnections.isEmpty()) {
            removeCloudStreamRouteIfNoLocalActive(toolSessionId);
        }

        int relayed = isCloudControlRelayed(invokeMessage)
                ? 0
                : relayAbortToOwningGateways(invokeMessage, toolSessionId);
        if (activeConnections.isEmpty() && relayed == 0) {
            log.info("[CLOUD_AGENT] abort_session ignored: reason=no_active_connection, toolSessionId={}, welinkSessionId={}, traceId={}",
                    toolSessionId, invokeMessage.getWelinkSessionId(), invokeMessage.getTraceId());
            return;
        }

        log.info("[CLOUD_AGENT] abort_session cancelled active streams: localMatched={}, localCancelled={}, remoteRelayed={}, toolSessionId={}, welinkSessionId={}, traceId={}",
                activeConnections.size(), cancelled, relayed, toolSessionId,
                invokeMessage.getWelinkSessionId(), invokeMessage.getTraceId());
    }

    /**
     * 如果配置了 type=abort 的 remoteProperty，异步调用第三方终止接口。
     * 如果 remoteProperty 未命中，回退到 SysConfig 兜底配置（cloud_route_fallback_v2:{businessTag}:abort）。
     *
     * <p>fire-and-forget：路由解析、SysConfig 读取、HTTP 调用整体包进专用线程池的异步分支，
     * 不阻塞 cancelStreamingConnection()。失败（含线程池拒绝）仅打 WARN 日志，不回传 tool_error。</p>
     *
     * <p>安全约束：abort route 的 channelType 必须是 webhook/http（POST 语义），
     * 误配成 sse/websocket 时记 WARN 跳过，避免向流式地址发 POST。</p>
     */
    private void invokeRemoteAbortIfConfigured(GatewayMessage invokeMessage,
                                               String toolSessionId,
                                               String assistantAccount,
                                               String businessTag) {
        final String ak = invokeMessage.getAk();
        final String traceId = invokeMessage.getTraceId();
        try {
            abortExecutor.execute(() -> {
                try {
                    RemoteRoute route = resolveRemoteRoute(assistantAccount, ACTION_ABORT_SESSION, businessTag);
                    if (route == null) {
                        // remoteProperty 未配置 abort → 回退到 SysConfig 兜底
                        String scope = ACTION_TO_SCOPE.get(ACTION_ABORT_SESSION);
                        CallbackConfig cfg = sysConfigRouteProvider.load(ak, scope, businessTag);
                        if (cfg == null) {
                            log.debug("[CLOUD_AGENT] No remote abort route configured, skipping third-party stop call: traceId={}", traceId);
                            return;
                        }
                        route = new RemoteRoute(cfg.getChannelAddress(), cfg.getChannelType(),
                                cfg.getAppId(), businessTag, cfg.getAuthType());
                    }
                    // 校验 channelType 必须是 webhook/http：sendAbortRequest 始终按 HTTP POST 发送，
                    // 误配成 sse/websocket 会向流式地址发 POST，显式拒绝并继续本地 cancel。
                    if (!isWebhookChannel(route.channelType())) {
                        log.warn("[CLOUD_AGENT] Abort route channelType is not webhook/http, skip third-party abort: channelType={}, url={}, traceId={}",
                                route.channelType(), route.channelAddress(), traceId);
                        return;
                    }
                    // 构造请求体（与 question 接口同构）
                    ObjectNode body = buildAbortBody(invokeMessage, toolSessionId);
                    // 构建 context 供统一远程调用日志 helper 使用（header 脱敏 + 字段形态一致）
                    CloudConnectionContext abortContext = CloudConnectionContext.builder()
                            .channelAddress(route.channelAddress())
                            .channelType(route.channelType())
                            .scope(ACTION_TO_SCOPE.get(ACTION_ABORT_SESSION))
                            .appId(route.appId())
                            .authType(route.authType())
                            .traceId(traceId)
                            .cloudProfile(route.cloudProfile())
                            .build();
                    sendAbortRequest(route, body, abortContext);
                } catch (Exception e) {
                    log.warn("[CLOUD_AGENT] Async abort request failed: traceId={}, error={}",
                            traceId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池队列满时 execute 同步抛出，必须吞掉以免影响本地 cancel 主路径
            log.warn("[CLOUD_AGENT] Abort executor rejected task, skip third-party abort: traceId={}", traceId);
        }
    }

    /**
     * 构造终止请求体（与 question 接口同构）。
     *
     * <p>需求明确"只是接口地址变了，鉴权header和入参和原来的question接口一致"。
     * 因此不定义独立 DTO，而是构造与 cloudRequest 同构的 JSON 对象。</p>
     */
    private ObjectNode buildAbortBody(GatewayMessage invokeMessage, String toolSessionId) {
        JsonNode payload = invokeMessage.getPayload();
        ObjectNode body = objectMapper.createObjectNode();

        body.put("type", "abort");
        body.put("assistantAccount", firstNonBlank(
                invokeMessage.getAssistantAccount(),
                textAt(payload, "assistantAccount"),
                textAt(payload, "partnerAccount")));
        body.put("sendUserAccount", firstNonBlank(
                invokeMessage.getUserId(), textAt(payload, "sendUserAccount")));
        body.put("topicId", toolSessionId);

        // 可选字段
        putIfText(body, payload, "imGroupId");
        putIfText(body, payload, "messageId");
        String clientLang = textAt(payload, "clientLang");
        body.put("clientLang", (clientLang != null && !clientLang.isBlank()) ? clientLang : "zh");
        putIfText(body, payload, "clientType");

        // extParameters：与 question 接口对齐
        ObjectNode extParams = objectMapper.createObjectNode();
        extParams.set("businessExtParam",
                (payload != null && payload.has("businessExtParam")
                        && payload.get("businessExtParam").isObject())
                        ? payload.get("businessExtParam") : objectMapper.createObjectNode());
        extParams.set("platformExtParam", objectMapper.createObjectNode());
        body.set("extParameters", extParams);

        return body;
    }

    /**
     * 发送终止 HTTP POST 请求到第三方助手。
     *
     * <p>内联发送而非复用 WebHookExecutor：abort 是 fire-and-forget 旁路通知，
     * 失败不回传 tool_error；WebHookExecutor 失败会回调 onRelay 产生 tool_error，
     * 语义不匹配。</p>
     *
     * <p>请求日志走 {@link CloudRemoteRequestLogHelper#logRequest}，与 SSE/WebHook/WebSocket
     * 三条协议保持统一的 header 脱敏和字段形态。</p>
     */
    private void sendAbortRequest(RemoteRoute route, ObjectNode body, CloudConnectionContext context)
            throws Exception {
        String bodyStr = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(route.channelAddress()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .timeout(Duration.ofSeconds(10));
        if (context.getTraceId() != null) {
            builder.header("X-Trace-Id", context.getTraceId());
        }
        cloudAuthService.applyAuth(builder, route.appId(), route.authType());

        HttpRequest request = builder.build();
        CloudRemoteRequestLogHelper.logRequest(log, route.channelType(), route.channelAddress(),
                request.headers().map(), bodyStr, context);
        HttpResponse<String> resp = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        log.info("[CLOUD_AGENT] Abort response: url={}, status={}, body={}, traceId={}",
                route.channelAddress(), resp.statusCode(), resp.body(), context.getTraceId());
        if (resp.statusCode() != 200) {
            log.warn("[CLOUD_AGENT] Abort request returned non-200: url={}, status={}, body={}, traceId={}",
                    route.channelAddress(), resp.statusCode(), resp.body(), context.getTraceId());
        }
    }

    private void registerCloudStreamRoute(String toolSessionId) {
        if (!hasText(toolSessionId) || redisMessageBroker == null || !hasText(gatewayInstanceId)) {
            return;
        }
        redisMessageBroker.setCloudStreamRoute(toolSessionId, gatewayInstanceId, cloudStreamRouteTtl());
    }

    private void removeCloudStreamRouteIfNoLocalActive(String toolSessionId) {
        if (!hasText(toolSessionId) || redisMessageBroker == null || !hasText(gatewayInstanceId)) {
            return;
        }
        if (hasLocalActiveToolConnection(toolSessionId)) {
            return;
        }
        redisMessageBroker.removeCloudStreamRoute(toolSessionId, gatewayInstanceId);
    }

    private Duration cloudStreamRouteTtl() {
        long maxDurationSeconds = Math.max(0, timeoutProperties.getMaxDurationSeconds());
        return Duration.ofSeconds(Math.max(60, maxDurationSeconds + 60));
    }

    private int relayAbortToOwningGateways(GatewayMessage invokeMessage, String toolSessionId) {
        if (!hasText(toolSessionId)
                || redisMessageBroker == null
                || objectMapper == null
                || !hasText(gatewayInstanceId)) {
            return 0;
        }
        Set<String> ownerGatewayIds = cloudStreamOwnerGateways(toolSessionId);
        if (ownerGatewayIds.isEmpty()) {
            return 0;
        }
        GatewayMessage relayMessage = markCloudControlRelayed(invokeMessage);
        int relayed = 0;
        try {
            String originalJson = objectMapper.writeValueAsString(relayMessage);
            String relayJson = objectMapper.writeValueAsString(RelayMessage.toCloudControl(originalJson));
            for (String ownerGatewayId : ownerGatewayIds) {
                if (!hasText(ownerGatewayId) || gatewayInstanceId.equals(ownerGatewayId)) {
                    continue;
                }
                redisMessageBroker.publishToGwRelay(ownerGatewayId, relayJson);
                relayed++;
            }
            if (relayed > 0) {
                log.info("[CLOUD_AGENT] abort_session relayed to active stream owners: ownerCount={}, toolSessionId={}, traceId={}",
                        relayed, toolSessionId, invokeMessage.getTraceId());
            }
            return relayed;
        } catch (Exception e) {
            log.warn("[CLOUD_AGENT] abort_session owner relay failed: ownerCount={}, toolSessionId={}, traceId={}, error={}",
                    ownerGatewayIds.size(), toolSessionId, invokeMessage.getTraceId(), e.getMessage());
            return relayed;
        }
    }

    private Set<String> cloudStreamOwnerGateways(String toolSessionId) {
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        Set<String> registeredOwners = redisMessageBroker.getCloudStreamOwners(toolSessionId);
        if (registeredOwners != null) {
            owners.addAll(registeredOwners);
        }
        String legacyOwner = redisMessageBroker.getCloudStreamRoute(toolSessionId);
        if (hasText(legacyOwner)) {
            owners.add(legacyOwner);
        }
        owners.removeIf(owner -> !hasText(owner));
        return owners;
    }

    private GatewayMessage markCloudControlRelayed(GatewayMessage invokeMessage) {
        JsonNode payload = invokeMessage.getPayload();
        ObjectNode relayPayload = payload != null && payload.isObject()
                ? ((ObjectNode) payload).deepCopy()
                : objectMapper.createObjectNode();
        relayPayload.put(CLOUD_CONTROL_RELAYED_FLAG, true);
        return invokeMessage.toBuilder().payload(relayPayload).build();
    }

    private static boolean isCloudControlRelayed(GatewayMessage invokeMessage) {
        JsonNode payload = invokeMessage.getPayload();
        return payload != null && payload.path(CLOUD_CONTROL_RELAYED_FLAG).asBoolean(false);
    }

    private ActiveCloudConnection registerActiveConnection(String toolSessionId, String welinkSessionId,
            CloudConnectionHandle handle) {
        List<String> keys = activeConnectionKeys(toolSessionId, welinkSessionId);
        if (keys.isEmpty()) {
            return null;
        }
        ActiveCloudConnection activeConnection = new ActiveCloudConnection(handle, keys);
        for (String key : keys) {
            activeStreamingConnections
                    .computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                    .add(activeConnection);
        }
        return activeConnection;
    }

    private void removeActiveConnection(ActiveCloudConnection activeConnection) {
        if (activeConnection == null) {
            return;
        }
        for (String key : activeConnection.keys()) {
            Set<ActiveCloudConnection> connections = activeStreamingConnections.get(key);
            if (connections == null) {
                continue;
            }
            connections.remove(activeConnection);
            if (connections.isEmpty()) {
                activeStreamingConnections.remove(key, connections);
            }
        }
    }

    private List<ActiveCloudConnection> activeConnections(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<ActiveCloudConnection> connections = new LinkedHashSet<>();
        for (String key : keys) {
            Set<ActiveCloudConnection> registered = activeStreamingConnections.get(key);
            if (registered != null) {
                connections.addAll(registered);
            }
        }
        return List.copyOf(connections);
    }

    private boolean hasLocalActiveToolConnection(String toolSessionId) {
        if (!hasText(toolSessionId)) {
            return false;
        }
        Set<ActiveCloudConnection> connections = activeStreamingConnections.get("tool:" + toolSessionId);
        return connections != null && !connections.isEmpty();
    }

    private static List<String> activeConnectionKeys(String toolSessionId, String welinkSessionId) {
        List<String> keys = new ArrayList<>(2);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            keys.add("tool:" + toolSessionId);
        }
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            keys.add("welink:" + welinkSessionId);
        }
        return List.copyOf(keys);
    }

    private static boolean isSseProtocol(String protocol) {
        return "sse".equalsIgnoreCase(protocol);
    }

    /**
     * 归一化事件类型（去掉 .delta/.done 后缀），使同类型事件共享 partId。
     */
    private static String normalizeEventType(String eventType) {
        if (eventType == null) return "unknown";
        if (eventType.endsWith(".delta")) return eventType.substring(0, eventType.length() - 6);
        if (eventType.endsWith(".done")) return eventType.substring(0, eventType.length() - 5);
        return eventType;
    }

    private static String rawCloudPayload(GatewayMessage event) {
        if (event == null) {
            return null;
        }
        JsonNode eventNode = event.getEvent();
        if (eventNode != null && !eventNode.isMissingNode() && !eventNode.isNull()) {
            return eventNode.toString();
        }
        JsonNode payload = event.getPayload();
        if (payload != null && !payload.isMissingNode() && !payload.isNull()) {
            return payload.toString();
        }
        return String.valueOf(event);
    }

    /**
     * 构建云端错误消息（tool_error 类型）。
     *
     * @param reason 失败原因枚举（如 {@link #REASON_CALLBACK_CONFIG_MISSING}）；
     *               可为 null，老 SS 仍可通过 error 文案启发式 fallback。
     */
    private GatewayMessage buildCloudError(GatewayMessage invokeMessage, String toolSessionId,
                                           Throwable error, String reason) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .ak(invokeMessage.getAk())
                .userId(invokeMessage.getUserId())
                .welinkSessionId(invokeMessage.getWelinkSessionId())
                .traceId(invokeMessage.getTraceId())
                .toolSessionId(toolSessionId)
                .error("Cloud agent error: " + error.getMessage())
                .reason(reason)
                .build();
    }

    private static String textAt(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    /**
     * 如果 payload 中指定字段存在且为非空文本，则写入目标 ObjectNode。
     */
    private static void putIfText(ObjectNode target, JsonNode source, String fieldName) {
        String value = textAt(source, fieldName);
        if (value != null) {
            target.put(fieldName, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RemoteRoute(String channelAddress,
                               String channelType,
                               String appId,
                               String cloudProfile,
                               String authType) {
    }

    private record ActiveCloudConnection(CloudConnectionHandle handle, List<String> keys) {
    }
}
