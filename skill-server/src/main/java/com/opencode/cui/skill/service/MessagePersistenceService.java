package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 流式消息持久化服务。
 * 负责将 AI Gateway 返回的流式消息（文本、工具调用、权限、文件等）持久化到数据库。
 *
 * InboundController 直接使用的方法：
 * - {@link #finalizeActiveAssistantTurn} — 结束当前助手回复轮次
 */
@Slf4j
@Service
public class MessagePersistenceService {

    private final SkillMessageService messageService; // 消息 CRUD 服务
    private final SkillMessagePartRepository partRepository; // 消息片段持久化仓库
    private final ObjectMapper objectMapper; // JSON 序列化
    private final SnowflakeIdGenerator snowflakeIdGenerator; // 分布式 ID 生成器
    private final ActiveMessageTracker tracker; // 活跃消息状态追踪器
    private final SkillSessionService sessionService; // 会话服务（用于延迟更新 last_active_at）
    private final PartBufferService partBufferService;

    public MessagePersistenceService(SkillMessageService messageService,
            SkillMessagePartRepository partRepository,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ActiveMessageTracker tracker,
            SkillSessionService sessionService,
            PartBufferService partBufferService) {
        this.messageService = messageService;
        this.partRepository = partRepository;
        this.objectMapper = objectMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.tracker = tracker;
        this.sessionService = sessionService;
        this.partBufferService = partBufferService;
        // Without this, parts buffered in Redis for an ASSISTANT message that gets
        // displaced (placeholder swap on messageId change, or new user turn) would
        // never reach MySQL — the cause of disappearing question/permission cards
        // and empty placeholder rows after a refresh.
        this.tracker.setBeforeFinalizeHook(this::flushAndSyncOnFinalize);
    }

    /**
     * Finalize hook: durably persist any buffered parts for {@code active} to MySQL
     * and sync the concatenated text content. Two-phase against Redis:
     * <ol>
     *   <li>{@link PartBufferService#prepareFlush} snapshots the buffer into a temp
     *       key without deleting it.</li>
     *   <li>On MySQL write success the snapshot is committed (Redis temp + seq deleted)
     *       only after the surrounding transaction commits — so a tx rollback puts
     *       the buffered parts back via {@link PartBufferService#rollbackFlush} and
     *       restores the active ref so a retry can pick up the same in-flight message.</li>
     *   <li>On MySQL write failure we rollback the snapshot immediately and rethrow,
     *       which causes {@link ActiveMessageTracker} to also restore the ref.</li>
     * </ol>
     */
    private void flushAndSyncOnFinalize(Long sessionId, ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return;
        }
        Long dbId = active.dbId();
        PartBufferService.FlushBatch batch = partBufferService.prepareFlush(dbId);
        try {
            if (!batch.parts().isEmpty()) {
                partRepository.batchUpsert(batch.parts());
                applyFlushedPartStats(dbId, batch.parts());
                log.info("Batch upserted {} parts for messageDbId={}", batch.parts().size(), dbId);
            }
            syncMessageContent(active);
        } catch (RuntimeException e) {
            partBufferService.rollbackFlush(batch);
            throw e;
        }
        registerCommitOrRollback(sessionId, active, batch);
    }

    private void registerCommitOrRollback(Long sessionId,
            ActiveMessageTracker.ActiveMessageRef active,
            PartBufferService.FlushBatch batch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Called outside a transaction — DB writes already executed, so confirm
            // the snapshot now.
            partBufferService.commitFlush(batch);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                partBufferService.commitFlush(batch);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                // Rollback + restore must run under the per-session lock so a
                // concurrent inbound resolveActiveMessage can't slip a new active in
                // between us and the restore — that race would orphan the buffer.
                // (Residual narrow race: see ActiveMessageTracker class-level note.)
                tracker.runUnderSessionLock(sessionId, () -> {
                    partBufferService.rollbackFlush(batch);
                    tracker.restoreIfAbsent(sessionId, active);
                    log.warn("Finalize tx rolled back: sessionId={}, dbId={}, restored buffer + active",
                            sessionId, active.dbId());
                });
            }
        });
    }

    private void applyFlushedPartStats(Long messageDbId, List<SkillMessagePart> parts) {
        int totalTokensIn = 0;
        int totalTokensOut = 0;
        double totalCost = 0.0;
        boolean hasStats = false;

        for (SkillMessagePart part : parts) {
            if ("step-finish".equals(part.getPartType())) {
                if (part.getTokensIn() != null) totalTokensIn += part.getTokensIn();
                if (part.getTokensOut() != null) totalTokensOut += part.getTokensOut();
                if (part.getCost() != null) totalCost += part.getCost();
                hasStats = true;
            }
        }
        if (hasStats) {
            messageService.updateMessageStats(messageDbId, totalTokensIn, totalTokensOut, totalCost);
        }
    }

    /** 为流式消息准备消息上下文（解析或创建活跃消息引用） */
    @Transactional
    public void prepareMessageContext(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null || !requiresMessageContext(msg)) {
            return;
        }
        tracker.resolveActiveMessage(sessionId, msg);
    }

    /**
     * Read-only enrichment for the outbound emit path. Applies messageId/seq/role
     * from the current active ref onto {@code msg} when available, but never
     * triggers a finalize or creates a placeholder message. This decouples the
     * WS delivery path from DB/Redis I/O so a finalize-hook failure can never
     * block message delivery to the front-end.
     */
    public void applyMessageContextIfPresent(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null || !requiresMessageContext(msg)) {
            return;
        }
        tracker.applyContextIfPresent(sessionId, msg);
    }

    /**
     * 当流式消息到达终态时，持久化到数据库。
     * 根据消息类型分发到不同的持久化方法。
     */
    @Transactional
    public void persistIfFinal(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null) {
            return;
        }

        ActiveMessageTracker.ActiveMessageRef active = requiresMessageContext(msg)
                ? tracker.resolveActiveMessage(sessionId, msg)
                : null;

        boolean refreshed = switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> persistTextPart(sessionId, msg, "text", active);
            case StreamMessage.Types.THINKING_DONE -> persistTextPart(sessionId, msg, "reasoning", active);
            case StreamMessage.Types.TOOL_UPDATE -> persistToolPartIfFinal(sessionId, msg, active);
            case StreamMessage.Types.QUESTION -> persistToolPart(sessionId, msg, active);
            case StreamMessage.Types.PERMISSION_ASK, StreamMessage.Types.PERMISSION_REPLY ->
                persistPermissionPart(sessionId, msg, active);
            case StreamMessage.Types.FILE -> persistFilePart(sessionId, msg, active);
            case StreamMessage.Types.STEP_DONE -> persistStepDone(sessionId, msg, active);
            case StreamMessage.Types.SESSION_STATUS -> {
                handleSessionStatus(sessionId, msg);
                yield false;
            }
            default -> false;
        };
        if (refreshed) {
            messageService.scheduleLatestHistoryRefreshAfterCommit(sessionId);
        }
    }

    // ==================== 助手消息轮次跟踪方法（委派给 ActiveMessageTracker）====================

    /** 清除会话的活跃消息状态 */
    public void clearSession(Long sessionId) {
        tracker.clearSession(sessionId);
    }


    @Transactional(readOnly = true)
    public StreamMessage synthesizePermissionReplyFromToolOutcome(Long sessionId, StreamMessage msg) {
        String inferredResponse = inferPermissionResponseFromToolOutcome(msg);
        if (inferredResponse == null) {
            return null;
        }

        // 先查 Redis 缓冲中的 pending permission part
        ActiveMessageTracker.ActiveMessageRef active = tracker.getActiveMessage(sessionId);
        SkillMessagePart pendingPart = null;
        if (active != null) {
            pendingPart = partBufferService.findLatestPendingPermission(active.dbId());
        }
        // 降级查 DB（兼容 takeover 后已刷盘的场景）
        if (pendingPart == null) {
            pendingPart = partRepository.findLatestPendingPermissionPart(sessionId);
        }
        if (pendingPart == null) {
            return null;
        }

        SkillMessage ownerMessage = messageService.findById(pendingPart.getMessageId());
        String protocolMessageId = ownerMessage != null ? ownerMessage.getMessageId() : null;

        return StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .messageId(protocolMessageId)
                .sourceMessageId(protocolMessageId)
                .partId(pendingPart.getPartId())
                .partSeq(pendingPart.getSeq())
                .role("assistant")
                .status("completed")
                .title(pendingPart.getContent())
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId(pendingPart.getToolCallId())
                        .permType(pendingPart.getToolName())
                        .response(inferredResponse)
                        .build())
                .build();
    }

    /** 结束当前活跃的助手回复轮次（在 ImInboundController 中发送新消息前调用） */
    @Transactional
    public void finalizeActiveAssistantTurn(Long sessionId) {
        tracker.finalizeActiveAssistantTurn(sessionId);
        messageService.scheduleLatestHistoryRefreshAfterCommit(sessionId);
    }

    // ==================== 持久化逻辑 ====================

    private boolean persistTextPart(Long sessionId, StreamMessage msg, String partType,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return false;
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : partType + "-" + active.messageSeq())
                .seq(resolvePartSeq(active.dbId(), msg))
                .partType(partType)
                .content(msg.getContent())
                .subagentSessionId(msg.getSubagentSessionId())
                .subagentName(msg.getSubagentName())
                .build();

        partBufferService.bufferPart(active.dbId(), part);
        log.debug("Buffered {} part: sessionId={}, protocolId={}, partId={}",
                partType, sessionId, active.protocolMessageId(), part.getPartId());

        return true;
    }

    private boolean persistToolPartIfFinal(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        String status = msg.getStatus();
        if ("completed".equals(status) || "error".equals(status)) {
            return persistToolPart(sessionId, msg, active);
        }
        return false;
    }

    private boolean persistToolPart(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return false;
        }

        String inputJson = null;
        var tool = msg.getTool();
        if (tool != null && tool.getInput() != null) {
            try {
                inputJson = objectMapper.writeValueAsString(tool.getInput());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize tool input: {}", e.getMessage());
            }
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "tool-" + active.messageSeq())
                .seq(resolvePartSeq(active.dbId(), msg))
                .partType("tool")
                .toolName(tool != null ? tool.getToolName() : null)
                .toolCallId(tool != null ? tool.getToolCallId() : null)
                .toolStatus(msg.getStatus())
                .toolInput(inputJson)
                .toolOutput(tool != null ? tool.getOutput() : null)
                .toolError(msg.getError())
                .toolTitle(msg.getTitle())
                .subagentSessionId(msg.getSubagentSessionId())
                .subagentName(msg.getSubagentName())
                .build();

        // Question parts must hit DB synchronously: the front-end refresh path reads
        // history from MySQL only, and the placeholder ASSISTANT message hosting a
        // pending Question card may stay alive for minutes waiting for the user.
        // Going through Redis buffer + finalize-time flush would lose it on refresh.
        if ("question".equals(part.getToolName())) {
            partRepository.upsert(part);
            log.debug("Persisted question part immediately: sessionId={}, protocolId={}, toolCallId={}, status={}",
                    sessionId, active.protocolMessageId(), part.getToolCallId(), part.getToolStatus());
            return true;
        }

        partBufferService.bufferPart(active.dbId(), part);
        log.debug("Buffered tool part: sessionId={}, protocolId={}, tool={}, status={}",
                sessionId, active.protocolMessageId(),
                tool != null ? tool.getToolName() : null, msg.getStatus());
        return true;
    }

    private boolean persistPermissionPart(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        // permission.reply 没有 active message 时（常见于 subagent 权限回复），
        // 直接按 permissionId 更新已有 permission part 的 status 和 response
        if (active == null && StreamMessage.Types.PERMISSION_REPLY.equals(msg.getType())) {
            return updatePermissionReplyByPermissionId(sessionId, msg);
        }
        if (active == null) {
            return false;
        }

        String metadataJson = null;
        var permission = msg.getPermission();
        if (permission != null && permission.getMetadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(permission.getMetadata());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize permission metadata: {}", e.getMessage());
            }
        }

        String permissionId = permission != null ? permission.getPermissionId() : null;
        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId()
                        : permissionId != null ? permissionId : "permission-" + active.messageSeq())
                .seq(resolvePartSeq(active.dbId(), msg))
                .partType("permission")
                .content(msg.getTitle() != null ? msg.getTitle() : msg.getContent())
                .toolName(permission != null ? permission.getPermType() : null)
                .toolCallId(permissionId)
                .toolStatus(msg.getStatus())
                .toolInput(metadataJson)
                .toolOutput(permission != null ? permission.getResponse() : null)
                .subagentSessionId(msg.getSubagentSessionId())
                .subagentName(msg.getSubagentName())
                .build();

        // Permission parts must hit DB synchronously for the same reason as question:
        // a pending PERMISSION_ASK card may live for minutes, and a PERMISSION_REPLY
        // must overwrite the existing row to update status/response on refresh.
        partRepository.upsert(part);
        log.debug("Persisted permission part immediately: sessionId={}, protocolId={}, permissionId={}, type={}",
                sessionId, active.protocolMessageId(), permissionId, msg.getType());
        return true;
    }

    /**
     * 通过 permissionId 直接更新已有 permission part 的 status 和 response。
     * 用于 subagent 的 permission.reply，此时无法通过 ActiveMessageTracker 找到关联的消息。
     */
    private boolean updatePermissionReplyByPermissionId(Long sessionId, StreamMessage msg) {
        var permission = msg.getPermission();
        if (permission == null || permission.getPermissionId() == null) {
            return false;
        }
        String permissionId = permission.getPermissionId();
        String response = permission.getResponse();
        String status = msg.getStatus() != null ? msg.getStatus() : "completed";

        // 先查 DB（已刷盘的场景）
        SkillMessagePart existing = partRepository.findByPartId(sessionId, permissionId);
        if (existing != null) {
            existing.setToolStatus(status);
            existing.setToolOutput(response);
            existing.setUpdatedAt(null);
            partRepository.upsert(existing);
            log.info("Updated permission reply by permissionId (DB): sessionId={}, permissionId={}, response={}",
                    sessionId, permissionId, response);
            return true;
        }

        // DB 中没有 → 尝试更新 Redis 缓冲中的 permission part
        ActiveMessageTracker.ActiveMessageRef active = tracker.getActiveMessage(sessionId);
        if (active != null) {
            boolean updated = partBufferService.updatePermissionReply(active.dbId(), permissionId, status, response);
            if (updated) {
                return true;
            }
        }

        log.debug("Permission part not found in DB or Redis buffer: sessionId={}, permissionId={}",
                sessionId, permissionId);
        return false;
    }

    private boolean persistFilePart(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return false;
        }

        var f = msg.getFile();
        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "file-" + active.messageSeq())
                .seq(resolvePartSeq(active.dbId(), msg))
                .partType("file")
                .fileName(f != null ? f.getFileName() : null)
                .fileUrl(f != null ? f.getFileUrl() : null)
                .fileMime(f != null ? f.getFileMime() : null)
                .subagentSessionId(msg.getSubagentSessionId())
                .subagentName(msg.getSubagentName())
                .build();

        partBufferService.bufferPart(active.dbId(), part);
        log.debug("Buffered file part: sessionId={}, protocolId={}, file={}",
                sessionId, active.protocolMessageId(),
                f != null ? f.getFileName() : null);
        return true;
    }

    private record UsageStats(Integer tokensIn, Integer tokensOut) {
    }

    private UsageStats extractUsageStats(StreamMessage msg) {
        var u = msg.getUsage();
        if (u == null || u.getTokens() == null) {
            return new UsageStats(null, null);
        }
        Object inVal = u.getTokens().get("input");
        Object outVal = u.getTokens().get("output");
        Integer tokensIn = (inVal instanceof Number n) ? n.intValue() : null;
        Integer tokensOut = (outVal instanceof Number n) ? n.intValue() : null;
        return new UsageStats(tokensIn, tokensOut);
    }

    private boolean persistStepDone(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return false;
        }

        var u = msg.getUsage();
        UsageStats stats = extractUsageStats(msg);
        int partSeq = resolvePartSeq(active.dbId(), msg);
        Double cost = u != null ? u.getCost() : null;
        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "step-done-" + active.dbId() + "-" + partSeq)
                .seq(partSeq)
                .partType("step-finish")
                .tokensIn(stats.tokensIn())
                .tokensOut(stats.tokensOut())
                .cost(cost)
                .finishReason(u != null ? u.getReason() : null)
                .subagentSessionId(msg.getSubagentSessionId())
                .subagentName(msg.getSubagentName())
                .build();

        partBufferService.bufferPart(active.dbId(), part);
        log.debug("Buffered step.done: sessionId={}, protocolId={}, tokensIn={}, tokensOut={}, cost={}",
                sessionId, active.protocolMessageId(), stats.tokensIn(), stats.tokensOut(), cost);
        return true;
    }

    private void handleSessionStatus(Long sessionId, StreamMessage msg) {
        if (!"idle".equals(msg.getSessionStatus()) && !"completed".equals(msg.getSessionStatus())) {
            return;
        }
        sessionService.touchSession(sessionId);
        // Hook handles flush + sync + tx-aware commit/rollback.
        tracker.removeAndFinalize(sessionId);
        // Earlier text/step.done events warmed the latest-history cache while the
        // placeholder DB row was still empty. Without this refresh the front-end
        // would keep seeing the stale snapshot after the turn settles to idle.
        messageService.scheduleLatestHistoryRefreshAfterCommit(sessionId);
    }

    // ==================== Internal Helpers ====================

    private int resolvePartSeq(Long messageDbId, StreamMessage msg) {
        if (msg.getPartSeq() != null && msg.getPartSeq() > 0) {
            return msg.getPartSeq();
        }
        return partBufferService.nextSeq(messageDbId);
    }

    private void syncMessageContent(ActiveMessageTracker.ActiveMessageRef active) {
        String content = partRepository.findConcatenatedTextByMessageId(active.dbId());
        messageService.updateMessageContent(active.dbId(), content != null ? content : "");
    }

    private boolean requiresMessageContext(StreamMessage msg) {
        return switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DELTA,
                    StreamMessage.Types.TEXT_DONE,
                    StreamMessage.Types.THINKING_DELTA,
                    StreamMessage.Types.THINKING_DONE,
                    StreamMessage.Types.TOOL_UPDATE,
                    StreamMessage.Types.QUESTION,
                    StreamMessage.Types.FILE,
                    StreamMessage.Types.STEP_START,
                    StreamMessage.Types.STEP_DONE,
                    StreamMessage.Types.PERMISSION_ASK,
                    StreamMessage.Types.PERMISSION_REPLY,
                    // 云端扩展类型
                    StreamMessage.Types.PLANNING_DELTA,
                    StreamMessage.Types.PLANNING_DONE,
                    StreamMessage.Types.SEARCHING,
                    StreamMessage.Types.SEARCH_RESULT,
                    StreamMessage.Types.REFERENCE,
                    StreamMessage.Types.ASK_MORE ->
                true;
            default -> false;
        };
    }

    private String inferPermissionResponseFromToolOutcome(StreamMessage msg) {
        if (msg == null || !StreamMessage.Types.TOOL_UPDATE.equals(msg.getType())) {
            return null;
        }
        if ("completed".equals(msg.getStatus())) {
            // OpenCode sometimes executes the gated tool directly after approval
            // without emitting a separate permission.reply event. In that case we
            // infer a one-time approval from the successful tool completion.
            return "once";
        }
        if (!"error".equals(msg.getStatus())) {
            return null;
        }
        String error = msg.getError();
        if (error == null || error.isBlank()) {
            return null;
        }
        if (error.contains("The user rejected permission to use this specific tool call.")) {
            return "reject";
        }
        return null;
    }
}
