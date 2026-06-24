package com.opencode.cui.skill.service.listener;

import com.opencode.cui.skill.config.UnreadProperties;
import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;
import com.opencode.cui.skill.model.SyncType;
import com.opencode.cui.skill.model.event.ReadReportedEvent;
import com.opencode.cui.skill.model.event.SessionDeletedEvent;
import com.opencode.cui.skill.model.event.ToolDoneEvent;
import com.opencode.cui.skill.model.event.ToolErrorEvent;
import com.opencode.cui.skill.model.event.UnreadSyncKeys;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import com.opencode.cui.skill.service.UnreadRedisService;
import com.opencode.cui.skill.service.sync.MultiDeviceSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Unified listener for unread badge lifecycle events.
 * <ul>
 *   <li>{@code ToolDoneEvent} — a new message has been persisted; update the unread cache.</li>
 *   <li>{@code ReadReportedEvent} — the frontend reported a read position; push clearance to other devices.</li>
 * </ul>
 * <p>
 * All methods use top-level try-catch so that a single event failure never
 * disrupts subsequent events or the publishing thread.
 * </p>
 */
@Slf4j
@Component
public class UnreadManageListener {

    private final UnreadRedisService unreadRedisService;
    private final UnreadProperties unreadProperties;
    private final MultiDeviceSyncService multiDeviceSyncService;
    private final SkillMessageRepository skillMessageRepository;

    @Lazy
    @Autowired
    private UnreadManageListener self;

    public UnreadManageListener(UnreadRedisService unreadRedisService,
            UnreadProperties unreadProperties,
            MultiDeviceSyncService multiDeviceSyncService,
            SkillMessageRepository skillMessageRepository) {
        this.unreadRedisService = unreadRedisService;
        this.unreadProperties = unreadProperties;
        this.multiDeviceSyncService = multiDeviceSyncService;
        this.skillMessageRepository = skillMessageRepository;
    }

    /**
     * After a message turn completes (tool_done), update the per-session maxSeq
     * in Redis synchronously so the cache is populated before the idle event
     * reaches the frontend. The multi-device sync push is delegated to
     * {@link #pushUnreadSessionAsync} to avoid blocking the Gateway thread.
     */
    @EventListener
    public void onToolDone(ToolDoneEvent event) {
        try {
            if (event.sessionId() == null || event.userId() == null || event.session() == null) {
                log.debug("UnreadManageListener.onToolDone skipped: incomplete event sessionId={}", event.sessionId());
                return;
            }

            String domain = event.session().getBusinessSessionDomain();
            if (domain == null || !unreadProperties.getSessionDomainWhitelist().contains(domain)) {
                return;
            }

            int maxSeq = skillMessageRepository.findMaxSeqBySessionId(event.sessionId());
            String assistantAccount = event.session().getAssistantAccount();

            Long result = unreadRedisService.updateMaxSeq(
                    event.userId(), assistantAccount,
                    event.sessionId().toString(), maxSeq,
                    unreadProperties.getHashTtlSeconds());

            if (result == null || result != 1) {
                log.warn("UnreadManageListener.onToolDone: updateMaxSeq failed result={} sessionId={} maxSeq={}",
                        result, event.sessionId(), maxSeq);
                return;
            }

            self.pushUnreadSessionAsync(event.sessionId().toString(), event.userId(),
                    assistantAccount, maxSeq);

            log.debug("UnreadManageListener.onToolDone: updated maxSeq={} sessionId={}", maxSeq, event.sessionId());
        } catch (Exception e) {
            log.error("UnreadManageListener.onToolDone failed: sessionId={}, error={}",
                    event.sessionId(), e.getMessage(), e);
        }
    }

