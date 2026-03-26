package com.opencode.cui.skill.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "messageHistoryRefreshExecutor")
    public Executor messageHistoryRefreshExecutor(
            @Value("${skill.message-history.refresh.core-pool-size:2}") int corePoolSize,
            @Value("${skill.message-history.refresh.max-pool-size:4}") int maxPoolSize,
            @Value("${skill.message-history.refresh.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("history-refresh-");
        executor.initialize();
        return executor;
    }
}
