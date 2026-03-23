package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.gateway.model.AkSkCredential;
import com.opencode.cui.gateway.repository.AkSkCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * AK/SK 认证服务。
 *
 * <p>
 * 支持两种验签模式（通过 {@code gateway.auth.mode} 配置）：
 * </p>
 *
 * <ul>
 * <li><b>gateway</b>（默认）— 本地验签：从 ak_sk_credential 表获取 SK，
 * 使用 HMAC-SHA256 在 Gateway 本地完成签名校验，并返回关联的 userId。</li>
 * <li><b>remote</b> — 远程验签：委托外部身份 API 完成验签，
 * 支持 L1(Caffeine) → L2(Redis) → L3(外部API) 缓存分级管线。</li>
 * </ul>
 *
 * <p>
 * 两种模式共用：时间窗口校验（±5分钟） + Nonce 防重放（Redis SET NX）。
 * </p>
 */
@Slf4j
@Service
public class AkSkAuthService {

    private static final String NONCE_KEY_PREFIX = "gw:auth:nonce:";
    private static final String IDENTITY_CACHE_KEY_PREFIX = "auth:identity:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdentityApiClient identityApiClient;
    private final AkSkCredentialRepository akSkCredentialRepository;

    /** L1 本地缓存: ak → IdentityCacheEntry（仅 remote 模式使用） */
    private final Cache<String, IdentityCacheEntry> l1Cache;

    private final long timestampToleranceSeconds;
    private final long nonceTtlSeconds;
    private final long l2TtlSeconds;

    /** 验签模式：gateway=本地 HMAC 验签，remote=外部 API 验签 */
    private final String authMode;

