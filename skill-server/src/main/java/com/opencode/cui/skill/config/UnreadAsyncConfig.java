package com.opencode.cui.skill.config;

import com.opencode.cui.skill.logging.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executor configuration for unread message badge processing.
 * Provides a dedicated {@code unreadExecutor} so that unread cache updates
 * and sync pushes never block the Gateway message routing thread.
 */
@Configuration
@EnableAsync
public class UnreadAsyncConfig {

    @Bean(name = "unreadExecutor")
    public Executor unreadExecutor(UnreadProperties properties) {
        UnreadProperties.Async async = properties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.getCorePoolSize());
        executor.setMaxPoolSize(async.getMaxPoolSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.setThreadNamePrefix("unread-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
