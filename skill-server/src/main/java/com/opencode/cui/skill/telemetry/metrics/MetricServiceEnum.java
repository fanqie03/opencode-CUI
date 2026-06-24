package com.opencode.cui.skill.telemetry.metrics;

import lombok.Getter;

/**
 * 第三方服务枚举 — 按具体接口枚举（非服务粗粒度）。
 * id 为接口级 snake_case 标识，同时用作 Prometheus tag 和慧眼告警 MDC businessDomain 值。
 */
@Getter
public enum MetricServiceEnum {
    // IM（3 个端点）
    IM_GROUP_CHAT("im_group_chat", "群聊消息发送"),
    IM_DIRECT_CHAT("im_direct_chat", "单聊消息发送"),
    IM_MESSAGE_SEND("im_message_send", "IM 消息发送"),

    // Gateway（6 个端点：3 REST + 3 WS）
    GATEWAY_AGENTS_LIST("gateway_agents_list", "查询在线 Agent 列表"),
    GATEWAY_AGENTS_BY_AK("gateway_agents_by_ak", "按 AK 查询 Agent"),
    GATEWAY_AGENT_AVAILABILITY("gateway_agent_availability", "查询 Agent 可及性"),
    GATEWAY_WS_INVOKE("gateway_ws_invoke", "Gateway WS invoke 指令"),
    GATEWAY_WS_ROUTE_CONFIRM("gateway_ws_route_confirm", "Gateway WS 路由确认"),
    GATEWAY_WS_ROUTE_REJECT("gateway_ws_route_reject", "Gateway WS 路由拒绝"),

    // 业务中心（3 个端点）
    BUSINESS_CENTER_ASSISTANT_INFO("business_center_assistant_info", "查询助手信息"),
    BUSINESS_CENTER_INSTANCE_QUERY("business_center_instance_query", "查询助手实例"),
    BUSINESS_CENTER_PERSONA_QUERY("business_center_persona_query", "查询 Persona"),

    // 埋码上报（1 个端点）
    TELEMETRY_WELINK_UPLOAD("telemetry_welink_upload", "WeLink 埋码上报");

    private final String id;
    private final String comment;

    MetricServiceEnum(String id, String comment) {
        this.id = id;
        this.comment = comment;
    }
}