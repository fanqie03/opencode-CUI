package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.repository.AgentConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AgentRegistryService 单元测试：验证 Agent 注册、复用已有身份等逻辑。 */
@ExtendWith(MockitoExtension.class)
class AgentRegistryServiceTest {

    @Mock
    private AgentConnectionRepository repository;
    @Mock
    private EventRelayService eventRelayService;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private AgentRegistryService service;

    @BeforeEach
    void setUp() {
        service = new AgentRegistryService(repository, eventRelayService, snowflakeIdGenerator);
    }

    @Test
    @DisplayName("register creates new agent with snowflake id")
    void registerCreatesNewAgentWithSnowflakeId() {
        when(snowflakeIdGenerator.nextId()).thenReturn(9001L);
        when(repository.findByAkIdAndToolType("ak-1", "channel")).thenReturn(null);

        AgentConnection connection = service.register("user-1", "ak-1", "laptop", "mac", "win", null, "1.0.0");

        assertEquals(9001L, connection.getId());
        assertEquals(AgentConnection.AgentStatus.ONLINE, connection.getStatus());

        ArgumentCaptor<AgentConnection> captor = ArgumentCaptor.forClass(AgentConnection.class);
        verify(repository).insert(captor.capture());
        assertEquals(9001L, captor.getValue().getId());
        assertEquals("ak-1", captor.getValue().getAkId());
    }

    @Test
    @DisplayName("register reuses existing identity without generating new id")
    void registerReusesExistingIdentity() {
        AgentConnection existing = AgentConnection.builder()
                .id(77L)
                .akId("ak-1")
                .toolType("opencode")
                .status(AgentConnection.AgentStatus.OFFLINE)
                .build();
        when(repository.findByAkIdAndToolType("ak-1", "opencode")).thenReturn(existing);

        AgentConnection connection = service.register("user-1", "ak-1", "desktop", "mac", "macOS", "opencode", "2.0.0");

        assertSame(existing, connection);
        assertEquals(AgentConnection.AgentStatus.ONLINE, connection.getStatus());
        verify(repository).updateAgentInfo(existing);
    }
}
