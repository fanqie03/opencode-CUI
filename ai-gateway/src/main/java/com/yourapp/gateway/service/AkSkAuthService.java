package com.yourapp.gateway.service;

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
 * Signature algorithm: HMAC-SHA256(SK, "{AK}\n{timestamp}\n{nonce}")
 * Timestamp window: +/-5 minutes
 * Nonce replay prevention: Redis with TTL 5 minutes
 */
@Slf4j
@Service
public class AkSkAuthService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String NONCE_KEY_PREFIX = "gw:auth:nonce:";

    private final StringRedisTemplate redisTemplate;

    @Value("${gateway.auth.timestamp-tolerance-seconds:300}")
    private long timestampToleranceSeconds;

    @Value("${gateway.auth.nonce-ttl-seconds:300}")
    private long nonceTtlSeconds;

    public AkSkAuthService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
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
    public Long verify(String ak, String timestamp, String nonce, String signature) {
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

        // 3. Look up SK by AK
        // TODO: Replace with actual AK/SK store lookup (database or config)
        // For now, using a simple in-memory mapping for development
        AkSkRecord record = lookupByAk(ak);
        if (record == null) {
            log.warn("Auth failed: unknown AK. ak={}", ak);
            // Clean up the nonce since auth failed at AK lookup
            redisTemplate.delete(nonceKey);
            return null;
        }

        // 4. Compute expected signature: HMAC-SHA256(SK, "{AK}\n{timestamp}\n{nonce}")
        String message = ak + "\n" + timestamp + "\n" + nonce;
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
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }

    /**
     * Look up AK/SK record. In production, this queries a database table or
     * calls the chat platform's open API to resolve AK -> (SK, userId).
     *
     * TODO: Replace with actual persistence-backed lookup.
     */
    private AkSkRecord lookupByAk(String ak) {
        // Placeholder: in production, query from database
        // Example: SELECT sk, user_id FROM ak_sk_credential WHERE ak = ?
        //
        // For development/testing, accept a well-known test AK:
        if ("test-ak-001".equals(ak)) {
            return new AkSkRecord("test-sk-secret-001", 1L);
        }
        return null;
    }

    /**
     * Internal record holding AK lookup result.
     */
    private record AkSkRecord(String sk, Long userId) {}
}
