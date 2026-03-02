package com.yourapp.gateway.service;

import com.yourapp.gateway.model.AgentConnection;
import com.yourapp.gateway.model.AgentConnection.AgentStatus;
import com.yourapp.gateway.repository.AgentConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Agent lifecycle management: registration, heartbeat, online/offline transitions.
 *
 * Key behaviors:
 * - Same AK + same toolType -> kick old connection (only one active connection per AK+toolType)
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
     * @return the newly created or updated AgentConnection
     */
    @Transactional
    public AgentConnection register(Long userId, String akId, String deviceName,
                                    String os, String toolType, String toolVersion) {
        // Kick old connection with same AK + toolType
        Optional<AgentConnection> existing = repository
                .findByAkIdAndToolTypeAndStatus(akId, toolType, AgentStatus.ONLINE);
        if (existing.isPresent()) {
            AgentConnection old = existing.get();
            log.info("Kicking old agent connection: id={}, ak={}, toolType={}",
                    old.getId(), akId, toolType);
            old.setStatus(AgentStatus.OFFLINE);
            repository.save(old);

            // Close the old WebSocket session and notify Skill Server
            eventRelayService.removeAgentSession(old.getId());
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
        agent = repository.save(agent);

        log.info("Agent registered: id={}, userId={}, ak={}, device={}, os={}, tool={}/{}",
                agent.getId(), userId, akId, deviceName, os, toolType, toolVersion);
        return agent;
    }

    /**
     * Update heartbeat timestamp for an agent.
     */
    @Transactional
    public void heartbeat(Long agentId) {
        repository.findById(agentId).ifPresent(agent -> {
            agent.setLastSeenAt(LocalDateTime.now());
            repository.save(agent);
            log.debug("Heartbeat received: agentId={}", agentId);
        });
    }

    /**
     * Mark an agent as offline.
     */
    @Transactional
    public void markOffline(Long agentId) {
        repository.findById(agentId).ifPresent(agent -> {
            agent.setStatus(AgentStatus.OFFLINE);
            repository.save(agent);
            log.info("Agent marked offline: agentId={}", agentId);
        });
    }

    /**
     * Find all online agents.
     */
    public List<AgentConnection> findOnlineAgents() {
        return repository.findByStatus(AgentStatus.ONLINE);
    }

    /**
     * Get agent by ID.
     */
    public Optional<AgentConnection> findById(Long agentId) {
        return repository.findById(agentId);
    }

    /**
     * Scheduled task: check for timed-out agents and mark them offline.
     * Runs at the interval configured by gateway.agent.heartbeat-check-interval-seconds.
     */
    @Scheduled(fixedDelayString = "${gateway.agent.heartbeat-check-interval-seconds:30}000")
    @Transactional
    public void checkTimeouts() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(heartbeatTimeoutSeconds);
        List<AgentConnection> staleAgents = repository.findStaleAgents(threshold);

        if (!staleAgents.isEmpty()) {
            log.info("Found {} stale agents, marking offline", staleAgents.size());
            for (AgentConnection agent : staleAgents) {
                agent.setStatus(AgentStatus.OFFLINE);
                repository.save(agent);
                // Remove WebSocket session and notify Skill Server
                eventRelayService.removeAgentSession(agent.getId());
                log.info("Stale agent marked offline: agentId={}, lastSeen={}",
                        agent.getId(), agent.getLastSeenAt());
            }
        }
    }
}
