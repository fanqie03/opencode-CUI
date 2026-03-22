package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentConnection.AgentStatus;
import com.opencode.cui.gateway.repository.AgentConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 生命周期管理服务：注册、心跳、上线/下线状态转换。
 *
 * <p>
 * 核心行为：
 * </p>
 * <ul>
 * <li>相同 AK + 相同 toolType → 复用已有记录（身份持久化）</li>
 * <li>调用 register 之前应先检查是否存在重复的活跃连接</li>
 * <li>心跳更新 last_seen_at</li>
 * <li>定时任务检测超时 Agent 并标记为离线</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentRegistryService {

    private final AgentConnectionRepository repository;
    private final EventRelayService eventRelayService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Value("${gateway.agent.heartbeat-timeout-seconds:90}")
    private int heartbeatTimeoutSeconds;

    public AgentRegistryService(AgentConnectionRepository repository,
            EventRelayService eventRelayService,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repository = repository;
        this.eventRelayService = eventRelayService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * 注册 Agent 连接。相同 AK + toolType 复用已有记录（身份持久化），否则创建新记录。
     * 调用此方法之前应先检查是否存在重复的活跃连接。
     *
     * @return AgentConnection（复用或新建）
     */
    @Transactional
    public AgentConnection register(String userId, String akId, String deviceName,
            String macAddress, String os, String toolType, String toolVersion) {
        String effectiveToolType = toolType != null ? toolType : "channel";

        // 查找相同 AK + toolType 的已有记录（任意状态）
        AgentConnection existing = repository.findByAkIdAndToolType(akId, effectiveToolType);

        if (existing != null) {
            // 复用已有记录：更新为 ONLINE 并刷新元数据
            existing.setStatus(AgentStatus.ONLINE);
            existing.setDeviceName(deviceName);
            existing.setMacAddress(macAddress);
            existing.setOs(os);
            existing.setToolVersion(toolVersion);
            existing.setLastSeenAt(LocalDateTime.now());
            repository.updateAgentInfo(existing);

            log.info("Agent re-registered (reused): id={}, ak={}, device={}, tool={}/{}",
                    existing.getId(), akId, deviceName, effectiveToolType, toolVersion);
            return existing;
        }

        // 首次注册：创建新记录
        AgentConnection agent = AgentConnection.builder()
                .id(snowflakeIdGenerator.nextId())
                .userId(userId)
                .akId(akId)
                .deviceName(deviceName)
                .macAddress(macAddress)
                .os(os)
                .toolType(effectiveToolType)
                .toolVersion(toolVersion)
                .status(AgentStatus.ONLINE)
                .lastSeenAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        repository.insert(agent);

        log.info("Agent registered (new): id={}, userId={}, ak={}, device={}, tool={}/{}",
                agent.getId(), userId, akId, deviceName, effectiveToolType, toolVersion);
        return agent;
    }

    /** 更新 Agent 心跳时间戳。 */
    @Transactional
    public void heartbeat(Long agentId) {
        repository.updateLastSeenAt(agentId, LocalDateTime.now());
        log.debug("Heartbeat received: agentId={}", agentId);
    }

    /** 将 Agent 标记为离线。 */
    @Transactional
    public void markOffline(Long agentId) {
        repository.updateStatus(agentId, AgentStatus.OFFLINE);
        log.info("Agent marked offline: agentId={}", agentId);
    }

    /** 查询所有在线 Agent。 */
    public List<AgentConnection> findOnlineAgents() {
        return repository.findByStatus(AgentStatus.ONLINE);
    }

    /** 查询指定用户的所有在线 Agent。 */
    public List<AgentConnection> findOnlineByUserId(String userId) {
        return repository.findByUserIdAndStatus(userId, AgentStatus.ONLINE);
    }

    /** 按 ID 查询 Agent。 */
    public AgentConnection findById(Long agentId) {
        return repository.findById(agentId);
    }

    /** 获取指定 AK 最近的连接记录。 */
    public AgentConnection findLatestByAk(String ak) {
        return repository.findLatestByAkId(ak);
    }

    /**
     * 定时任务：检测心跳超时的 Agent 并标记为离线。
     * 执行间隔由 gateway.agent.heartbeat-check-interval-seconds 配置。
     */
    @Scheduled(fixedDelayString = "${gateway.agent.heartbeat-check-interval-seconds:30}000")
    @Transactional
    public void checkTimeouts() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(heartbeatTimeoutSeconds);
        List<AgentConnection> staleAgents = repository.findStaleAgents(threshold);

        if (!staleAgents.isEmpty()) {
            log.info("Found {} stale agents, marking offline", staleAgents.size());
            for (AgentConnection agent : staleAgents) {
                repository.updateStatus(agent.getId(), AgentStatus.OFFLINE);
                // 使用 akId 清理 WebSocket 会话（agentSessions 以 ak 为 key）
                eventRelayService.removeAgentSession(agent.getAkId());
                log.info("Stale agent marked offline: agentId={}, ak={}, lastSeen={}",
                        agent.getId(), agent.getAkId(), agent.getLastSeenAt());
            }
        }
    }
}
