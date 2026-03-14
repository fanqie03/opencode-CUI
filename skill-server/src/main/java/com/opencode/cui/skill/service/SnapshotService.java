package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessagePartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 构建 session 快照和实时流式状态。
 * 从 SkillStreamHandler 中拆出，保持 WS Handler 只关注连接生命周期。
 */
@Slf4j
@Service
public class SnapshotService {

    private final ObjectMapper objectMapper;
    private final StreamBufferService bufferService;
    private final SkillSessionService sessionService;
    private final SkillMessageService messageService;
    private final SkillMessagePartRepository partRepository;

    public SnapshotService(ObjectMapper objectMapper,
            StreamBufferService bufferService,
            SkillSessionService sessionService,
            SkillMessageService messageService,
            SkillMessagePartRepository partRepository) {
        this.objectMapper = objectMapper;
        this.bufferService = bufferService;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.partRepository = partRepository;
    }

    /**
     * 构建指定 session 的历史消息快照 StreamMessage。
     */
    public StreamMessage buildSnapshot(String sessionId, long seq) {
        List<Object> messages = buildSnapshotMessages(sessionId);
        return StreamMessage.builder()
                .type(StreamMessage.Types.SNAPSHOT)
                .seq(seq)
                .sessionId(sessionId)
                .emittedAt(Instant.now().toString())
                .messages(messages)
                .build();
    }

    /**
     * 构建指定 session 的实时流式状态 StreamMessage。
     */
    public StreamMessage buildStreamingState(String sessionId, long seq) {
        boolean isStreaming = bufferService.isSessionStreaming(sessionId);
        List<StreamMessage> parts = bufferService.getStreamingParts(sessionId);
        List<Object> aggregatedParts = parts.stream()
                .map(part -> ProtocolMessageMapper.toProtocolStreamingPart(part, objectMapper))
                .filter(Objects::nonNull)
                .map(part -> (Object) part)
                .toList();

        StreamMessage streamingMsg = StreamMessage.builder()
                .type(StreamMessage.Types.STREAMING)
                .seq(seq)
                .sessionId(sessionId)
                .emittedAt(Instant.now().toString())
                .sessionStatus(isStreaming ? "busy" : "idle")
                .parts(aggregatedParts)
                .build();

        if (!parts.isEmpty()) {
            StreamMessage firstPart = parts.get(0);
            streamingMsg.setMessageId(firstPart.getMessageId());
            streamingMsg.setMessageSeq(firstPart.getMessageSeq());
            streamingMsg.setRole(firstPart.getRole());
        }

        return streamingMsg;
    }

    /**
     * 获取用户的所有活跃 session，用于初始化推送。
     */
    public List<SkillSession> getActiveSessionsForUser(String userId) {
        return sessionService.findActiveByUserId(userId);
    }

    private List<Object> buildSnapshotMessages(String sessionId) {
        Long numericSessionId = ProtocolUtils.parseSessionId(sessionId);
        if (numericSessionId == null) {
            log.warn("Cannot build snapshot: invalid sessionId={}", sessionId);
            return List.of();
        }

        return messageService.getAllMessages(numericSessionId).stream()
                .map(this::toSnapshotMessage)
                .map(node -> (Object) node)
                .toList();
    }

    private ObjectNode toSnapshotMessage(SkillMessage message) {
        ProtocolMessageView messageView = ProtocolMessageMapper.toProtocolMessage(
                message,
                partRepository.findByMessageId(message.getId()),
                objectMapper);
        return objectMapper.valueToTree(messageView);
    }
}