    /**
     * After a tool error is persisted, update the unread cache synchronously
     * just like {@code onToolDone}.
     */
    @EventListener
    public void onToolError(ToolErrorEvent event) {
        try {
            if (event.sessionId() == null || event.userId() == null || event.session() == null) {
                return;
            }
            String domain = event.session().getBusinessSessionDomain();
            if (domain == null || !unreadProperties.getSessionDomainWhitelist().contains(domain)) {
                return;
            }

            int maxSeq = skillMessageRepository.findMaxSeqBySessionId(event.sessionId());
            String assistantAccount = event.session().getAssistantAccount();

            Long result = unreadRedisService.updateMaxSeq(
                    event.userId(), assistantAccount,
                    event.sessionId().toString(), maxSeq,
                    unreadProperties.getHashTtlSeconds());

            if (result == null || result != 1) {
                log.warn("UnreadManageListener.onToolError: updateMaxSeq failed result={} sessionId={} maxSeq={}",
                        result, event.sessionId(), maxSeq);
                return;
            }

            self.pushUnreadSessionAsync(event.sessionId().toString(), event.userId(),
                    assistantAccount, maxSeq);
        } catch (Exception e) {
            log.error("UnreadManageListener.onToolError failed: sessionId={}, error={}",
                    event.sessionId(), e.getMessage(), e);
        }
    }

    /**
     * Push {@code session.unread} to other devices asynchronously.
     * Decoupled from the maxSeq cache update so the Gateway thread is not blocked.
     */
    @Async("unreadExecutor")
    public void pushUnreadSessionAsync(String sessionId, String userId,
                                        String assistantAccount, int maxSeq) {
        try {
            SyncMode mode = SyncMode.valueOf(unreadProperties.getSyncMode().toUpperCase());
            multiDeviceSyncService.push(new SyncRequest(
                    mode,
                    SyncType.SESSION_UNREAD,
                    Map.of(
                            UnreadSyncKeys.WELINK_SESSION_ID, sessionId,
                            UnreadSyncKeys.MAX_SEQ, String.valueOf(maxSeq),
                            UnreadSyncKeys.ASSISTANT_ACCOUNT, assistantAccount != null ? assistantAccount : ""
                    ),
                    userId));
            log.info("UnreadManageListener.pushUnreadSessionAsync: sessionId={} maxSeq={}", sessionId, maxSeq);
        } catch (Exception e) {
            log.error("UnreadManageListener.pushUnreadSessionAsync failed: sessionId={}, error={}",
                    sessionId, e.getMessage(), e);
        }
    }

    /**
     * After the frontend reports a read position, push a {@code session.read} sync
     * so all other devices can clear the badge.
     */
    @EventListener
    public void onReadReported(ReadReportedEvent event) {
        try {
            if (event.sessionId() == null || event.userId() == null) {
                return;
            }

            SyncMode mode = SyncMode.valueOf(unreadProperties.getSyncMode().toUpperCase());
            multiDeviceSyncService.push(new SyncRequest(
                    mode,
                    SyncType.SESSION_READ,
                    Map.of(
                            UnreadSyncKeys.WELINK_SESSION_ID, event.sessionId().toString(),
                            UnreadSyncKeys.MAX_SEQ, String.valueOf(event.readSeq()),
                            UnreadSyncKeys.READ_SEQ, String.valueOf(event.readSeq()),
                            UnreadSyncKeys.ASSISTANT_ACCOUNT,
                            event.assistantAccount() != null ? event.assistantAccount() : ""
                    ),
                    event.userId()));

            log.debug("UnreadManageListener.onReadReported: pushed SESSION_READ sessionId={} readSeq={}",
                    event.sessionId(), event.readSeq());
        } catch (Exception e) {
            log.error("UnreadManageListener.onReadReported failed: sessionId={}, error={}",
                    event.sessionId(), e.getMessage(), e);
        }
    }

    /**
     * Hard-delete cleanup: remove the deleted session's field from the unread
     * Hash so stale entries don't linger.
     */
    @EventListener
    public void onSessionDeleted(SessionDeletedEvent event) {
        try {
            if (event.session() == null || event.session().getId() == null) {
                return;
            }
            String sessionId = event.session().getId().toString();
            String userId = event.deletedBy();
            String assistantAccount = event.session().getAssistantAccount();
            unreadRedisService.removeField(userId, assistantAccount, sessionId);
            log.debug("UnreadManageListener.onSessionDeleted: removed field sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("UnreadManageListener.onSessionDeleted failed: sessionId={}, error={}",
                    event.session() != null ? event.session().getId() : null, e.getMessage(), e);
        }
    }
}
