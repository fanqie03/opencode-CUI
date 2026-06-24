package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantInfoProperties;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.AssistantInstanceInfo;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import com.opencode.cui.skill.telemetry.metrics.MetricServiceEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * AssistantInfo 查询服务。
 *
 * 功能：根据 ak 查询助手类型（business/personal），含 Redis 缓存。
 *
 * 缓存策略：
 * - Redis: key=ss:assistant:info:{ak}，value=JSON，TTL 可配置（默认 300s）
 *
 * 降级策略：
 * - 上游不可用时 getAssistantInfo 返回 null，getCachedScope 返回 "personal"
 *
 * identityType 映射：
 * - "3" → business
 * - "2" → personal
 */
@Slf4j
@Service
public class AssistantInfoService {

    private static final String CACHE_KEY_PREFIX = "ss:assistant:info:";

    /** identityType 值常量 */
    private static final String IDENTITY_TYPE_BUSINESS = "3";

    private final AssistantInfoProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AssistantInstanceInfoService assistantInstanceInfoService;
    private final ApiCallMetricsService apiCallMetricsService;

    @Autowired
    public AssistantInfoService(AssistantInfoProperties properties,
                                StringRedisTemplate redisTemplate,
                                AssistantInstanceInfoService assistantInstanceInfoService,
                                ApiCallMetricsService apiCallMetricsService) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.assistantInstanceInfoService = assistantInstanceInfoService;
        this.apiCallMetricsService = apiCallMetricsService;
    }

    public AssistantInfoService(AssistantInfoProperties properties,
                                StringRedisTemplate redisTemplate,
                                ApiCallMetricsService apiCallMetricsService) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.assistantInstanceInfoService = null;
        this.apiCallMetricsService = apiCallMetricsService;
    }

    /**
     * 获取 AssistantInfo。先查 Redis 缓存，miss 则调用上游 API 并写缓存。
     *
     * @param ak Agent 应用密钥
     * @return AssistantInfo，上游不可用时返回 null
     */
    public AssistantInfo getAssistantInfo(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        String cacheKey = buildCacheKey(ak);

        // 1. 查询 Redis 缓存
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                log.debug("[AssistantInfoService] cache hit: ak={}", ak);
                return objectMapper.readValue(cached, AssistantInfo.class);
            }
        } catch (Exception e) {
            log.warn("[AssistantInfoService] cache read error: ak={}, error={}", ak, e.getMessage());
        }

        // 2. 调用上游 API
        log.debug("[AssistantInfoService] cache miss, fetching from upstream: ak={}", ak);
        AssistantInfo info;
        try {
            info = fetchFromUpstream(ak);
        } catch (Exception e) {
            log.warn("[AssistantInfoService] upstream fetch failed: ak={}, error={}", ak, e.getMessage());
            return null;
        }

        if (info == null) {
            log.warn("[AssistantInfoService] upstream returned null: ak={}", ak);
            return null;
        }

        // 3. 写入 Redis 缓存
        try {
            String json = objectMapper.writeValueAsString(info);
            redisTemplate.opsForValue().set(cacheKey, json,
                    Duration.ofSeconds(properties.getCacheTtlSeconds()));
            log.debug("[AssistantInfoService] cached: ak={}, scope={}", ak, info.getAssistantScope());
        } catch (Exception e) {
            log.warn("[AssistantInfoService] cache write error: ak={}, error={}", ak, e.getMessage());
        }

        return info;
    }

    public AssistantInfo getAssistantInfo(String ak, String assistantAccount) {
        AssistantInfo instanceInfo = getAssistantInfoFromInstance(assistantAccount);
        if (instanceInfo != null) {
            return instanceInfo;
        }
        if (ak != null && !ak.isBlank()) {
            return getAssistantInfo(ak);
        }
        return null;
    }

    private AssistantInfo getAssistantInfoFromInstance(String assistantAccount) {
        if (assistantInstanceInfoService == null
                || assistantAccount == null || assistantAccount.isBlank()) {
            return null;
        }
        AssistantInstanceInfo instance = assistantInstanceInfoService.getInstanceInfo(assistantAccount);
        if (instance == null) {
            return null;
        }
        if (instance.businessRoutableAssistant()) {
            AssistantInfo info = new AssistantInfo();
            info.setId(instance.getId());
            info.setAssistantScope("business");
            info.setBusinessTag(instance.getBizRobotTag());
            info.setCloudProfile(instance.protocolProfile());
            return info;
        }
        String effectiveAk = instance.effectiveAk();
        if (effectiveAk != null) {
            AssistantInfo info = getAssistantInfo(effectiveAk);
            String bizRobotTag = firstNonBlank(instance.getBizRobotTag(), null);
            if (info != null) {
                info.setId(firstNonBlank(instance.getId(), info.getId()));
                if (info.isPersonal() && bizRobotTag != null) {
                    info.setBusinessTag(bizRobotTag);
                }
                return info;
            }
            if (bizRobotTag != null) {
                AssistantInfo fallback = new AssistantInfo();
                fallback.setId(instance.getId());
                fallback.setAssistantScope("personal");
                fallback.setBusinessTag(bizRobotTag);
                return fallback;
            }
        }
        return null;
    }

    /**
     * 获取 scope，上游不可用或返回 null 时降级为 "personal"。
     *
     * @param ak Agent 应用密钥
     * @return "business" | "personal"
     */
    public String getCachedScope(String ak) {
        AssistantInfo info = getAssistantInfo(ak);
        if (info == null || info.getAssistantScope() == null) {
            log.debug("[AssistantInfoService] scope degraded to personal: ak={}", ak);
            return "personal";
        }
        return info.getAssistantScope();
    }

    /**
     * 调用上游 API 获取 AssistantInfo。
     *
     * 子类可 override 此方法，便于单元测试。
     *
     * @param ak Agent 应用密钥
     * @return AssistantInfo，解析失败时返回 null
     */
    protected AssistantInfo fetchFromUpstream(String ak) {
        String urlTemplate = "/appstore/wecodeapi/open/ak/info";
        MetricServiceEnum metricService = MetricServiceEnum.BUSINESS_CENTER_ASSISTANT_INFO;
        boolean success = false;
        long start = System.currentTimeMillis();
        String url = properties.getApiUrl();

        try {
            MdcHelper.putBusinessDomain(metricService.getId());
            // 上游接口要求 GET + JSON body，Spring RestTemplate GET 不支持 body，
            // 使用 Java HttpClient 的 method("GET", body) 实现。
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .method("GET", java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"ak\":\"" + ak + "\"}"));
            if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + properties.getApiToken());
            }

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpResponse<String> response = client.send(
                    reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.currentTimeMillis() - start);

            if (response.statusCode() != 200 || response.body() == null) {
                log.warn("[AssistantInfoService] upstream non-success: ak={}, status={}, durationMs={}",
                        ak, response.statusCode(), elapsedMs);
                return null;
            }

            AssistantInfo info = parseApiResponse(response.body());
            log.info("[AssistantInfoService] upstream success: ak={}, scope={}, durationMs={}",
                    ak, info != null ? info.getAssistantScope() : null, elapsedMs);
            success = true;
            return info;

        } catch (Exception e) {
            long elapsedMs = (System.currentTimeMillis() - start);
            log.warn("[AssistantInfoService] upstream error: ak={}, durationMs={}, error={}",
                    ak, elapsedMs, e.getMessage());
            throw new RuntimeException("AssistantInfo upstream fetch failed: " + e.getMessage(), e);
        } finally {
            apiCallMetricsService.recordApiCall(metricService, urlTemplate, success, System.currentTimeMillis() - start);
            MdcHelper.putBusinessDomain(null);
        }
    }

    /**
     * 解析上游 API 响应 JSON。
     *
     * 响应格式：
     * <pre>
     * {
     *   "code": "200",
     *   "data": {
     *     "identityType": "3",   // "2"=personal, "3"=business
     *     "businessTag": "tag-foo",   // 业务标签（注：原代码误读为 hisAppId/appId 是 bug）
     *     "endpoint": "https://cloud.example.com/chat",
     *     "protocol": "2",     // 1=rest, 2=sse, 3=websocket
     *     "authType": "1"      // 1=soa
     *   }
     * }
     * </pre>
     *
     * @param responseBody 响应体 JSON 字符串
     * @return AssistantInfo，解析失败时返回 null
     */
    AssistantInfo parseApiResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode()) {
                log.warn("[AssistantInfoService] parseApiResponse: missing data field");
                return null;
            }

            String identityType = dataNode.path("identityType").asText(null);
            String scope = IDENTITY_TYPE_BUSINESS.equals(identityType) ? "business" : "personal";

            AssistantInfo info = new AssistantInfo();
            info.setId(dataNode.path("id").asText(null));
            info.setAssistantScope(scope);
            info.setBusinessTag(dataNode.path("businessTag").asText(null));
            info.setCloudEndpoint(dataNode.path("endpoint").asText(null));
            info.setCloudProtocol(mapProtocol(dataNode.path("protocol").asText(null)));
            info.setAuthType(mapAuthType(dataNode.path("authType").asText(null)));

            return info;

        } catch (Exception e) {
            log.warn("[AssistantInfoService] parseApiResponse error: {}", e.getMessage());
            return null;
        }
    }

    /** 上游 protocol 数字码 → 内部字符串：1=rest, 2=sse, 3=websocket */
    private String mapProtocol(String code) {
        if (code == null) return null;
        return switch (code) {
            case "1" -> "rest";
            case "2" -> "sse";
            case "3" -> "websocket";
            default -> code; // 未知码直接透传
        };
    }

    /** 上游 authType 数字码 → 内部字符串：1=soa */
    private String mapAuthType(String code) {
        if (code == null) return null;
        return switch (code) {
            case "1" -> "soa";
            default -> code;
        };
    }

    private String buildCacheKey(String ak) {
        return CACHE_KEY_PREFIX + ak;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
