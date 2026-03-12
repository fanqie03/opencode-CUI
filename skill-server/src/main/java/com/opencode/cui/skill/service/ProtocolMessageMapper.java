package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ProtocolMessagePart;
import com.opencode.cui.skill.model.ProtocolMessageView;
import com.opencode.cui.skill.model.SkillMessage;
import com.opencode.cui.skill.model.SkillMessagePart;
import com.opencode.cui.skill.model.StreamMessage;

import java.util.List;

public final class ProtocolMessageMapper {

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
                .meta(parseJsonValue(message.getMeta(), objectMapper))
                .parts(parts == null ? List.of() : parts.stream()
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
                        .input(parseJsonValue(part.getToolInput(), objectMapper))
                        .output(part.getToolOutput())
                        .error(part.getToolError())
                        .title(part.getToolTitle());
            }
            case "question" -> {
                JsonNode inputNode = parseJsonNode(part.getToolInput(), objectMapper);
                JsonNode questionNode = resolveQuestionPayload(inputNode);
                builder.toolName("question")
                        .toolCallId(part.getToolCallId())
                        .status(part.getToolStatus())
                        .input(inputNode);
                if (questionNode != null && questionNode.isObject()) {
                    JsonNode header = questionNode.get("header");
                    JsonNode question = questionNode.get("question");
                    if (header != null && header.isTextual()) {
                        builder.header(header.asText());
                    }
                    if (question != null && question.isTextual()) {
                        builder.question(question.asText());
                    }
                    builder.options(normalizeQuestionOptions(questionNode.get("options")));
                }
            }
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
            case "tool" -> builder.toolName(message.getToolName())
                    .toolCallId(message.getToolCallId())
                    .status(message.getStatus())
                    .input(normalizeObjectValue(message.getInput(), objectMapper))
                    .output(message.getOutput())
                    .error(message.getError())
                    .title(message.getTitle());
            case "question" -> builder.toolName("question")
                    .toolCallId(message.getToolCallId())
                    .status(message.getStatus())
                    .input(normalizeObjectValue(message.getInput(), objectMapper))
                    .header(message.getHeader())
                    .question(message.getQuestion())
                    .options(message.getOptions());
            case "permission" -> builder.permissionId(message.getPermissionId())
                    .permType(message.getPermType())
                    .metadata(normalizeObjectValue(message.getMetadata(), objectMapper))
                    .response(message.getResponse())
                    .status(message.getStatus());
            case "file" -> builder.fileName(message.getFileName())
                    .fileUrl(message.getFileUrl())
                    .fileMime(message.getFileMime());
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
            case "file" -> "file";
            default -> null;
        };
    }

    private static Object parseJsonValue(String value, ObjectMapper objectMapper) {
        JsonNode node = parseJsonNode(value, objectMapper);
        if (node == null) {
            return null;
        }
        return node;
    }

    private static Object normalizeObjectValue(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return objectMapper.valueToTree(value);
    }

    private static JsonNode parseJsonNode(String value, ObjectMapper objectMapper) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(value);
        }
    }

    private static JsonNode resolveQuestionPayload(JsonNode inputNode) {
        if (inputNode == null || !inputNode.isObject()) {
            return null;
        }

        JsonNode questionsNode = inputNode.get("questions");
        if (questionsNode != null && questionsNode.isArray() && !questionsNode.isEmpty()) {
            JsonNode firstQuestion = questionsNode.get(0);
            if (firstQuestion != null && firstQuestion.isObject()) {
                return firstQuestion;
            }
        }

        return inputNode;
    }

    private static List<String> normalizeQuestionOptions(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray()) {
            return null;
        }

        List<String> options = new java.util.ArrayList<>();
        optionsNode.forEach(optionNode -> {
            String label = null;
            if (optionNode.isTextual()) {
                label = optionNode.asText();
            } else if (optionNode.isObject()) {
                JsonNode labelNode = optionNode.get("label");
                if (labelNode != null && labelNode.isTextual()) {
                    label = labelNode.asText();
                }
            }
            if (label != null && !label.isBlank()) {
                options.add(label);
            }
        });
        return options.isEmpty() ? null : options;
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
}
