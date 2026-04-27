package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.ResolveOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 助手账号解析服务。
 * 将 IM 平台的 assistantAccount 解析为 ak（应用密钥）和 ownerWelinkId（助手拥有者 WeLink ID），
 * 并作为助手 existence 权威（EXISTS / NOT_EXISTS / UNKNOWN 三态）。
 *
 * <p>缓存策略（统一 status key，双 TTL）：
 * <ul>
 *   <li>EXISTS → {@code assistantAccount:status:<account>} = {@code {"status":"EXISTS","ak":"...","ownerWelinkId":"..."}}，TTL 300s</li>
 *   <li>NOT_EXISTS → {@code assistantAccount:status:<account>} = {@code {"status":"NOT_EXISTS"}}，TTL 60s</li>
 *   <li>UNKNOWN → 不写缓存</li>
 * </ul>
 *
 * <p>响应判定：
 * <ul>
 *   <li>HTTP 200 + body.code=200 + data.appKey 和 data.ownerWelinkId 都有 → EXISTS</li>
 *   <li>HTTP 200 + body.code=200 + data 为空 / data.appKey 缺 → NOT_EXISTS</li>
 *   <li>HTTP 200 + body.code=200 + appKey 有但 ownerWelinkId 缺 → UNKNOWN（上游数据残缺）</li>
 *   <li>body.code != 200 / HTTP 非 200 / 超时 / 异常 → UNKNOWN</li>
 * </ul>
 */
@Slf4j
@Service
public class AssistantAccountResolverService {

    private static final String STATUS_CACHE_KEY_PREFIX = "assistantAccount:status:"; // Redis 缓存 key 前缀：status（统一）
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate; // HTTP 客户端
    private final StringRedisTemplate redisTemplate; // Redis 操作模板
    private final String resolveUrl; // 远程解析接口地址
    private final String resolveToken; // Bearer Token 认证
    private final boolean skipOnNullAssistantAccount; // miniapp 入口 null 时开关：true=跳过放行；false=严格校验
    private final int statusCacheTtlExistsSeconds; // EXISTS 缓存 TTL（秒）
    private final int statusCacheTtlNotExistsSeconds; // NOT_EXISTS 缓存 TTL（秒）
    private final String deletionMessage; // 助理删除后对外文案

