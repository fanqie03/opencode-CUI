package com.opencode.cui.gateway.service;

import com.opencode.cui.gateway.model.GatewayMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Source 服务消息路由策略接口。
 *
 * <p>新版 Source 服务（带 instanceId 握手）走 Mesh 路径（SkillRelayService 内置），
 * 旧版 Source 服务（不带 instanceId）走 Legacy 路径（{@link LegacySkillRelayStrategy}）。</p>
 */
public interface SkillRelayStrategy {

    /** Session attribute key：标记当前会话使用的路由策略 */
    String STRATEGY_ATTR = "relayStrategy";

    /** 旧版策略标识 */
    String LEGACY = "legacy";

    /** 新版全连接网格策略标识 */
    String MESH = "mesh";

    /**
     * 注册 Source 服务的 WebSocket 连接。
     */
    void registerSession(WebSocketSession session);

    /**
     * 移除 Source 服务的 WebSocket 连接。
     */
    void removeSession(WebSocketSession session);

    /**
     * 上行消息路由：将 Agent 消息投递到 Source 服务。
     *
     * @return true 如果消息被成功投递
     */
    boolean relayToSkill(GatewayMessage message);

    /**
     * 处理 Source 服务发来的 invoke 消息，路由到目标 Agent。
     */
    void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message);

    /**
     * 获取当前活跃的 Source 服务连接数。
     */
    int getActiveConnectionCount();
}
