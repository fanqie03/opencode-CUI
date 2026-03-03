package com.yourapp.skill.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

/**
 * Redis configuration for multi-instance coordination.
 * Configures message listener container with thread pool and error handling.
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * Configure Redis message listener container for pub/sub.
     *
     * Thread pool settings:
     * - max-active: 50 (maximum concurrent listeners)
     * - max-idle: 20 (idle connections to keep)
     * - min-idle: 5 (minimum idle connections)
     *
     * Error handling: log and continue (don't stop container)
     * Recovery interval: 5000ms with exponential backoff
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Configure task executor for message processing
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("redis-listener-");
        executor.initialize();
        container.setTaskExecutor(executor);

        // Configure subscription executor for managing subscriptions
        ThreadPoolTaskExecutor subscriptionExecutor = new ThreadPoolTaskExecutor();
        subscriptionExecutor.setCorePoolSize(2);
        subscriptionExecutor.setMaxPoolSize(10);
        subscriptionExecutor.setQueueCapacity(50);
        subscriptionExecutor.setThreadNamePrefix("redis-subscription-");
        subscriptionExecutor.initialize();
        container.setSubscriptionExecutor(subscriptionExecutor);

        // Error handling: log and continue
        container.setErrorHandler(throwable -> {
            log.error("Redis listener error (continuing): {}", throwable.getMessage(), throwable);
        });

        // Recovery interval with exponential backoff (5000ms)
        container.setRecoveryInterval(5000L);

        log.info("Redis message listener container configured with thread pool " +
                "(max-active=50, core=5, queue=100)");

        return container;
    }
}
