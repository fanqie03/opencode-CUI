package com.opencode.cui.skill.service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.event.SessionDeletedEvent;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.GatewayRelayService;
import com.opencode.cui.skill.service.PayloadBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 会话删除后通知 Gateway 释放 Agent 资源。
 */
@Slf4j
@Component
public class SessionDeletedGatewayNotifier {

    private final GatewayRelayService gatewayRelayService;
    private final ObjectMapper objectMapper;

    public SessionDeletedGatewayNotifier(
            GatewayRelayService gatewayRelayService,
            ObjectMapper objectMapper) {
        this.gatewayRelayService = gatewayRelayService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void notifyGatewayCloseSession(SessionDeletedEvent event) {
        try {
            SkillSession session = event.session();
            if (session == null) {
                return;
            }
            if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
                log.debug("Skipping Gateway close_session: no toolSessionId, sessionId={}", session.getId());
                return;
            }
            gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                    session.getAk(),
                    session.getUserId(),
                    session.getId().toString(),
                    GatewayActions.CLOSE_SESSION,
                    PayloadBuilder.buildPayload(objectMapper,
                            Map.of("toolSessionId", session.getToolSessionId())),
                    null,
                    session.getBusinessSessionDomain(),
                    session.getBusinessSessionType(),
                    session.getBusinessSessionId(),
                    null,
                    session.getAssistantAccount(),
                    session.getAssistantAccount()));
            log.info("Gateway close_session sent: sessionId={}, toolSessionId={}",
                    session.getId(), session.getToolSessionId());
        } catch (Exception e) {
            log.error("Failed to notify Gateway of session deletion: sessionId={}, error={}",
                    event.session() != null ? event.session().getId() : null, e.getMessage(), e);
        }
    }
}
