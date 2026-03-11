package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SkillSession;
import org.springframework.stereotype.Service;

@Service
public class SessionAccessControlService {

    private final SkillSessionService sessionService;
    private final GatewayApiClient gatewayApiClient;

    public SessionAccessControlService(SkillSessionService sessionService,
            GatewayApiClient gatewayApiClient) {
        this.sessionService = sessionService;
        this.gatewayApiClient = gatewayApiClient;
    }

    public String requireUserId(String userIdCookie) {
        if (userIdCookie == null || userIdCookie.isBlank()) {
            throw new ProtocolException(400, "userId is required");
        }
        return userIdCookie.trim();
    }

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
