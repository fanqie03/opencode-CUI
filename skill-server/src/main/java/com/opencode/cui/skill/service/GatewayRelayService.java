package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI Gateway 通信服务。
 * 负责 Skill Server 与 AI Gateway 之间的双向消息传输。
 *
 * 下行（Skill → Gateway）：通过 WebSocket 发送 invoke 指令
 * 上行（Gateway → Skill）：接收并路由到 GatewayMessageRouter
 * 多实例广播：通过 Redis Pub/Sub 实现跨实例消息广播
 */
@Slf4j
@Service
public class GatewayRelayService {

    public static final String SOURCE = "skill-server"; // 消息来源标识

    /** Gateway WebSocket 通信接口（由 WebSocket Handler 注入） */
    public interface GatewayRelayTarget {
        /** 发送消息到 Gateway（round-robin 选择可用连接） */
        boolean sendToGateway(String message);

        /** 是否有活跃的 WebSocket 连接 */
        boolean hasActiveConnection();
    }

    private final ObjectMapper objectMapper;
    private final GatewayMessageRouter messageRouter;
    private final SessionRebuildService rebuildService;
    private final RedisMessageBroker redisMessageBroker;
    private final AssistantIdResolverService assistantIdResolverService;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
    private volatile GatewayRelayTarget gatewayRelayTarget;

    public GatewayRelayService(ObjectMapper objectMapper,
            GatewayMessageRouter messageRouter,
            SessionRebuildService rebuildService,
            RedisMessageBroker redisMessageBroker,
            AssistantIdResolverService assistantIdResolverService,
            AssistantInfoService assistantInfoService,
            AssistantScopeDispatcher scopeDispatcher,
            com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter) {
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.rebuildService = rebuildService;
        this.redisMessageBroker = redisMessageBroker;
        this.assistantIdResolverService = assistantIdResolverService;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.emitter = emitter;

        // 向 MessageRouter 注入下行发送能力，避免循环依赖
        messageRouter.setDownstreamSender(this::sendInvokeToGateway);
        // 向 MessageRouter 注入路由响应发送能力（Task 2.10）
        messageRouter.setRouteResponseSender(new GatewayMessageRouter.RouteResponseSender() {
            @Override
            public boolean sendRouteConfirm(String toolSessionId, String welinkSessionId) {
                return GatewayRelayService.this.sendRouteConfirm(toolSessionId, welinkSessionId);
            }

            @Override
            public void sendRouteReject(String toolSessionId) {
                GatewayRelayService.this.sendRouteReject(toolSessionId);
            }
        });
    }

    public void setGatewayRelayTarget(GatewayRelayTarget gatewayRelayTarget) {
        this.gatewayRelayTarget = gatewayRelayTarget;
    }

    // ==================== 下行：Skill → Gateway ====================

    /**
     * 通过 WebSocket 发送 invoke 指令到 AI Gateway。
     * 
     * @param command 调用指令，包含 ak、userId、sessionId、action 和 payload
     */
    public void sendInvokeToGateway(InvokeCommand command) {
        String action = command.action();

        log.info("[ENTRY] GatewayRelayService.sendInvokeToGateway: ak={}, userId={}, sessionId={}, action={}",
                command.ak(), command.userId(), command.sessionId(), action);

        // 发送新消息时清除已完成标记，防止新一轮对话的 tool_event 被误拦截
        if (GatewayActions.CHAT.equals(action)) {
            messageRouter.clearCompletionMark(command.sessionId());
        }

        // 根据助手类型（scope）选择构建策略
        String messageText;
        AssistantInfo info = assistantInfoService.getAssistantInfo(command.ak());
        if (info != null && info.isBusiness()) {
            AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(info.getAssistantScope());
            messageText = strategy.buildInvoke(command, info);
            if (messageText == null) {
                log.warn("[SKIP] GatewayRelayService.sendInvokeToGateway: reason=strategy_build_null, ak={}, scope=business",
                        command.ak());
                return;
            }
        } else {
            // personal 策略（默认）：使用原有构建逻辑
            messageText = buildInvokeMessage(command);
            if (messageText == null) {
                return;
            }
        }

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("[SKIP] GatewayRelayService.sendInvokeToGateway: reason=no_connection, ak={}, action={}",
                    command.ak(), action);
            return;
        }

