package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Translates raw OpenCode SSE events into semantic StreamMessage DTOs.
 * <p>
 * This is the single point of protocol translation from the OpenCode event
 * format to the frontend-friendly StreamMessage format.
 * <p>
 * Key mapping rules:
 * <ul>
 * <li>message.part.updated (TextPart + delta) → text.delta</li>
 * <li>message.part.updated (TextPart, no delta) → text.done</li>
 * <li>message.part.updated (ReasoningPart + delta) → thinking.delta</li>
 * <li>message.part.updated (ToolPart, question, running) → question</li>
 * <li>message.part.updated (ToolPart, other) → tool.update</li>
 * <li>message.part.updated (StepStartPart) → step.start</li>
 * <li>message.part.updated (StepFinishPart) → step.done</li>
 * <li>message.part.updated (FilePart) → file</li>
 * <li>session.status → session.status</li>
 * <li>session.idle → session.status (idle)</li>
 * <li>session.updated → session.title</li>
 * <li>session.error → session.error</li>
 * <li>permission.updated → permission.ask</li>
 * </ul>
 */
@Slf4j
@Component
public class OpenCodeEventTranslator {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, String> partTypes = new ConcurrentHashMap<>();

    public OpenCodeEventTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Translate an OpenCode event (the "event" field from tool_event) into a
     * StreamMessage.
     *
     * @param event the raw OpenCode event JSON
     * @return translated StreamMessage, or null if the event should be ignored
     */
    public StreamMessage translate(JsonNode event) {
        if (event == null) {
            return null;
        }

        String eventType = event.path("type").asText("");

        return switch (eventType) {
            case "message.part.updated" -> translatePartUpdated(event);
            case "message.part.delta" -> translatePartDelta(event);
            case "message.part.removed" -> {
                evictPartType(
                        event.path("properties").path("sessionID").asText(null),
                        event.path("properties").path("partID").asText(null));
                yield null;
            }
            case "message.updated" -> translateMessageUpdated(event);
            case "session.status" -> translateSessionStatus(event);
            case "session.idle" -> {
                clearSessionPartTypes(event.path("properties").path("sessionID").asText(null));
                yield StreamMessage.builder()
                        .type(StreamMessage.Types.SESSION_STATUS)
                        .sessionStatus("idle")
                        .build();
            }
            case "session.updated" -> translateSessionUpdated(event);
            case "session.error" -> StreamMessage.builder()
                    .type(StreamMessage.Types.SESSION_ERROR)
                    .error(event.path("properties").path("error").toString())
                    .build();
            case "permission.updated" -> translatePermission(event);
            case "permission.asked" -> translatePermission(event);
            case "question.asked" -> translateQuestionAsked(event);
            default -> {
                log.debug("Ignoring OpenCode event type: {}", eventType);
                yield null;
            }
        };
    }

