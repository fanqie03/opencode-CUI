package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Gateway WebSocket 消息协议。
 *
 * <p>本类作为所有消息类型的统一载体（flat DTO），通过 {@code type} 字段区分语义。
 * 不同消息类型使用不同的字段子集，未使用的字段序列化时为 null（JsonInclude.NON_NULL）。</p>
 *
 * <h3>消息类型与字段映射矩阵</h3>
 * <pre>
 * Type                | 使用字段
 * --------------------|----------------------------------------------
 * REGISTER            | deviceName, macAddress, os, toolType, toolVersion
 * REGISTER_OK         | (无额外字段)
 * REGISTER_REJECTED   | reason
 * HEARTBEAT           | (无额外字段)
 * INVOKE              | ak, welinkSessionId, action, payload, userId, source
 * TOOL_EVENT          | toolSessionId, event
 * TOOL_DONE           | toolSessionId, usage
 * TOOL_ERROR          | toolSessionId, error
 * SESSION_CREATED     | welinkSessionId, toolSessionId
 * AGENT_ONLINE        | ak, toolType, toolVersion
 * AGENT_OFFLINE       | ak
 * STATUS_QUERY        | (无额外字段)
 * STATUS_RESPONSE     | opencodeOnline
 * PERMISSION_REQUEST  | (透传到 Skill Server)
 * </pre>
 *
 * <h3>路由字段说明</h3>
 * <ul>
 *   <li>{@code ak} — Agent AK，消息路由的主键</li>
 *   <li>{@code welinkSessionId} — Skill 侧会话 ID</li>
 *   <li>{@code toolSessionId} — OpenCode 侧会话 ID</li>
 *   <li>{@code userId} / {@code source} — 服务端注入的路由上下文，下行时剥离</li>
 *   <li>{@code traceId} — 跨服务追踪 ID</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayMessage {

    // ==================== 消息类型常量 ====================

    /**
     * 消息类型常量集合。
     * 所有 type 字段的合法值都定义在此，避免跨文件的魔法字符串。
     */
    public interface Type {
        String REGISTER             = "register";
        String REGISTER_OK          = "register_ok";
        String REGISTER_REJECTED    = "register_rejected";
        String HEARTBEAT            = "heartbeat";
        String INVOKE               = "invoke";
        String TOOL_EVENT           = "tool_event";
        String TOOL_DONE            = "tool_done";
        String TOOL_ERROR           = "tool_error";
        String SESSION_CREATED      = "session_created";
        String AGENT_ONLINE         = "agent_online";
        String AGENT_OFFLINE        = "agent_offline";
        String STATUS_QUERY         = "status_query";
        String STATUS_RESPONSE      = "status_response";
        String PERMISSION_REQUEST   = "permission_request";
    }

    // ==================== 通用字段 ====================

    /** 消息类型标识，取值见 {@link Type} 常量 */
    private String type;

    /** Legacy/internal DB identifier; protocol routing uses ak instead */
    private String agentId;

    /** Agent AK identifier used for routing */
    private String ak;

    /** Skill session identifier from Layer2/3 protocol (String to prevent JS precision loss) */
    private String welinkSessionId;

    /** User identifier trusted by server-side routing */
    private String userId;

    /** Upstream source service identifier */
    private String source;

    /** Trace identifier for cross-service routing observability */
    private String traceId;

    // ==================== Invoke 字段 ====================

    /** Action for invoke messages: chat, create_session, close_session, ... */
    private String action;

    /** Payload for invoke or register messages */
    private JsonNode payload;

    // ==================== Tool 事件字段 ====================

    /** OpenCode raw event (transparent relay) */
    private JsonNode event;

    /** Error description */
    private String error;

    /** Token usage information */
    private JsonNode usage;

    // ==================== 多实例协调字段 ====================

    /** Sequence number for message ordering (multi-instance coordination) */
    private Long sequenceNumber;

    /** Gateway instance identifier (internal routing, stripped before sending to Agent) */
    private String gatewayInstanceId;

    // ==================== Register 消息字段 ====================

    private String deviceName;
    private String macAddress;
    private String os;
    private String toolType;
    private String toolVersion;

    /** Register rejection reason */
    private String reason;

    // ==================== Session/Status 字段 ====================

    private String toolSessionId;
    private JsonNode session;

    /** OpenCode online status (status_response) */
    private Boolean opencodeOnline;

    // ==================== 类型判断便利方法 ====================

    public boolean isType(String expected) {
        return expected != null && expected.equals(this.type);
    }

    // ==================== 静态工厂方法 ====================

    public static GatewayMessage register(String deviceName, String macAddress,
            String os, String toolType, String toolVersion) {
        return GatewayMessage.builder()
                .type(Type.REGISTER)
                .deviceName(deviceName)
                .macAddress(macAddress)
                .os(os)
                .toolType(toolType)
                .toolVersion(toolVersion)
                .build();
    }

    public static GatewayMessage registerOk() {
        return GatewayMessage.builder()
                .type(Type.REGISTER_OK)
                .build();
    }

    public static GatewayMessage registerRejected(String reason) {
        return GatewayMessage.builder()
                .type(Type.REGISTER_REJECTED)
                .reason(reason)
                .build();
    }

    public static GatewayMessage heartbeat() {
        return GatewayMessage.builder()
                .type(Type.HEARTBEAT)
                .build();
    }

    public static GatewayMessage toolEvent(String toolSessionId, JsonNode event) {
        return GatewayMessage.builder()
                .type(Type.TOOL_EVENT)
                .toolSessionId(toolSessionId)
                .event(event)
                .build();
    }

    public static GatewayMessage toolDone(String toolSessionId, JsonNode usage) {
        return GatewayMessage.builder()
                .type(Type.TOOL_DONE)
                .toolSessionId(toolSessionId)
                .usage(usage)
                .build();
    }

    public static GatewayMessage toolError(String toolSessionId, String error) {
        return GatewayMessage.builder()
                .type(Type.TOOL_ERROR)
                .toolSessionId(toolSessionId)
                .error(error)
                .build();
    }

    public static GatewayMessage sessionCreated(String welinkSessionId, String toolSessionId) {
        return GatewayMessage.builder()
                .type(Type.SESSION_CREATED)
                .welinkSessionId(welinkSessionId)
                .toolSessionId(toolSessionId)
                .build();
    }

    public static GatewayMessage agentOnline(String ak, String toolType, String toolVersion) {
        return GatewayMessage.builder()
                .type(Type.AGENT_ONLINE)
                .ak(ak)
                .toolType(toolType)
                .toolVersion(toolVersion)
                .build();
    }

    public static GatewayMessage agentOffline(String ak) {
        return GatewayMessage.builder()
                .type(Type.AGENT_OFFLINE)
                .ak(ak)
                .build();
    }

    public static GatewayMessage invoke(String ak, String welinkSessionId,
            String action, JsonNode payload) {
        return GatewayMessage.builder()
                .type(Type.INVOKE)
                .ak(ak)
                .welinkSessionId(welinkSessionId)
                .action(action)
                .payload(payload)
                .build();
    }

    public static GatewayMessage statusQuery() {
        return GatewayMessage.builder()
                .type(Type.STATUS_QUERY)
                .build();
    }

    // ==================== 不可变转换方法 ====================

    public GatewayMessage withAgentId(String agentId) {
        return this.toBuilder()
                .agentId(agentId)
                .build();
    }

    public GatewayMessage withAk(String ak) {
        return this.toBuilder()
                .ak(ak)
                .build();
    }

    public GatewayMessage withSequenceNumber(Long sequenceNumber) {
        return this.toBuilder()
                .sequenceNumber(sequenceNumber)
                .build();
    }

    public GatewayMessage withUserId(String userId) {
        return this.toBuilder()
                .userId(userId)
                .build();
    }

    public GatewayMessage withSource(String source) {
        return this.toBuilder()
                .source(source)
                .build();
    }

    public GatewayMessage withTraceId(String traceId) {
        return this.toBuilder()
                .traceId(traceId)
                .build();
    }

    public GatewayMessage withoutUserId() {
        return this.toBuilder()
                .userId(null)
                .build();
    }

    public GatewayMessage withoutSource() {
        return this.toBuilder()
                .source(null)
                .build();
    }

    public GatewayMessage withGatewayInstanceId(String gatewayInstanceId) {
        return this.toBuilder()
                .gatewayInstanceId(gatewayInstanceId)
                .build();
    }

    public GatewayMessage withoutRoutingContext() {
        return this.toBuilder()
                .userId(null)
                .source(null)
                .gatewayInstanceId(null)
                .build();
    }

    /**
     * 确保消息拥有 traceId，若缺失则生成 UUID。
     * 集中管理 traceId 生成逻辑，避免各 Service 中重复实现。
     */
    public GatewayMessage ensureTraceId() {
        if (this.traceId != null && !this.traceId.isBlank()) {
            return this;
        }
        return this.withTraceId(UUID.randomUUID().toString());
    }
}
