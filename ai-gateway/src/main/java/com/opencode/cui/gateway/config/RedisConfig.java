package com.opencode.cui.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Redis configuration for multi-instance coordination.
 * Configures message listener container with thread pool and error handling.
 */
@Slf4j
@Configuration
public class RedisConfig {

    @Value("${gateway.redis.listener.core-pool-size:5}")
    private int listenerCorePoolSize;

    @Value("${gateway.redis.listener.max-pool-size:50}")
    private int listenerMaxPoolSize;

    @Value("${gateway.redis.listener.queue-capacity:100}")
    private int listenerQueueCapacity;

    @Value("${gateway.redis.subscription.core-pool-size:2}")
    private int subscriptionCorePoolSize;

    @Value("${gateway.redis.subscription.max-pool-size:10}")
    private int subscriptionMaxPoolSize;

    @Value("${gateway.redis.subscription.queue-capacity:50}")
    private int subscriptionQueueCapacity;

    @Value("${gateway.redis.recovery-interval-ms:5000}")
    private long recoveryIntervalMs;

    /**
     * Task executor for Redis message processing.
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
     * Subscription executor for managing Redis subscriptions.
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
     * Configure Redis message listener container for pub/sub.
     * Error handling: log and continue (don't stop container).
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

        // Error handling: log and continue
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
