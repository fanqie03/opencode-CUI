package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

        // 必须按 toolCallId 精确关联到触发它的 pending permission；否则在多 permission 并发
        // 场景下会把无关 permission 错误地标记为 resolved（前端表现为「点 1 → 4 也变已选择」）。
        String toolCallId = msg.getTool() != null ? msg.getTool().getToolCallId() : null;
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }

        // 先查 Redis 缓冲中按 toolCallId 精确匹配的 pending permission part
        ActiveMessageTracker.ActiveMessageRef active = tracker.getActiveMessage(sessionId);
        SkillMessagePart pendingPart = null;
        if (active != null) {
            pendingPart = partBufferService.findPendingPermissionByToolCallId(active.dbId(), toolCallId);
        }
        // 降级查 DB（兼容 takeover 后已刷盘的场景）
        if (pendingPart == null) {
            pendingPart = partRepository.findPendingPermissionPartByToolCallId(sessionId, toolCallId);
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

    @Transactional
    public boolean recordQuestionReply(Long sessionId, String toolCallId, String answer, String questionId) {
        boolean updated = updateQuestionReplyPart(
                sessionId,
                questionId,
                toolCallId,
                "completed",
                answer,
                null,
                null,
                null,
                null,
                null);
        if (updated) {
            messageService.scheduleLatestHistoryRefreshAfterCommit(sessionId);
        }
        return updated;
    }

    @Transactional
    public boolean recordPermissionReply(Long sessionId, String permissionId, String response) {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .status("completed")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId(permissionId)
                        .response(response)
                        .build())
                .build();
        boolean updated = updatePermissionReplyByPermissionId(sessionId, msg);
        if (updated) {
            messageService.scheduleLatestHistoryRefreshAfterCommit(sessionId);
        }
        return updated;
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

        partRepository.upsert(part);
        syncMessageContent(active);
        log.debug("Persisted {} part immediately: sessionId={}, protocolId={}, partId={}",
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

        var tool = msg.getTool();
        String inputJson = serializeToolInput(msg);
        String toolName = tool != null ? tool.getToolName() : null;
        String toolStatus = resolveToolStatus(msg);
        if (isQuestionToolUpdate(msg)
                && mergeQuestionToolUpdateIntoExistingQuestion(sessionId, msg, active, toolStatus)) {
            return true;
        }
        if (StreamMessage.Types.QUESTION.equals(msg.getType()) && (toolName == null || toolName.isBlank())) {
            toolName = "question";
        }
        if (StreamMessage.Types.QUESTION.equals(msg.getType()) && isResolvedQuestionStatus(toolStatus)) {
            String toolCallId = tool != null ? tool.getToolCallId() : null;
            String toolOutput = tool != null ? tool.getOutput() : null;
            if (updateQuestionReplyPart(
                    sessionId,
                    msg.getPartId(),
                    toolCallId,
                    toolStatus,
                    toolOutput,
                    msg.getError(),
                    msg.getTitle(),
                    inputJson,
                    msg.getSubagentSessionId(),
                    msg.getSubagentName())) {
                return true;
            }
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "tool-" + active.messageSeq())
                .seq(resolvePartSeq(active.dbId(), msg))
                .partType("tool")
                .toolName(toolName)
                .toolCallId(tool != null ? tool.getToolCallId() : null)
                .toolStatus(toolStatus)
                .toolInput(inputJson)
                .toolOutput(tool != null ? tool.getOutput() : null)
                .toolError(msg.getError())
                .toolTitle(msg.getTitle())
                .subagentSessionId(msg.getSubagentSessionId())
                .subagentName(msg.getSubagentName())
                .build();

        // Semantic tool states must be durable as soon as they become meaningful.
        // Running question cards and completed/error tool parts are recovered from
        // MySQL history after a refresh; Redis remains only the live replay layer.
        partRepository.upsert(part);
        log.debug("Persisted tool part immediately: sessionId={}, protocolId={}, tool={}, status={}",
                sessionId, active.protocolMessageId(),
                toolName, toolStatus);
        return true;
    }

    private boolean mergeQuestionToolUpdateIntoExistingQuestion(Long sessionId,
            StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active,
            String toolStatus) {
        var tool = msg.getTool();
        String toolOutput = tool != null ? tool.getOutput() : null;
        SkillMessagePart existing = findCanonicalQuestionPartInMessage(
                active.dbId(), msg.getPartId(), toolOutput);
        if (existing == null) {
            return false;
        }

        existing.setToolStatus(toolStatus != null && !toolStatus.isBlank() ? toolStatus : existing.getToolStatus());
        if (existing.getToolOutput() == null || existing.getToolOutput().isBlank()) {
            existing.setToolOutput(normalizeQuestionToolUpdateOutput(existing.getToolInput(), toolOutput));
        }
        if (msg.getError() != null) {
            existing.setToolError(msg.getError());
        }
        if (msg.getTitle() != null) {
            existing.setToolTitle(msg.getTitle());
        }
        if (msg.getSubagentSessionId() != null) {
            existing.setSubagentSessionId(msg.getSubagentSessionId());
        }
        if (msg.getSubagentName() != null) {
            existing.setSubagentName(msg.getSubagentName());
        }
        existing.setUpdatedAt(null);
        partRepository.upsert(existing);
        log.info("Merged question tool.update into existing question part: sessionId={}, toolUpdatePartId={}, questionPartId={}, status={}",
                sessionId, msg.getPartId(), existing.getPartId(), existing.getToolStatus());
        return true;
    }

    private SkillMessagePart findCanonicalQuestionPartInMessage(Long messageDbId,
            String toolUpdatePartId,
            String toolOutput) {
        if (messageDbId == null) {
            return null;
        }
        List<SkillMessagePart> parts = partRepository.findByMessageId(messageDbId);
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        List<SkillMessagePart> candidates = parts.stream()
                .filter(this::isQuestionPart)
                .filter(part -> toolUpdatePartId == null || !toolUpdatePartId.equals(part.getPartId()))
                .filter(this::hasQuestionInput)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (toolOutput != null && !toolOutput.isBlank()) {
            return candidates.stream()
                    .filter(part -> questionOutputMentionsQuestion(part.getToolInput(), toolOutput))
                    .findFirst()
                    .orElse(candidates.size() == 1 ? candidates.get(0) : null);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private boolean hasQuestionInput(SkillMessagePart part) {
        return part.getToolInput() != null && !part.getToolInput().isBlank();
    }

    private String normalizeQuestionToolUpdateOutput(String inputJson, String output) {
        if (output == null) {
            return null;
        }
        if (inputJson == null || inputJson.isBlank()) {
            return output;
        }
        try {
            JsonNode inputNode = objectMapper.readTree(inputJson);
            return ProtocolUtils.normalizeQuestionAnswerOutput(output, inputNode);
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse question input while merging tool.update: {}", e.getMessage());
            return output;
        }
    }

    private boolean questionOutputMentionsQuestion(String inputJson, String output) {
        if (inputJson == null || inputJson.isBlank() || output == null || output.isBlank()) {
            return false;
        }
        try {
            JsonNode inputNode = objectMapper.readTree(inputJson);
            JsonNode questionNode = ProtocolUtils.resolveQuestionPayload(inputNode);
            String question = questionNode != null ? questionNode.path("question").asText(null) : null;
            return question != null && !question.isBlank() && output.contains(question);
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse question input while matching tool.update: {}", e.getMessage());
            return false;
        }
    }

    private boolean isQuestionToolUpdate(StreamMessage msg) {
        if (msg == null || !StreamMessage.Types.TOOL_UPDATE.equals(msg.getType()) || msg.getTool() == null) {
            return false;
        }
        return "question".equals(msg.getTool().getToolName());
    }

    private boolean persistPermissionPart(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        // A reply should complete the original permission card first. If the
        // original card was persisted with a different partId, match by toolCallId.
        if (StreamMessage.Types.PERMISSION_REPLY.equals(msg.getType())) {
            boolean updated = updatePermissionReplyByPermissionId(sessionId, msg);
            if (updated || active == null) {
                return updated;
            }
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
                .toolStatus(resolvePermissionStatus(msg))
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
        if (sessionId == null || permission == null || permission.getPermissionId() == null
                || permission.getPermissionId().isBlank()) {
            return false;
        }
        String permissionId = permission.getPermissionId();
        String response = permission.getResponse();
        String status = msg.getStatus() != null ? msg.getStatus() : "completed";

        // 先查 DB（已刷盘的场景）
        SkillMessagePart existing = findPermissionPartForReply(sessionId, permissionId);
        if (existing != null) {
            updatePermissionPart(existing, status, response);
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

    private boolean updateQuestionReplyPart(Long sessionId,
            String partId,
            String toolCallId,
            String status,
            String output,
            String error,
            String title,
            String inputJson,
            String subagentSessionId,
            String subagentName) {
        if (sessionId == null || ((partId == null || partId.isBlank())
                && (toolCallId == null || toolCallId.isBlank()))) {
            return false;
        }
        SkillMessagePart existing = findQuestionPartForReply(sessionId, partId, toolCallId);
        if (existing == null) {
            log.debug("Question part not found for reply: sessionId={}, partId={}, toolCallId={}",
                    sessionId, partId, toolCallId);
            return false;
        }

        existing.setToolStatus(status != null && !status.isBlank() ? status : "completed");
        if (output != null) {
            existing.setToolOutput(output);
        }
        if (error != null) {
            existing.setToolError(error);
        }
        if (title != null) {
            existing.setToolTitle(title);
        }
        if (inputJson != null) {
            existing.setToolInput(inputJson);
        }
        if (subagentSessionId != null) {
            existing.setSubagentSessionId(subagentSessionId);
        }
        if (subagentName != null) {
            existing.setSubagentName(subagentName);
        }
        existing.setUpdatedAt(null);
        partRepository.upsert(existing);
        log.info("Updated question reply by protocol id: sessionId={}, partId={}, toolCallId={}, status={}",
                sessionId, existing.getPartId(), existing.getToolCallId(), existing.getToolStatus());
        return true;
    }

    private SkillMessagePart findQuestionPartForReply(Long sessionId, String partId, String toolCallId) {
        if (partId != null && !partId.isBlank()) {
            SkillMessagePart byPartId = partRepository.findByPartId(sessionId, partId);
            if (isQuestionPart(byPartId)) {
                return byPartId;
            }
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        SkillMessagePart byToolCallId = partRepository.findPendingQuestionPartByToolCallId(sessionId, toolCallId);
        return isQuestionPart(byToolCallId, toolCallId) ? byToolCallId : null;
    }

    private boolean isQuestionPart(SkillMessagePart part, String toolCallId) {
        if (!isQuestionPart(part)) {
            return false;
        }
        return toolCallId == null || toolCallId.isBlank() || toolCallId.equals(part.getToolCallId());
    }

    private boolean isQuestionPart(SkillMessagePart part) {
        return part != null && "tool".equals(part.getPartType()) && "question".equals(part.getToolName());
    }

    private SkillMessagePart findPermissionPartForReply(Long sessionId, String permissionId) {
        if (permissionId == null || permissionId.isBlank()) {
            return null;
        }
        SkillMessagePart byPartId = partRepository.findByPartId(sessionId, permissionId);
        if (isPermissionPart(byPartId)) {
            return byPartId;
        }
        SkillMessagePart byToolCallId = partRepository.findPendingPermissionPartByToolCallId(sessionId, permissionId);
        return isPermissionPart(byToolCallId, permissionId) ? byToolCallId : null;
    }

    private boolean isPermissionPart(SkillMessagePart part, String permissionId) {
        if (!isPermissionPart(part)) {
            return false;
        }
        return permissionId == null || permissionId.isBlank() || permissionId.equals(part.getToolCallId());
    }

    private boolean isPermissionPart(SkillMessagePart part) {
        return part != null && "permission".equals(part.getPartType());
    }

    private void updatePermissionPart(SkillMessagePart part, String status, String response) {
        part.setToolStatus(status);
        part.setToolOutput(response);
        part.setUpdatedAt(null);
        partRepository.upsert(part);
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

        partRepository.upsert(part);
        log.debug("Persisted file part immediately: sessionId={}, protocolId={}, file={}",
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

        partRepository.upsert(part);
        applyFlushedPartStats(active.dbId(), List.of(part));
        log.debug("Persisted step.done immediately: sessionId={}, protocolId={}, tokensIn={}, tokensOut={}, cost={}",
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

    private String serializeToolInput(StreamMessage msg) {
        Object input = resolveToolInput(msg);
        if (input == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tool input: {}", e.getMessage());
            return null;
        }
    }

    private Object resolveToolInput(StreamMessage msg) {
        var tool = msg.getTool();
        if (tool != null && tool.getInput() != null) {
            return tool.getInput();
        }
        if (StreamMessage.Types.QUESTION.equals(msg.getType())) {
            return buildQuestionInput(msg.getQuestionInfo());
        }
        return null;
    }

    private JsonNode buildQuestionInput(StreamMessage.QuestionInfo questionInfo) {
        if (questionInfo == null) {
            return null;
        }
        ObjectNode input = objectMapper.createObjectNode();
        putText(input, "header", questionInfo.getHeader());
        putText(input, "question", questionInfo.getQuestion());
        if (questionInfo.getOptions() != null && !questionInfo.getOptions().isEmpty()) {
            input.set("options", objectMapper.valueToTree(questionInfo.getOptions()));
        }
        if (questionInfo.getMultiSelect() != null) {
            input.put("multiSelect", questionInfo.getMultiSelect());
        }
        if (questionInfo.getQuestions() != null && !questionInfo.getQuestions().isEmpty()) {
            input.set("questions", objectMapper.valueToTree(questionInfo.getQuestions()));
        }
        if (questionInfo.getExtParam() != null && !questionInfo.getExtParam().isNull()) {
            input.set("extParam", questionInfo.getExtParam());
        }
        putText(input, "questionId", questionInfo.getQuestionId());
        return input.size() == 0 ? null : input;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private String resolvePermissionStatus(StreamMessage msg) {
        if (msg.getStatus() != null && !msg.getStatus().isBlank()) {
            return msg.getStatus();
        }
        return switch (msg.getType()) {
            case StreamMessage.Types.PERMISSION_ASK -> "pending";
            case StreamMessage.Types.PERMISSION_REPLY -> "completed";
            default -> null;
        };
    }

    private String resolveToolStatus(StreamMessage msg) {
        if (msg.getStatus() != null && !msg.getStatus().isBlank()) {
            return msg.getStatus();
        }
        if (StreamMessage.Types.QUESTION.equals(msg.getType())) {
            return "running";
        }
        return null;
    }

    private boolean isResolvedQuestionStatus(String status) {
        return "completed".equals(status) || "error".equals(status);
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
