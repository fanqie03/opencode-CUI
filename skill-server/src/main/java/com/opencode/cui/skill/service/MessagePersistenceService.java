package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists streamed assistant output.
 *
 * Active message identity tracking is delegated to
 * {@link ActiveMessageTracker}.
 */
@Slf4j
@Service
public class MessagePersistenceService {

    private final SkillMessageService messageService;
    private final SkillMessagePartRepository partRepository;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ActiveMessageTracker tracker;

    public MessagePersistenceService(SkillMessageService messageService,
            SkillMessagePartRepository partRepository,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ActiveMessageTracker tracker) {
        this.messageService = messageService;
        this.partRepository = partRepository;
        this.objectMapper = objectMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.tracker = tracker;
    }

    @Transactional
    public void prepareMessageContext(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null || !requiresMessageContext(msg)) {
            return;
        }
        tracker.resolveActiveMessage(sessionId, msg);
    }

    /**
     * Persist a StreamMessage if it represents a final state.
     */
    @Transactional
    public void persistIfFinal(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null) {
            return;
        }

        ActiveMessageTracker.ActiveMessageRef active = requiresMessageContext(msg)
                ? tracker.resolveActiveMessage(sessionId, msg)
                : null;

        switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> persistTextPart(sessionId, msg, "text", active);
            case StreamMessage.Types.THINKING_DONE -> persistTextPart(sessionId, msg, "reasoning", active);
            case StreamMessage.Types.TOOL_UPDATE -> persistToolPartIfFinal(sessionId, msg, active);
            case StreamMessage.Types.QUESTION -> persistToolPart(sessionId, msg, active);
            case StreamMessage.Types.FILE -> persistFilePart(sessionId, msg, active);
            case StreamMessage.Types.STEP_DONE -> persistStepDone(sessionId, msg, active);
            case StreamMessage.Types.SESSION_STATUS -> handleSessionStatus(sessionId, msg);
            default -> {
                // Intermediate deltas and status-only events do not persist parts.
            }
        }
    }

    // ==================== Delegated Tracking Methods ====================

    public void clearSession(Long sessionId) {
        tracker.clearSession(sessionId);
    }

    public void markPendingUserMessage(Long sessionId) {
        tracker.markPendingUserMessage(sessionId);
    }

    public boolean consumePendingUserMessage(Long sessionId) {
        return tracker.consumePendingUserMessage(sessionId);
    }

    @Transactional
    public void finalizeActiveAssistantTurn(Long sessionId) {
        tracker.finalizeActiveAssistantTurn(sessionId);
    }

    // ==================== Persistence Logic ====================

    private void persistTextPart(Long sessionId, StreamMessage msg, String partType,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return;
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : partType + "-" + active.messageSeq())
                .seq(resolvePartSeq(sessionId, active.dbId(), msg))
                .partType(partType)
                .content(msg.getContent())
                .build();

        partRepository.upsert(part);
        log.debug("Persisted {} part: sessionId={}, protocolId={}, partId={}",
                partType, sessionId, active.protocolMessageId(), part.getPartId());

        if ("text".equals(partType)) {
            syncMessageContent(active);
        }
    }

    private void persistToolPartIfFinal(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        String status = msg.getStatus();
        if ("completed".equals(status) || "error".equals(status)) {
            persistToolPart(sessionId, msg, active);
        }
    }

    private void persistToolPart(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return;
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
                .seq(resolvePartSeq(sessionId, active.dbId(), msg))
                .partType("tool")
                .toolName(tool != null ? tool.getToolName() : null)
                .toolCallId(tool != null ? tool.getToolCallId() : null)
                .toolStatus(msg.getStatus())
                .toolInput(inputJson)
                .toolOutput(tool != null ? tool.getOutput() : null)
                .toolError(msg.getError())
                .toolTitle(msg.getTitle())
                .build();

        partRepository.upsert(part);
        log.debug("Persisted tool part: sessionId={}, protocolId={}, tool={}, status={}",
                sessionId, active.protocolMessageId(),
                tool != null ? tool.getToolName() : null, msg.getStatus());
    }

    private void persistFilePart(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return;
        }

        var f = msg.getFile();
        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "file-" + active.messageSeq())
                .seq(resolvePartSeq(sessionId, active.dbId(), msg))
                .partType("file")
                .fileName(f != null ? f.getFileName() : null)
                .fileUrl(f != null ? f.getFileUrl() : null)
                .fileMime(f != null ? f.getFileMime() : null)
                .build();

        partRepository.upsert(part);
        log.debug("Persisted file part: sessionId={}, protocolId={}, file={}",
                sessionId, active.protocolMessageId(),
                f != null ? f.getFileName() : null);
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

    private void persistStepDone(Long sessionId, StreamMessage msg,
            ActiveMessageTracker.ActiveMessageRef active) {
        if (active == null) {
            return;
        }

        var u = msg.getUsage();
        UsageStats stats = extractUsageStats(msg);
        int partSeq = resolvePartSeq(sessionId, active.dbId(), msg);
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
                .build();

        partRepository.upsert(part);
        messageService.updateMessageStats(active.dbId(), stats.tokensIn(), stats.tokensOut(), cost);
        log.debug("Persisted step.done: sessionId={}, protocolId={}, tokensIn={}, tokensOut={}, cost={}",
                sessionId, active.protocolMessageId(), stats.tokensIn(), stats.tokensOut(), cost);
    }

    private void handleSessionStatus(Long sessionId, StreamMessage msg) {
        if (!"idle".equals(msg.getSessionStatus()) && !"completed".equals(msg.getSessionStatus())) {
            return;
        }
        tracker.removeAndFinalize(sessionId);
    }

    // ==================== Internal Helpers ====================

    private int resolvePartSeq(Long sessionId, Long messageDbId, StreamMessage msg) {
        if (msg.getPartId() != null && !msg.getPartId().isBlank()) {
            SkillMessagePart existing = partRepository.findByPartId(sessionId, msg.getPartId());
            if (existing != null && existing.getSeq() != null) {
                return existing.getSeq();
            }
        }

        if (msg.getPartSeq() != null && msg.getPartSeq() > 0) {
            return msg.getPartSeq();
        }

        return partRepository.findMaxSeqByMessageId(messageDbId) + 1;
    }

    private void syncMessageContent(ActiveMessageTracker.ActiveMessageRef active) {
        StringBuilder content = new StringBuilder();
        for (SkillMessagePart existingPart : partRepository.findByMessageId(active.dbId())) {
            if ("text".equals(existingPart.getPartType()) && existingPart.getContent() != null) {
                content.append(existingPart.getContent());
            }
        }
        messageService.updateMessageContent(active.dbId(), content.toString());
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
                    StreamMessage.Types.PERMISSION_REPLY ->
                true;
            default -> false;
        };
    }
}
