package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SkillSession;
import org.springframework.stereotype.Service;

/**
 * 会话访问控制服务。
 * 提供统一的身份校验和会话权限检查逻辑，
 * 确保用户只能访问自己拥有的会话。
 */
@Service
public class SessionAccessControlService {

    private final SkillSessionService sessionService;
    private final GatewayApiClient gatewayApiClient;

    public SessionAccessControlService(SkillSessionService sessionService,
            GatewayApiClient gatewayApiClient) {
        this.sessionService = sessionService;
        this.gatewayApiClient = gatewayApiClient;
    }

    /**
     * 校验并提取 userId。
     *
     * @param userIdCookie 从 Cookie 中读取的 userId 值
     * @return 去除空格后的 userId
     * @throws ProtocolException 400 如果 userId 为空
     */
    public String requireUserId(String userIdCookie) {
        if (userIdCookie == null || userIdCookie.isBlank()) {
            throw new ProtocolException(400, "userId is required");
        }
        return userIdCookie.trim();
    }

    /**
     * 校验用户对指定会话的访问权限。
     * 同时校验 userId 归属和 AK 所有权。
     *
     * @param sessionId    会话 ID
     * @param userIdCookie 从 Cookie 中读取的 userId 值
     * @return 校验通过的 SkillSession 对象
     * @throws ProtocolException 403 如果访问被拒绝
     */
    public SkillSession requireSessionAccess(Long sessionId, String userIdCookie) {
        String userId = requireUserId(userIdCookie);
        SkillSession session = sessionService.getSession(sessionId);

        if (!userId.equals(session.getUserId())) {
            throw new ProtocolException(403, "Session access denied");
        }

        if (session.getAk() != null && !session.getAk().isBlank()
                && !gatewayApiClient.isAkOwnedByUser(session.getAk(), userId)) {
            throw new ProtocolException(403, "Session access denied");
        }

        return session;
    }
}
