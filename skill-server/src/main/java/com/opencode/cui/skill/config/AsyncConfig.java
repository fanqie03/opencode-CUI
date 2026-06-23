package com.opencode.cui.skill.config;

import com.opencode.cui.skill.logging.MdcTaskDecorator;
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
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Bean(name = "asyncTaskExecutor")
    public Executor asyncTaskExecutor(
            @Value("${skill.async-task.core-pool-size:2}") int corePoolSize,
            @Value("${skill.async-task.max-pool-size:4}") int maxPoolSize,
            @Value("${skill.async-task.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("async-task-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