    public AssistantAccountResolverService(
            RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-url:}") String resolveUrl,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-token:}") String resolveToken,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.existence-check.skip-on-null-assistant-account:true}") boolean skipOnNullAssistantAccount,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.status-cache-ttl-exists-seconds:300}") int statusCacheTtlExistsSeconds,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.status-cache-ttl-not-exists-seconds:60}") int statusCacheTtlNotExistsSeconds,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.deletion-message:该助理已被删除}") String deletionMessage) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.resolveUrl = resolveUrl;
        this.resolveToken = resolveToken;
        this.skipOnNullAssistantAccount = skipOnNullAssistantAccount;
        this.statusCacheTtlExistsSeconds = statusCacheTtlExistsSeconds;
        this.statusCacheTtlNotExistsSeconds = statusCacheTtlNotExistsSeconds;
        this.deletionMessage = deletionMessage;
    }

    /**
     * 解析助手账号，返回 ak 和 ownerWelinkId（仅 EXISTS 返非 null）。
     * 保持对老调用方的兼容：NOT_EXISTS / UNKNOWN 均返 null。
     */
    public AssistantResolveResult resolve(String assistantAccount) {
        ResolveOutcome outcome = lookup(assistantAccount);
        if (outcome.status() == ExistenceStatus.EXISTS) {
            return new AssistantResolveResult(outcome.ak(), outcome.ownerWelinkId());
        }
        return null;
    }

    /**
     * 便捷方法：仅返回 ak（仅 EXISTS 返非 null）。
     */
    public String resolveAk(String assistantAccount) {
        ResolveOutcome outcome = lookup(assistantAccount);
        return outcome.status() == ExistenceStatus.EXISTS ? outcome.ak() : null;
    }

    /**
     * 轻量 existence 查询：只返状态，供 miniapp 三入口使用。
     */
    public ExistenceStatus check(String assistantAccount) {
        return lookup(assistantAccount).status();
    }

    /**
     * 单次调用返回 {status, ak?, ownerWelinkId?}，供 IM/external 入站复用。
     * 严禁在入站路径"先 check 再 resolve"打两次远端。
     */
    public ResolveOutcome resolveWithStatus(String assistantAccount) {
        return lookup(assistantAccount);
    }

    /** 是否开启 "miniapp null assistantAccount 跳过校验" 开关。 */
    public boolean isSkipOnNullAssistantAccount() {
        return skipOnNullAssistantAccount;
    }

    /** 对外文案。 */
    public String getDeletionMessage() {
        return deletionMessage;
    }

    /**
     * 内部核心方法：优先读 status 缓存，未命中走远端，按判定规则写缓存（UNKNOWN 不写）。
     */
    private ResolveOutcome lookup(String assistantAccount) {
        if (assistantAccount == null || assistantAccount.isBlank() || resolveUrl == null || resolveUrl.isBlank()) {
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }

        String maskedAccount = com.opencode.cui.skill.logging.SensitiveDataMasker.maskToken(assistantAccount);
        String cacheKey = cacheKey(assistantAccount);

        // 先读缓存
        ResolveOutcome cached = readFromCache(cacheKey);
        if (cached != null) {
            String decision = cached.status() == ExistenceStatus.EXISTS ? "allow" : "block";
            log.debug("AssistantResolve cache hit: decision={}, source=cache, assistantAccount={}, cacheHit=true",
                    decision, maskedAccount);
            return cached;
        }

        // 远端解析
        long start = System.nanoTime();
        try {
            String requestUrl = UriComponentsBuilder.fromUriString(resolveUrl)
                    .queryParam("partnerAccount", assistantAccount)
                    .build(true)
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (resolveToken != null && !resolveToken.isBlank()) {
                headers.setBearerAuth(resolveToken);
            }
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    requestUrl, HttpMethod.GET, request, JsonNode.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            ResolveOutcome outcome = judge(response, maskedAccount, elapsedMs);
            writeCache(cacheKey, outcome);
            return outcome;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[EXT_CALL] AssistantResolve error: decision=unknown, source=remote, assistantAccount={}, cacheHit=false, durationMs={}, error={}",
                    maskedAccount, elapsedMs, e.getMessage());
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }
    }

    /**
     * 按 PRD Technical Notes 规则判定三态。
     * HTTP 非 200 / body.code != 200 / data.appKey 有但 owner 缺 / data.appKey 缺（data 空/无该字段）
     * 等各种情况分别映射到 EXISTS / NOT_EXISTS / UNKNOWN。
     */
    private ResolveOutcome judge(ResponseEntity<JsonNode> response, String maskedAccount, long elapsedMs) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.warn("[EXT_CALL] AssistantResolve non-2xx: decision=unknown, source=remote, assistantAccount={}, cacheHit=false, durationMs={}, httpStatus={}",
                    maskedAccount, elapsedMs, response != null ? response.getStatusCode() : null);
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }

        JsonNode body = response.getBody();
        if (body == null || body.isNull()) {
            log.warn("[EXT_CALL] AssistantResolve empty body: decision=unknown, source=remote, assistantAccount={}, cacheHit=false, durationMs={}",
                    maskedAccount, elapsedMs);
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }

        // body.code 必须 == 200 才算业务成功；包括 code 字段缺失 / 非 200 都归 UNKNOWN
        JsonNode codeNode = body.get("code");
        if (codeNode == null || !codeNode.canConvertToInt() || codeNode.asInt() != 200) {
            log.warn("[EXT_CALL] AssistantResolve business failure: decision=unknown, source=remote, assistantAccount={}, cacheHit=false, durationMs={}, bodyCode={}",
                    maskedAccount, elapsedMs, codeNode);
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }

        // data 为空 / null → NOT_EXISTS
        JsonNode data = body.get("data");
        if (data == null || data.isNull() || data.isMissingNode()
                || (data.isObject() && data.size() == 0)) {
            log.info("[EXT_CALL] AssistantResolve not_exists: decision=block, source=remote, assistantAccount={}, cacheHit=false, durationMs={}, reason=data_empty",
                    maskedAccount, elapsedMs);
            return new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null);
        }

        String ak = firstNonBlank(data.path("appKey").asText(null), data.path("ak").asText(null));
        if (ak == null || ak.isBlank()) {
            // data 有但关键字段 appKey 缺 → NOT_EXISTS
            log.info("[EXT_CALL] AssistantResolve not_exists: decision=block, source=remote, assistantAccount={}, cacheHit=false, durationMs={}, reason=appKey_missing",
                    maskedAccount, elapsedMs);
            return new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null);
        }

        String ownerWelinkId = firstNonBlank(data.path("ownerWelinkId").asText(null), data.path("welinkId").asText(null));
        if (ownerWelinkId == null || ownerWelinkId.isBlank()) {
            // appKey 有但 owner 缺 → UNKNOWN（上游数据残缺，别写死 NOT_EXISTS）
            log.warn("[EXT_CALL] AssistantResolve owner_missing: decision=unknown, source=remote, assistantAccount={}, cacheHit=false, durationMs={}, ak={}",
                    maskedAccount, elapsedMs, com.opencode.cui.skill.logging.SensitiveDataMasker.maskToken(ak));
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }

        log.info("[EXT_CALL] AssistantResolve success: decision=allow, source=remote, assistantAccount={}, cacheHit=false, ak={}, durationMs={}",
                maskedAccount,
                com.opencode.cui.skill.logging.SensitiveDataMasker.maskToken(ak),
                elapsedMs);
        return new ResolveOutcome(ExistenceStatus.EXISTS, ak, ownerWelinkId);
    }

    /** 拼 status 缓存 key。 */
    private String cacheKey(String assistantAccount) {
        return STATUS_CACHE_KEY_PREFIX + assistantAccount;
    }

    /** 读 status 缓存；解析失败当未命中处理。 */
    private ResolveOutcome readFromCache(String cacheKey) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("AssistantResolve cache read error: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            String statusStr = node.path("status").asText(null);
            if (statusStr == null) return null;
            if ("EXISTS".equals(statusStr)) {
                String ak = node.path("ak").asText(null);
                String owner = node.path("ownerWelinkId").asText(null);
                if (ak == null || ak.isBlank() || owner == null || owner.isBlank()) {
                    return null; // 脏缓存，当未命中
                }
                return new ResolveOutcome(ExistenceStatus.EXISTS, ak, owner);
            }
            if ("NOT_EXISTS".equals(statusStr)) {
                return new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null);
            }
            return null;
        } catch (JsonProcessingException e) {
            log.warn("AssistantResolve cache parse error: key={}, raw={}, error={}", cacheKey, raw, e.getMessage());
            return null;
        }
    }

    /** 写 status 缓存：EXISTS/NOT_EXISTS 分别用不同 TTL 原子覆盖；UNKNOWN 不写。 */
    private void writeCache(String cacheKey, ResolveOutcome outcome) {
        if (outcome.status() == ExistenceStatus.UNKNOWN) {
            return;
        }
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("status", outcome.status().name());
            Duration ttl;
            if (outcome.status() == ExistenceStatus.EXISTS) {
                payload.put("ak", outcome.ak());
                payload.put("ownerWelinkId", outcome.ownerWelinkId());
                ttl = Duration.ofSeconds(statusCacheTtlExistsSeconds);
            } else {
                ttl = Duration.ofSeconds(statusCacheTtlNotExistsSeconds);
            }
            redisTemplate.opsForValue().set(cacheKey, MAPPER.writeValueAsString(payload), ttl);
        } catch (Exception e) {
            log.warn("AssistantResolve cache write error: key={}, status={}, error={}",
                    cacheKey, outcome.status(), e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
