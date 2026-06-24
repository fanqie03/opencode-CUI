package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.repository.SkillSessionRepository;
import com.opencode.cui.skill.telemetry.metrics.ApiCallMetricsService;
import com.opencode.cui.skill.telemetry.metrics.MetricServiceEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * AssistantId 解析服务。
 * 根据 ak 的 toolType 和会话的 assistantAccount，从 persona 接口获取 assistantId。
 *
 * 缓存策略：
 * - L1 Caffeine: session → assistantAccount（5min, max 1000）
 * - L2 Caffeine: ak → toolType（5min, max 500）
 * - L3 Redis: assistantAccount → assistantId（可配置 TTL）
 *
 * 降级策略：任何异常或数据缺失时静默降级，返回 null，消息正常发送。
 */
@Slf4j
@Service
public class AssistantIdResolverService {

    private final AssistantIdProperties properties;
    private final SkillSessionRepository sessionRepository;
    private final GatewayApiClient gatewayApiClient;
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApiCallMetricsService apiCallMetricsService;

    /** L1: sessionId → assistantAccount */
    private final Cache<String, String> sessionCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    /** L2: ak → toolType */
    private final Cache<String, String> toolTypeCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(500)
            .build();

    private static final String REDIS_KEY_PREFIX = "ss:assistant-id:";
    /** Caffeine 中用于标记"查过但 session 不存在或无 assistantAccount"的占位符 */
    private static final String ABSENT = "__ABSENT__";

    public AssistantIdResolverService(
            AssistantIdProperties properties,
            SkillSessionRepository sessionRepository,
            GatewayApiClient gatewayApiClient,
            RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ApiCallMetricsService apiCallMetricsService) {
        this.properties = properties;
        this.sessionRepository = sessionRepository;
        this.gatewayApiClient = gatewayApiClient;
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.apiCallMetricsService = apiCallMetricsService;
    }

