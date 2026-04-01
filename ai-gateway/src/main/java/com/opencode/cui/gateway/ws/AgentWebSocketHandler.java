package com.opencode.cui.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.AkSkAuthService;
import com.opencode.cui.gateway.service.DeviceBindingService;
import com.opencode.cui.gateway.service.EventRelayService;
import com.opencode.cui.gateway.service.RedisMessageBroker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PCAgent WebSocket 处理器。
 *
 * <p>
 * 握手认证：通过 Sec-WebSocket-Protocol 子协议提取认证参数，
 * 经 AkSkAuthService 验签通过后建立连接。
 * </p>
 *
 * <p>
 * 连接后支持的消息类型：
 * </p>
 * <ul>
 * <li>register — 设备绑定校验 + 重复连接检查 + 注册 Agent</li>
 * <li>heartbeat — 刷新 last_seen_at</li>
 * <li>tool_event / tool_done / tool_error / session_created /
 * permission_request — 转发至 Skill Server</li>
 * <li>status_response — 供 Gateway REST 状态查询使用</li>
 * </ul>
 *
 * <p>
 * 断开时：标记 Agent 离线、通知 Skill Server agent_offline。
 * </p>
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_AK_ID = "akId";
    private static final String ATTR_AGENT_ID = "agentId";
    private static final String AUTH_PROTOCOL_PREFIX = "auth.";

    /** 自定义 WebSocket 关闭码：重复连接被拒绝 */
    private static final CloseStatus CLOSE_DUPLICATE = new CloseStatus(4409, "duplicate_connection");
    /** 自定义 WebSocket 关闭码：设备绑定校验失败 */
    private static final CloseStatus CLOSE_BINDING_FAILED = new CloseStatus(4403, "device_binding_failed");
    /** 自定义 WebSocket 关闭码：注册超时 */
    private static final CloseStatus CLOSE_REGISTER_TIMEOUT = new CloseStatus(4408, "register_timeout");

    private final AkSkAuthService akSkAuthService;
    private final AgentRegistryService agentRegistryService;
    private final DeviceBindingService deviceBindingService;
    private final EventRelayService eventRelayService;
    private final RedisMessageBroker redisMessageBroker;
    private final String gatewayInstanceId;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private static final Duration CONN_AK_TTL = Duration.ofSeconds(120);

    @Value("${gateway.agent.register-timeout-seconds:10}")
    private int registerTimeoutSeconds;

    @Value("${gateway.relay.pending-ttl-seconds:60}")
    private int pendingTtlSeconds;

    /** wsSessionId → ak 映射，用于断开时的路由清理 */
    private final Map<String, String> sessionAkMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "register-timeout");
        t.setDaemon(true);
        return t;
    });

    public AgentWebSocketHandler(AkSkAuthService akSkAuthService,
            AgentRegistryService agentRegistryService,
            DeviceBindingService deviceBindingService,
            EventRelayService eventRelayService,
            RedisMessageBroker redisMessageBroker,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate) {
        this.akSkAuthService = akSkAuthService;
        this.agentRegistryService = agentRegistryService;
        this.deviceBindingService = deviceBindingService;
        this.eventRelayService = eventRelayService;
        this.redisMessageBroker = redisMessageBroker;
        this.gatewayInstanceId = gatewayInstanceId;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
        log.info("AgentWebSocketHandler scheduler shut down");
    }

    // ==================== 握手拦截器 ====================

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        long start = System.nanoTime();
        log.info("[ENTRY] AgentWSHandler.beforeHandshake: remoteAddr={}",
                request.getRemoteAddress());

        // 从 Sec-WebSocket-Protocol 提取认证信息："auth.{base64-json}"
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null || protocols.isEmpty()) {
            log.warn("[AUTH] AgentWSHandler.beforeHandshake: reason=no_subprotocol");
            return false;
        }

        // 查找认证协议（可能在单个 header 值中以逗号分隔）
        String authPayload = null;
        for (String protocol : protocols) {
            // 处理单个 header 中逗号分隔的多个值
            for (String p : protocol.split(",")) {
                String trimmed = p.trim();
                if (trimmed.startsWith(AUTH_PROTOCOL_PREFIX)) {
                    authPayload = trimmed.substring(AUTH_PROTOCOL_PREFIX.length());
                    break;
                }
            }
            if (authPayload != null)
                break;
        }

        if (authPayload == null) {
            log.warn("[AUTH] AgentWSHandler.beforeHandshake: reason=no_auth_subprotocol");
            return false;
        }

        // 解码 Base64URL → JSON → 提取 ak/ts/nonce/sign
        // 使用 URL 安全 Base64（RFC 4648 §5），因为 WebSocket 子协议不支持 '+', '/', '='
        String ak, ts, nonce, sign;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(authPayload);
            String json = new String(decoded, StandardCharsets.UTF_8);
            var authNode = objectMapper.readTree(json);
            ak = authNode.path("ak").asText(null);
            ts = authNode.path("ts").asText(null);
            nonce = authNode.path("nonce").asText(null);
            sign = authNode.path("sign").asText(null);
        } catch (Exception e) {
            log.warn("[AUTH] AgentWSHandler.beforeHandshake: reason=decode_failed, error={}",
                    e.getMessage());
            return false;
        }

        // 验证 AK/SK 签名
        String userId = akSkAuthService.verify(ak, ts, nonce, sign);
        if (userId == null) {
            log.warn("[AUTH] AgentWSHandler.beforeHandshake: reason=auth_failed, ak={}", ak);
            return false;
        }

        // 将认证信息保存到 session 属性中
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_AK_ID, ak);

        // 在响应中回显选定的子协议
        // RFC 6455 要求服务端必须原样回复客户端提供的子协议，
        // Bun 会严格校验该值
        String selectedProtocol = AUTH_PROTOCOL_PREFIX + authPayload;
        response.getHeaders().set("Sec-WebSocket-Protocol", selectedProtocol);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] AgentWSHandler.beforeHandshake: ak={}, userId={}, durationMs={}",
                ak, userId, elapsedMs);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    // ==================== WebSocket 处理器 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        String akId = (String) session.getAttributes().get(ATTR_AK_ID);
        log.info("PCAgent WebSocket connected: sessionId={}, userId={}, ak={}",
                session.getId(), userId, akId);

        // 启动注册超时定时器：N 秒内未收到 register 消息则关闭连接
        scheduler.schedule(() -> {
            if (!sessionAkMap.containsKey(session.getId()) && session.isOpen()) {
                log.warn("Register timeout: closing unregistered connection. sessionId={}, ak={}",
                        session.getId(), akId);
                try {
                    session.close(CLOSE_REGISTER_TIMEOUT);
                } catch (IOException e) {
                    log.debug("Error closing timed-out session: {}", e.getMessage());
                }
            }
        }, registerTimeoutSeconds, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        String payload = textMessage.getPayload();
        GatewayMessage message;
        try {
            message = objectMapper.readValue(payload, GatewayMessage.class);
        } catch (Exception e) {
            log.warn("Failed to parse message from PCAgent: sessionId={}, error={}",
                    session.getId(), e.getMessage());
            return;
        }

        String type = message.getType();
        if (type == null) {
            log.warn("Message without type from PCAgent: sessionId={}", session.getId());
            return;
        }


        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        String akId = (String) session.getAttributes().get(ATTR_AK_ID);

        try {
            MdcHelper.fromGatewayMessage(message);
            MdcHelper.putAk(akId);
            MdcHelper.putUserId(userId);
            MdcHelper.putScenario("ws-agent-" + type);

            switch (type) {
                case GatewayMessage.Type.REGISTER -> handleRegister(session, message, userId, akId);
                case GatewayMessage.Type.HEARTBEAT -> handleHeartbeat(session);
                case GatewayMessage.Type.TOOL_EVENT, GatewayMessage.Type.TOOL_DONE,
                        GatewayMessage.Type.TOOL_ERROR, GatewayMessage.Type.SESSION_CREATED,
                        GatewayMessage.Type.PERMISSION_REQUEST ->
                    handleRelayToSkillServer(session, message);
                case GatewayMessage.Type.STATUS_RESPONSE -> handleStatusResponse(session, message);
                default -> log.warn("Unknown message type from PCAgent: type={}, sessionId={}",
                        type, session.getId());
            }
        } finally {
            MdcHelper.clearAll();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String ak = sessionAkMap.remove(session.getId());
        if (ak != null) {
            // 从 session 属性中获取 agentId（Long）用于数据库操作
            Long agentId = (Long) session.getAttributes().get(ATTR_AGENT_ID);
            log.info("PCAgent disconnected: ak={}, agentId={}, sessionId={}, status={}",
                    ak, agentId, session.getId(), status);

            // 在数据库中标记 Agent 离线（需要 agentId）
            if (agentId != null) {
                agentRegistryService.markOffline(agentId);
            }

            // 从中继服务移除（使用 ak）
            eventRelayService.removeAgentSession(ak);

            // v3: 条件删除 conn:ak（仅删本实例注册的，防误删已重连到其他 GW 的 Agent）
            redisMessageBroker.conditionalRemoveConnAk(ak, gatewayInstanceId);

            // Phase 1.3: remove gw:internal:agent:{ak} in sync with conn:ak cleanup
            redisMessageBroker.removeInternalAgent(ak);

            // 通知 Skill Server Agent 已离线（使用 ak）
            GatewayMessage offlineMsg = GatewayMessage.agentOffline(ak);
            eventRelayService.relayToSkillServer(ak, offlineMsg);
        } else {
            log.info("PCAgent WebSocket closed (not registered): sessionId={}, status={}",
                    session.getId(), status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String ak = sessionAkMap.get(session.getId());
        log.error("WebSocket transport error: ak={}, sessionId={}, error={}",
                ak, session.getId(), exception.getMessage());
    }

    // ==================== 消息处理方法 ====================

    private static final String REGISTER_LOCK_PREFIX = "gw:register:lock:";
    private static final long REGISTER_LOCK_TTL_SECONDS = 10;
    /** Lua 脚本：仅当 value 匹配 owner 时才释放锁，防止超时后误删他人的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private void handleRegister(WebSocketSession session, GatewayMessage message,
            String userId, String akId) {
        long start = System.nanoTime();
        String deviceName = message.getDeviceName();
        String macAddress = message.getMacAddress();
        String os = message.getOs();
        String toolType = message.getToolType() != null ? message.getToolType() : "channel";
        String toolVersion = message.getToolVersion();

        log.info("[ENTRY] AgentWSHandler.handleRegister: ak={}, toolType={}, os={}",
                akId, toolType, os);

        // 分布式锁：防止同一 AK 并发注册导致写入多条记录
        String lockKey = REGISTER_LOCK_PREFIX + akId;
        String lockOwner = gatewayInstanceId + ":" + Thread.currentThread().threadId();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockOwner, REGISTER_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (acquired == null || !acquired) {
            log.warn("Register rejected: concurrent registration in progress. ak={}", akId);
            sendAndClose(session, GatewayMessage.registerRejected("concurrent_registration"),
                    CLOSE_DUPLICATE);
            return;
        }

        try {
            // 步骤 1：验证设备绑定（查 agent_connection 表，首次放行）
            if (!deviceBindingService.validate(akId, macAddress, toolType)) {
                log.warn("Register rejected: device binding failed. ak={}, mac={}, toolType={}",
                        akId, macAddress, toolType);
                sendAndClose(session, GatewayMessage.registerRejected("device_binding_failed"),
                        CLOSE_BINDING_FAILED);
                return;
            }

            // 步骤 2：检查重复活跃连接（保留旧连接，拒绝新连接）
            if (eventRelayService.hasAgentSession(akId)) {
                log.warn("Register rejected: duplicate connection. ak={}, toolType={}",
                        akId, toolType);
                sendAndClose(session, GatewayMessage.registerRejected("duplicate_connection"),
                        CLOSE_DUPLICATE);
                return;
            }

            // 步骤 3：数据库注册（复用已有记录或新建）
            AgentConnection agent = agentRegistryService.register(
                    userId, akId, deviceName, macAddress, os, toolType, toolVersion);

            Long agentId = agent.getId();

            // 将 agentId 保存到 session 属性（断开时用于数据库操作）
            session.getAttributes().put(ATTR_AGENT_ID, agentId);
            // 将 ak 保存到 session-ak 映射（用于消息路由）
            sessionAkMap.put(session.getId(), akId);

            // 在中继服务中注册 WebSocket 会话（以 ak 为键）
            eventRelayService.registerAgentSession(akId, userId, session);

            // v3: 注册 conn:ak → gatewayInstanceId（Source 服务查询用于下行精确投递）
            redisMessageBroker.bindConnAk(akId, gatewayInstanceId, CONN_AK_TTL);

            // Phase 1.3: also write gw:internal:agent:{ak} for intra-GW routing
            redisMessageBroker.bindInternalAgent(akId, gatewayInstanceId, CONN_AK_TTL);

            // 发送 register_ok 给客户端
            try {
                String okJson = objectMapper.writeValueAsString(GatewayMessage.registerOk());
                session.sendMessage(new TextMessage(okJson));
            } catch (IOException e) {
                log.error("Failed to send register_ok: ak={}", akId, e);
            }

            // Drain pending downlink messages buffered while agent was offline
            drainAndDeliverPending(akId, session);

            // 通知 Skill Server Agent 已上线（以 ak 为键）
            GatewayMessage onlineMsg = GatewayMessage.agentOnline(
                    akId, toolType, toolVersion);
            eventRelayService.relayToSkillServer(akId, onlineMsg);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info(
                    "[EXIT] AgentWSHandler.handleRegister: ak={}, agentId={}, device={}, mac={}, tool={}/{}, durationMs={}",
                    akId, agentId, deviceName,
                    com.opencode.cui.gateway.logging.SensitiveDataMasker.maskMac(macAddress),
                    toolType, toolVersion, elapsedMs);
        } finally {
            // 安全释放分布式锁：仅释放自己持有的锁
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockOwner);
        }
    }

    /** 处理心跳：刷新 Agent 最后活跃时间和 conn:ak TTL。 */
    private void handleHeartbeat(WebSocketSession session) {
        // 心跳需要从 session 属性中获取 agentId（Long）用于数据库操作
        Long agentId = (Long) session.getAttributes().get(ATTR_AGENT_ID);
        if (agentId != null) {
            agentRegistryService.heartbeat(agentId);
        } else {
            log.warn("Heartbeat from unregistered session: sessionId={}", session.getId());
        }

        // v3: 刷新 conn:ak TTL
        String ak = sessionAkMap.get(session.getId());
        if (ak != null) {
            redisMessageBroker.refreshConnAkTtl(ak, CONN_AK_TTL);
            // Phase 1.3: also refresh gw:internal:agent:{ak} TTL
            redisMessageBroker.refreshInternalAgentTtl(ak, CONN_AK_TTL);
        }
    }

    /** 将消息转发至 Skill Server。 */
    private void handleRelayToSkillServer(WebSocketSession session, GatewayMessage message) {
        String ak = sessionAkMap.get(session.getId());
        if (ak == null) {
            log.warn(
                    "[SKIP] AgentWSHandler.handleRelayToSkillServer: reason=unregistered_session, sessionId={}, type={}",
                    session.getId(), message.getType());
            return;
        }

        long start = System.nanoTime();
        log.info(
                "[ENTRY] AgentWSHandler.handleRelayToSkillServer: type={}, ak={}, welinkSessionId={}, toolSessionId={}, subagentSessionId={}, subagentName={}",
                message.getType(), ak, message.getWelinkSessionId(), message.getToolSessionId(),
                message.getSubagentSessionId(), message.getSubagentName());

        eventRelayService.relayToSkillServer(ak, message);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXIT] AgentWSHandler.handleRelayToSkillServer: type={}, ak={}, durationMs={}",
                message.getType(), ak, elapsedMs);
    }

    /** 处理状态响应：记录 Agent 的 OpenCode 在线状态。 */
    private void handleStatusResponse(WebSocketSession session, GatewayMessage message) {
        String ak = sessionAkMap.get(session.getId());
        if (ak == null) {
            log.warn("status_response from unregistered session: sessionId={}", session.getId());
            return;
        }

        eventRelayService.recordStatusResponse(ak, message.getOpencodeOnline());
        log.debug("Recorded status_response: ak={}, opencodeOnline={}", ak, message.getOpencodeOnline());
    }

    // ==================== 辅助方法 ====================

    /**
     * Drains all pending downlink messages buffered in Redis for the given agent and
     * delivers them over the newly established WebSocket session.
     *
     * <p>Called immediately after {@code register_ok} is sent so the agent receives
     * any messages that arrived while it was offline. Does nothing if the queue is empty.
     *
     * @param ak      Agent Access Key
     * @param session the agent's newly registered WebSocket session
     */
    private void drainAndDeliverPending(String ak, WebSocketSession session) {
        List<String> pending = redisMessageBroker.drainPending(ak);
        if (pending.isEmpty()) {
            return;
        }
        log.info("[ENTRY] AgentWSHandler.drainAndDeliverPending: ak={}, count={}", ak, pending.size());
        int delivered = 0;
        for (String json : pending) {
            if (!session.isOpen()) {
                log.warn("AgentWSHandler.drainAndDeliverPending: session closed mid-drain, ak={}, remaining={}",
                        ak, pending.size() - delivered);
                break;
            }
            try {
                session.sendMessage(new TextMessage(json));
                delivered++;
            } catch (IOException e) {
                log.error("[ERROR] AgentWSHandler.drainAndDeliverPending: failed to deliver pending message, ak={}, error={}",
                        ak, e.getMessage());
            }
        }
        log.info("[EXIT] AgentWSHandler.drainAndDeliverPending: ak={}, delivered={}/{}", ak, delivered, pending.size());
    }

    /** 发送拒绝消息并关闭 WebSocket 连接。 */
    private void sendAndClose(WebSocketSession session, GatewayMessage message, CloseStatus status) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            session.close(status);
        } catch (IOException e) {
            log.error("Failed to send rejection message: {}", e.getMessage());
            try {
                session.close(status);
            } catch (IOException ex) {
                log.debug("Error closing session after send failure: {}", ex.getMessage());
            }
        }
    }
}
