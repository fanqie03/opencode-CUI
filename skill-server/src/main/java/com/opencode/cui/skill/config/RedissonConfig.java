package com.opencode.cui.skill.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Redisson 客户端配置。
 * 自动适配 Redis Cluster 或单机模式，提供分布式锁等高级特性。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        List<String> clusterNodes = redisProperties.getCluster() != null
                ? redisProperties.getCluster().getNodes() : null;

        if (clusterNodes != null && !clusterNodes.isEmpty()) {
            org.redisson.config.ClusterServersConfig clusterConfig = config.useClusterServers();
            clusterConfig.setNodeAddresses(clusterNodes.stream()
                    .map(n -> n.startsWith("redis://") ? n : "redis://" + n)
                    .toList());
            String password = redisProperties.getPassword();
            if (password != null && !password.isBlank()) {
                clusterConfig.setPassword(password);
            }
        } else {
            String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
            org.redisson.config.SingleServerConfig singleConfig = config.useSingleServer();
            singleConfig.setAddress(address);
            String password = redisProperties.getPassword();
            if (password != null && !password.isBlank()) {
                singleConfig.setPassword(password);
            }
        }
        return Redisson.create(config);
    }
}
