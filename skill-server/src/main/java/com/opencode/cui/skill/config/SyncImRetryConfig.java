package com.opencode.cui.skill.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry for {@code @Retryable} annotations on beans such as
 * {@code ImMultiDeviceSyncService}.
 */
@Configuration
@EnableRetry
public class SyncImRetryConfig {
}
