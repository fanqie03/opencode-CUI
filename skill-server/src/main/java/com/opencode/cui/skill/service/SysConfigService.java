package com.opencode.cui.skill.service;

import com.opencode.cui.skill.repository.SysConfigMapper;
import com.opencode.cui.skill.model.SysConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 系统配置服务。
 * 提供带 Redis 缓存的配置读取（getValue），以及配置的增删改查操作。
 *
 * <p>
 * 缓存策略：
 * <ul>
 *   <li>缓存 key 格式：{@code ss:config:{configType}:{configKey}}</li>
 *   <li>TTL：30 分钟</li>
 *   <li>仅缓存 status=1（启用）的配置值</li>
 *   <li>create/update 时主动删除对应缓存，delete 时缓存自然过期</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private static final String CACHE_PREFIX = "ss:config:";
    private static final long CACHE_TTL_MINUTES = 30L;

    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 获取配置值。优先读 Redis 缓存，未命中时查 DB。
     * 只有 status=1 的配置才会返回值并写入缓存。
     *
     * @param configType 配置类型
     * @param configKey  配置键
     * @return 配置值，不存在或已禁用时返回 null
     */
    public String getValue(String configType, String configKey) {
        String cacheKey = buildCacheKey(configType, configKey);

        // 1. 尝试读缓存（Redis 故障时降级直查 DB）
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("Config cache hit: {}", cacheKey);
                return cached;
            }
        } catch (RuntimeException e) {
            log.warn("Redis read failed for {}, falling back to DB: {}", cacheKey, e.getMessage());
        }

        // 2. 缓存未命中，查 DB
        SysConfig config = sysConfigMapper.findByTypeAndKey(configType, configKey);
        if (config == null) {
            log.debug("Config not found in DB: {}", cacheKey);
            return null;
        }

        // 3. status=0 禁用，不缓存
        if (config.getStatus() == null || config.getStatus() != 1) {
            log.debug("Config is disabled, skip caching: {}", cacheKey);
            return null;
        }

        // 4. status=1，写缓存并返回（Redis 故障时静默）
        String value = config.getConfigValue();
        try {
            redisTemplate.opsForValue().set(cacheKey, value, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Config loaded from DB and cached: {}", cacheKey);
        } catch (RuntimeException e) {
            log.warn("Redis write failed for {}: {}", cacheKey, e.getMessage());
        }
        return value;
    }

    /**
     * 按配置类型查询所有配置，按 sort_order 升序排列。
     *
     * @param configType 配置类型
     * @return 配置列表
     */
    public List<SysConfig> listByType(String configType) {
        return sysConfigMapper.findByType(configType);
    }

    /**
     * 新增配置。插入 DB 后清除对应缓存（防止脏读）。
     *
     * @param config 待新增的配置
     */
    @Transactional
    public void create(SysConfig config) {
        sysConfigMapper.insert(config);
        evictCache(config.getConfigType(), config.getConfigKey());
        log.info("SysConfig created: type={}, key={}", config.getConfigType(), config.getConfigKey());
    }

    /**
     * 更新配置。更新 DB 后清除对应缓存。
     *
     * @param config 待更新的配置（需含 id）
     */
    @Transactional
    public void update(SysConfig config) {
        sysConfigMapper.update(config);
        evictCache(config.getConfigType(), config.getConfigKey());
        log.info("SysConfig updated: id={}, type={}, key={}", config.getId(), config.getConfigType(), config.getConfigKey());
    }

    /**
     * 删除配置。仅删除 DB 记录，缓存依赖自然过期（TTL 30min）。
     *
     * @param id 配置主键
     */
    @Transactional
    public void delete(Long id) {
        sysConfigMapper.deleteById(id);
        log.info("SysConfig deleted: id={}", id);
    }

    // ------------------------------------------------------------------ private

    private String buildCacheKey(String configType, String configKey) {
        return CACHE_PREFIX + configType + ":" + configKey;
    }

    private void evictCache(String configType, String configKey) {
        String cacheKey = buildCacheKey(configType, configKey);
        try {
            redisTemplate.delete(cacheKey);
            log.debug("Config cache evicted: {}", cacheKey);
        } catch (RuntimeException e) {
            log.warn("Redis evict failed for {}: {}", cacheKey, e.getMessage());
        }
    }
}
