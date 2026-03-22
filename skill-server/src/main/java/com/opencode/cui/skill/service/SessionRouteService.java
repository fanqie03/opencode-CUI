package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SessionRoute;
import com.opencode.cui.skill.repository.SessionRouteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 会话级路由服务。
 *
 * 管理 session_route 表的 CRUD，提供 ownership 检查用于广播降级时的过滤。
 */
@Slf4j
@Service
public class SessionRouteService {

    private final SessionRouteRepository repository;
    private final String instanceId;

    public SessionRouteService(SessionRouteRepository repository,
            @Value("${skill.instance-id:${HOSTNAME:skill-server-local}}") String instanceId) {
        this.repository = repository;
        this.instanceId = instanceId;
    }

    /**
     * 创建路由记录。会话创建时调用。
     * toolSessionId 此时为 null，等 session_created 回来后由 updateToolSessionId 回填。
     */
    public void createRoute(String ak, Long welinkSessionId, String sourceType, String userId) {
        SessionRoute route = SessionRoute.builder()
                .id(welinkSessionId) // 用 welinkSessionId 作为主键（Snowflake ID）
                .ak(ak)
                .welinkSessionId(welinkSessionId)
                .sourceType(sourceType)
                .sourceInstance(instanceId)
                .userId(userId)
                .status("ACTIVE")
                .build();
        repository.insert(route);
        log.info("Created session route: ak={}, welinkSessionId={}, sourceType={}, sourceInstance={}",
                ak, welinkSessionId, sourceType, instanceId);
    }

    /**
     * 回填 toolSessionId。session_created 事件到达时调用。
     */
    public void updateToolSessionId(Long welinkSessionId, String sourceType, String toolSessionId) {
        repository.updateToolSessionId(welinkSessionId, sourceType, toolSessionId);
        log.debug("Updated toolSessionId: welinkSessionId={}, toolSessionId={}", welinkSessionId, toolSessionId);
    }

    /**
     * 关闭路由。会话关闭时调用。
     */
    public void closeRoute(Long welinkSessionId, String sourceType) {
        repository.updateStatus(welinkSessionId, sourceType, "CLOSED");
        log.debug("Closed session route: welinkSessionId={}, sourceType={}", welinkSessionId, sourceType);
    }

    /**
     * 检查指定 welinkSessionId 的会话是否属于本实例。
     * 广播降级时由 GatewayMessageRouter 调用。
     */
    public boolean isMySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            Long numericId = Long.parseLong(sessionId);
            SessionRoute route = repository.findByWelinkSessionId(numericId);
            return route != null && instanceId.equals(route.getSourceInstance());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查指定 toolSessionId 的会话是否属于本实例。
     */
    public boolean isMyToolSession(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return false;
        }
        SessionRoute route = repository.findByToolSessionId(toolSessionId);
        return route != null && instanceId.equals(route.getSourceInstance());
    }

    /**
     * 根据 toolSessionId 查询路由记录。
     */
    public SessionRoute findByToolSessionId(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return null;
        }
        return repository.findByToolSessionId(toolSessionId);
    }

    /**
     * 根据 welinkSessionId 查询路由记录。
     */
    public SessionRoute findByWelinkSessionId(Long welinkSessionId) {
        if (welinkSessionId == null) {
            return null;
        }
        return repository.findByWelinkSessionId(welinkSessionId);
    }

    public String getInstanceId() {
        return instanceId;
    }
}
