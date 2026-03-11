package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Translates raw OpenCode events into frontend-facing StreamMessage DTOs.
 */
@Slf4j
@Component
public class OpenCodeEventTranslator {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, String> partTypes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> partSequences = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> nextPartSequences = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> messageRoles = new ConcurrentHashMap<>();

    public OpenCodeEventTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public StreamMessage translate(JsonNode event) {
        if (event == null) {
            return null;
        }

        String eventType = event.path("type").asText("");
        return switch (eventType) {
            case "message.part.updated" -> translatePartUpdated(event);
            case "message.part.delta" -> translatePartDelta(event);
            case "message.part.removed" -> {
                JsonNode props = event.path("properties");
                evictPart(
                        props.path("sessionID").asText(null),
                        props.path("messageID").asText(null),
                        props.path("partID").asText(null));
                yield null;
            }
            case "message.updated" -> translateMessageUpdated(event);
            case "session.status" -> translateSessionStatus(event);
            case "session.idle" -> {
                String sessionId = event.path("properties").path("sessionID").asText(null);
                clearSessionCaches(sessionId);
                yield baseBuilder(StreamMessage.Types.SESSION_STATUS, sessionId)
                        .sessionStatus("idle")
                        .build();
            }
            case "session.updated" -> translateSessionUpdated(event);
            case "session.error" -> translateSessionError(event);
            case "permission.updated", "permission.asked" -> translatePermission(event);
            case "question.asked" -> translateQuestionAsked(event);
            default -> {
                log.debug("Ignoring OpenCode event type: {}", eventType);
                yield null;
            }
        };
    }

    /**
     * Translate a raw gateway permission_request message into a StreamMessage.
     */
    public StreamMessage translatePermissionFromGateway(JsonNode node) {
        String sessionId = node.path("welinkSessionId").asText(null);
        return baseBuilder(StreamMessage.Types.PERMISSION_ASK, sessionId)
                .role("assistant")
                .permissionId(node.path("permissionId").asText(null))
                .permType(node.path("permType").asText(null))
                .title(node.path("command").asText(null))
                .metadata(jsonNodeToMap(node.get("metadata")))
                .build();
    }

    private StreamMessage translatePartUpdated(JsonNode event) {
        JsonNode props = event.path("properties");
        JsonNode part = props.path("part");
        if (part.isMissingNode() || part.isNull()) {
            return null;
        }

        String sessionId = firstNonBlank(part.path("sessionID").asText(null), props.path("sessionID").asText(null));
        String messageId = firstNonBlank(part.path("messageID").asText(null), props.path("messageID").asText(null));
        String partId = part.path("id").asText(null);
        String partType = part.path("type").asText("");
        String delta = props.has("delta") && !props.get("delta").isNull()
                ? props.path("delta").asText(null)
                : null;
        Integer partSeq = rememberPartSeq(sessionId, messageId, partId);
        String role = resolveMessageRole(sessionId, messageId);

        rememberPartType(sessionId, partId, partType);
        if (shouldIgnoreMessage(role)) {
            return null;
        }

        return switch (partType) {
            case "text" -> translateTextPart(sessionId, messageId, partId, partSeq, part, delta, role);
            case "reasoning" -> translateReasoningPart(sessionId, messageId, partId, partSeq, part, delta, role);
            case "tool" -> translateToolPart(sessionId, messageId, partId, partSeq, part, role);
            case "step-start" -> messageBuilder(StreamMessage.Types.STEP_START, sessionId, messageId, role)
                    .build();
            case "step-finish" -> translateStepFinish(sessionId, messageId, part, role);
            case "file" -> translateFilePart(sessionId, messageId, partId, partSeq, part, role);
            default -> {
                log.debug("Ignoring part type: {}", partType);
                yield null;
            }
        };
    }

    private StreamMessage translatePartDelta(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        String messageId = props.path("messageID").asText(null);
        String partId = props.path("partID").asText(null);
        String delta = props.path("delta").asText(null);
        if (partId == null || delta == null) {
            return null;
        }

        String partType = getPartType(sessionId, partId);
        if (partType == null) {
            log.debug("Ignoring delta for unknown part: sessionId={}, partId={}", sessionId, partId);
            return null;
        }

        Integer partSeq = rememberPartSeq(sessionId, messageId, partId);
        String role = resolveMessageRole(sessionId, messageId);
        if (shouldIgnoreMessage(role)) {
            return null;
        }
        return switch (partType) {
            case "text" -> partBuilder(StreamMessage.Types.TEXT_DELTA, sessionId, messageId, partId, partSeq, role)
                    .content(delta)
                    .build();
            case "reasoning" -> partBuilder(StreamMessage.Types.THINKING_DELTA, sessionId, messageId, partId, partSeq, role)
                    .content(delta)
                    .build();
            default -> {
                log.debug("Ignoring delta for unsupported part type: sessionId={}, partId={}, partType={}",
                        sessionId, partId, partType);
                yield null;
            }
        };
    }

