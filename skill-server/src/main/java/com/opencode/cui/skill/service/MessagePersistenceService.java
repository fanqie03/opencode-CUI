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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists streaming messages to the database.
 * <p>
 * Strategy: Only persist FINAL states, not intermediate deltas.
 * <ul>
 * <li>text.done / thinking.done → persist full content</li>
 * <li>tool.update (completed/error) → persist final tool state</li>
 * <li>question (running) → persist question (needs user response)</li>
 * <li>file → persist immediately</li>
 * <li>step.done → update message-level token stats</li>
 * <li>text.delta / thinking.delta → SKIP (intermediate)</li>
 * <li>tool.update (pending/running) → SKIP (intermediate)</li>
 * </ul>
 */
@Slf4j
@Service
public class MessagePersistenceService {

    private final SkillMessageService messageService;
    private final SkillMessagePartRepository partRepository;
    private final ObjectMapper objectMapper;

    /**
     * Tracks the current assistant message ID per session.
     * When a new assistant turn starts (first part), we create a message row.
     * Subsequent parts in the same turn attach to the same message ID.
     */
    private final ConcurrentHashMap<Long, Long> activeMessageIds = new ConcurrentHashMap<>();

    public MessagePersistenceService(SkillMessageService messageService,
            SkillMessagePartRepository partRepository,
            ObjectMapper objectMapper) {
        this.messageService = messageService;
        this.partRepository = partRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a StreamMessage if it represents a final state.
     * Called from GatewayRelayService after broadcasting.
     *
     * @param sessionId the skill session ID
     * @param msg       the translated StreamMessage
     */
    @Transactional
    public void persistIfFinal(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null)
            return;

        switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> persistTextPart(sessionId, msg, "text");
            case StreamMessage.Types.THINKING_DONE -> persistTextPart(sessionId, msg, "reasoning");
            case StreamMessage.Types.TOOL_UPDATE -> persistToolPartIfFinal(sessionId, msg);
            case StreamMessage.Types.QUESTION -> persistToolPart(sessionId, msg);
            case StreamMessage.Types.FILE -> persistFilePart(sessionId, msg);
            case StreamMessage.Types.STEP_DONE -> persistStepDone(sessionId, msg);
            case StreamMessage.Types.SESSION_STATUS -> handleSessionStatus(sessionId, msg);
            default -> {
                // text.delta, thinking.delta, step.start, etc. → skip persistence
            }
        }
    }

    /**
     * Get or create the active assistant message for a session.
     */
    private Long getOrCreateMessageId(Long sessionId) {
        return activeMessageIds.computeIfAbsent(sessionId, sid -> {
            SkillMessage message = messageService.saveMessage(
                    sid,
                    SkillMessage.Role.ASSISTANT,
                    "", // content will be updated later or left empty
                    SkillMessage.ContentType.MARKDOWN,
                    null);
            log.debug("Created new assistant message for session {}: messageId={}", sid, message.getId());
            return message.getId();
        });
    }

    /**
     * Persist a completed text or reasoning part.
     */
    private void persistTextPart(Long sessionId, StreamMessage msg, String partType) {
        Long messageId = getOrCreateMessageId(sessionId);
        int nextSeq = partRepository.findMaxSeqByMessageId(messageId) + 1;

        SkillMessagePart part = SkillMessagePart.builder()
                .messageId(messageId)
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : partType + "-" + nextSeq)
                .seq(nextSeq)
                .partType(partType)
                .content(msg.getContent())
                .build();

        partRepository.upsert(part);
        log.debug("Persisted {} part: sessionId={}, partId={}", partType, sessionId, part.getPartId());
    }

    /**
     * Persist a tool part only if it's in a final state (completed or error).
     */
    private void persistToolPartIfFinal(Long sessionId, StreamMessage msg) {
        String status = msg.getStatus();
        if ("completed".equals(status) || "error".equals(status)) {
            persistToolPart(sessionId, msg);
        }
        // pending / running → skip
    }

    /**
     * Persist a tool part (any status — used for question and final tool states).
     */
    private void persistToolPart(Long sessionId, StreamMessage msg) {
        Long messageId = getOrCreateMessageId(sessionId);
        int nextSeq = partRepository.findMaxSeqByMessageId(messageId) + 1;

        String inputJson = null;
        if (msg.getInput() != null) {
            try {
                inputJson = objectMapper.writeValueAsString(msg.getInput());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize tool input: {}", e.getMessage());
            }
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .messageId(messageId)
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "tool-" + nextSeq)
                .seq(nextSeq)
                .partType("tool")
                .toolName(msg.getToolName())
                .toolCallId(msg.getToolCallId())
                .toolStatus(msg.getStatus())
                .toolInput(inputJson)
                .toolOutput(msg.getOutput())
                .toolError(msg.getError())
                .toolTitle(msg.getTitle())
                .build();

        partRepository.upsert(part);
        log.debug("Persisted tool part: sessionId={}, tool={}, status={}",
                sessionId, msg.getToolName(), msg.getStatus());
    }

    /**
     * Persist a file part.
     */
    private void persistFilePart(Long sessionId, StreamMessage msg) {
        Long messageId = getOrCreateMessageId(sessionId);
        int nextSeq = partRepository.findMaxSeqByMessageId(messageId) + 1;

        String mime = "";
        if (msg.getMetadata() instanceof Map<?, ?> meta) {
            Object mimeVal = meta.get("mime");
            if (mimeVal != null)
                mime = mimeVal.toString();
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .messageId(messageId)
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "file-" + nextSeq)
                .seq(nextSeq)
                .partType("file")
                .fileName(msg.getTitle())
                .fileUrl(msg.getContent())
                .fileMime(mime)
                .build();

        partRepository.upsert(part);
        log.debug("Persisted file part: sessionId={}, file={}", sessionId, msg.getTitle());
    }

    /**
     * Persist step completion with token/cost stats.
     * Also updates the parent message's aggregate stats.
     */
    private void persistStepDone(Long sessionId, StreamMessage msg) {
        Long messageId = getOrCreateMessageId(sessionId);
        int nextSeq = partRepository.findMaxSeqByMessageId(messageId) + 1;

        Integer tokensIn = null;
        Integer tokensOut = null;
        if (msg.getTokens() != null) {
            Object inVal = msg.getTokens().get("input");
            Object outVal = msg.getTokens().get("output");
            if (inVal instanceof Number n)
                tokensIn = n.intValue();
            if (outVal instanceof Number n)
                tokensOut = n.intValue();
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .messageId(messageId)
                .sessionId(sessionId)
                .partId("step-done-" + nextSeq)
                .seq(nextSeq)
                .partType("step-finish")
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .cost(msg.getCost())
                .finishReason(msg.getReason())
                .build();

        partRepository.insert(part);

        // Update message-level stats
        messageService.updateMessageStats(messageId, tokensIn, tokensOut, msg.getCost());
        log.debug("Persisted step.done: sessionId={}, tokensIn={}, tokensOut={}, cost={}",
                sessionId, tokensIn, tokensOut, msg.getCost());
    }

    /**
     * Handle session status changes.
     * When session goes idle, finalize the current message and clear the active ID.
     */
    private void handleSessionStatus(Long sessionId, StreamMessage msg) {
        if ("idle".equals(msg.getSessionStatus())) {
            Long messageId = activeMessageIds.remove(sessionId);
            if (messageId != null) {
                messageService.markMessageFinished(messageId);
                log.debug("Finalized assistant message: sessionId={}, messageId={}", sessionId, messageId);
            }
        }
    }

    /**
     * Clear the active message tracking for a session.
     * Called when a session is closed or reset.
     */
    public void clearSession(Long sessionId) {
        activeMessageIds.remove(sessionId);
    }
}
