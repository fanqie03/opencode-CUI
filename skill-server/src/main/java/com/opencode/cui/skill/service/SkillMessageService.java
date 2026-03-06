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

    public SkillMessageService(SkillMessageRepository messageRepository,
            SkillSessionService sessionService) {
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
    }

    /**
     * Save a message with auto-incrementing seq per session.
     * Also touches the parent session to refresh last_active_at.
     */
    @Transactional
    public SkillMessage saveMessage(Long sessionId, SkillMessage.Role role, String content,
            SkillMessage.ContentType contentType, String meta) {
        // Auto-increment seq within the session
        int nextSeq = messageRepository.findMaxSeqBySessionId(sessionId) + 1;

        SkillMessage message = SkillMessage.builder()
                .sessionId(sessionId)
                .seq(nextSeq)
                .role(role)
                .content(content)
                .contentType(contentType != null ? contentType : SkillMessage.ContentType.MARKDOWN)
                .meta(meta)
                .build();

        messageRepository.insert(message);

        // Touch session to update last_active_at
        sessionService.touchSession(sessionId);

        log.debug("Saved message: sessionId={}, seq={}, role={}", sessionId, nextSeq, role);
        return message;
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
     * Get the total message count for a session.
     */
    @Transactional(readOnly = true)
    public long getMessageCount(Long sessionId) {
        return messageRepository.countBySessionId(sessionId);
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

    /**
     * Mark a message as finished (called when session goes idle).
     */
    @Transactional
    public void markMessageFinished(Long messageId) {
        messageRepository.markFinished(messageId);
        log.debug("Marked message as finished: messageId={}", messageId);
    }
}
