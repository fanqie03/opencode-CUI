package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.AssistantResolveResult;
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

@Slf4j
@Service
public class AssistantAccountResolverService {

    private static final String CACHE_KEY_PREFIX = "assistantAccount:ak:";
    private static final String OWNER_CACHE_KEY_PREFIX = "assistantAccount:owner:";

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final String resolveUrl;
    private final String resolveToken;
    private final int cacheTtlMinutes;

    public AssistantAccountResolverService(
            RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-url:}") String resolveUrl,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-token:}") String resolveToken,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.cache-ttl-minutes:30}") int cacheTtlMinutes) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.resolveUrl = resolveUrl;
        this.resolveToken = resolveToken;
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    /**
     * Resolve assistant account to AK and owner WeLink ID.
     *
     * @param assistantAccount the assistant's platform account identifier
     * @return resolve result containing ak and ownerWelinkId, or null if resolution
     *         fails
     */
    public AssistantResolveResult resolve(String assistantAccount) {
        if (assistantAccount == null || assistantAccount.isBlank() || resolveUrl == null || resolveUrl.isBlank()) {
            return null;
        }

        String akCacheKey = CACHE_KEY_PREFIX + assistantAccount;
        String ownerCacheKey = OWNER_CACHE_KEY_PREFIX + assistantAccount;
        String cachedAk = redisTemplate.opsForValue().get(akCacheKey);
        String cachedOwner = redisTemplate.opsForValue().get(ownerCacheKey);
        if (cachedAk != null && !cachedAk.isBlank() && cachedOwner != null && !cachedOwner.isBlank()) {
            return new AssistantResolveResult(cachedAk, cachedOwner);
        }

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
            AssistantResolveResult result = extractResult(response.getBody());
            if (result == null) {
                log.warn("Failed to resolve assistant account: assistantAccount={}", assistantAccount);
                return null;
            }
            Duration ttl = Duration.ofMinutes(cacheTtlMinutes);
            redisTemplate.opsForValue().set(akCacheKey, result.ak(), ttl);
            redisTemplate.opsForValue().set(ownerCacheKey, result.ownerWelinkId(), ttl);
            return result;
        } catch (Exception e) {
            log.warn("Resolve assistant account failed: assistantAccount={}, error={}",
                    assistantAccount, e.getMessage());
            return null;
        }
    }

    /**
     * Convenience method: resolve only the AK.
     */
    public String resolveAk(String assistantAccount) {
        AssistantResolveResult result = resolve(assistantAccount);
        return result != null ? result.ak() : null;
    }

    private AssistantResolveResult extractResult(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        String ak = extractField(body, "appKey", "ak");
        String ownerWelinkId = extractField(body, "ownerWelinkId", "welinkId");
        if (ak == null || ak.isBlank()) {
            return null;
        }
        if (ownerWelinkId == null || ownerWelinkId.isBlank()) {
            log.warn("ownerWelinkId not found in resolve response, using assistantAccount as fallback");
            return null;
        }
        return new AssistantResolveResult(ak, ownerWelinkId);
    }

    private String extractField(JsonNode body, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String nested = body.path("data").path(fieldName).asText(null);
            if (nested != null && !nested.isBlank()) {
                return nested;
            }
            String direct = body.path(fieldName).asText(null);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        return null;
    }
}