        boolean sent = relayTarget.sendToGateway(messageText);

        if (!sent) {
            log.warn("[ERROR] GatewayRelayService.sendInvokeToGateway: reason=send_failed, ak={}, action={}",
                    command.ak(), action);
            return;
        }

        log.info("[EXIT->GW] GatewayRelayService.sendInvokeToGateway: action={}, ak={}",
                action, command.ak());
    }

    /**
     * 构建发往 Gateway 的 invoke JSON 消息体。
     *
     * @return 序列化后的 JSON 字符串，构建失败返回 null
     */
    private String buildInvokeMessage(InvokeCommand command) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "invoke");
        message.put("ak", command.ak());
        message.put("source", SOURCE);
        if (command.userId() != null && !command.userId().isBlank()) {
            message.put("userId", command.userId());
        }
        if (GatewayActions.CREATE_SESSION.equals(command.action())
                && command.sessionId() != null && !command.sessionId().isBlank()) {
            // 使用字符串传输 welinkSessionId，防止 JavaScript IEEE 754 大数精度丢失
            message.put("welinkSessionId", command.sessionId());
        }
        message.put("action", command.action());

        // 注入 traceId：从 MDC 获取或自动生成，确保跨服务链路可追踪
        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        try {
            if (command.payload() != null) {
                JsonNode payloadNode = objectMapper.readTree(command.payload());
                message.set("payload", payloadNode);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse invoke payload as JSON, sending as string: {}", e.getMessage());
            message.put("payload", command.payload());
        }

        // 注入 assistantId：仅在 create_session 和 chat 时注入
        if (GatewayActions.CREATE_SESSION.equals(command.action())
                || GatewayActions.CHAT.equals(command.action())) {
            String assistantId = assistantIdResolverService.resolve(command.ak(), command.sessionId());
            if (assistantId != null) {
                ObjectNode targetPayload;
                JsonNode existingPayload = message.get("payload");
                if (existingPayload instanceof ObjectNode on) {
                    targetPayload = on;
                } else {
                    targetPayload = objectMapper.createObjectNode();
                    message.set("payload", targetPayload);
                }
                targetPayload.put("assistantId", assistantId);
            }
        }

        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize invoke message", e);
            return null;
        }
    }

    // ==================== 上行：Gateway → Skill ====================

    /**
     * 处理来自 AI Gateway 的上行消息。
     * 解析 JSON 后委派给 GatewayMessageRouter 进行路由分发。
     */
    public void handleGatewayMessage(String rawMessage) {
        long start = System.nanoTime();
        log.info("[ENTRY] GatewayRelayService.handleGatewayMessage: length={}",
                rawMessage != null ? rawMessage.length() : 0);
        JsonNode node;
        try {
            node = objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException e) {
            log.error("[ERROR] GatewayRelayService.handleGatewayMessage: reason=parse_failed, length={}",
                    rawMessage != null ? rawMessage.length() : 0, e);
            return;
        }

        try {
            // 从 Gateway 消息提取关联字段到 MDC，实现跨服务链路追踪
            MdcHelper.fromJsonNode(node);
            MdcHelper.putScenario("ws-gateway-" + node.path("type").asText("unknown"));

            String type = node.path("type").asText("");
            String ak = node.path("ak").asText(null);
            String userId = node.path("userId").asText(null);
            if (ak == null || ak.isBlank()) {
                ak = node.path("agentId").asText(null);
                MdcHelper.putAk(ak); // 补充 fallback 的 ak 到 MDC
            }
            log.info("GatewayRelayService.handleGatewayMessage: dispatching type={}, ak={}, userId={}",
                    type, ak, userId);

            messageRouter.route(type, ak, userId, node);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[EXIT] GatewayRelayService.handleGatewayMessage: type={}, ak={}, durationMs={}",
                    type, ak, elapsedMs);
        } finally {
            MdcHelper.clearAll();
        }
    }

    // ==================== 路由协议响应（Task 2.10） ====================

    /**
     * 向 AI Gateway 发送 route_confirm，确认 SS 已接受该 toolSessionId 的路由归属。
     *
     * @param toolSessionId  OpenCode 侧会话 ID
     * @param welinkSessionId SS 侧会话 ID（可为 null）
     * @return true 表示已成功投递到 GW；false 表示因序列化失败或无活跃连接而未投递。
     *         调用方（{@link GatewayMessageRouter#maybeSendRouteConfirm}）依赖该返回值实现 cache-after-success。
     */
    public boolean sendRouteConfirm(String toolSessionId, String welinkSessionId) {
        log.info("[ENTRY] GatewayRelayService.sendRouteConfirm: toolSessionId={}, welinkSessionId={}",
                toolSessionId, welinkSessionId);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "route_confirm");
        message.put("toolSessionId", toolSessionId);
        message.put("source", SOURCE);
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            message.put("welinkSessionId", welinkSessionId);
        }

        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("[ERROR] GatewayRelayService.sendRouteConfirm: serialize_failed, toolSessionId={}", toolSessionId, e);
            return false;
        }

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("[SKIP] GatewayRelayService.sendRouteConfirm: reason=no_connection, toolSessionId={}", toolSessionId);
            return false;
        }

        boolean sent = relayTarget.sendToGateway(messageText);
        log.info("[EXIT] GatewayRelayService.sendRouteConfirm: toolSessionId={}, sent={}", toolSessionId, sent);
        return sent;
    }

    /**
     * 向 AI Gateway 发送 route_reject，通知 GW 本 SS 无法处理该 toolSessionId 的消息。
     *
     * @param toolSessionId OpenCode 侧会话 ID
     */
    public void sendRouteReject(String toolSessionId) {
        log.info("[ENTRY] GatewayRelayService.sendRouteReject: toolSessionId={}", toolSessionId);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "route_reject");
        message.put("toolSessionId", toolSessionId);
        message.put("source", SOURCE);

        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("[ERROR] GatewayRelayService.sendRouteReject: serialize_failed, toolSessionId={}", toolSessionId, e);
            return;
        }

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("[SKIP] GatewayRelayService.sendRouteReject: reason=no_connection, toolSessionId={}", toolSessionId);
            return;
        }

        boolean sent = relayTarget.sendToGateway(messageText);
        log.info("[EXIT] GatewayRelayService.sendRouteReject: toolSessionId={}, sent={}", toolSessionId, sent);
    }

    // ==================== 公共委派方法 ====================

    /**
     * 通过广播 + 缓冲区发布协议消息。
     * 用于控制器中的即时推送（如权限回复）。
     */
    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        messageRouter.publishProtocolMessage(sessionId, msg);
    }

    /**
     * 触发 toolSession 重建。
     * 缓存待发消息 → 通知前端重试 → 发送 create_session 到 Gateway。
     */
    public void rebuildToolSession(String sessionId, SkillSession session, String pendingMessage) {
        log.info("Initiating toolSession rebuild: sessionId={}, ak={}, hasPendingMessage={}",
                sessionId, session != null ? session.getAk() : null, pendingMessage != null);
        rebuildService.rebuildToolSession(sessionId, session, pendingMessage,
                new SessionRebuildService.RebuildCallback() {
                    @Override
                    public void broadcast(String sid, String uid, StreamMessage msg) {
                        emitter.emitToClient(sid, uid, msg);
                    }

                    @Override
                    public void sendInvoke(InvokeCommand command) {
                        sendInvokeToGateway(command);
                    }
                });
    }
}
