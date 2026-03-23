package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.AkSkCredential;
import com.opencode.cui.gateway.repository.AkSkCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * AkSkAuthService 单元测试。
 *
 * gateway 模式：时间窗口 + Nonce + ak_sk_credential 表 HMAC-SHA256 本地验签
 * remote 模式：时间窗口 + Nonce + L1→L2→L3 缓存分级（含外部 API）
 */
@ExtendWith(MockitoExtension.class)
class AkSkAuthServiceTest {

    private static final String TEST_AK = "test-ak-001";
    private static final String TEST_SK = "test-sk-secret-001";
    private static final String TEST_USER_ID = "1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private IdentityApiClient identityApiClient;

    @Mock
    private AkSkCredentialRepository akSkCredentialRepository;

    /** gateway 模式的 service（默认） */
    private AkSkAuthService gatewayService;

    /** remote 模式的 service */
    private AkSkAuthService remoteService;

    @BeforeEach
    void setUp() {
        gatewayService = new AkSkAuthService(
                redisTemplate, new ObjectMapper(), identityApiClient, akSkCredentialRepository,
                300L, 300L, 300L, 10000L, 3600L, "gateway");
        remoteService = new AkSkAuthService(
                redisTemplate, new ObjectMapper(), identityApiClient, akSkCredentialRepository,
                300L, 300L, 300L, 10000L, 3600L, "remote");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private String validTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private String randomNonce() {
        return UUID.randomUUID().toString();
    }

    /** 计算 HMAC-SHA256 签名（与 AkSkAuthService.hmacSha256 相同算法） */
    private static String computeSignature(String sk, String ak, String timestamp, String nonce) {
        try {
            String data = ak + timestamp + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sk.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void mockNonceSuccess() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
    }

    private void mockL2CacheMiss() {
        when(valueOperations.get(startsWith("auth:identity:"))).thenReturn(null);
        lenient().doNothing().when(valueOperations)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    private void mockExternalApiSuccess() {
        when(identityApiClient.isEnabled()).thenReturn(true);
        when(identityApiClient.check(eq(TEST_AK), anyString(), anyString(), anyString()))
                .thenReturn(TEST_USER_ID);
    }

    private AkSkCredential activeCredential() {
        return AkSkCredential.builder()
                .ak(TEST_AK)
                .sk(TEST_SK)
                .userId(TEST_USER_ID)
                .status(AkSkCredential.CredentialStatus.ACTIVE)
                .build();
    }

    // -----------------------------------------------------------------------
    // 参数校验（gateway/remote 通用）
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("参数校验")
    class ParameterValidation {

        @Test
        @DisplayName("缺少参数返回 null")
        void nullParamsReturnNull() {
            assertNull(gatewayService.verify(null, "123", "abc", "sign"));
            assertNull(gatewayService.verify("ak", null, "abc", "sign"));
            assertNull(gatewayService.verify("ak", "123", null, "sign"));
            assertNull(gatewayService.verify("ak", "123", "abc", null));
        }

        @Test
        @DisplayName("时间戳格式错误返回 null")
        void invalidTimestampFormat() {
            assertNull(gatewayService.verify(TEST_AK, "not-a-number", "abc", "sign"));
        }

        @Test
        @DisplayName("时间戳过期（10 分钟前）返回 null")
        void expiredTimestamp() {
            String ts = String.valueOf(Instant.now().getEpochSecond() - 600);
            assertNull(gatewayService.verify(TEST_AK, ts, "abc", "sign"));
        }

        @Test
        @DisplayName("时间戳超前（10 分钟后）返回 null")
        void futureTimestamp() {
            String ts = String.valueOf(Instant.now().getEpochSecond() + 600);
            assertNull(gatewayService.verify(TEST_AK, ts, "abc", "sign"));
        }
    }

    // -----------------------------------------------------------------------
    // Nonce 防重放（gateway/remote 通用）
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Nonce 防重放")
    class NonceReplay {

        @Test
        @DisplayName("重复 nonce 返回 null")
        void replayedNonce() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(false);

            assertNull(gatewayService.verify(TEST_AK, validTimestamp(), "replayed", "sign"));
        }
    }

    // -----------------------------------------------------------------------
    // Gateway 模式（本地 HMAC-SHA256 验签，ak_sk_credential 表）
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("gateway 验签模式")
    class GatewayMode {

        @Test
        @DisplayName("本地验签成功，返回 userId")
        void gatewayVerifySuccess() {
            mockNonceSuccess();
            when(akSkCredentialRepository.findActiveByAk(TEST_AK)).thenReturn(activeCredential());

            String ts = validTimestamp();
            String nonce = randomNonce();
            String sign = computeSignature(TEST_SK, TEST_AK, ts, nonce);

            String userId = gatewayService.verify(TEST_AK, ts, nonce, sign);

            assertEquals(TEST_USER_ID, userId);
            // 外部 API 不应被调用
            verifyNoInteractions(identityApiClient);
        }

        @Test
        @DisplayName("凭证不存在，返回 null")
        void gatewayCredentialNotFound() {
            mockNonceSuccess();
            when(akSkCredentialRepository.findActiveByAk(TEST_AK)).thenReturn(null);

            assertNull(gatewayService.verify(TEST_AK, validTimestamp(), randomNonce(), "any-sign"));
            verifyNoInteractions(identityApiClient);
        }

        @Test
        @DisplayName("签名不匹配，返回 null")
        void gatewaySignatureMismatch() {
            mockNonceSuccess();
            when(akSkCredentialRepository.findActiveByAk(TEST_AK)).thenReturn(activeCredential());

            assertNull(gatewayService.verify(TEST_AK, validTimestamp(), randomNonce(), "wrong-signature"));
            verifyNoInteractions(identityApiClient);
        }
    }

    // -----------------------------------------------------------------------
    // Remote 模式 — L3 外部 API
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("remote 模式 — L3 外部身份 API")
    class RemoteL3ExternalApi {

        @Test
        @DisplayName("L3 外部 API 验证成功，返回 userId")
        void l3Success() {
            mockNonceSuccess();
            mockL2CacheMiss();
            mockExternalApiSuccess();

            String userId = remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "valid-sig");

            assertEquals(TEST_USER_ID, userId);
        }

        @Test
        @DisplayName("L3 外部 API 明确拒绝，返回 null")
        void l3Rejected() {
            mockNonceSuccess();
            mockL2CacheMiss();
            when(identityApiClient.isEnabled()).thenReturn(true);
            when(identityApiClient.check(eq(TEST_AK), anyString(), anyString(), anyString()))
                    .thenReturn(null);

            assertNull(remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "bad-sig"));
        }

        @Test
        @DisplayName("L3 外部 API 不可用，返回 null")
        void l3UnavailableRejects() {
            mockNonceSuccess();
            mockL2CacheMiss();
            when(identityApiClient.isEnabled()).thenReturn(true);
            when(identityApiClient.check(eq(TEST_AK), anyString(), anyString(), anyString()))
                    .thenThrow(new IdentityApiClient.IdentityApiException("Connection refused"));

            assertNull(remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "sign"));
            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("外部 API 未配置，返回 null")
        void l3NotConfiguredRejects() {
            mockNonceSuccess();
            mockL2CacheMiss();
            when(identityApiClient.isEnabled()).thenReturn(false);

            assertNull(remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "sign"));
            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("L3 成功后回填 L2 Redis 缓存")
        void l3BackfillsL2Cache() {
            mockNonceSuccess();
            mockL2CacheMiss();
            mockExternalApiSuccess();

            remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "valid-sig");

            verify(valueOperations).set(
                    eq("auth:identity:" + TEST_AK), anyString(), eq(3600L), eq(TimeUnit.SECONDS));
        }
    }

    // -----------------------------------------------------------------------
    // Remote 模式 — L1 Caffeine 缓存
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("remote 模式 — L1 Caffeine 缓存")
    class RemoteL1CaffeineCache {

        @Test
        @DisplayName("第二次请求命中 L1 缓存，不调外部 API")
        void l1CacheHitOnSecondCall() {
            mockNonceSuccess();
            mockL2CacheMiss();
            mockExternalApiSuccess();

            assertEquals(TEST_USER_ID, remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "sig"));
            assertEquals(TEST_USER_ID, remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "sig"));

            verify(identityApiClient, times(1)).check(eq(TEST_AK), anyString(), anyString(), anyString());
        }
    }

    // -----------------------------------------------------------------------
    // Remote 模式 — L2 Redis 缓存
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("remote 模式 — L2 Redis 缓存")
    class RemoteL2RedisCache {

        @Test
        @DisplayName("L2 Redis 缓存命中，不调外部 API")
        void l2CacheHit() {
            mockNonceSuccess();
            String cachedJson = "{\"userId\":\"" + TEST_USER_ID + "\",\"level\":\"L3\"}";
            when(valueOperations.get(startsWith("auth:identity:"))).thenReturn(cachedJson);

            assertEquals(TEST_USER_ID, remoteService.verify(TEST_AK, validTimestamp(), randomNonce(), "sig"));

            verify(identityApiClient, never()).check(anyString(), anyString(), anyString(), anyString());
        }
    }
}
