package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.logging.SensitiveDataMasker;
import com.opencode.cui.skill.model.AssistantInstanceInfo;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import com.opencode.cui.skill.telemetry.metrics.MetricServiceEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;

/**
 * 数字分身实例信息查询服务。
 *
 * <p>按 {@code partnerAccount/assistantAccount} 查询 instance/query 接口，并缓存完整 data。
 * UNKNOWN / NOT_EXISTS 不写实例缓存，避免把临时故障或空数据固化到热路径。</p>
 */
@Slf4j
@Service
public class AssistantInstanceInfoService {

    private static final String CACHE_KEY_PREFIX = "ss:assistant:instance:";

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String queryUrl;
    private final String queryToken;
    private final int cacheTtlSeconds;
    private final ApiCallMetricsService apiCallMetricsService;

    public AssistantInstanceInfoService(RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${skill.assistant.resolve-url:}") String queryUrl,
            @Value("${skill.assistant.resolve-token:}") String queryToken,
            @Value("${skill.assistant.instance-cache-ttl-seconds:${skill.assistant.status-cache-ttl-exists-seconds:300}}") int cacheTtlSeconds,
            ApiCallMetricsService apiCallMetricsService) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.queryUrl = queryUrl;
        this.queryToken = queryToken;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.apiCallMetricsService = apiCallMetricsService;
    }

    public AssistantInstanceInfo getInstanceInfo(String partnerAccount) {
        LookupResult result = lookup(partnerAccount);
        return result.status() == ExistenceStatus.EXISTS ? result.info() : null;
    }

    public LookupResult lookup(String partnerAccount) {
        if (partnerAccount == null || partnerAccount.isBlank()
                || queryUrl == null || queryUrl.isBlank()) {
            return LookupResult.unknown();
        }

        String urlTemplate = "/assistant-api/integration/v4-1/we-crew/instance/query";
        MetricServiceEnum metricService = MetricServiceEnum.BUSINESS_CENTER_INSTANCE_QUERY;
        boolean success = false;
        long start = System.currentTimeMillis();
        String masked = SensitiveDataMasker.maskToken(partnerAccount);

        String cacheKey = cacheKey(partnerAccount);
        AssistantInstanceInfo cached = readFromCache(cacheKey);
        if (cached != null) {
            log.debug("[AssistantInstance] cache hit: partnerAccount={}",
                    SensitiveDataMasker.maskToken(partnerAccount));
            return new LookupResult(ExistenceStatus.EXISTS, cached);
        }

        try {
            MdcHelper.putBusinessDomain(metricService.getId());
            String requestUrl = UriComponentsBuilder.fromUriString(queryUrl)
                    .queryParam("partnerAccount", partnerAccount)
                    .build(true)
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            applyAuthorization(headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    requestUrl, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            long elapsedMs = (System.currentTimeMillis() - start);

            LookupResult result = parseResponse(response, partnerAccount, masked, elapsedMs);
            if (result.status() == ExistenceStatus.EXISTS && result.info() != null) {
                writeCache(cacheKey, result.info());
            }
            // Success = response received without exception (even if it's not exists)
            success = true;
            return result;
        } catch (Exception e) {
            long elapsedMs = (System.currentTimeMillis() - start);
            log.warn("[EXT_CALL] AssistantInstance.query failed: partnerAccount={}, durationMs={}, error={}",
                    masked, elapsedMs, e.getMessage());
            return LookupResult.unknown();
        } finally {
            apiCallMetricsService.recordApiCall(metricService, urlTemplate, success, System.currentTimeMillis() - start);
            MdcHelper.putBusinessDomain(null);
        }
    }

    private LookupResult parseResponse(ResponseEntity<JsonNode> response,
                                       String requestedPartnerAccount,
                                       String maskedAccount,
                                       long elapsedMs) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.warn("[EXT_CALL] AssistantInstance.query non-2xx: partnerAccount={}, durationMs={}, httpStatus={}",
                    maskedAccount, elapsedMs, response != null ? response.getStatusCode() : null);
            return LookupResult.unknown();
        }
        JsonNode body = response.getBody();
        if (body == null || body.isNull()) {
            log.warn("[EXT_CALL] AssistantInstance.query empty body: partnerAccount={}, durationMs={}",
                    maskedAccount, elapsedMs);
            return LookupResult.unknown();
        }
        JsonNode codeNode = body.get("code");
        if (!isSuccessCode(codeNode)) {
            log.warn("[EXT_CALL] AssistantInstance.query business failure: partnerAccount={}, durationMs={}, bodyCode={}",
                    maskedAccount, elapsedMs, codeNode);
            return LookupResult.unknown();
        }
        JsonNode data = body.get("data");
        if (data == null || data.isNull() || data.isMissingNode()
                || (data.isObject() && data.size() == 0)) {
            log.info("[EXT_CALL] AssistantInstance.query not_exists: partnerAccount={}, durationMs={}, reason=data_empty",
                    maskedAccount, elapsedMs);
            return new LookupResult(ExistenceStatus.NOT_EXISTS, null);
        }
        try {
            AssistantInstanceInfo info = objectMapper.treeToValue(data, AssistantInstanceInfo.class);
            if (info.getPartnerAccount() == null || info.getPartnerAccount().isBlank()) {
                info.setPartnerAccount(requestedPartnerAccount);
            }
            log.info("[EXT_CALL] AssistantInstance.query success: partnerAccount={}, durationMs={}, remote={}, hasAk={}",
                    maskedAccount, elapsedMs, info.businessRoutableAssistant(), info.effectiveAk() != null);
            return new LookupResult(ExistenceStatus.EXISTS, info);
        } catch (JsonProcessingException e) {
            log.warn("[EXT_CALL] AssistantInstance.query parse failed: partnerAccount={}, durationMs={}, error={}",
                    maskedAccount, elapsedMs, e.getMessage());
            return LookupResult.unknown();
        }
    }

    private void applyAuthorization(HttpHeaders headers) {
        if (queryToken == null || queryToken.isBlank()) {
            return;
        }
        if (queryToken.regionMatches(true, 0, "Bearer ", 0, 7)
                || queryToken.contains(" ")) {
            headers.set(HttpHeaders.AUTHORIZATION, queryToken);
            return;
        }
        headers.setBearerAuth(queryToken);
    }

    private AssistantInstanceInfo readFromCache(String cacheKey) {
        try {
            String raw = redisTemplate.opsForValue().get(cacheKey);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return objectMapper.readValue(raw, AssistantInstanceInfo.class);
        } catch (Exception e) {
            log.warn("[AssistantInstance] cache read failed: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    private void writeCache(String cacheKey, AssistantInstanceInfo info) {
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(info),
                    Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("[AssistantInstance] cache write failed: key={}, error={}", cacheKey, e.getMessage());
        }
    }

    private String cacheKey(String partnerAccount) {
        return CACHE_KEY_PREFIX + partnerAccount;
    }

    private static boolean isSuccessCode(JsonNode codeNode) {
        if (codeNode == null || codeNode.isNull()) {
            return false;
        }
        if (codeNode.isInt() || codeNode.isLong()) {
            return codeNode.asInt() == 200;
        }
        return "200".equals(codeNode.asText());
    }

    public record LookupResult(ExistenceStatus status, AssistantInstanceInfo info) {
        public static LookupResult unknown() {
            return new LookupResult(ExistenceStatus.UNKNOWN, null);
        }
    }
}
