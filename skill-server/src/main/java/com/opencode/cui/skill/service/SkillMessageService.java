package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SaveMessageCommand;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class SkillMessageService {

    private final SkillMessageRepository messageRepository;
    private final SkillMessagePartRepository partRepository;
    private final SkillSessionService sessionService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectMapper objectMapper;

    public SkillMessageService(SkillMessageRepository messageRepository,
            SkillMessagePartRepository partRepository,
            SkillSessionService sessionService,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.partRepository = partRepository;
        this.sessionService = sessionService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * Save a message with auto-incrementing seq per session.
     * Also touches the parent session to refresh last_active_at.
     */
    @Transactional
    SkillMessage saveMessage(SaveMessageCommand cmd) {
        int nextSeq = messageRepository.findMaxSeqBySessionId(cmd.sessionId()) + 1;
        String effectiveMessageId = cmd.messageId() != null && !cmd.messageId().isBlank()
                ? cmd.messageId()
                : generateMessageId(cmd.sessionId(), nextSeq);

        SkillMessage message = SkillMessage.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(effectiveMessageId)
                .sessionId(cmd.sessionId())
                .seq(nextSeq)
                .messageSeq(nextSeq)
                .role(cmd.role())
                .content(cmd.content())
                .contentType(cmd.contentType() != null ? cmd.contentType() : SkillMessage.ContentType.MARKDOWN)
                .meta(cmd.meta())
                .build();

        messageRepository.insert(message);
        sessionService.touchSession(cmd.sessionId());

        log.debug("Saved message: sessionId={}, messageId={}, seq={}, role={}",
                cmd.sessionId(), effectiveMessageId, nextSeq, cmd.role());
        return message;
    }

    /**
     * Save a message with auto-incrementing seq per session.
     * Also touches the parent session to refresh last_active_at.
     */
    @Transactional
    SkillMessage saveMessage(Long sessionId, SkillMessage.Role role, String content,
            SkillMessage.ContentType contentType, String meta) {
        return saveMessage(new SaveMessageCommand(sessionId, role, content, contentType, meta));
    }

    /**
     * Save a user message.
     */
    @Transactional
    public SkillMessage saveUserMessage(Long sessionId, String content) {
        return saveMessage(sessionId, SkillMessage.Role.USER, content,
                SkillMessage.ContentType.PLAIN, null);
    }

    /**
     * Save an assistant message (raw OpenCode output, stored as-is).
     */
    @Transactional
    public SkillMessage saveAssistantMessage(Long sessionId, String content, String meta) {
        return saveMessage(sessionId, SkillMessage.Role.ASSISTANT, content,
                SkillMessage.ContentType.MARKDOWN, meta);
    }

    /**
     * Save a tool message (OpenCode tool use output, stored as-is).
     */
    @Transactional
    public SkillMessage saveToolMessage(Long sessionId, String content, String meta) {
        return saveMessage(sessionId, SkillMessage.Role.TOOL, content,
                SkillMessage.ContentType.CODE, meta);
    }

    /**
     * Save a system message.
     */
    @Transactional
    public SkillMessage saveSystemMessage(Long sessionId, String content) {
        return saveMessage(sessionId, SkillMessage.Role.SYSTEM, content,
                SkillMessage.ContentType.PLAIN, null);
    }

    /**
     * Query message history with pagination, ordered by seq ascending.
     */
    @Transactional(readOnly = true)
    public PageResult<SkillMessage> getMessageHistory(Long sessionId, int page, int size) {
        int offset = page * size;
        var content = messageRepository.findBySessionId(sessionId, offset, size);
        long total = messageRepository.countBySessionId(sessionId);
        return new PageResult<>(content, total, page, size);
    }

    /**
     * Query message history with attached parts, returning protocol-ready views.
     */
    @Transactional(readOnly = true)
    public PageResult<ProtocolMessageView> getMessageHistoryWithParts(Long sessionId, int page, int size) {
        PageResult<SkillMessage> messages = getMessageHistory(sessionId, page, size);
        var content = messages.getContent().stream()
                .map(message -> ProtocolMessageMapper.toProtocolMessage(
                        message,
                        partRepository.findByMessageId(message.getId()),
                        objectMapper))
                .toList();
        return new PageResult<>(content, messages.getTotalElements(),
                messages.getNumber(), messages.getSize());
    }

    /**
     * Query all messages for a session in seq order.
     */
    @Transactional(readOnly = true)
    public java.util.List<SkillMessage> getAllMessages(Long sessionId) {
        return messageRepository.findAllBySessionId(sessionId);
    }

    /**
     * Get the total message count for a session.
     */
    @Transactional(readOnly = true)
    public long getMessageCount(Long sessionId) {
        return messageRepository.countBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public SkillMessage findBySessionIdAndMessageId(Long sessionId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        return messageRepository.findBySessionIdAndMessageId(sessionId, messageId);
    }

    @Transactional(readOnly = true)
    public SkillMessage findById(Long messageId) {
        if (messageId == null) {
            return null;
        }
        return messageRepository.findById(messageId);
    }

    /**
     * Update token/cost stats for a message (called on step.done).
     */
    @Transactional
    public void updateMessageStats(Long messageId, Integer tokensIn, Integer tokensOut, Double cost) {
        messageRepository.updateStats(messageId, tokensIn, tokensOut, cost);
        log.debug("Updated message stats: messageId={}, tokensIn={}, tokensOut={}, cost={}",
                messageId, tokensIn, tokensOut, cost);
    }

    @Transactional
    public void updateMessageContent(Long messageId, String content) {
        messageRepository.updateContent(messageId, content);
        log.debug("Updated message content: messageId={}, length={}",
                messageId, content != null ? content.length() : 0);
    }

    /**
     * Mark a message as finished (called when session goes idle).
     */
    @Transactional
    public void markMessageFinished(Long messageId) {
        messageRepository.markFinished(messageId);
        log.debug("Marked message as finished: messageId={}", messageId);
    }

    private String generateMessageId(Long sessionId, int seq) {
        return "msg_" + sessionId + "_" + seq;
    }
}
