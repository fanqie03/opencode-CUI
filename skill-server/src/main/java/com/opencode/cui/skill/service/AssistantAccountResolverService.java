package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInstanceInfo;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.ExistenceStatus;
import com.opencode.cui.skill.model.ResolveOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

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
 *   <li>HTTP 200 + body.code=200 + data 为空 → NOT_EXISTS</li>
 *   <li>HTTP 200 + body.code=200 + data.remoteType=1/2 → EXISTS，允许 appKey 为空</li>
 *   <li>HTTP 200 + body.code=200 + data 为本地助手且 appKey / ownerWelinkId 缺失 → UNKNOWN（上游数据残缺）</li>
 *   <li>body.code != 200 / HTTP 非 200 / 超时 / 异常 → UNKNOWN</li>
 * </ul>
 */
@Slf4j
@Service
public class AssistantAccountResolverService {

    private static final String STATUS_CACHE_KEY_PREFIX = "assistantAccount:status:"; // Redis 缓存 key 前缀：status（统一）
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistantInstanceInfoService assistantInstanceInfoService;
    private final StringRedisTemplate redisTemplate; // Redis 操作模板
    private final boolean skipOnNullAssistantAccount; // miniapp 入口 null 时开关：true=跳过放行；false=严格校验
    private final int statusCacheTtlExistsSeconds; // EXISTS 缓存 TTL（秒）
    private final int statusCacheTtlNotExistsSeconds; // NOT_EXISTS 缓存 TTL（秒）
    private final String deletionMessage; // 助理删除后对外文案

    @Autowired
    public AssistantAccountResolverService(
            AssistantInstanceInfoService assistantInstanceInfoService,
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.existence-check.skip-on-null-assistant-account:true}") boolean skipOnNullAssistantAccount,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.status-cache-ttl-exists-seconds:300}") int statusCacheTtlExistsSeconds,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.status-cache-ttl-not-exists-seconds:60}") int statusCacheTtlNotExistsSeconds,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.deletion-message:该助理已被删除}") String deletionMessage) {
        this.assistantInstanceInfoService = assistantInstanceInfoService;
        this.redisTemplate = redisTemplate;
        this.skipOnNullAssistantAccount = skipOnNullAssistantAccount;
        this.statusCacheTtlExistsSeconds = statusCacheTtlExistsSeconds;
        this.statusCacheTtlNotExistsSeconds = statusCacheTtlNotExistsSeconds;
        this.deletionMessage = deletionMessage;
    }

    public AssistantAccountResolverService(
            RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService apiCallMetricsService,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-url:}") String resolveUrl,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-token:}") String resolveToken,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.existence-check.skip-on-null-assistant-account:true}") boolean skipOnNullAssistantAccount,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.status-cache-ttl-exists-seconds:300}") int statusCacheTtlExistsSeconds,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.status-cache-ttl-not-exists-seconds:60}") int statusCacheTtlNotExistsSeconds,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.deletion-message:该助理已被删除}") String deletionMessage) {
        this(new AssistantInstanceInfoService(restTemplate, redisTemplate, MAPPER,
                        resolveUrl, resolveToken, statusCacheTtlExistsSeconds, apiCallMetricsService),
                redisTemplate,
                skipOnNullAssistantAccount,
                statusCacheTtlExistsSeconds,
                statusCacheTtlNotExistsSeconds,
                deletionMessage);
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
        if (assistantAccount == null || assistantAccount.isBlank()) {
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

        AssistantInstanceInfoService.LookupResult result = assistantInstanceInfoService.lookup(assistantAccount);
        ResolveOutcome outcome = judge(result, assistantAccount, maskedAccount);
        writeCache(cacheKey, outcome);
        return outcome;
    }

    /**
     * 按 PRD Technical Notes 规则判定三态。
     * HTTP 非 200 / body.code != 200 / data 为空 / 本地助手数据残缺
     * 等各种情况分别映射到 EXISTS / NOT_EXISTS / UNKNOWN。
     */
    private ResolveOutcome judge(AssistantInstanceInfoService.LookupResult result,
                                 String fallbackAssistantAccount,
                                 String maskedAccount) {
        if (result == null || result.status() == ExistenceStatus.UNKNOWN) {
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }
        if (result.status() == ExistenceStatus.NOT_EXISTS) {
            return new ResolveOutcome(ExistenceStatus.NOT_EXISTS, null, null);
        }

        AssistantInstanceInfo info = result.info();
        if (info == null) {
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }

        String ak = info.effectiveAk();
        String resolvedAccount = info.effectivePartnerAccount(fallbackAssistantAccount);
        boolean remote = info.businessRoutableAssistant();
        String ownerWelinkId = remote ? null : info.effectiveOwnerUserId();

        if (!remote && (ak == null || ak.isBlank())) {
            log.warn("[EXT_CALL] AssistantResolve local_missing_ak: decision=unknown, source=instance, assistantAccount={}",
                    maskedAccount);
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }
        if (!remote && (ownerWelinkId == null || ownerWelinkId.isBlank())) {
            log.warn("[EXT_CALL] AssistantResolve local_owner_missing: decision=unknown, source=instance, assistantAccount={}, ak={}",
                    maskedAccount, com.opencode.cui.skill.logging.SensitiveDataMasker.maskToken(ak));
            return new ResolveOutcome(ExistenceStatus.UNKNOWN, null, null);
        }

        log.info("[EXT_CALL] AssistantResolve success: decision=allow, source=instance, assistantAccount={}, ak={}, remote={}, businessTag={}",
                maskedAccount,
                com.opencode.cui.skill.logging.SensitiveDataMasker.maskToken(ak),
                remote,
                info.getBizRobotTag());
        return new ResolveOutcome(ExistenceStatus.EXISTS, ak, ownerWelinkId,
                resolvedAccount, remote, info.getBizRobotTag());
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
                String owner = firstNonBlank(
                        node.path("createdBy").asText(null),
                        node.path("ownerWelinkId").asText(null));
                String assistantAccount = node.path("assistantAccount").asText(null);
                boolean remote = node.path("remote").asBoolean(false);
                String businessTag = node.path("businessTag").asText(null);
                if (remote) {
                    owner = null;
                }
                if (owner != null && !owner.isBlank()
                        && assistantAccount != null && owner.equals(assistantAccount)) {
                    log.warn("AssistantResolve cache dirty: reason=owner_equals_assistantAccount, key={}", cacheKey);
                    return null;
                }
                if (!remote && (ak == null || ak.isBlank() || owner == null || owner.isBlank())) {
                    return null; // 脏缓存，当未命中
                }
                return new ResolveOutcome(ExistenceStatus.EXISTS, ak, owner,
                        assistantAccount, remote, businessTag);
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
                putIfNotBlank(payload, "ak", outcome.ak());
                putIfNotBlank(payload, "ownerWelinkId", outcome.ownerWelinkId());
                putIfNotBlank(payload, "assistantAccount", outcome.assistantAccount());
                payload.put("remote", outcome.remote());
                putIfNotBlank(payload, "businessTag", outcome.businessTag());
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

    private static void putIfNotBlank(ObjectNode payload, String field, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(field, value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

}
