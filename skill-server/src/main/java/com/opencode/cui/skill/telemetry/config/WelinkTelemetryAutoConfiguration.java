package com.opencode.cui.skill.telemetry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.telemetry.client.WelinkTelemetryClient;
import com.opencode.cui.skill.telemetry.core.TelemetryExecutor;
import com.opencode.cui.skill.telemetry.core.WelinkTelemetryReporter;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * WeLink telemetry autoconfiguration。
 *
 * <p>{@code telemetry.welink.enabled=true} 才装配 bean；
 * 装配时若 {@code url}/{@code token}/{@code publicKey}/{@code tenantId} 任一为空，
 * 启动日志 WARN 并把 reporter 标记为 effectiveEnabled=false（视同关闭）。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WelinkTelemetryProperties.class)
@ConditionalOnProperty(name = "telemetry.welink.enabled", havingValue = "true")
public class WelinkTelemetryAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    public TelemetryExecutor telemetryExecutor(WelinkTelemetryProperties properties) {
        return new TelemetryExecutor(properties.getExecutor());
    }

     @Bean
     public WelinkTelemetryClient welinkTelemetryClient(RestTemplate restTemplate,
                                                        ObjectMapper objectMapper,
                                                        WelinkTelemetryProperties properties,
                                                        ApiCallMetricsService apiCallMetricsService) {
         return new WelinkTelemetryClient(restTemplate, objectMapper, properties, apiCallMetricsService);
     }

    @Bean
    public WelinkTelemetryReporter welinkTelemetryReporter(WelinkTelemetryProperties properties,
                                                           WelinkTelemetryClient client,
                                                           TelemetryExecutor executor) {
        boolean effectiveEnabled = validate(properties);
        if (!effectiveEnabled) {
            log.warn("[WelinkTelemetry] enabled=true but required config missing "
                    + "(url/token/publicKey/tenantId) - reporter will be silently disabled");
        } else {
            log.info("[WelinkTelemetry] reporter enabled: url={}, serviceName={}, tenantId={}",
                    properties.getUrl(), properties.getServiceName(), properties.getTenantId());
        }
        return new WelinkTelemetryReporter(properties, client, executor, effectiveEnabled);
    }

    private boolean validate(WelinkTelemetryProperties p) {
        return notBlank(p.getUrl())
                && notBlank(p.getToken())
                && notBlank(p.getPublicKey())
                && notBlank(p.getTenantId());
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
