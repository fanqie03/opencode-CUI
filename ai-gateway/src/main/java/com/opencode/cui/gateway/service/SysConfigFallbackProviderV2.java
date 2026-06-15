package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads cloud route fallback config directly from skill-server SysConfig.
 *
 * <p>Key format: {@code type=cloud_route_fallback_v2,
 * key={businessTag}:{chat|question|permission}}. Values are JSON objects with
 * {@code channelAddress}, {@code channelType}, and {@code authType}.</p>
 */
@Slf4j
@Component
public class SysConfigFallbackProviderV2 {

    private static final String SS_CONFIG_TYPE = "cloud_route_fallback_v2";

    /** scope literal to SysConfig short name. */
    private static final Map<String, String> SCOPE_TO_SHORT_NAME = Map.of(
            "callback:weagent:chat", "chat",
            "callback:weagent:question_reply", "question",
            "callback:weagent:permission_reply", "permission",
            "callback:weagent:abort", "abort"
    );

    private final SkillServerConfigClient skillServerConfigClient;
    private final ObjectMapper objectMapper;
    private final long fallbackCacheTtlMs;

    /** 按 (cloudProfile, scope 短名) 缓存兜底配置；value 为 (CallbackConfig, fetchedAtMs)。 */
    private final ConcurrentHashMap<String, AtomicReference<CachedFallback>> cacheByKey =
            new ConcurrentHashMap<>();

    public SysConfigFallbackProviderV2(SkillServerConfigClient skillServerConfigClient,
                                       ObjectMapper objectMapper,
                                       @Value("${gateway.cloud-route.sysconfig-cache-ttl-ms:300000}") long fallbackCacheTtlMs) {
        this.skillServerConfigClient = skillServerConfigClient;
        this.objectMapper = objectMapper;
        this.fallbackCacheTtlMs = fallbackCacheTtlMs;
    }

    /**
     * 加载 (ak, scope, businessTag) 对应的兜底配置。
     *
     * @return 解析成功的 {@link CallbackConfig}；scope 不在白名单 / businessTag 缺失 /
     *         SysConfig 缺失 / JSON 解析失败一律返回 null。
     */
    public CallbackConfig load(String ak, String scope, String businessTag) {
        if (businessTag == null || businessTag.isBlank()) {
            log.warn("[FALLBACK_PROVIDER_V2] businessTag is blank: ak={}, scope={}", ak, scope);
            return null;
        }
        String shortName = SCOPE_TO_SHORT_NAME.get(scope);
        if (shortName == null) {
            log.warn("[FALLBACK_PROVIDER_V2] scope not in fallback whitelist: ak={}, scope={}, businessTag={}",
                    ak, scope, businessTag);
            return null;
        }

        String ssKey = businessTag + ":" + shortName;

        long now = System.currentTimeMillis();
        AtomicReference<CachedFallback> ref = cacheByKey.computeIfAbsent(
                ssKey, k -> new AtomicReference<>());
        CachedFallback cached = ref.get();
        CallbackConfig template;
        if (cached != null && now - cached.fetchedAtMs < fallbackCacheTtlMs) {
            template = cached.config;
        } else {
            template = fetchFromSysConfig(ssKey, businessTag, shortName);
            ref.set(new CachedFallback(template, now));
        }

        if (template == null) {
            return null;
        }
        // 拷贝模板并按本次请求填充 ak / scope（缓存的 template 本身不带 ak/scope，避免跨请求串号）
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk(ak);
        cfg.setScope(scope);
        cfg.setChannelAddress(template.getChannelAddress());
        cfg.setChannelType(template.getChannelType());
        cfg.setAuthType(template.getAuthType());
        cfg.setAppId(null);
        log.info("[FALLBACK_PROVIDER_V2] loaded fallback: ak={}, scope={}, businessTag={}, shortName={}, channelType={}, authType={}",
                ak, scope, businessTag, shortName, cfg.getChannelType(), cfg.getAuthType());
        return cfg;
    }

    /**
     * 从 skill-server SysConfig 拉取并解析 JSON；任何失败返回 null。
     */
    private CallbackConfig fetchFromSysConfig(String ssKey, String businessTag, String shortName) {
        String json;
        try {
            json = skillServerConfigClient.getConfigValue(SS_CONFIG_TYPE, ssKey);
        } catch (Exception e) {
            log.warn("[FALLBACK_PROVIDER_V2] SS config read failed: businessTag={}, shortName={}, error={}",
                    businessTag, shortName, e.getMessage());
            return null;
        }
        if (json == null || json.isBlank()) {
            log.warn("[FALLBACK_PROVIDER_V2] SS config missing: type={}, key={}", SS_CONFIG_TYPE, ssKey);
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String channelAddress = textOrNull(root.path("channelAddress"));
            String channelType = textOrNull(root.path("channelType"));
            String authType = textOrNull(root.path("authType"));
            if (channelAddress == null || channelType == null || authType == null) {
                log.warn("[FALLBACK_PROVIDER_V2] SS config incomplete: businessTag={}, shortName={}, channelAddress={}, channelType={}, authType={}",
                        businessTag, shortName, channelAddress, channelType, authType);
                return null;
            }
            CallbackConfig cfg = new CallbackConfig();
            cfg.setChannelAddress(channelAddress);
            cfg.setChannelType(channelType);
            cfg.setAuthType(authType);
            return cfg;
        } catch (Exception e) {
            log.warn("[FALLBACK_PROVIDER_V2] SS config json parse failed: businessTag={}, shortName={}, error={}",
                    businessTag, shortName, e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /** in-memory 兜底缓存 entry；config 可能为 null（表示上次拉取就缺失）。 */
    private record CachedFallback(CallbackConfig config, long fetchedAtMs) {}
}