    public AkSkAuthService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            IdentityApiClient identityApiClient,
            AkSkCredentialRepository akSkCredentialRepository,
            @Value("${gateway.auth.timestamp-tolerance-seconds:300}") long timestampToleranceSeconds,
            @Value("${gateway.auth.nonce-ttl-seconds:300}") long nonceTtlSeconds,
            @Value("${gateway.auth.identity-cache.l1-ttl-seconds:300}") long l1TtlSeconds,
            @Value("${gateway.auth.identity-cache.l1-max-size:10000}") long l1MaxSize,
            @Value("${gateway.auth.identity-cache.l2-ttl-seconds:3600}") long l2TtlSeconds,
            @Value("${gateway.auth.mode:gateway}") String authMode) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.identityApiClient = identityApiClient;
        this.akSkCredentialRepository = akSkCredentialRepository;
        this.timestampToleranceSeconds = timestampToleranceSeconds;
        this.nonceTtlSeconds = nonceTtlSeconds;
        this.l2TtlSeconds = l2TtlSeconds;
        this.authMode = authMode;
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterWrite(Duration.ofSeconds(l1TtlSeconds))
                .build();
        if ("remote".equalsIgnoreCase(authMode)) {
            log.info("[INIT] Auth mode: REMOTE — delegating signature verification to external API");
        } else {
            log.info("[INIT] Auth mode: GATEWAY — local HMAC-SHA256 verification via ak_sk_credential");
        }
    }

    /**
     * 验证 AK/SK 签名。
     *
     * @param ak        Access Key
     * @param timestamp Unix 时间戳（秒，字符串）
     * @param nonce     随机字符串（防重放）
     * @param signature Base64 编码的 HMAC-SHA256 签名
     * @return 验证成功返回 userId；失败返回 null
     */
    public String verify(String ak, String timestamp, String nonce, String signature) {
        if (ak == null || timestamp == null || nonce == null || signature == null) {
            log.warn("Auth failed: missing parameters. ak={}", ak);
            return null;
        }

        // === 两种模式共用：时间窗口 + Nonce 检查 ===

        // 1. 时间窗口校验
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            log.warn("Auth failed: invalid timestamp format. ak={}, ts={}", ak, timestamp);
            return null;
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > timestampToleranceSeconds) {
            log.warn("Auth failed: timestamp out of window. ak={}, ts={}, now={}", ak, ts, now);
            return null;
        }

        // 2. Nonce 防重放
        String nonceKey = NONCE_KEY_PREFIX + nonce;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(nonceKey, "1", nonceTtlSeconds, TimeUnit.SECONDS);
        if (isNew == null || !isNew) {
            log.warn("Auth failed: nonce replay detected. ak={}", ak);
            return null;
        }

        // 3. 身份解析（按模式分支）
        if ("remote".equalsIgnoreCase(authMode)) {
            // remote 模式：走 L1→L2→L3 缓存分级管线（含外部 API 调用）
            long authStart = System.nanoTime();
            IdentityCacheEntry identity = resolveIdentity(ak, timestamp, nonce, signature);
            long authElapsedMs = (System.nanoTime() - authStart) / 1_000_000;
            if (identity == null) {
                log.warn("Auth failed (remote): identity not resolved. ak={}, durationMs={}", ak, authElapsedMs);
                redisTemplate.delete(nonceKey);
                return null;
            }
            log.info("Auth success (remote). ak={}, userId={}, level={}, durationMs={}",
                    ak, identity.userId(), identity.level(), authElapsedMs);
            return identity.userId();
        } else {
            // gateway 模式：本地 HMAC-SHA256 验签，从 ak_sk_credential 表获取 SK 和 userId
            long authStart = System.nanoTime();
            String userId = verifyLocally(ak, timestamp, nonce, signature);
            long authElapsedMs = (System.nanoTime() - authStart) / 1_000_000;
            if (userId == null) {
                redisTemplate.delete(nonceKey);
                return null;
            }
            log.info("Auth success (gateway). ak={}, userId={}, durationMs={}", ak, userId, authElapsedMs);
            return userId;
        }
    }

    // -----------------------------------------------------------------------
    // Gateway 模式：本地 HMAC-SHA256 验签
    // -----------------------------------------------------------------------

    /**
     * 从 ak_sk_credential 表获取 SK，本地计算 HMAC-SHA256 并比对签名。
     *
     * @return 验签通过返回 userId；失败返回 null
     */
    private String verifyLocally(String ak, String timestamp, String nonce, String signature) {
        AkSkCredential credential = akSkCredentialRepository.findActiveByAk(ak);
        if (credential == null) {
            log.warn("Auth failed (gateway): credential not found or disabled. ak={}", ak);
            return null;
        }

        // 构造待签名字符串：ak + timestamp + nonce
        String stringToSign = ak + timestamp + nonce;
        String expectedSignature = hmacSha256(credential.getSk(), stringToSign);

        if (expectedSignature == null || !MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Auth failed (gateway): signature mismatch. ak={}", ak);
            return null;
        }

        return String.valueOf(credential.getUserId());
    }

    /**
     * 使用 HMAC-SHA256 算法计算签名，返回 Base64 编码结果。
     */
    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            log.error("HMAC-SHA256 computation failed: {}", e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Remote 模式：多级身份解析（L1→L2→L3）
    // -----------------------------------------------------------------------

    /**
     * 按 L1→L2→L3 优先级解析身份信息。
     *
     * L1/L2 命中时信任缓存的 userId（签名已在首次 L3 调用时由外部 API 验证）。
     * L3 外部 API 在服务端完成签名验证，成功后回填 L1+L2。
     */
    IdentityCacheEntry resolveIdentity(String ak, String timestamp, String nonce, String signature) {
        // L1: Caffeine 本地缓存
        IdentityCacheEntry l1 = l1Cache.getIfPresent(ak);
        if (l1 != null) {
            log.debug("Auth L1 cache hit: ak={}", ak);
            return l1;
        }

        // L2: Redis 缓存
        IdentityCacheEntry l2 = getFromRedisCache(ak);
        if (l2 != null) {
            log.debug("Auth L2 cache hit: ak={}", ak);
            l1Cache.put(ak, l2);
            return l2;
        }

        // L3: 外部身份 API
        if (!identityApiClient.isEnabled()) {
            log.error("Auth failed: external identity API not configured. ak={}", ak);
            return null;
        }

        try {
            String userId = identityApiClient.check(ak, timestamp, nonce, signature);
            if (userId != null) {
                IdentityCacheEntry entry = new IdentityCacheEntry(userId, "L3");
                l1Cache.put(ak, entry);
                writeToRedisCache(ak, entry);
                log.info("Auth L3 external API success: ak={}, userId={}", ak, userId);
                return entry;
            }
            // checkResult=false → 外部 API 明确拒绝
            log.info("Auth L3 external API rejected: ak={}", ak);
            return null;
        } catch (IdentityApiClient.IdentityApiException e) {
            log.error("Auth L3 external API unavailable: ak={}, error={}", ak, e.getMessage());
            return null;
        }

        // L4: 拒绝（隐含在上方所有分支 return null）
    }

    // -----------------------------------------------------------------------
    // L2 Redis 缓存操作
    // -----------------------------------------------------------------------

    private IdentityCacheEntry getFromRedisCache(String ak) {
        try {
            String json = redisTemplate.opsForValue().get(IDENTITY_CACHE_KEY_PREFIX + ak);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, IdentityCacheEntry.class);
        } catch (Exception e) {
            log.debug("Failed to read L2 cache: ak={}, error={}", ak, e.getMessage());
            return null;
        }
    }

    private void writeToRedisCache(String ak, IdentityCacheEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(
                    IDENTITY_CACHE_KEY_PREFIX + ak, json, l2TtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.debug("Failed to write L2 cache: ak={}, error={}", ak, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 缓存条目
    // -----------------------------------------------------------------------

    /**
     * 身份缓存条目。
     *
     * @param userId 用户 ID
     * @param level  解析级别（L1/L2/L3，仅用于日志）
     */
    record IdentityCacheEntry(String userId, String level) {
    }
}
