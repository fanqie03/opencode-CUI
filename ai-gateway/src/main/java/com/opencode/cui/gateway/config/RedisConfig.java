package com.opencode.cui.gateway.config;

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

    /** 监听器线程池核心线程数 */
    @Value("${gateway.redis.listener.core-pool-size:5}")
    private int listenerCorePoolSize;

    /** 监听器线程池最大线程数 */
    @Value("${gateway.redis.listener.max-pool-size:50}")
    private int listenerMaxPoolSize;

    /** 监听器线程池队列容量 */
    @Value("${gateway.redis.listener.queue-capacity:100}")
    private int listenerQueueCapacity;

    /** 订阅线程池核心线程数 */
    @Value("${gateway.redis.subscription.core-pool-size:2}")
    private int subscriptionCorePoolSize;

    /** 订阅线程池最大线程数 */
    @Value("${gateway.redis.subscription.max-pool-size:10}")
    private int subscriptionMaxPoolSize;

    /** 订阅线程池队列容量 */
    @Value("${gateway.redis.subscription.queue-capacity:50}")
    private int subscriptionQueueCapacity;

    /** 连接断开后的恢复间隔（毫秒） */
    @Value("${gateway.redis.recovery-interval-ms:5000}")
    private long recoveryIntervalMs;

    /**
     * Redis 消息处理线程池。
     * 声明为 Bean 由 Spring 管理生命周期（自动 shutdown）。
     */
    @Bean(name = "redisListenerExecutor")
    public ThreadPoolTaskExecutor redisListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(listenerCorePoolSize);
        executor.setMaxPoolSize(listenerMaxPoolSize);
        executor.setQueueCapacity(listenerQueueCapacity);
        executor.setThreadNamePrefix("redis-listener-");
        return executor;
    }

    /**
     * Redis 订阅管理线程池。
     * 声明为 Bean 由 Spring 管理生命周期（自动 shutdown）。
     */
    @Bean(name = "redisSubscriptionExecutor")
    public ThreadPoolTaskExecutor redisSubscriptionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(subscriptionCorePoolSize);
        executor.setMaxPoolSize(subscriptionMaxPoolSize);
        executor.setQueueCapacity(subscriptionQueueCapacity);
        executor.setThreadNamePrefix("redis-subscription-");
        return executor;
    }

    /**
     * 配置 Redis 消息监听容器（Pub/Sub）。
     * 错误处理策略：记录日志后继续运行，不停止容器。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            ThreadPoolTaskExecutor redisListenerExecutor,
            ThreadPoolTaskExecutor redisSubscriptionExecutor) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(redisListenerExecutor);
        container.setSubscriptionExecutor(redisSubscriptionExecutor);

        // 错误处理：记录日志后继续运行
        container.setErrorHandler(throwable -> {
            log.error("Redis listener error (continuing): {}", throwable.getMessage(), throwable);
        });

        container.setRecoveryInterval(recoveryIntervalMs);

        log.info("Redis message listener container configured with thread pool " +
                "(max-active={}, core={}, queue={})",
                listenerMaxPoolSize, listenerCorePoolSize, listenerQueueCapacity);

        return container;
    }
}
