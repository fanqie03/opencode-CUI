package com.opencode.cui.skill.service.listener;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import com.opencode.cui.skill.model.SyncType;
import com.opencode.cui.skill.model.event.SessionDeletedEvent;
import com.opencode.cui.skill.service.sync.MultiDeviceSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 会话删除后通过多端同步服务推送到用户所有设备。
 */
@Slf4j
@Component
public class SessionDeletedSyncNotifier {

    private final MultiDeviceSyncService multiDeviceSyncService;

    public SessionDeletedSyncNotifier(MultiDeviceSyncService multiDeviceSyncService) {
        this.multiDeviceSyncService = multiDeviceSyncService;
    }

    @EventListener
    public void notifySessionDeleted(SessionDeletedEvent event) {
        if (event.session() == null) {
            return;
        }
        try {
            SkillSession session = event.session();
            Map<String, Object> content = Map.of(
                    "welinkSessionId", session.getId().toString());
            multiDeviceSyncService.push(new SyncRequest(
                    SyncMode.WS,
                    SyncType.SESSION_DELETED,
                    content,
                    session.getUserId()));
            log.info("WS push session.deleted: sessionId={}, userId={}",
                    session.getId(), session.getUserId());
        } catch (Exception e) {
            log.error("Failed to push WS session.deleted: sessionId={}, error={}",
                    event.session().getId(), e.getMessage(), e);
        }
    }
}
