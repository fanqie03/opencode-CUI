package com.opencode.cui.gateway.config;

import com.opencode.cui.gateway.logging.MdcTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 云端 Agent 配置（线程池等基础设施）。
 *
 * <p>为 abort 第三方终止接口调用提供专用线程池，
 * 与 Redis 监听线程池隔离，独立配置、独立生命周期。</p>
 */
@Slf4j
@Configuration
public class CloudAgentConfig {

    /** abort 异步线程池核心线程数 */
    @Value("${gateway.cloud.abort.thread-pool.core-pool-size:2}")
    private int abortCorePoolSize;

    /** abort 异步线程池最大线程数 */
    @Value("${gateway.cloud.abort.thread-pool.max-pool-size:10}")
    private int abortMaxPoolSize;

    /** abort 异步线程池队列容量 */
    @Value("${gateway.cloud.abort.thread-pool.queue-capacity:100}")
    private int abortQueueCapacity;

    /**
     * abort 第三方终止接口异步线程池。
     *
     * <p>使用 {@link ThreadPoolTaskExecutor}（对齐 RedisConfig 模式），
     * 由 Spring 管理生命周期（自动 shutdown）。</p>
     */
    @Bean(name = "cloudAbortExecutor")
    public ThreadPoolTaskExecutor cloudAbortExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(abortCorePoolSize);
        executor.setMaxPoolSize(abortMaxPoolSize);
        executor.setQueueCapacity(abortQueueCapacity);
        executor.setThreadNamePrefix("cloud-abort-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