    private StreamMessage translateTextPart(String sessionId, String messageId, String partId,
            Integer partSeq, JsonNode part, String delta, String role) {
        String type = delta != null ? StreamMessage.Types.TEXT_DELTA : StreamMessage.Types.TEXT_DONE;
        String content = delta != null ? delta : part.path("text").asText("");
        return partBuilder(type, sessionId, messageId, partId, partSeq, role)
                .content(content)
                .build();
    }

    private StreamMessage translateReasoningPart(String sessionId, String messageId, String partId,
            Integer partSeq, JsonNode part, String delta, String role) {
        String type = delta != null ? StreamMessage.Types.THINKING_DELTA : StreamMessage.Types.THINKING_DONE;
        String content = delta != null ? delta : part.path("text").asText("");
        return partBuilder(type, sessionId, messageId, partId, partSeq, role)
                .content(content)
                .build();
    }

    private StreamMessage translateToolPart(String sessionId, String messageId, String partId,
            Integer partSeq, JsonNode part, String role) {
        String toolName = part.path("tool").asText("");
        String callId = part.path("callID").asText(null);
        JsonNode state = part.get("state");
        String status = state != null ? state.path("status").asText("") : "";

        if ("question".equals(toolName) && "running".equals(status)) {
            return translateQuestion(sessionId, messageId, partId, partSeq, callId, state, role);
        }

        StreamMessage.StreamMessageBuilder builder = partBuilder(
                StreamMessage.Types.TOOL_UPDATE,
                sessionId,
                messageId,
                partId,
                partSeq,
                role)
                .toolName(toolName)
                .toolCallId(callId)
                .status(status);

        if (state != null) {
            JsonNode inputNode = state.get("input");
            if (inputNode != null && !inputNode.isNull()) {
                builder.input(jsonNodeToMap(inputNode));
            }
            String output = state.path("output").asText(null);
            if (output != null) {
                builder.output(output);
            }
            String error = state.path("error").asText(null);
            if (error != null) {
                builder.error(error);
            }
            String title = state.path("title").asText(null);
            if (title != null) {
                builder.title(title);
            }
        }

        return builder.build();
    }

    private StreamMessage translateQuestion(String sessionId, String messageId, String partId,
            Integer partSeq, String callId, JsonNode state, String role) {
        JsonNode input = state != null ? state.get("input") : null;
        JsonNode questionNode = resolveQuestionPayload(input);
        StreamMessage.StreamMessageBuilder builder = partBuilder(
                StreamMessage.Types.QUESTION,
                sessionId,
                messageId,
                partId,
                partSeq,
                role)
                .toolCallId(callId)
                .toolName("question")
                .status("running");

        if (input != null && !input.isNull()) {
            builder.input(jsonNodeToMap(input));
        }

        if (questionNode != null) {
            builder.header(questionNode.path("header").asText(null));
            builder.question(questionNode.path("question").asText(null));

            List<String> options = extractQuestionOptions(questionNode.get("options"));
            if (!options.isEmpty()) {
                builder.options(options);
            }
        }

        return builder.build();
    }

    private StreamMessage translateStepFinish(String sessionId, String messageId, JsonNode part, String role) {
        StreamMessage.StreamMessageBuilder builder = messageBuilder(
                StreamMessage.Types.STEP_DONE,
                sessionId,
                messageId,
                role);

        JsonNode tokensNode = part.get("tokens");
        if (tokensNode != null) {
            builder.tokens(jsonNodeToMap(tokensNode));
        }

        double cost = part.path("cost").asDouble(0);
        if (cost > 0) {
            builder.cost(cost);
        }

        String reason = part.path("reason").asText(null);
        if (reason != null) {
            builder.reason(reason);
        }

        return builder.build();
    }

    private StreamMessage translateFilePart(String sessionId, String messageId, String partId,
            Integer partSeq, JsonNode part, String role) {
        return partBuilder(StreamMessage.Types.FILE, sessionId, messageId, partId, partSeq, role)
                .fileName(part.path("filename").asText(null))
                .fileUrl(part.path("url").asText(null))
                .fileMime(part.path("mime").asText(null))
                .build();
    }

