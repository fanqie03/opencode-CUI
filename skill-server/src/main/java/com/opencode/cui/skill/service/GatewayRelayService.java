package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Manages communication with AI-Gateway.
 *
 * Downstream (Skill -> Gateway):
 * - Sends invoke messages through the active internal WebSocket connection
 *
 * Upstream (Gateway -> Skill via WS):
 * - Delegates to GatewayMessageRouter for message routing and handling
 *
 * Multi-instance broadcast:
 * - Delegates to GatewayMessageRouter for Redis pub/sub broadcast
 */
@Slf4j
@Service
public class GatewayRelayService {

    public static final String SOURCE = "skill-server";

    public interface GatewayRelayTarget {
        boolean sendToGateway(String message);

        boolean hasActiveConnection();
    }

    private final ObjectMapper objectMapper;
    private final GatewayMessageRouter messageRouter;
    private final SessionRebuildService rebuildService;
    private volatile GatewayRelayTarget gatewayRelayTarget;

    public GatewayRelayService(ObjectMapper objectMapper,
            GatewayMessageRouter messageRouter,
            SessionRebuildService rebuildService) {
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.rebuildService = rebuildService;

        // Wire downstream sender callback to avoid circular dependency
        messageRouter.setDownstreamSender(this::sendInvokeToGateway);
    }

    public void setGatewayRelayTarget(GatewayRelayTarget gatewayRelayTarget) {
        this.gatewayRelayTarget = gatewayRelayTarget;
    }

    // ==================== Downstream: Skill -> Gateway ====================

    /**
     * Send an invoke command to AI-Gateway over the active internal WebSocket.
     *
     * @param command the invoke command encapsulating ak, userId, sessionId,
     *                action, and payload
     */
    public void sendInvokeToGateway(InvokeCommand command) {
        String action = command.action();

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
            log.warn("Gateway WS connection not available, invoke dropped: ak={}, userId={}, action={}",
                    command.ak(), command.userId(), action);
            return;
        }

        boolean sent = relayTarget.sendToGateway(messageText);
        if (!sent) {
            log.warn("Failed to send invoke through Gateway WS: ak={}, userId={}, action={}",
                    command.ak(), command.userId(), action);
            return;
        }

        log.debug("Invoke sent via Gateway WS: ak={}, userId={}, action={}",
                command.ak(), command.userId(), action);
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

    // ==================== Upstream: Gateway -> Skill (via WS) ====================

    /**
     * Handle an incoming message from AI-Gateway.
     * Parses JSON and delegates to GatewayMessageRouter for dispatch.
     */
    public void handleGatewayMessage(String rawMessage) {
        JsonNode node;
        try {
            node = objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse gateway message: {}", rawMessage, e);
            return;
        }

        String type = node.path("type").asText("");
        String ak = node.path("ak").asText(null);
        String userId = node.path("userId").asText(null);
        if (ak == null || ak.isBlank()) {
            ak = node.path("agentId").asText(null);
        }

        messageRouter.route(type, ak, userId, node);
    }

    // ==================== Public Delegates ====================

    /**
     * Publish a protocol message via broadcast + buffer.
     * Used by controllers for ad-hoc push (e.g. permission replies).
     */
    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        messageRouter.publishProtocolMessage(sessionId, msg);
    }

    /**
     * Trigger toolSession rebuild: store pending message, notify frontend retry,
     * send create_session to Gateway.
     * Can be called by Controller when toolSessionId is null.
     */
    public void rebuildToolSession(String sessionId, SkillSession session, String pendingMessage) {
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
