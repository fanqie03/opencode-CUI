package com.opencode.cui.skill.telemetry.core;

import com.opencode.cui.skill.logging.MdcConstants;
import com.opencode.cui.skill.telemetry.client.WelinkTelemetryClient;
import com.opencode.cui.skill.telemetry.client.dto.TelemetryPayload;
import com.opencode.cui.skill.telemetry.config.WelinkTelemetryProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Telemetry 上报核心：组装 payload + 提交到 {@link TelemetryExecutor}。
 *
 * <p>组装的 {@code data} 字段集合（PRD §3.1 共同字段 + §3.2 差异字段）：
 * <ul>
 *   <li>policyName / serviceName / appName / appPackageName / tenantId（配置）</li>
 *   <li>sessionId（businessSessionId）</li>
 *   <li>eventType=event / eventTime=now / traceId(MDC) / eventId / eventLabel / userId</li>
 *   <li>extendData（业务自由组装）</li>
 * </ul>
 *
 * <p>{@code effectiveEnabled=false} 时直接 return，绝不提交到 executor。
 */
@Slf4j
public class WelinkTelemetryReporter {

    private final WelinkTelemetryProperties properties;
    private final WelinkTelemetryClient client;
    private final TelemetryExecutor executor;
    private final boolean effectiveEnabled;

    public WelinkTelemetryReporter(WelinkTelemetryProperties properties,
                                   WelinkTelemetryClient client,
                                   TelemetryExecutor executor,
                                   boolean effectiveEnabled) {
        this.properties = properties;
        this.client = client;
        this.executor = executor;
        this.effectiveEnabled = effectiveEnabled;
    }

    /** 上报一个事件。任何阶段失败仅 WARN，绝不抛回业务线程。 */
    public void report(TelemetryEvent event) {
        if (!effectiveEnabled) {
            return;
        }
        if (event == null) {
            return;
        }
        log.info("[WelinkTelemetry] report entry: eventId={}, sessionId={}, userId={}",
                event.eventId(), event.sessionId(), event.userId());
        try {
            String traceId = MDC.get(MdcConstants.TRACE_ID);
            if (traceId == null) {
                traceId = "";
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("policyName", properties.getPolicyName());
            data.put("serviceName", properties.getServiceName());
            data.put("appName", properties.getAppName());
            data.put("appPackageName", properties.getAppPackageName());
            data.put("tenantId", properties.getTenantId());
            data.put("sessionId", event.sessionId());
            data.put("eventType", "event");
            data.put("eventTime", System.currentTimeMillis());
            data.put("traceId", traceId);
            data.put("eventId", event.eventId());
            data.put("eventLabel", event.eventLabel());
            data.put("userId", event.userId());
            data.put("extendData", event.extendData() == null
                    ? new LinkedHashMap<>() : event.extendData());

            TelemetryPayload payload = new TelemetryPayload(data, properties.getPolicyName());
            String eventId = event.eventId();
            String sessionId = event.sessionId();
            executor.submit(() -> client.send(eventId, sessionId, payload));
        } catch (Throwable t) {
            log.warn("[WelinkTelemetry] report assemble failed: eventId={}, sessionId={}, error={}",
                    event.eventId(), event.sessionId(), t.getMessage());
        }
    }

    /** 测试与诊断用：当前 reporter 是否真正会向下游发送。 */
    public boolean isEffectiveEnabled() {
        return effectiveEnabled;
    }
}
