package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
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
        boolean sendToGateway(String message); // 发送消息到 Gateway（兼容旧接口）

        boolean hasActiveConnection(); // 是否有活跃的 WebSocket 连接

        /**
         * v3: 精确发送到指定 Gateway 实例。
         * 默认 fallback 到 sendToGateway（兼容旧实现）。
         */
        default boolean sendToGateway(String gwInstanceId, String message) {
            return sendToGateway(message);
        }

        /**
         * v3: 广播到所有 Gateway 连接。
         * 默认 fallback 到 sendToGateway（兼容旧实现）。
         */
        default boolean broadcastToAllGateways(String message) {
            return sendToGateway(message);
        }

        /**
         * v4: Send message via consistent hash ring selection.
         * Uses hashKey (typically ak) to deterministically select a GW connection.
         * Default fallback to sendToGateway for backward compatibility.
         *
         * @param hashKey hash key for node selection (typically ak)
         * @param message serialized message payload
         * @return true if message was sent successfully
         */
        default boolean sendViaHash(String hashKey, String message) {
            return sendToGateway(message);
        }
    }

    private final ObjectMapper objectMapper;
    private final GatewayMessageRouter messageRouter;
    private final SessionRebuildService rebuildService;
    private final RedisMessageBroker redisMessageBroker;
    private volatile GatewayRelayTarget gatewayRelayTarget;

    public GatewayRelayService(ObjectMapper objectMapper,
            GatewayMessageRouter messageRouter,
            SessionRebuildService rebuildService,
            RedisMessageBroker redisMessageBroker) {
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.rebuildService = rebuildService;
        this.redisMessageBroker = redisMessageBroker;

        // 向 MessageRouter 注入下行发送能力，避免循环依赖
        messageRouter.setDownstreamSender(this::sendInvokeToGateway);
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

        String messageText = buildInvokeMessage(command);
        if (messageText == null) {
            return;
        }

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("[SKIP] GatewayRelayService.sendInvokeToGateway: reason=no_connection, ak={}, action={}",
                    command.ak(), action);
            return;
        }

        // v4: consistent hash routing — primary path
        boolean sent = relayTarget.sendViaHash(command.ak(), messageText);
        String gwInstanceId = null;

        if (!sent) {
            // Fallback path 1: legacy conn:ak precise delivery (compatible with older GW)
            gwInstanceId = redisMessageBroker.getConnAk(command.ak());
            if (gwInstanceId != null && !gwInstanceId.isBlank()) {
                log.info(
                        "GatewayRelayService.sendInvokeToGateway: hash routing missed, trying conn:ak fallback, ak={}, gwInstanceId={}",
                        command.ak(), gwInstanceId);
                sent = relayTarget.sendToGateway(gwInstanceId, messageText);
            }
        }

        if (!sent) {
            // Fallback path 2: broadcast to all GW instances
            log.warn(
                    "GatewayRelayService.sendInvokeToGateway: conn:ak fallback failed, falling back to broadcast, ak={}, gwInstanceId={}",
                    command.ak(), gwInstanceId);
            sent = relayTarget.broadcastToAllGateways(messageText);
        }

        if (!sent) {
            log.warn("[ERROR] GatewayRelayService.sendInvokeToGateway: reason=send_failed, ak={}, action={}",
                    command.ak(), action);
            return;
        }

        log.info("[EXIT->GW] GatewayRelayService.sendInvokeToGateway: action={}, ak={}, gwInstanceId={}",
                action, command.ak(), gwInstanceId);
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
                        messageRouter.broadcastStreamMessage(sid, uid, msg);
                    }

                    @Override
                    public void sendInvoke(InvokeCommand command) {
                        sendInvokeToGateway(command);
                    }
                });
    }
}
