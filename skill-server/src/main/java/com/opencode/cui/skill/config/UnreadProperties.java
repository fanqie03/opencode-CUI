package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Configuration properties for unread message badge tracking.
 * Bound from {@code skill.unread.*}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "skill.unread")
public class UnreadProperties {

    /** Sync mode for unread badge push: ws (Redis pub/sub) or im (IM app-notify). */
    private String syncMode = "ws";

    /** Comma-separated list of business session domains that participate in unread tracking. */
    private Set<String> sessionDomainWhitelist = Set.of("miniapp");

    /** Maximum sessionIds allowed in a single {@code POST /unread} query. */
    private int maxQuerySessionIds = 50;

    /** TTL in seconds for the {@code ss:unread:{userId}:{assistantAccount}} Redis Hash. Default 7 days. */
    private int hashTtlSeconds = 7 * 24 * 3600;

    /** Async executor configuration for unread processing. */
    private Async async = new Async();

    @Data
    public static class Async {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 100;
    }
}
