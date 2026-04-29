package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.CloudRouteInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 云端路由服务。
 *
 * <p>从上游 API 获取云端路由信息（endpoint/protocol/authType），并使用 Redis 缓存以减少外部调用。</p>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>缓存 key：{@code gw:cloud:route:{ak}}</li>
 *   <li>TTL 通过 {@code gateway.cloud-route.cache-ttl-seconds} 配置（默认 300s）</li>
 * </ul>
 *
 * <h3>上游 API</h3>
 * <pre>GET {gateway.cloud-route.api-url}?ak={ak}
 * Authorization: Bearer {gateway.cloud-route.bearer-token}</pre>
 *
 * <p>响应格式：
 * <pre>{"code":"200","data":{"hisAppId":"...","endpoint":"...","protocol":"...","authType":"..."}}</pre>
 */
@Slf4j
@Service
public class CloudRouteService {

    private static final String CACHE_KEY_PREFIX = "gw:cloud:route:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String bearerToken;
    private final long cacheTtlSeconds;
    private final HttpClient httpClient;

    public CloudRouteService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${gateway.cloud-route.api-url:}") String apiUrl,
            @Value("${gateway.cloud-route.bearer-token:}") String bearerToken,
            @Value("${gateway.cloud-route.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.bearerToken = bearerToken;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 获取指定 AK 的云端路由信息。
     *
     * <p>先查 Redis 缓存，命中则直接返回；未命中则调用上游 API 并回写缓存。</p>
     *
     * @param ak Access Key
     * @return 云端路由信息，上游 API 不可用或 AK 不存在时返回 {@code null}
     */
    public CloudRouteInfo getRouteInfo(String ak) {
        String cacheKey = CACHE_KEY_PREFIX + ak;

        // 1. 查 Redis 缓存
        CloudRouteInfo cached = getFromCache(cacheKey);
        if (cached != null) {
            log.debug("[CLOUD_ROUTE] Cache hit: ak={}", ak);
            return cached;
        }

        // 2. 缓存未命中，调用上游 API
        String responseBody;
        try {
            responseBody = fetchFromUpstream(ak);
        } catch (Exception e) {
            log.warn("[CLOUD_ROUTE] Upstream API error: ak={}, error={}", ak, e.getMessage());
            return null;
        }

        if (responseBody == null) {
            log.warn("[CLOUD_ROUTE] Upstream API returned null: ak={}", ak);
            return null;
        }

        // 3. 解析响应
        CloudRouteInfo info = parseResponse(ak, responseBody);
        if (info == null) {
            return null;
        }

        // 4. 写入缓存
        writeToCache(cacheKey, info);
        log.info("[CLOUD_ROUTE] Fetched and cached: ak={}, appId={}, endpoint={}, protocol={}, authType={}",
                ak, info.getAppId(), info.getEndpoint(), info.getProtocol(), info.getAuthType());
        return info;
    }

    // -----------------------------------------------------------------------
    // Protected 方法（便于子类/Spy 在测试中 mock）
    // -----------------------------------------------------------------------

    /**
     * 调用上游 API 获取云端路由原始响应体。
     *
     * <p>声明为 {@code protected} 以便测试时通过 Spy mock 此方法，
     * 无需启动真实 HTTP 服务器。</p>
     *
     * @param ak Access Key
     * @return 响应体字符串；失败时抛出异常
     */
    protected String fetchFromUpstream(String ak) throws Exception {
        // 上游接口：GET + JSON body {"ak":"xxx"}
        String body = "{\"ak\":\"" + ak + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .method("GET", HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() != 200) {
            log.warn("[CLOUD_ROUTE] Upstream HTTP error: ak={}, status={}, durationMs={}",
                    ak, response.statusCode(), elapsedMs);
            throw new RuntimeException("Upstream HTTP " + response.statusCode());
        }

        log.debug("[CLOUD_ROUTE] Upstream response: ak={}, durationMs={}", ak, elapsedMs);
        return response.body();
    }

    // -----------------------------------------------------------------------
    // 私有辅助方法
    // -----------------------------------------------------------------------

    /**
     * 从 Redis 缓存读取路由信息。
     */
    private CloudRouteInfo getFromCache(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, CloudRouteInfo.class);
        } catch (Exception e) {
            log.debug("[CLOUD_ROUTE] Cache read error: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    /**
     * 将路由信息写入 Redis 缓存。
     */
    private void writeToCache(String cacheKey, CloudRouteInfo info) {
        try {
            String json = objectMapper.writeValueAsString(info);
            redisTemplate.opsForValue().set(cacheKey, json, cacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[CLOUD_ROUTE] Cache write error: key={}, error={}", cacheKey, e.getMessage());
        }
    }

    /**
     * 解析上游 API 响应，提取路由字段。
     *
     * <p>仅当响应 code 为 {@code "200"} 且 data 节点存在时才返回非 null 结果。</p>
     */
    private CloudRouteInfo parseResponse(String ak, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String code = root.path("code").asText("");
            if (!"200".equals(code)) {
                log.warn("[CLOUD_ROUTE] Upstream returned non-200 code: ak={}, code={}", ak, code);
                return null;
            }

            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                log.warn("[CLOUD_ROUTE] Upstream response missing data: ak={}", ak);
                return null;
            }

            CloudRouteInfo info = new CloudRouteInfo();
            info.setAppId(data.path("hisAppId").asText(null));
            info.setEndpoint(data.path("endpoint").asText(null));
            info.setProtocol(mapProtocol(data.path("protocol").asText(null)));
            info.setAuthType(mapAuthType(data.path("authType").asText(null)));
            return info;

        } catch (Exception e) {
            log.warn("[CLOUD_ROUTE] Response parse error: ak={}, error={}", ak, e.getMessage());
            return null;
        }
    }

    /** 上游 protocol 数字码 → 内部字符串：1=rest, 2=sse, 3=websocket, 4=dify, 5=agentmaker, 6=uniknow, 7=athena, 8=standard */
    private String mapProtocol(String code) {
        if (code == null) return null;
        return switch (code) {
            case "1" -> "rest";
            case "2" -> "sse";
            case "3" -> "websocket";
            case "4" -> "dify";
            case "5" -> "agentmaker";
            case "6" -> "uniknow";
            case "7" -> "athena";
            case "8" -> "standard";
            default -> code;
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
}
