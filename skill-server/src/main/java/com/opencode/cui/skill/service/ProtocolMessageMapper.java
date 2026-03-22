package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ProtocolMessagePart;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 协议消息映射工具类。
 * 负责将内部存储模型（SkillMessage / SkillMessagePart / StreamMessage）
 * 转换为前端协议视图对象（ProtocolMessageView / ProtocolMessagePart）。
 *
 * <p>
 * 处理各类消息 Part 类型的规范化和字段映射：text、tool、question、permission、file。
 * </p>
 */
public final class ProtocolMessageMapper {

    private static final Logger log = LoggerFactory.getLogger(ProtocolMessageMapper.class);

    private ProtocolMessageMapper() {
    }

    public static ProtocolMessageView toProtocolMessage(
            SkillMessage message,
            List<SkillMessagePart> parts,
            ObjectMapper objectMapper) {
        return ProtocolMessageView.builder()
                .id(resolveMessageId(message))
                .welinkSessionId(message.getSessionId() != null ? message.getSessionId().toString() : null)
                .seq(message.getSeq())
                .messageSeq(message.getMessageSeq() != null ? message.getMessageSeq() : message.getSeq())
                .role(message.getRole() != null ? message.getRole().name().toLowerCase() : null)
                .content(message.getContent())
                .contentType(message.getContentType() != null ? message.getContentType().name().toLowerCase() : null)
                .createdAt(message.getCreatedAt() != null ? message.getCreatedAt().toString() : null)
                .meta(parseJsonNode(message.getMeta(), objectMapper))
                .parts(parts == null ? List.of()
                        : parts.stream()
                                .map(part -> toProtocolPart(part, objectMapper))
                                .filter(java.util.Objects::nonNull)
                                .toList())
                .build();
    }

    public static ProtocolMessagePart toProtocolPart(SkillMessagePart part, ObjectMapper objectMapper) {
        if (part == null || part.getPartType() == null) {
            return null;
        }

        String normalizedType = normalizePartType(part);
        if (normalizedType == null) {
            return null;
        }

        ProtocolMessagePart.ProtocolMessagePartBuilder builder = ProtocolMessagePart.builder()
                .partId(part.getPartId())
                .partSeq(part.getSeq())
                .type(normalizedType)
                .content(part.getContent());

        switch (normalizedType) {
            case "tool" -> {
                builder.toolName(part.getToolName())
                        .toolCallId(part.getToolCallId())
                        .status(part.getToolStatus())
                        .input(parseJsonNode(part.getToolInput(), objectMapper))
                        .output(part.getToolOutput())
                        .error(part.getToolError())
                        .title(part.getToolTitle());
            }
            case "question" -> {
                JsonNode inputNode = parseJsonNode(part.getToolInput(), objectMapper);
                JsonNode questionNode = ProtocolUtils.resolveQuestionPayload(inputNode);
                String normalizedOutput = ProtocolUtils.normalizeQuestionAnswerOutput(part.getToolOutput(), inputNode);
                builder.toolName("question")
                        .toolCallId(part.getToolCallId())
                        .status(part.getToolStatus())
                        .input(inputNode)
                        .output(normalizedOutput);
                if ("completed".equals(part.getToolStatus())) {
                    builder.answered(true);
                }
                if (questionNode != null && questionNode.isObject()) {
                    JsonNode header = questionNode.get("header");
                    JsonNode question = questionNode.get("question");
                    if (header != null && header.isTextual()) {
                        builder.header(header.asText());
                    }
                    if (question != null && question.isTextual()) {
                        builder.question(question.asText());
                    }
                    builder.options(ProtocolUtils.extractQuestionOptions(questionNode.get("options")));
                }
            }
            case "permission" -> builder.permissionId(
                    part.getToolCallId() != null ? part.getToolCallId() : part.getPartId())
                    .permType(part.getToolName())
                    .metadata(parseJsonNode(part.getToolInput(), objectMapper))
                    .response(part.getToolOutput())
                    .status(part.getToolStatus())
                    .content(part.getContent());
            case "file" -> builder.fileName(part.getFileName())
                    .fileUrl(part.getFileUrl())
                    .fileMime(part.getFileMime());
            default -> {
                // text / thinking do not require extra fields.
            }
        }

        return builder.build();
    }

