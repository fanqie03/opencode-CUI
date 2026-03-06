package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.model.AkSkCredential;
import com.opencode.cui.gateway.repository.AkSkCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AkSkAuthService.
 *
 * Uses Mockito to mock Redis and AkSkCredentialRepository,
 * isolating the signature verification logic.
 */
@ExtendWith(MockitoExtension.class)
class AkSkAuthServiceTest {

    private static final String TEST_AK = "test-ak-001";
    private static final String TEST_SK = "test-sk-secret-001";
    private static final Long TEST_USER_ID = 1L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AkSkCredentialRepository credentialRepository;

    private AkSkAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AkSkAuthService(redisTemplate, credentialRepository);

        // Inject config values via reflection (since @Value not available in unit test)
        try {
            var toleranceField = AkSkAuthService.class.getDeclaredField("timestampToleranceSeconds");
            toleranceField.setAccessible(true);
            toleranceField.setLong(authService, 300);

            var ttlField = AkSkAuthService.class.getDeclaredField("nonceTtlSeconds");
            ttlField.setAccessible(true);
            ttlField.setLong(authService, 300);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set test config", e);
        }
    }

    // -----------------------------------------------------------------------
    // Helper: compute valid signature
    // -----------------------------------------------------------------------

    private String computeSignature(String ak, String sk, String timestamp, String nonce) throws Exception {
        String message = ak + "\n" + timestamp + "\n" + nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sk.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private void setupMocksForSuccess() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(credentialRepository.findActiveByAk(TEST_AK))
                .thenReturn(AkSkCredential.builder()
                        .ak(TEST_AK)
                        .sk(TEST_SK)
                        .userId(TEST_USER_ID)
                        .status(AkSkCredential.CredentialStatus.ACTIVE)
                        .build());
    }

    // -----------------------------------------------------------------------
    // Success case
    // -----------------------------------------------------------------------

    @Test
    void testVerifySuccess() throws Exception {
        setupMocksForSuccess();

        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String sign = computeSignature(TEST_AK, TEST_SK, ts, nonce);

        Long userId = authService.verify(TEST_AK, ts, nonce, sign);

        assertNotNull(userId);
        assertEquals(TEST_USER_ID, userId);
    }

    // -----------------------------------------------------------------------
    // Failure cases
    // -----------------------------------------------------------------------

    @Test
    void testVerifyWithNullParams() {
        assertNull(authService.verify(null, "123", "abc", "sign"));
        assertNull(authService.verify("ak", null, "abc", "sign"));
        assertNull(authService.verify("ak", "123", null, "sign"));
        assertNull(authService.verify("ak", "123", "abc", null));
    }

    @Test
    void testVerifyWithInvalidTimestampFormat() {
        assertNull(authService.verify(TEST_AK, "not-a-number", "abc", "sign"));
    }

    @Test
    void testVerifyWithExpiredTimestamp() {
        // No mocks needed â€?timestamp check fails before nonce/AK check
        // Timestamp 10 minutes ago (beyond 5-minute window)
        String ts = String.valueOf(Instant.now().getEpochSecond() - 600);
        assertNull(authService.verify(TEST_AK, ts, "abc", "sign"));
    }

    @Test
    void testVerifyWithFutureTimestamp() {
        // No mocks needed â€?timestamp check fails before nonce/AK check
        // Timestamp 10 minutes in the future
        String ts = String.valueOf(Instant.now().getEpochSecond() + 600);
        assertNull(authService.verify(TEST_AK, ts, "abc", "sign"));
    }

    @Test
    void testVerifyWithReplayedNonce() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Redis returns false = nonce already used
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        String ts = String.valueOf(Instant.now().getEpochSecond());
        assertNull(authService.verify(TEST_AK, ts, "replayed-nonce", "sign"));
    }

    @Test
    void testVerifyWithUnknownAk() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(credentialRepository.findActiveByAk("unknown-ak")).thenReturn(null);

        String ts = String.valueOf(Instant.now().getEpochSecond());
        assertNull(authService.verify("unknown-ak", ts, UUID.randomUUID().toString(), "sign"));

        // Verify nonce cleanup on failed lookup
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void testVerifyWithWrongSignature() throws Exception {
        setupMocksForSuccess();

        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();

        assertNull(authService.verify(TEST_AK, ts, nonce, "wrong-signature"));

        // Verify nonce cleanup on failed signature
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void testVerifyWithDifferentSkProducesDifferentSignature() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();

        String sign1 = computeSignature(TEST_AK, "sk-one", ts, nonce);
        String sign2 = computeSignature(TEST_AK, "sk-two", ts, nonce);

        assertNotEquals(sign1, sign2);
    }
}
