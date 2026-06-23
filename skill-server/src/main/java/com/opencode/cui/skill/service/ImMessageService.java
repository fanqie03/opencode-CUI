package com.opencode.cui.skill.service;

import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import com.opencode.cui.skill.telemetry.metrics.MetricServiceEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 消息发送服务。
 * 通过平台 REST API 向 IM 聊天发送文本消息。
 */
@Slf4j
@Service
public class ImMessageService {

    private final RestTemplate restTemplate;
    /** IM 平台 API 根地址 */
    private final String imApiUrl;
    private final ApiCallMetricsService apiCallMetricsService;

    public ImMessageService(RestTemplate restTemplate,
            @Value("${skill.im.api-url}") String imApiUrl,
            ApiCallMetricsService apiCallMetricsService) {
        this.restTemplate = restTemplate;
        this.imApiUrl = imApiUrl;
        this.apiCallMetricsService = apiCallMetricsService;
    }

    /**
     * 向 IM 聊天发送文本消息。
     *
     * <p>body 字段：{@code { targetType, targetId, senderAccount, content, msgType: "text" }}。
     *
     * @param targetType    目标类型（{@code group} / {@code direct}）
     * @param targetId      目标会话 ID（群 ID 或私聊对方账号）
     * @param senderAccount 发送人账号（与 cookie userId 一致）
     * @param content       文本内容
     * @param msgExt        扩展字段 JSON 字符串（可为 null，非空时加入 body 的 msg_ext 字段）
     * @return 发送成功返回 true；任一参数为空 / 下游非 2xx / 抛异常返回 false
     */
    public boolean sendMessage(String targetType, String targetId, String senderAccount, String content, String msgExt) {
        if (targetType == null || targetType.isBlank()) {
            log.warn("Cannot send IM message: targetType is empty");
            return false;
        }
        if (targetId == null || targetId.isBlank()) {
            log.warn("Cannot send IM message: targetId is empty");
            return false;
        }
        if (senderAccount == null || senderAccount.isBlank()) {
            log.warn("Cannot send IM message: senderAccount is empty");
            return false;
        }
        if (content == null || content.isBlank()) {
            log.warn("Cannot send IM message: content is empty");
            return false;
        }

        String urlTemplate = "/messages/send";
        String sendUrl = imApiUrl + urlTemplate;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("senderAccount", senderAccount);
        body.put("content", content);
        body.put("msgType", "text");

        if (msgExt != null && !msgExt.isBlank()) {
            body.put("msg_ext", msgExt);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        MetricServiceEnum metricService = MetricServiceEnum.IM_MESSAGE_SEND;
        boolean success = false;
        long start = System.currentTimeMillis();
        try {
            MdcHelper.putBusinessDomain(metricService.getId());
            ResponseEntity<String> response = com.opencode.cui.skill.logging.LogTimer.timed(
                    log,
                    "ImMessage.send(targetType=" + targetType + ",targetId=" + targetId + ")",
                    () -> restTemplate.postForEntity(sendUrl, request, String.class));
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("IM message sent successfully: targetType={}, targetId={}, senderAccount={}, contentLength={}",
                        targetType, targetId, senderAccount, content.length());
                success = true;
                return true;
            } else {
                log.error("IM message send failed: targetType={}, targetId={}, status={}",
                        targetType, targetId, response.getStatusCode());
                return false;
            }
        } catch (RestClientException e) {
            log.error("IM message send error: targetType={}, targetId={}, error={}",
                    targetType, targetId, e.getMessage());
            return false;
        } finally {
            apiCallMetricsService.recordApiCall(metricService, urlTemplate, success, System.currentTimeMillis() - start);
            MdcHelper.putBusinessDomain(null);
        }
    }
}