    private StreamMessage translateSessionStatus(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        String rawStatus = props.path("status").path("type").asText(props.path("status").asText(""));
        String status = normalizeSessionStatus(rawStatus);
        if ("idle".equals(status)) {
            clearSessionCaches(sessionId);
        }
        return baseBuilder(StreamMessage.Types.SESSION_STATUS, sessionId)
                .sessionStatus(status)
                .build();
    }

    private StreamMessage translateSessionUpdated(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        String title = props.path("info").path("title").asText(null);
        if (title == null) {
            title = props.path("title").asText(null);
        }
        return baseBuilder(StreamMessage.Types.SESSION_TITLE, sessionId)
                .title(title)
                .build();
    }

    private StreamMessage translateSessionError(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        String error = props.path("error").isTextual()
                ? props.path("error").asText(null)
                : props.path("error").toString();
        return baseBuilder(StreamMessage.Types.SESSION_ERROR, sessionId)
                .error(error)
                .build();
    }

    private StreamMessage translateMessageUpdated(JsonNode event) {
        JsonNode props = event.path("properties");
        JsonNode info = props.path("info");
        if (info.isMissingNode() || info.isNull()) {
            return null;
        }

        String sessionId = firstNonBlank(props.path("sessionID").asText(null), info.path("sessionID").asText(null));
        String messageId = firstNonBlank(props.path("messageID").asText(null), info.path("id").asText(null));
        String role = normalizeRole(info.path("role").asText(null));
        rememberMessageRole(sessionId, messageId, role);

        if (!info.has("finish") || shouldIgnoreMessage(role)) {
            return null;
        }

        return messageBuilder(
                StreamMessage.Types.STEP_DONE,
                sessionId,
                messageId,
                role)
                .reason(info.path("finish").path("reason").asText(null))
                .build();
    }

    private StreamMessage translatePermission(JsonNode event) {
        JsonNode props = event.path("properties");
        if (props.isMissingNode() || props.isNull()) {
            return null;
        }

        return messageBuilder(
                StreamMessage.Types.PERMISSION_ASK,
                props.path("sessionID").asText(null),
                props.path("messageID").asText(null))
                .permissionId(props.path("id").asText(null))
                .permType(props.path("type").asText(props.path("permission").asText(null)))
                .title(props.path("title").asText(props.path("permission").asText(null)))
                .metadata(jsonNodeToMap(props.get("metadata")))
                .build();
    }

    private StreamMessage translateQuestionAsked(JsonNode event) {
        JsonNode props = event.path("properties");
        JsonNode firstQuestion = resolveQuestionPayload(props);
        if (firstQuestion == null) {
            return null;
        }

        List<String> options = extractQuestionOptions(firstQuestion.get("options"));

        String sessionId = props.path("sessionID").asText(null);
        String messageId = props.path("messageID").asText(null);
        String partId = props.path("id").asText(null);
        Integer partSeq = rememberPartSeq(sessionId, messageId, partId);

        return partBuilder(StreamMessage.Types.QUESTION, sessionId, messageId, partId, partSeq)
                .toolName("question")
                .status("running")
                .input(jsonNodeToMap(props))
                .header(firstQuestion.path("header").asText(null))
                .question(firstQuestion.path("question").asText(null))
                .options(options.isEmpty() ? null : options)
                .build();
    }

    private JsonNode resolveQuestionPayload(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return null;
        }

        JsonNode questionsNode = input.get("questions");
        if (questionsNode != null && questionsNode.isArray() && !questionsNode.isEmpty()) {
            JsonNode firstQuestion = questionsNode.get(0);
            if (firstQuestion != null && !firstQuestion.isNull()) {
                return firstQuestion;
            }
        }