    public static ProtocolMessagePart toProtocolStreamingPart(StreamMessage message, ObjectMapper objectMapper) {
        if (message == null || message.getType() == null) {
            return null;
        }

        String normalizedType = normalizeStreamingType(message.getType());
        if (normalizedType == null) {
            return null;
        }

        ProtocolMessagePart.ProtocolMessagePartBuilder builder = ProtocolMessagePart.builder()
                .partId(message.getPartId())
                .partSeq(message.getPartSeq())
                .type(normalizedType)
                .content(message.getContent());

        switch (normalizedType) {
            case "tool" -> {
                var t = message.getTool();
                if (t != null) {
                    builder.toolName(t.getToolName())
                            .toolCallId(t.getToolCallId())
                            .input(normalizeObjectValue(t.getInput(), objectMapper))
                            .output(t.getOutput());
                }
                builder.status(message.getStatus())
                        .error(message.getError())
                        .title(message.getTitle());
            }
            case "question" -> {
                var t = message.getTool();
                var q = message.getQuestionInfo();
                Object normalizedInput = t != null ? normalizeObjectValue(t.getInput(), objectMapper) : null;
                JsonNode normalizedInputNode = normalizedInput instanceof JsonNode node ? node : null;
                builder.toolName("question")
                        .status(message.getStatus())
                        .output(ProtocolUtils.normalizeQuestionAnswerOutput(
                                t != null ? t.getOutput() : null,
                                normalizedInputNode));
                if ("completed".equals(message.getStatus())) {
                    builder.answered(true);
                }
                if (t != null) {
                    builder.toolCallId(t.getToolCallId())
                            .input(normalizedInput);
                }
                if (q != null) {
                    builder.header(q.getHeader())
                            .question(q.getQuestion())
                            .options(q.getOptions());
                }
            }
            case "permission" -> {
                var p = message.getPermission();
                if (p != null) {
                    builder.permissionId(p.getPermissionId())
                            .permType(p.getPermType())
                            .metadata(normalizeObjectValue(p.getMetadata(), objectMapper))
                            .response(p.getResponse());
                }
                builder.status(message.getStatus());
            }
            case "file" -> {
                var f = message.getFile();
                if (f != null) {
                    builder.fileName(f.getFileName())
                            .fileUrl(f.getFileUrl())
                            .fileMime(f.getFileMime());
                }
            }
            default -> {
                // text / thinking do not require extra fields.
            }
        }

        return builder.build();
    }

    private static String resolveMessageId(SkillMessage message) {
        if (message.getMessageId() != null && !message.getMessageId().isBlank()) {
            return message.getMessageId();
        }
        return message.getId() != null ? String.valueOf(message.getId()) : null;
    }

    private static String normalizePartType(SkillMessagePart part) {
        return switch (part.getPartType()) {
            case "text" -> "text";
            case "reasoning" -> "thinking";
            case "tool" -> "question".equals(part.getToolName()) ? "question" : "tool";
            case "permission" -> "permission";
            case "file" -> "file";
            default -> null;
        };
    }

    private static String normalizeStreamingType(String type) {
        return switch (type) {
            case StreamMessage.Types.TEXT_DELTA, StreamMessage.Types.TEXT_DONE -> "text";
            case StreamMessage.Types.THINKING_DELTA, StreamMessage.Types.THINKING_DONE -> "thinking";
            case StreamMessage.Types.TOOL_UPDATE -> "tool";
            case StreamMessage.Types.QUESTION -> "question";
            case StreamMessage.Types.PERMISSION_ASK, StreamMessage.Types.PERMISSION_REPLY -> "permission";
            case StreamMessage.Types.FILE -> "file";
            default -> null;
        };
    }

    // ==================== JSON helpers ====================

    /**
     * 安全解析 JSON 字符串为 JsonNode，失败或空值返回 null。
     */
    private static JsonNode parseJsonNode(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("Failed to parse JSON: {}", json, e);
            return null;
        }
    }

    /**
     * 将任意对象安全转为 JsonNode 表示：
     * - String → readTree 解析；其他 → valueToTree 转换。
     */
    private static Object normalizeObjectValue(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return parseJsonNode(str, objectMapper);
        }
        try {
            return objectMapper.valueToTree(value);
        } catch (Exception e) {
            return value;
        }
    }
}
