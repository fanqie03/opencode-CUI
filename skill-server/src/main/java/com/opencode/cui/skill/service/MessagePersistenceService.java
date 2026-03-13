package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persists streamed assistant output and maintains stable message identity.
 */
@Slf4j
@Service
public class MessagePersistenceService {

    private final SkillMessageService messageService;
    private final SkillMessagePartRepository partRepository;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    private final ConcurrentHashMap<Long, ActiveMessageRef> activeMessages = new ConcurrentHashMap<>();

    /**
     * 去重缓存：miniapp 发消息后标记 pending，收到 OpenCode 回传的 user message echo 时消费。
     * 未被消费的标记 5 分钟后过期，防止内存泄漏。
     */
    private final Cache<Long, AtomicInteger> pendingUserMessages = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    public MessagePersistenceService(SkillMessageService messageService,
            SkillMessagePartRepository partRepository,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this.messageService = messageService;
        this.partRepository = partRepository;
        this.objectMapper = objectMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Transactional
    public void prepareMessageContext(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null || !requiresMessageContext(msg)) {
            return;
        }
        resolveActiveMessage(sessionId, msg);
    }

    /**
     * Persist a StreamMessage if it represents a final state.
     */
    @Transactional
    public void persistIfFinal(Long sessionId, StreamMessage msg) {
        if (msg == null || msg.getType() == null) {
            return;
        }

        ActiveMessageRef active = requiresMessageContext(msg)
                ? resolveActiveMessage(sessionId, msg)
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

    /**
     * Clear active tracking for a session.
     */
    public void clearSession(Long sessionId) {
        activeMessages.remove(sessionId);
    }

    /**
     * 标记 miniapp 发出的 user message，供后续去重。
     * 当 OpenCode 回传 user message echo 时，可通过 consumePendingUserMessage 判断是否为 echo。
     */
    public void markPendingUserMessage(Long sessionId) {
        pendingUserMessages.asMap()
                .computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                .incrementAndGet();
        log.debug("Marked pending user message for session {}", sessionId);
    }

    /**
     * 消费一个 pending user message 标记。
     * @return true 表示当前消息是 miniapp echo（应跳过），false 表示来自 opencode CLI（应处理）
     */
    public boolean consumePendingUserMessage(Long sessionId) {
        AtomicInteger count = pendingUserMessages.getIfPresent(sessionId);
        if (count != null && count.get() > 0) {
            count.decrementAndGet();
            log.debug("Consumed pending user message for session {} (echo from miniapp)", sessionId);
            return true;
        }
        return false;
    }

    /**
     * Finalize the current assistant turn before a new user turn starts.
     */
    @Transactional
    public void finalizeActiveAssistantTurn(Long sessionId) {
        ActiveMessageRef active = activeMessages.remove(sessionId);
        if (active == null) {
            return;
        }

        messageService.markMessageFinished(active.dbId());
        log.debug("Finalized dangling assistant message before user turn: sessionId={}, messageId={}, protocolId={}",
                sessionId, active.dbId(), active.protocolMessageId());
    }

    private ActiveMessageRef resolveActiveMessage(Long sessionId, StreamMessage msg) {
        String requestedMessageId = ProtocolUtils.firstNonBlank(msg.getMessageId(), msg.getSourceMessageId());
        String role = ProtocolUtils.normalizeRole(msg.getRole());

        ActiveMessageRef active = activeMessages.get(sessionId);
        if (active != null) {
            if (requestedMessageId == null || requestedMessageId.equals(active.protocolMessageId())) {
                applyMessageContext(msg, active, role);
                return active;
            }

            finalizeActiveMessage(sessionId, active, "message_id_changed");
        }

        if (requestedMessageId != null) {
            SkillMessage existing = messageService.findBySessionIdAndMessageId(sessionId, requestedMessageId);
            if (existing != null) {
                ActiveMessageRef existingRef = new ActiveMessageRef(
                        existing.getId(),
                        existing.getMessageId(),
                        existing.getSeq());
                activeMessages.put(sessionId, existingRef);
                applyMessageContext(msg, existingRef, role);
                return existingRef;
            }
        }

        SkillMessage created = messageService.saveMessage(
                sessionId,
                requestedMessageId,
                toRoleEnum(role),
                "",
                toContentType(role),
                null);
        ActiveMessageRef createdRef = new ActiveMessageRef(
                created.getId(),
                created.getMessageId(),
                created.getSeq());
        activeMessages.put(sessionId, createdRef);
        applyMessageContext(msg, createdRef, role);
        log.debug("Created active streamed message: sessionId={}, dbId={}, protocolId={}, seq={}",
                sessionId, createdRef.dbId(), createdRef.protocolMessageId(), createdRef.messageSeq());
        return createdRef;
    }

    private void applyMessageContext(StreamMessage msg, ActiveMessageRef active, String role) {
        msg.setMessageId(active.protocolMessageId());
        msg.setMessageSeq(active.messageSeq());
        msg.setRole(role);
    }

    private void persistTextPart(Long sessionId, StreamMessage msg, String partType, ActiveMessageRef active) {
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

    private void persistToolPartIfFinal(Long sessionId, StreamMessage msg, ActiveMessageRef active) {
        String status = msg.getStatus();
        if ("completed".equals(status) || "error".equals(status)) {
            persistToolPart(sessionId, msg, active);
        }
    }

    private void persistToolPart(Long sessionId, StreamMessage msg, ActiveMessageRef active) {
        if (active == null) {
            return;
        }

        String inputJson = null;
        if (msg.getInput() != null) {
            try {
                inputJson = objectMapper.writeValueAsString(msg.getInput());
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
                .toolName(msg.getToolName())
                .toolCallId(msg.getToolCallId())
                .toolStatus(msg.getStatus())
                .toolInput(inputJson)
                .toolOutput(msg.getOutput())
                .toolError(msg.getError())
                .toolTitle(msg.getTitle())
                .build();

        partRepository.upsert(part);
        log.debug("Persisted tool part: sessionId={}, protocolId={}, tool={}, status={}",
                sessionId, active.protocolMessageId(), msg.getToolName(), msg.getStatus());
    }

    private void persistFilePart(Long sessionId, StreamMessage msg, ActiveMessageRef active) {
        if (active == null) {
            return;
        }

        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "file-" + active.messageSeq())
                .seq(resolvePartSeq(sessionId, active.dbId(), msg))
                .partType("file")
                .fileName(msg.getFileName())
                .fileUrl(msg.getFileUrl())
                .fileMime(msg.getFileMime())
                .build();

        partRepository.upsert(part);
        log.debug("Persisted file part: sessionId={}, protocolId={}, file={}",
                sessionId, active.protocolMessageId(), msg.getFileName());
    }

    private void persistStepDone(Long sessionId, StreamMessage msg, ActiveMessageRef active) {
        if (active == null) {
            return;
        }

        Integer tokensIn = null;
        Integer tokensOut = null;
        if (msg.getTokens() != null) {
            Object inVal = msg.getTokens().get("input");
            Object outVal = msg.getTokens().get("output");
            if (inVal instanceof Number inNumber) {
                tokensIn = inNumber.intValue();
            }
            if (outVal instanceof Number outNumber) {
                tokensOut = outNumber.intValue();
            }
        }

        int partSeq = resolvePartSeq(sessionId, active.dbId(), msg);
        SkillMessagePart part = SkillMessagePart.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(active.dbId())
                .sessionId(sessionId)
                .partId(msg.getPartId() != null ? msg.getPartId() : "step-done-" + active.dbId() + "-" + partSeq)
                .seq(partSeq)
                .partType("step-finish")
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .cost(msg.getCost())
                .finishReason(msg.getReason())
                .build();

        partRepository.upsert(part);
        messageService.updateMessageStats(active.dbId(), tokensIn, tokensOut, msg.getCost());
        log.debug("Persisted step.done: sessionId={}, protocolId={}, tokensIn={}, tokensOut={}, cost={}",
                sessionId, active.protocolMessageId(), tokensIn, tokensOut, msg.getCost());
    }

    private void handleSessionStatus(Long sessionId, StreamMessage msg) {
        if (!"idle".equals(msg.getSessionStatus()) && !"completed".equals(msg.getSessionStatus())) {
            return;
        }

        ActiveMessageRef active = activeMessages.remove(sessionId);
        if (active != null) {
            messageService.markMessageFinished(active.dbId());
            log.debug("Finalized assistant message on session status: sessionId={}, messageId={}, protocolId={}",
                    sessionId, active.dbId(), active.protocolMessageId());
        }
    }

    private void finalizeActiveMessage(Long sessionId, ActiveMessageRef active, String reason) {
        activeMessages.remove(sessionId, active);
        messageService.markMessageFinished(active.dbId());
        log.debug("Finalized active streamed message: sessionId={}, messageId={}, protocolId={}, reason={}",
                sessionId, active.dbId(), active.protocolMessageId(), reason);
    }

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

    private void syncMessageContent(ActiveMessageRef active) {
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
                    StreamMessage.Types.PERMISSION_REPLY -> true;
            default -> false;
        };
    }




    private SkillMessage.Role toRoleEnum(String role) {
        return switch (role) {
            case "user" -> SkillMessage.Role.USER;
            case "system" -> SkillMessage.Role.SYSTEM;
            case "tool" -> SkillMessage.Role.TOOL;
            default -> SkillMessage.Role.ASSISTANT;
        };
    }

    private SkillMessage.ContentType toContentType(String role) {
        return switch (role) {
            case "user", "system" -> SkillMessage.ContentType.PLAIN;
            case "tool" -> SkillMessage.ContentType.CODE;
            default -> SkillMessage.ContentType.MARKDOWN;
        };
    }

    private record ActiveMessageRef(Long dbId, String protocolMessageId, Integer messageSeq) {
    }
}
