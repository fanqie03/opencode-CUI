package com.opencode.cui.skill.logging;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MDC 上下文操作工具类。
 *
 * <p>提供 traceId/sessionId/ak/userId 等字段的设置、清理、快照恢复，
 * 以及从 {@link JsonNode}（Gateway 消息 JSON）批量提取字段到 MDC 的能力。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 处理 Gateway 消息
 * try {
 *     MdcHelper.fromJsonNode(messageNode);
 *     MdcHelper.putScenario("ws-gateway-tool-event");
 *     // ... 业务逻辑
 * } finally {
 *     MdcHelper.clearAll();
 * }
 * }</pre>
 */
public final class MdcHelper {

    private MdcHelper() {
        // 不可实例化
    }

    // --- 单字段设置 ---

    public static void putTraceId(String value) {
        safePut(MdcConstants.TRACE_ID, value);
    }

    public static void putSessionId(String value) {
        safePut(MdcConstants.SESSION_ID, value);
    }

    public static void putAk(String value) {
        safePut(MdcConstants.AK, value);
    }

    public static void putUserId(String value) {
        safePut(MdcConstants.USER_ID, value);
    }

    public static void putScenario(String value) {
        safePut(MdcConstants.SCENARIO, value);
    }

    public static void putBusinessDomain(String value) {
        safePut(MdcConstants.BUSINESS_DOMAIN, value);
    }

    // --- 批量操作 ---

    /**
     * 从 Gateway 消息 JSON 中提取 traceId、welinkSessionId、ak、userId 到 MDC。
     *
     * <p>Skill-Server 不直接使用 GatewayMessage 类，而是操作 JsonNode，
     * 因此此方法从 JsonNode 中按字段名提取。</p>
     *
     * @param node Gateway 消息的 JsonNode，null 安全
     */
    public static void fromJsonNode(JsonNode node) {
        if (node == null) {
            return;
        }
        safePut(MdcConstants.TRACE_ID, textOrNull(node, "traceId"));
        safePut(MdcConstants.SESSION_ID, textOrNull(node, "welinkSessionId"));
        safePut(MdcConstants.AK, textOrNull(node, "ak"));
        safePut(MdcConstants.USER_ID, textOrNull(node, "userId"));
    }

    /**
     * 清理所有自定义 MDC key，不影响其他框架设置的 key。
     */
    public static void clearAll() {
        for (String key : MdcConstants.ALL_KEYS) {
            MDC.remove(key);
        }
    }

    // --- traceId 保证 ---

    /**
     * 确保 MDC 中存在 traceId。若不存在则生成 UUID 并写入。
     *
     * @return 当前或新生成的 traceId
     */
    public static String ensureTraceId() {
        String existing = MDC.get(MdcConstants.TRACE_ID);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put(MdcConstants.TRACE_ID, generated);
        return generated;
    }

    // --- 快照 / 恢复（跨线程传播） ---

    /**
     * 捕获当前 MDC 中所有自定义 key 的快照。
     *
     * @return 包含所有自定义 MDC key 当前值的 Map
     */
    public static Map<String, String> snapshot() {
        Map<String, String> snap = new LinkedHashMap<>();
        for (String key : MdcConstants.ALL_KEYS) {
            snap.put(key, MDC.get(key));
        }
        return snap;
    }

    /**
     * 从快照恢复 MDC 上下文。
     *
     * @param snap 之前通过 {@link #snapshot()} 获取的快照，null 安全
     */
    public static void restore(Map<String, String> snap) {
        if (snap == null) {
            return;
        }
        for (Map.Entry<String, String> entry : snap.entrySet()) {
            if (entry.getValue() != null) {
                MDC.put(entry.getKey(), entry.getValue());
            } else {
                MDC.remove(entry.getKey());
            }
        }
    }

    // --- 内部方法 ---

    private static void safePut(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
            return;
        }
        MDC.remove(key);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        String text = child.asText(null);
        return (text != null && !text.isBlank()) ? text : null;
    }
}
