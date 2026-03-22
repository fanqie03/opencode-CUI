package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** GatewayDiscoveryService 单元测试：验证从 Redis 发现 Gateway 实例的逻辑。 */
class GatewayDiscoveryServiceTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private GatewayDiscoveryService.Listener listener;

    private GatewayDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new GatewayDiscoveryService(redisMessageBroker, new com.fasterxml.jackson.databind.ObjectMapper());
        service.addListener(listener);
    }

    @Test
    @DisplayName("发现新 GW 实例时通知 listener onGatewayAdded")
    void discoverNewGwInstanceNotifiesAdded() {
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}"));

        service.discover();

        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("GW 实例消失时通知 listener onGatewayRemoved")
    void discoverGwInstanceGoneNotifiesRemoved() {
        // 第一次发现 gw-1
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}"));
        service.discover();

        // 第二次 gw-1 消失
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(Map.of());
        service.discover();

        verify(listener).onGatewayRemoved("gw-1");
    }

    @Test
    @DisplayName("无变化时不通知 listener")
    void discoverNoChangeNoNotification() {
        Map<String, String> instances = Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}");
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(instances);

        service.discover();
        service.discover(); // 第二次无变化

        // onGatewayAdded 只调用一次
        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("getKnownInstanceIds 返回已知实例集合")
    void getKnownInstanceIdsReturnsDiscoveredIds() {
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://a\"}", "gw-2", "{\"wsUrl\":\"ws://b\"}"));

        service.discover();

        assertEquals(Set.of("gw-1", "gw-2"), service.getKnownInstanceIds());
    }
}