    /**
     * Translate a raw gateway permission_request message (not an OpenCode event)
     * into a StreamMessage. This handles the legacy gateway format where
     * permission info is at the top level of the node.
     */
    public StreamMessage translatePermissionFromGateway(JsonNode node) {
        return StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_ASK)
                .permissionId(node.path("permissionId").asText(null))
                .permType(node.path("permType").asText(null))
                .title(node.path("command").asText(null))
                .metadata(jsonNodeToMap(node.get("metadata")))
                .build();
    }

    // ==================== Internal: Part Updated ====================

    private StreamMessage translatePartUpdated(JsonNode event) {
        JsonNode props = event.get("properties");
        if (props == null)
            return null;

        JsonNode part = props.get("part");
        if (part == null)
            return null;

        String partType = part.path("type").asText("");
        String partId = part.path("id").asText(null);
        String delta = props.has("delta") && !props.get("delta").isNull()
                ? props.path("delta").asText(null)
                : null;
        String sessionId = part.path("sessionID").asText(null);

        rememberPartType(sessionId, partId, partType);

        return switch (partType) {
            case "text" -> translateTextPart(partId, part, delta);
            case "reasoning" -> translateReasoningPart(partId, part, delta);
            case "tool" -> translateToolPart(partId, part);
            case "step-start" -> StreamMessage.builder()
                    .type(StreamMessage.Types.STEP_START)
                    .build();
            case "step-finish" -> translateStepFinish(part);
            case "file" -> translateFilePart(partId, part);
            default -> {
                log.debug("Ignoring part type: {}", partType);
                yield null;
            }
        };
    }

    private StreamMessage translatePartDelta(JsonNode event) {
        JsonNode props = event.get("properties");
        if (props == null) {
            return null;
        }

        String sessionId = props.path("sessionID").asText(null);
        String partId = props.path("partID").asText(null);
        String delta = props.path("delta").asText(null);
        if (partId == null || delta == null) {
            return null;
        }

        String partType = getPartType(sessionId, partId);
        if (partType == null) {
            log.debug("Ignoring delta for unknown part: sessionId={}, partId={}, field={}",
                    sessionId, partId, props.path("field").asText(null));
            return null;
        }

        return switch (partType) {
            case "text" -> StreamMessage.builder()
                    .type(StreamMessage.Types.TEXT_DELTA)
                    .partId(partId)
                    .content(delta)
                    .build();
            case "reasoning" -> StreamMessage.builder()
                    .type(StreamMessage.Types.THINKING_DELTA)
                    .partId(partId)
                    .content(delta)
                    .build();
            default -> {
                log.debug("Ignoring delta for unsupported part type: sessionId={}, partId={}, partType={}",
                        sessionId, partId, partType);
                yield null;
            }
        };
    }

    private StreamMessage translateTextPart(String partId, JsonNode part, String delta) {
        if (delta != null) {
            return StreamMessage.builder()
                    .type(StreamMessage.Types.TEXT_DELTA)
                    .partId(partId)
                    .content(delta)
                    .build();
        } else {
            return StreamMessage.builder()
                    .type(StreamMessage.Types.TEXT_DONE)
                    .partId(partId)
                    .content(part.path("text").asText(""))
                    .build();
        }
    }

    private StreamMessage translateReasoningPart(String partId, JsonNode part, String delta) {
        if (delta != null) {
            return StreamMessage.builder()
                    .type(StreamMessage.Types.THINKING_DELTA)
                    .partId(partId)
                    .content(delta)
                    .build();
        } else {
            return StreamMessage.builder()
                    .type(StreamMessage.Types.THINKING_DONE)
                    .partId(partId)
                    .content(part.path("text").asText(""))
                    .build();
        }
    }

    private StreamMessage translateToolPart(String partId, JsonNode part) {
        String toolName = part.path("tool").asText("");
        String callId = part.path("callID").asText(null);
        JsonNode state = part.get("state");
        String status = state != null ? state.path("status").asText("") : "";

        // Special handling for question tool in running state
        if ("question".equals(toolName) && "running".equals(status)) {
            return translateQuestion(partId, callId, state);
        }

        // General tool update
        StreamMessage.StreamMessageBuilder builder = StreamMessage.builder()
                .type(StreamMessage.Types.TOOL_UPDATE)
                .partId(partId)
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

    private StreamMessage translateQuestion(String partId, String callId, JsonNode state) {
        JsonNode input = state != null ? state.get("input") : null;

        StreamMessage.StreamMessageBuilder builder = StreamMessage.builder()
                .type(StreamMessage.Types.QUESTION)
                .partId(partId)
                .toolCallId(callId)
                .toolName("question")
                .status("running");

        if (input != null) {
            builder.header(input.path("header").asText(null));
            builder.question(input.path("question").asText(null));

            JsonNode optionsNode = input.get("options");
            if (optionsNode != null && optionsNode.isArray()) {
                List<String> options = new ArrayList<>();
                for (JsonNode opt : optionsNode) {
                    options.add(opt.asText());
                }
                builder.options(options);
            }
        }

        return builder.build();
    }

    private StreamMessage translateStepFinish(JsonNode part) {
        StreamMessage.StreamMessageBuilder builder = StreamMessage.builder()
                .type(StreamMessage.Types.STEP_DONE);

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

    private StreamMessage translateFilePart(String partId, JsonNode part) {
        return StreamMessage.builder()
                .type(StreamMessage.Types.FILE)
                .partId(partId)
                .title(part.path("filename").asText(null))
                .content(part.path("url").asText(null))
                .metadata(Map.of("mime", part.path("mime").asText("")))
                .build();
    }

    // ==================== Internal: Top-level Events ====================

    private StreamMessage translateSessionStatus(JsonNode event) {
        String status = event.path("properties").path("status").path("type").asText(
                event.path("properties").path("status").asText(""));
        if ("idle".equals(status)) {
            clearSessionPartTypes(event.path("properties").path("sessionID").asText(null));
        }
        return StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS)
                .sessionStatus(status)
                .build();
    }

    private StreamMessage translateSessionUpdated(JsonNode event) {
        String title = event.path("properties").path("info").path("title").asText(null);
        if (title == null) {
            title = event.path("properties").path("title").asText(null);
        }
        return StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_TITLE)
                .title(title)
                .build();
    }

    private StreamMessage translateMessageUpdated(JsonNode event) {
        // message.updated with finish info → step.done
        JsonNode props = event.get("properties");
        if (props != null && props.has("info")) {
            JsonNode info = props.get("info");
            if (info != null && info.has("finish")) {
                return StreamMessage.builder()
                        .type(StreamMessage.Types.STEP_DONE)
                        .reason(info.path("finish").path("reason").asText(null))
                        .build();
            }
        }
        // Ignore other message.updated events
        return null;
    }

    private StreamMessage translatePermission(JsonNode event) {
        JsonNode props = event.get("properties");
        if (props == null)
            return null;

        return StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_ASK)
                .permissionId(props.path("id").asText(null))
                .permType(props.path("type").asText(
                        props.path("permission").asText(null)))
                .title(props.path("title").asText(
                        props.path("permission").asText(null)))
                .metadata(jsonNodeToMap(props.get("metadata")))
                .build();
    }

    private StreamMessage translateQuestionAsked(JsonNode event) {
        JsonNode props = event.get("properties");
        if (props == null) {
            return null;
        }

        JsonNode questionsNode = props.get("questions");
        JsonNode firstQuestion = questionsNode != null && questionsNode.isArray() && !questionsNode.isEmpty()
                ? questionsNode.get(0)
                : null;
        if (firstQuestion == null) {
            return null;
        }

        List<String> options = new ArrayList<>();
        JsonNode optionsNode = firstQuestion.get("options");
        if (optionsNode != null && optionsNode.isArray()) {
            for (JsonNode opt : optionsNode) {
                String label = opt.path("label").asText(null);
                if (label == null || label.isBlank()) {
                    label = opt.asText(null);
                }
                if (label != null && !label.isBlank()) {
                    options.add(label);
                }
            }
        }

        return StreamMessage.builder()
                .type(StreamMessage.Types.QUESTION)
                .partId(props.path("id").asText(null))
                .toolName("question")
                .status("running")
                .header(firstQuestion.path("header").asText(null))
                .question(firstQuestion.path("question").asText(null))
                .options(options.isEmpty() ? null : options)
                .build();
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

    private void evictPartType(String sessionId, String partId) {
        if (sessionId == null || sessionId.isBlank() || partId == null || partId.isBlank()) {
            return;
        }
        partTypes.remove(partCacheKey(sessionId, partId));
    }

    private void clearSessionPartTypes(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String prefix = sessionId + ":";
        partTypes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private String partCacheKey(String sessionId, String partId) {
        return sessionId + ":" + partId;
    }

    // ==================== Utilities ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull())
            return null;
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            log.warn("Failed to convert JsonNode to Map: {}", e.getMessage());
            return null;
        }
    }
}
