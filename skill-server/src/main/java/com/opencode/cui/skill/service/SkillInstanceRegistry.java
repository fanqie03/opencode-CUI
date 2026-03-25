package com.opencode.cui.skill.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SS 实例心跳注册器。
 *
 * <p>启动时写入 Redis 心跳 key {@code ss:internal:instance:{instanceId}}，
 * 并定时刷新 TTL，确保本实例在线期间 key 持续存活。
 * 用于 Task 2.6 失联 owner 探活：其他 SS 实例通过 {@link #isInstanceAlive} 判断
 * 目标实例是否仍然存活，决策是否接管其路由会话。
 *
 * <p>心跳 key TTL：30 秒；刷新间隔默认 10 秒（可通过配置项覆盖）。
 */
@Slf4j
@Component
public class SkillInstanceRegistry {

    /** 心跳 key TTL（秒）：比刷新间隔（10s）留足 3× 余量，避免误判失联。 */
    private static final int HEARTBEAT_TTL_SECONDS = 30;

    private final StringRedisTemplate redisTemplate;
    private final String instanceId;

    public SkillInstanceRegistry(StringRedisTemplate redisTemplate,
            @Value("${skill.instance-id:${HOSTNAME:skill-server-local}}") String instanceId) {
        this.redisTemplate = redisTemplate;
        this.instanceId = instanceId;
    }

    /**
     * 启动时注册实例心跳。
     * 写入 Redis key 并记录 ENTRY 日志。
     */
    @PostConstruct
    public void register() {
        writeHeartbeat();
        log.info("[ENTRY] SkillInstanceRegistry.register: instanceId={}", instanceId);
    }

    /**
     * 定时刷新心跳，防止 key 过期导致误判失联。
     * 默认每 10 秒执行一次，可通过 {@code skill.instance-registry.refresh-interval-ms} 配置。
     */
    @Scheduled(fixedDelayString = "${skill.instance-registry.refresh-interval-ms:10000}")
    public void refreshHeartbeat() {
        writeHeartbeat();
        log.info("[ENTRY] SkillInstanceRegistry.refreshHeartbeat: instanceId={}", instanceId);
    }

    /**
     * 服务关闭时主动删除心跳 key，加速其他实例感知本实例下线。
     */
    @PreDestroy
    public void destroy() {
        redisTemplate.delete(redisKey());
        log.info("[EXIT] SkillInstanceRegistry.destroy: instanceId={}", instanceId);
    }

    /**
     * 探测目标实例是否存活。
     *
     * <p>通过检查 Redis 心跳 key 是否存在来判断目标实例在线状态。
     * 用于 Task 2.6 失联 owner 探活逻辑。
     *
     * @param targetInstanceId 目标 SS 实例 ID
     * @return {@code true} 表示目标实例心跳 key 存在（实例在线）；{@code false} 表示已失联
     */
    public boolean isInstanceAlive(String targetInstanceId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("ss:internal:instance:" + targetInstanceId));
    }

    /**
     * 返回本实例 ID。
     *
     * @return 实例 ID，来源于配置 {@code skill.instance-id} 或环境变量 {@code HOSTNAME}
     */
    public String getInstanceId() {
        return instanceId;
    }

    // ==================== 私有方法 ====================

    private void writeHeartbeat() {
        redisTemplate.opsForValue().set(redisKey(), "alive", Duration.ofSeconds(HEARTBEAT_TTL_SECONDS));
    }

    private String redisKey() {
        return "ss:internal:instance:" + instanceId;
    }
}
