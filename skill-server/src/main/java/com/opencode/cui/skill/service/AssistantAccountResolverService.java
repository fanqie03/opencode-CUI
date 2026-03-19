package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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

    public String resolveAk(String assistantAccount) {
        if (assistantAccount == null || assistantAccount.isBlank() || resolveUrl == null || resolveUrl.isBlank()) {
            return null;
        }

        String cacheKey = CACHE_KEY_PREFIX + assistantAccount;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return cached;
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
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(requestUrl, request, JsonNode.class);
            String ak = extractAk(response.getBody());
            if (ak == null || ak.isBlank()) {
                log.warn("Failed to resolve assistant account: assistantAccount={}", assistantAccount);
                return null;
            }
            redisTemplate.opsForValue().set(cacheKey, ak, Duration.ofMinutes(cacheTtlMinutes));
            return ak;
        } catch (Exception e) {
            log.warn("Resolve assistant account failed: assistantAccount={}, error={}",
                    assistantAccount, e.getMessage());
            return null;
        }
    }

    private String extractAk(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        String appKey = body.path("data").path("appKey").asText(null);
        if (appKey != null && !appKey.isBlank()) {
            return appKey;
        }
        String directAppKey = body.path("appKey").asText(null);
        if (directAppKey != null && !directAppKey.isBlank()) {
            return directAppKey;
        }
        String direct = body.path("ak").asText(null);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String nested = body.path("data").path("ak").asText(null);
        if (nested != null && !nested.isBlank()) {
            return nested;
        }
        return null;
    }
}
