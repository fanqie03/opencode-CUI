package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 外部入站统一请求 DTO。
 * 固定信封 + 灵活 payload，与 GatewayMessage 风格一致。
 */
@Data
public class ExternalInvokeRequest {

    /** 动作类型：chat / question_reply / permission_reply / rebuild */
    private String action;

    /** 业务域（需与 WS source 一致） */
    private String businessDomain;

    /** 会话类型：group / direct */
    private String sessionType;

    /** 业务侧会话 ID */
    private String sessionId;

    /** 助手账号 */
    private String assistantAccount;

    /** 本次消息/回复/重建的发起用户账号（信封必填） */
    private String senderUserAccount;

    /** action 专属数据 */
    private JsonNode payload;

    // ==================== Payload 便捷访问 ====================

    /**
     * 从 payload 中安全提取字符串字段。
     *
     * @param field 字段名
     * @return 字段值，不存在或为 null 时返回 null
     */
    public String payloadString(String field) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return null;
        }
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
