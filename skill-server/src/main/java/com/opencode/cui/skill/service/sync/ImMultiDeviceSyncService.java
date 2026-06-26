package com.opencode.cui.skill.service.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.MultiSyncProperties;
import com.opencode.cui.skill.model.AppNotifyData;
import com.opencode.cui.skill.model.AppNotifyRequest;
import com.opencode.cui.skill.model.ImAppNotifyResponse;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class ImMultiDeviceSyncService implements MultiDeviceSyncService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MultiSyncProperties syncProperties;
    private final String imToken;

    public ImMultiDeviceSyncService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            MultiSyncProperties syncProperties,
            @Value("${skill.im.token:}") String imToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.syncProperties = syncProperties;
        this.imToken = imToken;
    }

    @Override
    public SyncMode getSyncMode() {
        return SyncMode.IM;
    }

    @Override
    public void push(SyncRequest request) {
        MultiSyncProperties.Im.AppNotify appNotify = syncProperties.getIm().getAppNotify();
        String appNotifyUrl = appNotify.getUrl();
        if (appNotifyUrl == null || appNotifyUrl.isBlank()) {
            log.warn("[SKIP] ImMultiDeviceSync.push: reason=app_notify_url_not_configured, type={}",
                    request.syncType().getType());
            return;
        }

        List<String> accounts = (request.targetAccount() != null && !request.targetAccount().isBlank())
                ? List.of(request.targetAccount()) : null;

        String notifyDataJson;
        try {
            AppNotifyData notifyData = new AppNotifyData(
                    request.syncType().getType(), request.syncContent());
            notifyDataJson = objectMapper.writeValueAsString(notifyData);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notify_data: type={}, error={}",
                    request.syncType().getType(), e.getMessage());
            return;
        }

        AppNotifyRequest body = new AppNotifyRequest(
                UUID.randomUUID().toString(),
                appNotify.getScope(),
                appNotify.getTenant(),
                accounts,
                appNotify.getModule(),
                notifyDataJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (imToken != null && !imToken.isBlank()) {
            headers.setBearerAuth(imToken);
        }

        long start = System.nanoTime();
        try {
            ResponseEntity<ImAppNotifyResponse> response = restTemplate.postForEntity(
                    appNotifyUrl,
                    new HttpEntity<>(body, headers),
                    ImAppNotifyResponse.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("[EXT_CALL] ImMultiDeviceSync.push HTTP error: type={}, status={}, durationMs={}",
                        request.syncType().getType(), response.getStatusCode(), elapsedMs);
                return;
            }
            ImAppNotifyResponse respBody = response.getBody();
            if (respBody == null) {
                log.warn("[EXT_CALL] ImMultiDeviceSync.push empty body: type={}, durationMs={}",
                        request.syncType().getType(), elapsedMs);
                return;
            }
            log.info("[EXT_CALL] ImMultiDeviceSync.push: type={}, clientNotifyId={}, serverNotifyId={}, errorCode={}, errorMsg={}, invalidAccount={}, durationMs={}",
                    request.syncType().getType(),
                    respBody.clientNotifyId(),
                    respBody.serverNotifyId(),
                    respBody.error() != null ? respBody.error().errorCode() : null,
                    respBody.error() != null ? respBody.error().errorMsg() : null,
                    respBody.invalidAccount(),
                    elapsedMs);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] ImMultiDeviceSync.push failed: type={}, durationMs={}, error={}",
                    request.syncType().getType(), elapsedMs, e.getMessage());
        }
    }

}
