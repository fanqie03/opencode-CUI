package com.opencode.cui.skill.telemetry.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 第三方接口调用埋码服务。
 * 记录 3 个 Counter + 1 个 Timer，URL 去 query 参数，失败时写 [EXT_CALL] ERROR 日志。
 */
@Slf4j
@Service
public class ApiCallMetricsService {

    private final MeterRegistry meterRegistry;

    public ApiCallMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordApiCall(MetricServiceEnum service, String url, boolean success, long durationMs) {
        // 去 query 参数
        String cleanUrl = url;
        int q = cleanUrl.indexOf('?');
        if (q >= 0) {
            cleanUrl = cleanUrl.substring(0, q);
        }

        Tags tags = Tags.of(
            "serviceId", service.getId(),
            "serviceComment", service.getComment(),
            "url", cleanUrl
        );

        meterRegistry.counter("external_api_call_total", tags).increment();
        meterRegistry.counter(success
            ? "external_api_call_success_total"
            : "external_api_call_failure_total", tags).increment();
        meterRegistry.timer("external_api_call_duration_seconds", tags)
            .record(durationMs, TimeUnit.MILLISECONDS);

        if (!success) {
            log.error("[EXT_CALL] {} failed: durationMs={}", service.getId(), durationMs);
        }
    }
}