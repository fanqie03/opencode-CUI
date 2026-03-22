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

/**
 * 消息管理服务。
 * 提供消息的保存、查询、更新、分页等操作，
 * 支持多种消息角色（user / assistant / tool / system）和内容类型。
 *
 * <p>
 * 消息 seq 在每个 session 内自增，保存时同步刷新会话的 last_active_at。
 * </p>
 */
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
     * 保存消息，session 内 seq 自动递增。
     * 同时刷新父会话的 last_active_at。
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
     * 保存消息（重载方法），session 内 seq 自动递增。
     * 同时刷新父会话的 last_active_at。
     */
    @Transactional
    SkillMessage saveMessage(Long sessionId, SkillMessage.Role role, String content,
            SkillMessage.ContentType contentType, String meta) {
        return saveMessage(new SaveMessageCommand(sessionId, role, content, contentType, meta));
    }

    /** 保存用户消息。 */
    @Transactional
    public SkillMessage saveUserMessage(Long sessionId, String content) {
        return saveMessage(sessionId, SkillMessage.Role.USER, content,
                SkillMessage.ContentType.PLAIN, null);
    }

    /** 保存助手消息（OpenCode 原始输出，原样存储）。 */
    @Transactional
    public SkillMessage saveAssistantMessage(Long sessionId, String content, String meta) {
        return saveMessage(sessionId, SkillMessage.Role.ASSISTANT, content,
                SkillMessage.ContentType.MARKDOWN, meta);
    }

    /** 保存工具消息（OpenCode 工具调用输出，原样存储）。 */
    @Transactional
    public SkillMessage saveToolMessage(Long sessionId, String content, String meta) {
        return saveMessage(sessionId, SkillMessage.Role.TOOL, content,
                SkillMessage.ContentType.CODE, meta);
    }

    /** 保存系统消息。 */
    @Transactional
    public SkillMessage saveSystemMessage(Long sessionId, String content) {
        return saveMessage(sessionId, SkillMessage.Role.SYSTEM, content,
                SkillMessage.ContentType.PLAIN, null);
    }

    /** 分页查询消息历史，按 seq 升序排列。 */
    @Transactional(readOnly = true)
    public PageResult<SkillMessage> getMessageHistory(Long sessionId, int page, int size) {
        int offset = page * size;
        var content = messageRepository.findBySessionId(sessionId, offset, size);
        long total = messageRepository.countBySessionId(sessionId);
        return new PageResult<>(content, total, page, size);
    }

    /** 分页查询消息历史（含 Part 附件），返回协议层视图对象。 */
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

    /** 查询会话的全部消息，按 seq 排序。 */
    @Transactional(readOnly = true)
    public java.util.List<SkillMessage> getAllMessages(Long sessionId) {
        return messageRepository.findAllBySessionId(sessionId);
    }

    /** 获取会话的消息总数。 */
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

    /** 更新消息的 token 和费用统计（step.done 时调用）。 */
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

    /** 标记消息为已完成（会话进入 IDLE 时调用）。 */
    @Transactional
    public void markMessageFinished(Long messageId) {
        messageRepository.markFinished(messageId);
        log.debug("Marked message as finished: messageId={}", messageId);
    }

    private String generateMessageId(Long sessionId, int seq) {
        return "msg_" + sessionId + "_" + seq;
    }
}
