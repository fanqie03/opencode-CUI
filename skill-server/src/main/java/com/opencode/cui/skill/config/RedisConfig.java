package com.opencode.cui.skill.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Redis 配置类（多实例协调）。
 * 配置 Redis 消息监听容器的线程池、错误处理和恢复策略。
 */
@Slf4j
@Configuration
public class RedisConfig {

    /** 消息处理线程池核心线程数 */
    @Value("${skill.redis.listener.core-pool-size:5}")
    private int corePoolSize;

    /** 消息处理线程池最大线程数 */
    @Value("${skill.redis.listener.max-pool-size:50}")
    private int maxPoolSize;

    /** 线程空闲保活时间（秒） */
    @Value("${skill.redis.listener.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /** 消息处理线程池队列容量 */
    @Value("${skill.redis.listener.queue-capacity:200}")
    private int queueCapacity;

    /**
     * 配置 Redis 消息监听容器（Pub/Sub）。
     * 错误处理策略：记录日志后继续运行，不停止容器。
     * 恢复间隔：5000ms。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 配置消息处理线程池
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("redis-sub-");
        executor.initialize();
        container.setTaskExecutor(executor);

        // 配置订阅管理线程池
        ThreadPoolTaskExecutor subscriptionExecutor = new ThreadPoolTaskExecutor();
        subscriptionExecutor.setCorePoolSize(2);
        subscriptionExecutor.setMaxPoolSize(10);
        subscriptionExecutor.setQueueCapacity(50);
        subscriptionExecutor.setThreadNamePrefix("redis-subscription-");
        subscriptionExecutor.initialize();
        container.setSubscriptionExecutor(subscriptionExecutor);

        // 错误处理：记录日志后继续运行
        container.setErrorHandler(throwable -> {
            log.error("Redis listener error (continuing): {}", throwable.getMessage(), throwable);
        });

        // 连接恢复间隔
        container.setRecoveryInterval(5000L);

        log.info("Redis message listener container configured with thread pool " +
                "(core={}, max={}, queue={})", corePoolSize, maxPoolSize, queueCapacity);

        return container;
    }
}
