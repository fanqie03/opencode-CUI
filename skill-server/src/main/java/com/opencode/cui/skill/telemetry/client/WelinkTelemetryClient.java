package com.opencode.cui.skill.telemetry.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.telemetry.client.dto.EncryptedEnvelope;
import com.opencode.cui.skill.telemetry.client.dto.TelemetryPayload;
import com.opencode.cui.skill.telemetry.config.WelinkTelemetryProperties;
import com.opencode.cui.skill.telemetry.crypto.WelinkCipherUtil;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import com.opencode.cui.skill.telemetry.metrics.MetricServiceEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * WeLink 上报 HTTP 客户端：POST {url}，body=加密信封 {@code {key, content}}。
 *
 * <p>
 * 请求头：
 * Authorization: Bearer token
 * x-wlk-hwa: 1
 * Content-Type: application/json
 * </p>
 *
 * <p>
 * 所有异常都 catch + WARN，不抛回业务线程。
 * </p>
 */
@Slf4j
public class WelinkTelemetryClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final WelinkTelemetryProperties properties;
    private final ApiCallMetricsService apiCallMetricsService;

    public WelinkTelemetryClient(RestTemplate restTemplate,
                                 ObjectMapper objectMapper,
                                 WelinkTelemetryProperties properties,
                                 ApiCallMetricsService apiCallMetricsService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.apiCallMetricsService = apiCallMetricsService;
    }

    /**
     * 发送一条业务 payload；内部完成 RSA+AES 加密 → POST。
     *
     * @param eventId   仅用于失败日志关联（不参与发送本身）
     * @param sessionId 仅用于失败日志关联
     * @param payload   明文 {@link TelemetryPayload}
     */
    public void send(String eventId, String sessionId, TelemetryPayload payload) {
        log.info("[WelinkTelemetry] send entry: eventId={}, sessionId={}", eventId, sessionId);
        String urlTemplate = "{telemetry.welink.url}";
        MetricServiceEnum metricService = MetricServiceEnum.TELEMETRY_WELINK_UPLOAD;
        boolean success = false;
        long start = System.currentTimeMillis();
        try {
            MdcHelper.putBusinessDomain(metricService.getId());
            String plaintext = objectMapper.writeValueAsString(payload);
            WelinkCipherUtil.Envelope envelope = WelinkCipherUtil.encrypt(properties.getPublicKey(), plaintext);
            EncryptedEnvelope body = new EncryptedEnvelope(envelope.key(), envelope.content());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + properties.getToken());
            headers.set("x-wlk-hwa", "1");

            HttpEntity<EncryptedEnvelope> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    properties.getUrl(), HttpMethod.POST, entity, String.class);

            success = true;
            long elapsedMs = (System.currentTimeMillis() - start);
            int code = response.getStatusCode().value();
            log.info("[EXT_CALL] WelinkTelemetry.send completed: eventId={}, sessionId={}, httpCode={}, body={}, durationMs={}",
                    eventId, sessionId, code, response.getBody(), elapsedMs);
        } catch (WelinkCipherUtil.CipherException e) {
            long elapsedMs = (System.currentTimeMillis() - start);
            log.warn("[EXT_CALL] WelinkTelemetry.send cipher_failed: eventId={}, sessionId={}, durationMs={}, error={}",
                    eventId, sessionId, elapsedMs, e.getMessage());
        } catch (Exception e) {
            long elapsedMs = (System.currentTimeMillis() - start);
            // 业务核心不变量：上报链路任何异常都不得抛回业务线程
            Integer httpCode = null;
            if (e instanceof org.springframework.web.client.HttpStatusCodeException sc) {
                httpCode = sc.getStatusCode().value();
            }
            log.warn("[EXT_CALL] WelinkTelemetry.send http_failed: eventId={}, sessionId={}, httpCode={}, durationMs={}, error={}",
                    eventId, sessionId, httpCode, elapsedMs, e.getMessage());
        } finally {
            apiCallMetricsService.recordApiCall(metricService, urlTemplate, success, System.currentTimeMillis() - start);
            MdcHelper.putBusinessDomain(null);
        }
    }
}
