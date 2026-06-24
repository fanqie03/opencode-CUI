package com.opencode.cui.skill.service.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.enums.AsyncTaskType;
import com.opencode.cui.skill.model.event.SessionDeletedEvent;
import com.opencode.cui.skill.service.AsyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话删除后创建异步任务，用于后台批量清理 message/part。
 */
@Slf4j
@Component
public class DeleteSessionMessagesTaskCreator {

    private final AsyncTaskService asyncTaskService;
    private final ObjectMapper objectMapper;

    public DeleteSessionMessagesTaskCreator(
            AsyncTaskService asyncTaskService,
            ObjectMapper objectMapper) {
        this.asyncTaskService = asyncTaskService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void createDeleteMessagesTask(SessionDeletedEvent event) {
        try {
            SkillSession session = event.session();
            if (session == null) {
                return;
            }
            String payload = buildTaskPayload(session, event.messageCount());
            asyncTaskService.createTask(AsyncTaskType.DELETE_SESSION_MESSAGES, payload);
            log.info("Async task created for session deletion: sessionId={}, messageCount={}",
                    session.getId(), event.messageCount());
        } catch (Exception e) {
            log.error("Failed to create async task for session deletion: sessionId={}, error={}",
                    event.session() != null ? event.session().getId() : null, e.getMessage(), e);
        }
    }

    private String buildTaskPayload(SkillSession session, int messageCount) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", session.getId());
            payload.put("messageCount", messageCount);
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"sessionId\":" + session.getId() + ",\"messageCount\":" + messageCount + "}";
        }
    }
}
