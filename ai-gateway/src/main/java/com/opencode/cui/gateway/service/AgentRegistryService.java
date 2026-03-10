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
 * Agent lifecycle management: registration, heartbeat, online/offline
 * transitions.
 *
 * Key behaviors:
 * - Same AK + same toolType -> reuse existing record (identity persistence)
 * - Duplicate active connection check is done BEFORE calling register
 * - Heartbeat updates last_seen_at
 * - Scheduled task marks stale agents offline
 */
@Slf4j
@Service
public class AgentRegistryService {

    private final AgentConnectionRepository repository;
    private final EventRelayService eventRelayService;

    @Value("${gateway.agent.heartbeat-timeout-seconds:90}")
    private int heartbeatTimeoutSeconds;

    public AgentRegistryService(AgentConnectionRepository repository,
            EventRelayService eventRelayService) {
        this.repository = repository;
        this.eventRelayService = eventRelayService;
    }

    /**
     * Register an agent connection. Reuses existing record for the same
     * AK + toolType (identity persistence) or creates a new one.
     *
     * Duplicate active connection check should be done BEFORE calling this method.
     *
     * @return the AgentConnection (reused or newly created)
     */
    @Transactional
    public AgentConnection register(Long userId, String akId, String deviceName,
            String macAddress, String os, String toolType, String toolVersion) {
        String effectiveToolType = toolType != null ? toolType : "channel";

        // Look for existing record with same AK + toolType (any status)
        AgentConnection existing = repository.findByAkIdAndToolType(akId, effectiveToolType);

        if (existing != null) {
            // Reuse existing record: update to ONLINE with fresh metadata
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

        // First-time registration: create new record
        AgentConnection agent = AgentConnection.builder()
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

    /**
     * Update heartbeat timestamp for an agent.
     */
    @Transactional
    public void heartbeat(Long agentId) {
        repository.updateLastSeenAt(agentId, LocalDateTime.now());
        log.debug("Heartbeat received: agentId={}", agentId);
    }

    /**
     * Mark an agent as offline.
     */
    @Transactional
    public void markOffline(Long agentId) {
        repository.updateStatus(agentId, AgentStatus.OFFLINE);
        log.info("Agent marked offline: agentId={}", agentId);
    }

    /**
     * Find all online agents.
     */
    public List<AgentConnection> findOnlineAgents() {
        return repository.findByStatus(AgentStatus.ONLINE);
    }

    /**
     * Find all online agents for a specific user.
     */
    public List<AgentConnection> findOnlineByUserId(Long userId) {
        return repository.findByUserIdAndStatus(userId, AgentStatus.ONLINE);
    }

    /**
     * Get agent by ID.
     */
    public AgentConnection findById(Long agentId) {
        return repository.findById(agentId);
    }

    /**
     * Get the latest known connection record for an AK.
     */
    public AgentConnection findLatestByAk(String ak) {
        return repository.findLatestByAkId(ak);
    }

    /**
     * Scheduled task: check for timed-out agents and mark them offline.
     * Runs at the interval configured by
     * gateway.agent.heartbeat-check-interval-seconds.
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
                // Remove WebSocket session and notify Skill Server
                eventRelayService.removeAgentSession(String.valueOf(agent.getId()));
                log.info("Stale agent marked offline: agentId={}, lastSeen={}",
                        agent.getId(), agent.getLastSeenAt());
            }
        }
    }
}
