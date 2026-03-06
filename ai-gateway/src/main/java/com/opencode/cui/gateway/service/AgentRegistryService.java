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
 * - Same AK + same toolType -> kick old connection (only one active connection
 * per AK+toolType)
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
     * Register a new agent connection. If an existing ONLINE connection with the
     * same AK and toolType exists, it will be kicked (marked offline) first.
     *
     * @return the newly created AgentConnection (with generated id)
     */
    @Transactional
    public AgentConnection register(Long userId, String akId, String deviceName,
            String os, String toolType, String toolVersion) {
        // Kick old connection with same AK + toolType
        AgentConnection existing = repository
                .findByAkIdAndToolTypeAndStatus(akId, toolType, AgentStatus.ONLINE);
        if (existing != null) {
            log.info("Kicking old agent connection: id={}, ak={}, toolType={}",
                    existing.getId(), akId, toolType);
            repository.updateStatus(existing.getId(), AgentStatus.OFFLINE);

            // Close the old WebSocket session and notify Skill Server
            eventRelayService.removeAgentSession(String.valueOf(existing.getId()));
        }

        // Create new connection record
        AgentConnection agent = AgentConnection.builder()
                .userId(userId)
                .akId(akId)
                .deviceName(deviceName)
                .os(os)
                .toolType(toolType != null ? toolType : "OPENCODE")
                .toolVersion(toolVersion)
                .status(AgentStatus.ONLINE)
                .lastSeenAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        repository.insert(agent);

        log.info("Agent registered: id={}, userId={}, ak={}, device={}, os={}, tool={}/{}",
                agent.getId(), userId, akId, deviceName, os, toolType, toolVersion);
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
