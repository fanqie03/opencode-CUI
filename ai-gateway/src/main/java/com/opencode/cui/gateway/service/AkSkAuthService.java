package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.model.AkSkCredential;
import com.opencode.cui.gateway.repository.AkSkCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * AK/SK signature verification service.
 *
 * Signature algorithm: HMAC-SHA256(SK, "{AK}{timestamp}{nonce}")
 * Timestamp window: +/-5 minutes
 * Nonce replay prevention: Redis with TTL 5 minutes
 */
@Slf4j
@Service
public class AkSkAuthService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String NONCE_KEY_PREFIX = "gw:auth:nonce:";

    private final StringRedisTemplate redisTemplate;
    private final AkSkCredentialRepository credentialRepository;

    @Value("${gateway.auth.timestamp-tolerance-seconds:300}")
    private long timestampToleranceSeconds;

    @Value("${gateway.auth.nonce-ttl-seconds:300}")
    private long nonceTtlSeconds;

    public AkSkAuthService(StringRedisTemplate redisTemplate,
            AkSkCredentialRepository credentialRepository) {
        this.redisTemplate = redisTemplate;
        this.credentialRepository = credentialRepository;
    }

    /**
     * Verify the AK/SK signature from WebSocket handshake parameters.
     *
     * @param ak        Access Key
     * @param timestamp Unix timestamp in seconds (string)
     * @param nonce     Random string for replay prevention
     * @param signature Base64-encoded HMAC-SHA256 signature
     * @return userId associated with the AK, or null if verification fails
     */
    public String verify(String ak, String timestamp, String nonce, String signature) {
        if (ak == null || timestamp == null || nonce == null || signature == null) {
            log.warn("Auth failed: missing parameters. ak={}, ts={}, nonce={}, sign={}",
                    ak, timestamp, nonce, signature != null ? "[present]" : "null");
            return null;
        }

        // 1. Validate timestamp window (+/- 5 minutes)
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            log.warn("Auth failed: invalid timestamp format. ak={}, ts={}", ak, timestamp);
            return null;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > timestampToleranceSeconds) {
            log.warn("Auth failed: timestamp out of window. ak={}, ts={}, now={}, tolerance={}s",
                    ak, ts, now, timestampToleranceSeconds);
            return null;
        }

        // 2. Check nonce replay (Redis SET NX with TTL)
        String nonceKey = NONCE_KEY_PREFIX + nonce;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(nonceKey, "1", nonceTtlSeconds, TimeUnit.SECONDS);
        if (isNew == null || !isNew) {
            log.warn("Auth failed: nonce replay detected. ak={}, nonce={}", ak, nonce);
            return null;
        }

        // 3. Look up SK by AK from database (REQ-26)
        AkSkRecord record = lookupByAk(ak);
        if (record == null) {
            log.warn("Auth failed: unknown AK. ak={}", ak);
            // Clean up the nonce since auth failed at AK lookup
            redisTemplate.delete(nonceKey);
            return null;
        }

        // 4. Compute expected signature: HMAC-SHA256(SK, "{AK}{timestamp}{nonce}")
        String message = ak + timestamp + nonce;
        String expectedSignature;
        try {
            expectedSignature = computeHmacSha256(record.sk, message);
        } catch (Exception e) {
            log.error("Auth failed: HMAC computation error. ak={}", ak, e);
            redisTemplate.delete(nonceKey);
            return null;
        }

        // 5. Compare signatures (constant-time comparison)
        if (!constantTimeEquals(expectedSignature, signature)) {
            log.warn("Auth failed: signature mismatch. ak={}", ak);
            redisTemplate.delete(nonceKey);
            return null;
        }

        log.info("Auth success. ak={}, userId={}", ak, record.userId);
        return record.userId;
    }

    /**
     * Compute HMAC-SHA256 and return Base64-encoded result.
     */
    private String computeHmacSha256(String key, String message)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKey = new SecretKeySpec(
                key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(secretKey);
        byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     * Delegates to JDK's MessageDigest.isEqual() for a proven implementation.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Look up AK/SK record from database (REQ-26).
     * Queries the ak_sk_credential table for active credentials.
     */
    private AkSkRecord lookupByAk(String ak) {
        AkSkCredential credential = credentialRepository.findActiveByAk(ak);
        if (credential == null) {
            return null;
        }
        return new AkSkRecord(credential.getSk(), credential.getUserId());
    }

    /**
     * Internal record holding AK lookup result.
     */
    private record AkSkRecord(String sk, String userId) {
    }
}