    /**
     * 根据 ak 和 sessionId 解析 assistantId。
     *
     * @param ak        Agent 应用密钥
     * @param sessionId Skill 侧会话 ID（String 类型，内部转为 Long）
     * @return assistantId，不需要注入或解析失败时返回 null
     */
    public String resolve(String ak, String sessionId) {
        if (!properties.isEnabled()) {
            return null;
        }

        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        if (ak == null || ak.isBlank()) {
            return null;
        }

        // sessionId String → Long 转换
        long sessionIdLong;
        try {
            sessionIdLong = Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.warn("[SKIP] AssistantIdResolver.resolve: invalid sessionId={}", sessionId);
            return null;
        }

        log.info("[ENTRY] AssistantIdResolver.resolve: ak={}, sessionId={}", ak, sessionId);

        long start = System.nanoTime();
        try {
            // Step 1: 获取 assistantAccount（L1 Caffeine 缓存）
            String assistantAccount = getAssistantAccount(sessionId, sessionIdLong);
            if (assistantAccount == null) {
                return null;
            }

            // Step 2: 检查 toolType（L2 Caffeine 缓存）
            if (!isTargetToolType(ak)) {
                return null;
            }

            // Step 3: 查 Redis 缓存（L3）
            String cacheKey = buildRedisCacheKey(assistantAccount, ak);
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.info("[EXIT] AssistantIdResolver.resolve: ak={}, sessionId={}, assistantId={}, source=cache, durationMs={}",
                        ak, sessionId, cached, elapsedMs);
                return cached;
            }

            // Step 4: 调用 persona 接口
            String assistantId = fetchFromPersonaApi(assistantAccount, ak);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (assistantId != null && !assistantId.isBlank()) {
                // 写入 Redis 缓存
                redisTemplate.opsForValue().set(cacheKey, assistantId,
                        Duration.ofMinutes(properties.getCacheTtlMinutes()));
                log.info("[EXIT] AssistantIdResolver.resolve: ak={}, sessionId={}, assistantId={}, source=api, durationMs={}",
                        ak, sessionId, assistantId, elapsedMs);
                return assistantId;
            }

            log.warn("[EXIT] AssistantIdResolver.resolve: ak={}, sessionId={}, assistantId=null, durationMs={}",
                    ak, sessionId, elapsedMs);
            return null;

        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[ERROR] AssistantIdResolver.resolve: ak={}, sessionId={}, durationMs={}, error={}",
                    ak, sessionId, elapsedMs, e.getMessage());
            return null;
        }
    }

    /**
     * 从 L1 缓存或数据库获取 assistantAccount。
     */
    private String getAssistantAccount(String sessionId, long sessionIdLong) {
        String cached = sessionCache.getIfPresent(sessionId);
        if (ABSENT.equals(cached)) {
            return null;
        }
        if (cached != null) {
            return cached;
        }

        SkillSession session = sessionRepository.findById(sessionIdLong);
        if (session == null || session.getAssistantAccount() == null || session.getAssistantAccount().isBlank()) {
            sessionCache.put(sessionId, ABSENT);
            return null;
        }

        String assistantAccount = session.getAssistantAccount();
        sessionCache.put(sessionId, assistantAccount);
        return assistantAccount;
    }

    /**
     * 从 L2 缓存或 Gateway API 检查 ak 的 toolType 是否匹配。
     */
    private boolean isTargetToolType(String ak) {
        String cached = toolTypeCache.getIfPresent(ak);
        if (ABSENT.equals(cached)) {
            return false;
        }
        if (cached != null) {
            return cached.equalsIgnoreCase(properties.getTargetToolType());
        }

        AgentSummary agent = gatewayApiClient.getAgentByAk(ak);
        if (agent == null || agent.getToolType() == null) {
            toolTypeCache.put(ak, ABSENT);
            return false;
        }

        toolTypeCache.put(ak, agent.getToolType());
        return agent.getToolType().equalsIgnoreCase(properties.getTargetToolType());
    }

    /**
     * 调用 persona 接口获取 assistantId。
     */
    private String fetchFromPersonaApi(String assistantAccount, String ak) {
        String urlTemplate = "/welink-persona-settings/persona-new";
        MetricServiceEnum metricService = MetricServiceEnum.BUSINESS_CENTER_PERSONA_QUERY;
        boolean success = false;
        long start = System.currentTimeMillis();
        String url = properties.getPersonaBaseUrl()
                + urlTemplate + "?personaWelinkId=" + assistantAccount;

        try {
            MdcHelper.putBusinessDomain(metricService.getId());
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);
            long elapsedMs = (System.currentTimeMillis() - start);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[EXT_CALL] PersonaAPI.getPersona non-success: assistantAccount={}, status={}, durationMs={}",
                        assistantAccount, response.getStatusCode(), elapsedMs);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || !dataNode.isArray() || dataNode.isEmpty()) {
                log.warn("[EXT_CALL] PersonaAPI.getPersona empty_data: assistantAccount={}, durationMs={}",
                        assistantAccount, elapsedMs);
                return null;
            }

            // match-ak 过滤
            JsonNode target = null;
            if (properties.isMatchAk() && ak != null) {
                for (JsonNode item : dataNode) {
                    if (ak.equals(item.path("ak").asText(null))) {
                        target = item;
                        break;
                    }
                }
            } else {
                target = dataNode.get(0);
            }

            if (target == null) {
                log.warn("[EXT_CALL] PersonaAPI.getPersona no_match: assistantAccount={}, matchAk={}, durationMs={}",
                        assistantAccount, ak, elapsedMs);
                return null;
            }

            String id = target.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                log.info("[EXT_CALL] PersonaAPI.getPersona success: assistantAccount={}, assistantId={}, durationMs={}",
                        assistantAccount, id, elapsedMs);
            }
            success = true;
            return id;

        } catch (Exception e) {
            long elapsedMs = (System.currentTimeMillis() - start);
            log.warn("[EXT_CALL] PersonaAPI.getPersona failed: assistantAccount={}, durationMs={}, error={}",
                    assistantAccount, elapsedMs, e.getMessage());
            return null;
        } finally {
            apiCallMetricsService.recordApiCall(metricService, urlTemplate, success, System.currentTimeMillis() - start);
            MdcHelper.putBusinessDomain(null);
        }
    }

    /**
     * 构建 Redis 缓存 key。
     */
    private String buildRedisCacheKey(String assistantAccount, String ak) {
        if (properties.isMatchAk()) {
            return REDIS_KEY_PREFIX + assistantAccount + ":" + ak;
        }
        return REDIS_KEY_PREFIX + assistantAccount;
    }
}
