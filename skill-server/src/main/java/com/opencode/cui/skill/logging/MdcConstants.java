package com.opencode.cui.skill.logging;

import java.util.List;

/**
 * MDC key 常量定义。
 *
 * <p>与 logback-spring.xml 中的 {@code %X{traceId}} 等占位符对应。
 * 所有自定义 MDC key 都必须在此定义，禁止在业务代码中直接使用字符串字面量。</p>
 */
public final class MdcConstants {

    private MdcConstants() {
        // 不可实例化
    }

    /** 跨服务请求追踪 ID */
    public static final String TRACE_ID = "traceId";

    /** 会话 ID（welinkSessionId），会话级关联 */
    public static final String SESSION_ID = "sessionId";

    /** Agent Key，Agent 级关联 */
    public static final String AK = "ak";

    /** 用户标识 */
    public static final String USER_ID = "userId";

    /** 场景标识（ws-gateway, rest-im, rest-miniapp 等） */
    public static final String SCENARIO = "scenario";

    /** 业务域标识（接口级 serviceId，如 im_group_chat、gateway_ws_invoke）— 慧眼告警用 */
    public static final String BUSINESS_DOMAIN = "businessDomain";

/** 所有自定义 MDC key 列表，用于批量清理 */
    public static final List<String> ALL_KEYS = List.of(
            TRACE_ID, SESSION_ID, AK, USER_ID, SCENARIO, BUSINESS_DOMAIN
    );
}
