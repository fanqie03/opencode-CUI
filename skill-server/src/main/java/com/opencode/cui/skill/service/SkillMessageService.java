package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.PageResult;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class SkillMessageService {

    private final SkillMessageRepository messageRepository;
    private final SkillSessionService sessionService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public SkillMessageService(SkillMessageRepository messageRepository,
            SkillSessionService sessionService,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * Save a message with auto-incrementing seq per session.
     * Also touches the parent session to refresh last_active_at.
     */
    @Transactional
    SkillMessage saveMessage(Long sessionId, String messageId, SkillMessage.Role role, String content,
            SkillMessage.ContentType contentType, String meta) {
        int nextSeq = messageRepository.findMaxSeqBySessionId(sessionId) + 1;
        String effectiveMessageId = messageId != null && !messageId.isBlank()
                ? messageId
                : generateMessageId(sessionId, nextSeq);

        SkillMessage message = SkillMessage.builder()
                .id(snowflakeIdGenerator.nextId())
                .messageId(effectiveMessageId)
                .sessionId(sessionId)
                .seq(nextSeq)
                .messageSeq(nextSeq)
                .role(role)
                .content(content)
                .contentType(contentType != null ? contentType : SkillMessage.ContentType.MARKDOWN)
                .meta(meta)
                .build();

        messageRepository.insert(message);
        sessionService.touchSession(sessionId);

        log.debug("Saved message: sessionId={}, messageId={}, seq={}, role={}",
                sessionId, effectiveMessageId, nextSeq, role);
        return message;
    }

    /**
     * Save a message with auto-incrementing seq per session.
     * Also touches the parent session to refresh last_active_at.
     */
    @Transactional
    SkillMessage saveMessage(Long sessionId, SkillMessage.Role role, String content,
            SkillMessage.ContentType contentType, String meta) {
        return saveMessage(sessionId, null, role, content, contentType, meta);
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
