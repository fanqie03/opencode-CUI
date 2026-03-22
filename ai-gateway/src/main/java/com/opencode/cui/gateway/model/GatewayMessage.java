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
 * <p>
 * 本类作为所有消息类型的统一载体（flat DTO），通过 {@code type} 字段区分语义。
 * 不同消息类型使用不同的字段子集，未使用的字段序列化时为 null（JsonInclude.NON_NULL）。
 * </p>
 *
 * <h3>消息类型与字段映射矩阵</h3>
 * 
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
 * <li>{@code ak} — Agent AK，消息路由的主键</li>
 * <li>{@code welinkSessionId} — Skill 侧会话 ID</li>
 * <li>{@code toolSessionId} — OpenCode 侧会话 ID</li>
 * <li>{@code userId} / {@code source} — 服务端注入的路由上下文，下行时剥离</li>
 * <li>{@code traceId} — 跨服务追踪 ID</li>
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
        String REGISTER = "register";
        String REGISTER_OK = "register_ok";
        String REGISTER_REJECTED = "register_rejected";
        String HEARTBEAT = "heartbeat";
        String INVOKE = "invoke";
        String TOOL_EVENT = "tool_event";
        String TOOL_DONE = "tool_done";
        String TOOL_ERROR = "tool_error";
        String SESSION_CREATED = "session_created";
        String AGENT_ONLINE = "agent_online";
        String AGENT_OFFLINE = "agent_offline";
        String STATUS_QUERY = "status_query";
        String STATUS_RESPONSE = "status_response";
        String PERMISSION_REQUEST = "permission_request";
    }

    // ==================== 通用字段 ====================

    /** 消息类型标识，取值见 {@link Type} 常量 */
    private String type;

    /** 遗留/内部数据库标识符；协议路由已改用 ak */
    private String agentId;

    /** Agent 的应用密钥（AK），用于消息路由 */
    private String ak;

    /** Skill 侧会话 ID（String 类型，防止 JS 精度丢失） */
    private String welinkSessionId;

    /** 用户 ID（服务端注入，用于路由信任） */
    private String userId;

    /** 上游来源服务标识 */
    private String source;

    /** 跨服务追踪 ID，用于链路可观测性 */
    private String traceId;

    // ==================== Invoke 字段 ====================

    /** Invoke 动作类型：chat、create_session、close_session 等 */
    private String action;

    /** Invoke 或 Register 消息的载荷数据 */
    private JsonNode payload;

    // ==================== Tool 事件字段 ====================

    /** OpenCode 原始事件（透传中继） */
    private JsonNode event;

    /** 错误描述信息 */
    private String error;

    /** Token 用量信息 */
    private JsonNode usage;

    // ==================== 多实例协调字段 ====================

    /** 消息序号，用于多实例间的消息排序 */
    private Long sequenceNumber;

    /** Gateway 实例 ID（内部路由用，发送给 Agent 前剥离） */
    private String gatewayInstanceId;

    // ==================== Register 消息字段 ====================

    private String deviceName;
    private String macAddress;
    private String os;
    private String toolType;
    private String toolVersion;

    /** 注册拒绝原因 */
    private String reason;

    // ==================== Session/Status 字段 ====================

    private String toolSessionId;
    private JsonNode session;

    /** OpenCode 是否在线（status_response 消息使用） */
    private Boolean opencodeOnline;

    // ==================== 类型判断便利方法 ====================

    /**
     * 判断消息类型是否匹配。
     *
     * @param expected 预期的消息类型
     * @return 类型匹配返回 true
     */
    public boolean isType(String expected) {
        return expected != null && expected.equals(this.type);
    }

    // ==================== 静态工厂方法 ====================

    /** 创建 REGISTER（Agent 注册）消息 */
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

    /** 创建 REGISTER_OK（注册成功确认）消息 */
    public static GatewayMessage registerOk() {
        return GatewayMessage.builder()
                .type(Type.REGISTER_OK)
                .build();
    }

    /** 创建 REGISTER_REJECTED（注册被拒）消息 */
    public static GatewayMessage registerRejected(String reason) {
        return GatewayMessage.builder()
                .type(Type.REGISTER_REJECTED)
                .reason(reason)
                .build();
    }

    /** 创建 HEARTBEAT（心跳）消息 */
    public static GatewayMessage heartbeat() {
        return GatewayMessage.builder()
                .type(Type.HEARTBEAT)
                .build();
    }

    /** 创建 TOOL_EVENT（工具事件流）消息 */
    public static GatewayMessage toolEvent(String toolSessionId, JsonNode event) {
        return GatewayMessage.builder()
                .type(Type.TOOL_EVENT)
                .toolSessionId(toolSessionId)
                .event(event)
                .build();
    }

    /** 创建 TOOL_DONE（工具执行完成）消息 */
    public static GatewayMessage toolDone(String toolSessionId, JsonNode usage) {
        return GatewayMessage.builder()
                .type(Type.TOOL_DONE)
                .toolSessionId(toolSessionId)
                .usage(usage)
                .build();
    }

    /** 创建 TOOL_ERROR（工具执行错误）消息 */
    public static GatewayMessage toolError(String toolSessionId, String error) {
        return GatewayMessage.builder()
                .type(Type.TOOL_ERROR)
                .toolSessionId(toolSessionId)
                .error(error)
                .build();
    }

    /** 创建 SESSION_CREATED（会话创建完成）消息 */
    public static GatewayMessage sessionCreated(String welinkSessionId, String toolSessionId) {
        return GatewayMessage.builder()
                .type(Type.SESSION_CREATED)
                .welinkSessionId(welinkSessionId)
                .toolSessionId(toolSessionId)
                .build();
    }

    /** 创建 AGENT_ONLINE（Agent 上线通知）消息 */
    public static GatewayMessage agentOnline(String ak, String toolType, String toolVersion) {
        return GatewayMessage.builder()
                .type(Type.AGENT_ONLINE)
                .ak(ak)
                .toolType(toolType)
                .toolVersion(toolVersion)
                .build();
    }

    /** 创建 AGENT_OFFLINE（Agent 离线通知）消息 */
    public static GatewayMessage agentOffline(String ak) {
        return GatewayMessage.builder()
                .type(Type.AGENT_OFFLINE)
                .ak(ak)
                .build();
    }

    /** 创建 INVOKE（调用指令）消息 */
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

    /** 创建 STATUS_QUERY（状态查询）消息 */
    public static GatewayMessage statusQuery() {
        return GatewayMessage.builder()
                .type(Type.STATUS_QUERY)
                .build();
    }

    // ==================== 不可变转换方法 ====================

    /** 复制消息并设置 agentId */
    public GatewayMessage withAgentId(String agentId) {
        return this.toBuilder()
                .agentId(agentId)
                .build();
    }

    /** 复制消息并设置 ak */
    public GatewayMessage withAk(String ak) {
        return this.toBuilder()
                .ak(ak)
                .build();
    }

    /** 复制消息并设置序号 */
    public GatewayMessage withSequenceNumber(Long sequenceNumber) {
        return this.toBuilder()
                .sequenceNumber(sequenceNumber)
                .build();
    }

    /** 复制消息并设置 userId */
    public GatewayMessage withUserId(String userId) {
        return this.toBuilder()
                .userId(userId)
                .build();
    }

    /** 复制消息并设置来源标识 */
    public GatewayMessage withSource(String source) {
        return this.toBuilder()
                .source(source)
                .build();
    }

    /** 复制消息并设置 traceId */
    public GatewayMessage withTraceId(String traceId) {
        return this.toBuilder()
                .traceId(traceId)
                .build();
    }

    /** 复制消息并清除 userId（下行时剥离服务端上下文） */
    public GatewayMessage withoutUserId() {
        return this.toBuilder()
                .userId(null)
                .build();
    }

    /** 复制消息并清除 source */
    public GatewayMessage withoutSource() {
        return this.toBuilder()
                .source(null)
                .build();
    }

    /** 复制消息并设置 Gateway 实例 ID */
    public GatewayMessage withGatewayInstanceId(String gatewayInstanceId) {
        return this.toBuilder()
                .gatewayInstanceId(gatewayInstanceId)
                .build();
    }

    /** 复制消息并清除所有路由上下文（userId/source/gatewayInstanceId），用于下发给 Agent */
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
