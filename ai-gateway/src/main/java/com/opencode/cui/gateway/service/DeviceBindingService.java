package com.opencode.cui.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * AK 设备绑定校验服务。
 * 校验 AK 是否绑定到指定设备（MAC 地址）和工具类型，通过第三方服务进行验证。
 *
 * <p>
 * 失败开放策略：第三方服务不可用时，允许连接并记录警告日志。
 * </p>
 */
@Slf4j
@Service
public class DeviceBindingService {

    @Value("${gateway.device-binding.url:}")
    private String bindingServiceUrl;

    @Value("${gateway.device-binding.timeout-ms:3000}")
    private int timeoutMs;

    @Value("${gateway.device-binding.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;

    public DeviceBindingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 校验 AK 是否绑定到指定 MAC 地址和工具类型。
     *
     * @param ak         Access Key ID
     * @param macAddress 设备 MAC 地址
     * @param toolType   工具类型（如 OPENCODE）
     * @return 校验通过或服务不可用（失败开放）时返回 true
     */
    public boolean validate(String ak, String macAddress, String toolType) {
        if (!enabled) {
            log.debug("Device binding validation disabled, allowing: ak={}", ak);
            return true;
        }

        if (bindingServiceUrl == null || bindingServiceUrl.isBlank()) {
            log.warn("Device binding service URL not configured, fail-open: ak={}", ak);
            return true;
        }

        long start = System.nanoTime();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of(
                    "ak", ak,
                    "macAddress", macAddress,
                    "toolType", toolType);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    bindingServiceUrl, request, Map.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (response != null && Boolean.TRUE.equals(response.get("valid"))) {
                log.info("[EXT_CALL] DeviceBinding.validate success: ak={}, toolType={}, durationMs={}",
                        ak, toolType, elapsedMs);
                return true;
            }

            String message = response != null ? String.valueOf(response.get("message")) : "unknown";
            log.warn("Device binding validation failed: ak={}, mac={}, toolType={}, reason={}",
                    ak, macAddress, toolType, message);
            return false;

        } catch (Exception e) {
            log.warn("Device binding service unavailable, fail-open: ak={}, error={}",
                    ak, e.getMessage());
            return true; // 失败开放：服务不可用时允许连接
        }
    }
}