        return input;
    }

    private List<String> extractQuestionOptions(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray()) {
            return List.of();
        }

        List<String> options = new ArrayList<>();
        for (JsonNode optionNode : optionsNode) {
            String label = optionNode.path("label").asText(null);
            if (label == null || label.isBlank()) {
                label = optionNode.asText(null);
            }
            if (label != null && !label.isBlank()) {
                options.add(label);
            }
        }
        return options;
    }

    private StreamMessage.StreamMessageBuilder baseBuilder(String type, String sessionId) {
        return StreamMessage.builder()
                .type(type)
                .sessionId(sessionId)
                .emittedAt(Instant.now().toString());
    }

    private StreamMessage.StreamMessageBuilder messageBuilder(String type, String sessionId, String sourceMessageId) {
        return messageBuilder(type, sessionId, sourceMessageId, resolveMessageRole(sessionId, sourceMessageId));
    }

    private StreamMessage.StreamMessageBuilder messageBuilder(
            String type,
            String sessionId,
            String sourceMessageId,
            String role) {
        StreamMessage.StreamMessageBuilder builder = baseBuilder(type, sessionId)
                .role(normalizeRole(role));
        if (sourceMessageId != null && !sourceMessageId.isBlank()) {
            builder.messageId(sourceMessageId);
            builder.sourceMessageId(sourceMessageId);
        }
        return builder;
    }

    private StreamMessage.StreamMessageBuilder partBuilder(String type, String sessionId, String sourceMessageId,
            String partId, Integer partSeq) {
        return partBuilder(type, sessionId, sourceMessageId, partId, partSeq, resolveMessageRole(sessionId, sourceMessageId));
    }

    private StreamMessage.StreamMessageBuilder partBuilder(String type, String sessionId, String sourceMessageId,
            String partId, Integer partSeq, String role) {
        StreamMessage.StreamMessageBuilder builder = messageBuilder(type, sessionId, sourceMessageId, role)
                .partId(partId);
        if (partSeq != null) {
            builder.partSeq(partSeq);
        }
        return builder;
    }

    private void rememberPartType(String sessionId, String partId, String partType) {
        if (sessionId == null || sessionId.isBlank() || partId == null || partId.isBlank()
                || partType == null || partType.isBlank()) {
            return;
        }
        partTypes.put(partCacheKey(sessionId, partId), partType);
    }

    private String getPartType(String sessionId, String partId) {
        if (sessionId == null || sessionId.isBlank() || partId == null || partId.isBlank()) {
            return null;
        }
        return partTypes.get(partCacheKey(sessionId, partId));
    }

    private Integer rememberPartSeq(String sessionId, String messageId, String partId) {
        if (sessionId == null || sessionId.isBlank() || messageId == null || messageId.isBlank()
                || partId == null || partId.isBlank()) {
            return null;
        }

        String partKey = partCacheKey(sessionId, partId);
        Integer existing = partSequences.get(partKey);
        if (existing != null) {
            return existing;
        }

        String messageKey = messageCacheKey(sessionId, messageId);
        Integer nextSeq = nextPartSequences.compute(messageKey, (key, value) -> value == null ? 1 : value + 1);
        Integer prior = partSequences.putIfAbsent(partKey, nextSeq);
        return prior != null ? prior : nextSeq;
    }

    private void evictPart(String sessionId, String messageId, String partId) {
        if (sessionId == null || sessionId.isBlank() || partId == null || partId.isBlank()) {
            return;
        }
        partTypes.remove(partCacheKey(sessionId, partId));
        partSequences.remove(partCacheKey(sessionId, partId));
        if (messageId != null && !messageId.isBlank()) {
            String messageKey = messageCacheKey(sessionId, messageId);
            if (partSequences.keySet().stream().noneMatch(key -> key.startsWith(sessionId + ":"))) {
                nextPartSequences.remove(messageKey);
            }
        }
    }

    private void clearSessionCaches(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        String prefix = sessionId + ":";
        partTypes.keySet().removeIf(key -> key.startsWith(prefix));
        partSequences.keySet().removeIf(key -> key.startsWith(prefix));
        nextPartSequences.keySet().removeIf(key -> key.startsWith(prefix));
        messageRoles.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private String partCacheKey(String sessionId, String partId) {
        return sessionId + ":" + partId;
    }

    private String messageCacheKey(String sessionId, String messageId) {
        return sessionId + ":" + messageId;
    }

    private void rememberMessageRole(String sessionId, String messageId, String role) {
        if (sessionId == null || sessionId.isBlank() || messageId == null || messageId.isBlank()
                || role == null || role.isBlank()) {
            return;
        }
        messageRoles.put(messageCacheKey(sessionId, messageId), role);
    }

    private String resolveMessageRole(String sessionId, String messageId) {
        if (sessionId == null || sessionId.isBlank() || messageId == null || messageId.isBlank()) {
            return "assistant";
        }
        return normalizeRole(messageRoles.get(messageCacheKey(sessionId, messageId)));
    }

    private boolean shouldIgnoreMessage(String role) {
        return "user".equals(normalizeRole(role));
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "assistant";
        }
        return role.toLowerCase();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String normalizeSessionStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "busy";
        }
        return switch (rawStatus.toLowerCase()) {
            case "idle", "completed" -> "idle";
            case "reconnecting", "retry", "recovering" -> "retry";
            case "active", "running", "busy" -> "busy";
            default -> "busy";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            log.warn("Failed to convert JsonNode to Map: {}", e.getMessage());
            return null;
        }
    }
}
