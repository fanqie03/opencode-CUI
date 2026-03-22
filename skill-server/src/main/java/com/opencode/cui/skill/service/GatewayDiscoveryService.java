package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gateway 实例发现服务。
 *
 * 定期扫描 Redis 中的 gw:instance:* key，对比本地已知实例集合，
 * 发现新 GW 时通知 listener 建连，GW 消失时通知 listener 清理。
 *
 * 调度由外部驱动（如 @Scheduled 或手动调用 discover()）。
 */
@Slf4j
@Service
public class GatewayDiscoveryService {

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 当前已知的 GW 实例 ID 集合（线程安全：discover() 与 getKnownInstanceIds() 可能在不同线程） */
    private final Set<String> knownInstanceIds = ConcurrentHashMap.newKeySet();

    public GatewayDiscoveryService(RedisMessageBroker redisMessageBroker, ObjectMapper objectMapper) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
    }

    /**
     * GW 实例变化监听器。
     */
    public interface Listener {
        void onGatewayAdded(String instanceId, String wsUrl);
        void onGatewayRemoved(String instanceId);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * 执行一次发现。对比 Redis 中的 GW 实例与本地已知集合：
     * - 新增的 → 通知 onGatewayAdded
     * - 消失的 → 通知 onGatewayRemoved
     */
    public void discover() {
        Map<String, String> discovered = redisMessageBroker.scanGatewayInstances();
        Set<String> discoveredIds = discovered.keySet();

        // 新增的
        for (String id : discoveredIds) {
            if (!knownInstanceIds.contains(id)) {
                String wsUrl = extractWsUrl(discovered.get(id));
                if (wsUrl != null) {
                    knownInstanceIds.add(id);
                    for (Listener l : listeners) {
                        try {
                            l.onGatewayAdded(id, wsUrl);
                        } catch (Exception e) {
                            log.error("Listener onGatewayAdded failed: instanceId={}", id, e);
                        }
                    }
                    log.info("Gateway instance discovered: instanceId={}, wsUrl={}", id, wsUrl);
                }
            }
        }

        // 消失的
        Set<String> removed = new HashSet<>(knownInstanceIds);
        removed.removeAll(discoveredIds);
        for (String id : removed) {
            knownInstanceIds.remove(id);
            for (Listener l : listeners) {
                try {
                    l.onGatewayRemoved(id);
                } catch (Exception e) {
                    log.error("Listener onGatewayRemoved failed: instanceId={}", id, e);
                }
            }
            log.info("Gateway instance removed: instanceId={}", id);
        }
    }

    public Set<String> getKnownInstanceIds() {
        return Set.copyOf(knownInstanceIds);
    }

    private String extractWsUrl(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("wsUrl").asText(null);
        } catch (Exception e) {
            log.warn("Failed to parse gateway instance info: {}", e.getMessage());
            return null;
        }
    }
}
