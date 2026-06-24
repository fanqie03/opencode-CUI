package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retry configuration for IM multi-device sync ({@code /v1/app-notify}).
 * Bound from {@code skill.sync.im.retry.*}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "skill.sync.im.retry")
public class SyncImRetryProperties {

    /** Maximum retry attempts (including the initial call). Default 5. */
    private int maxAttempts = 5;

    /** Initial backoff delay in milliseconds. Default 1000. */
    private long delayMs = 1000;

    /** Backoff multiplier. Default 2.0 (1s -> 2s -> 4s -> 8s -> 16s). */
    private double multiplier = 2.0;
}
