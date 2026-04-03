package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.PartContext;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.StreamMessage.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Translates raw OpenCode events into frontend-facing StreamMessage DTOs.
 */
@Slf4j
@Component
public class OpenCodeEventTranslator {

    private final ObjectMapper objectMapper;
    private final TranslatorSessionCache cache;

    public OpenCodeEventTranslator(ObjectMapper objectMapper, TranslatorSessionCache cache) {
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    public StreamMessage translate(JsonNode event) {
        if (event == null) {
            return null;
        }

        String eventType = event.path("type").asText("");
        if (eventType.startsWith("permission.")) {
            return translatePermission(eventType, event);
        }
        return switch (eventType) {
            case "message.part.updated" -> translatePartUpdated(event);
            case "message.part.delta" -> translatePartDelta(event);
            case "message.part.removed" -> {
                JsonNode props = event.path("properties");
                cache.evictPart(
                        props.path("sessionID").asText(null),
                        props.path("messageID").asText(null),
                        props.path("partID").asText(null));
                yield null;
            }
            case "message.updated" -> translateMessageUpdated(event);
            case "session.status" -> translateSessionStatus(event);
            case "session.idle" -> {
                String sessionId = event.path("properties").path("sessionID").asText(null);
                cache.clearSession(sessionId);
                yield baseBuilder(StreamMessage.Types.SESSION_STATUS, sessionId)
                        .sessionStatus("idle")
                        .build();
            }
            case "session.updated" -> translateSessionUpdated(event);
            case "session.error" -> translateSessionError(event);
            case "question.asked" -> translateQuestionAsked(event);
            // question.replied / question.rejected: 不生成 StreamMessage。
            // Question 完成状态已由 message.part.updated (tool=question, status=completed) 覆盖，
            // 这里再生成会导致前端出现重复卡片。
            case "question.replied", "question.rejected" -> null;
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
        String messageId = node.path("messageId").asText(null);
        return messageBuilder(StreamMessage.Types.PERMISSION_ASK, sessionId, messageId)
                .permission(PermissionInfo.builder()
                        .permissionId(node.path("permissionId").asText(null))
                        .permType(node.path("permType").asText(null))
                        .metadata(jsonNodeToMap(node.get("metadata")))
                        .build())
                .title(node.path("command").asText(null))
                .build();
    }

    private StreamMessage translatePartUpdated(JsonNode event) {
        JsonNode props = event.path("properties");
        JsonNode part = props.path("part");
        if (part.isMissingNode() || part.isNull()) {
            return null;
        }

        String sessionId = ProtocolUtils.firstNonBlank(part.path("sessionID").asText(null),
                props.path("sessionID").asText(null));
        String messageId = ProtocolUtils.firstNonBlank(part.path("messageID").asText(null),
                props.path("messageID").asText(null));
        String partId = part.path("id").asText(null);
        String partType = part.path("type").asText("");
        String delta = props.has("delta") && !props.get("delta").isNull()
                ? props.path("delta").asText(null)
                : null;
        Integer partSeq = cache.rememberPartSeq(sessionId, messageId, partId);
        String role = cache.resolveMessageRole(sessionId, messageId);
        PartContext ctx = new PartContext(sessionId, messageId, partId, partSeq, role);

        cache.rememberPartType(sessionId, partId, partType);

        // User 消息文本特殊处理（必须在 shouldIgnoreMessage 之前）
        if ("text".equals(partType) && delta == null) {
            StreamMessage userResult = handleUserTextSpecial(ctx, part);
            if (userResult != null) {
                return userResult;
            }
        }

        if (shouldIgnoreMessage(role)) {
            return null;
        }

        return switch (partType) {
            case "text" -> translateTextPart(ctx, part, delta);
            case "reasoning" -> translateReasoningPart(ctx, part, delta);
            case "tool" -> translateToolPart(ctx, part);
            case "step-start" -> messageBuilder(StreamMessage.Types.STEP_START, sessionId, messageId, role)
                    .build();
            case "step-finish" -> translateStepFinish(ctx, part);
            case "file" -> translateFilePart(ctx, part);
            default -> {
                log.debug("Ignoring part type: {}", partType);
                yield null;
            }
        };
    }

    /**
     * 处理 user 消息的文本 part 时序问题。
     * 解决 message.part.updated 和 message.updated 到达顺序不确定的场景。
     *
     * @return 若能确定为 user TEXT_DONE，返回 StreamMessage；否则返回 null（缓存文本等待后续事件）
     */
    private StreamMessage handleUserTextSpecial(PartContext ctx, JsonNode part) {
        String textContent = part.path("text").asText("");
        if ("user".equals(ctx.role())) {
            // Case A：message.updated(role=user) 已到达，role 已缓存
            if (!textContent.isBlank()) {
                log.info("Emitting user TEXT_DONE (role cached): sessionId={}, messageId={}",
                        ctx.sessionId(), ctx.messageId());
                return partBuilder(StreamMessage.Types.TEXT_DONE, ctx)
                        .content(textContent)
                        .build();
            }
        } else {
            // Case B：message.updated 还没到，role 未知（默认 assistant）
            // 缓存文本，等 translateMessageUpdated(role=user) 到达时回溯使用
            if (ctx.sessionId() != null && ctx.messageId() != null && !textContent.isBlank()) {
                cache.rememberMessageText(ctx.sessionId(), ctx.messageId(), textContent);
            }
        }
        return null;
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

        String partType = cache.getPartType(sessionId, partId);
        if (partType == null) {
            log.debug("Ignoring delta for unknown part: sessionId={}, partId={}", sessionId, partId);
            return null;
        }

        Integer partSeq = cache.rememberPartSeq(sessionId, messageId, partId);
        String role = cache.resolveMessageRole(sessionId, messageId);
        if (shouldIgnoreMessage(role)) {
            return null;
        }
        PartContext ctx = new PartContext(sessionId, messageId, partId, partSeq, role);
        return switch (partType) {
            case "text" -> partBuilder(StreamMessage.Types.TEXT_DELTA, ctx)
                    .content(delta)
                    .build();
            case "reasoning" ->
                partBuilder(StreamMessage.Types.THINKING_DELTA, ctx)
                        .content(delta)
                        .build();
            default -> {
                log.debug("Ignoring delta for unsupported part type: sessionId={}, partId={}, partType={}",
                        sessionId, partId, partType);
                yield null;
            }
        };
    }

    private StreamMessage translateTextPart(PartContext ctx, JsonNode part, String delta) {
        String type = delta != null ? StreamMessage.Types.TEXT_DELTA : StreamMessage.Types.TEXT_DONE;
        String content = delta != null ? delta : part.path("text").asText("");
        // TEXT_DONE 时缓存文本，供 translateMessageUpdated(role=user) 回溯使用
        if (delta == null && ctx.sessionId() != null && ctx.messageId() != null && !content.isBlank()) {
            cache.rememberMessageText(ctx.sessionId(), ctx.messageId(), content);
        }
        return partBuilder(type, ctx)
                .content(content)
                .build();
    }

    private StreamMessage translateReasoningPart(PartContext ctx, JsonNode part, String delta) {
        String type = delta != null ? StreamMessage.Types.THINKING_DELTA : StreamMessage.Types.THINKING_DONE;
        String content = delta != null ? delta : part.path("text").asText("");
        return partBuilder(type, ctx)
                .content(content)
                .build();
    }

    private StreamMessage translateToolPart(PartContext ctx, JsonNode part) {
        String toolName = part.path("tool").asText("");
        String callId = part.path("callID").asText(null);
        JsonNode state = part.get("state");
        String status = state != null ? state.path("status").asText("") : "";

        // Question tool special handling:
        // - running: skip — the dedicated "question.asked" event is the source of truth
        // - completed/error: emit as QUESTION type so the frontend can update
        // the existing QuestionCard via matching partId
        if ("question".equals(toolName)) {
            if ("pending".equals(status) || "running".equals(status)) {
                return null;
            }
            // Resolve the original question partId cached from question.asked,
            // so the frontend matches the existing QuestionCard instead of creating a
            // duplicate
            String questionPartId = cache.getQuestionPartId(ctx.sessionId(), callId);
            PartContext questionCtx;
            if (questionPartId != null) {
                Integer questionPartSeq = cache.rememberPartSeq(
                        ctx.sessionId(), ctx.messageId(), questionPartId);
                questionCtx = new PartContext(
                        ctx.sessionId(), ctx.messageId(), questionPartId, questionPartSeq, ctx.role());
            } else {
                // Fallback: if question.asked wasn't seen, use original tool part ctx
                questionCtx = ctx;
            }

            ToolInfo.ToolInfoBuilder qToolBuilder = ToolInfo.builder()
                    .toolName(toolName)
                    .toolCallId(callId);
            if (state != null) {
                JsonNode inputNode = state.get("input");
                if (inputNode != null && !inputNode.isNull()) {
                    qToolBuilder.input(jsonNodeToMap(inputNode));
                }
                String output = state.path("output").asText(null);
                if (output != null) {
                    qToolBuilder.output(ProtocolUtils.normalizeQuestionAnswerOutput(output, inputNode));
                }
            }
            return partBuilder(StreamMessage.Types.QUESTION, questionCtx)
                    .tool(qToolBuilder.build())
                    .status(status)
                    .build();
        }

        ToolInfo.ToolInfoBuilder toolBuilder = ToolInfo.builder()
                .toolName(toolName)
                .toolCallId(callId);

        StreamMessage.StreamMessageBuilder msgBuilder = partBuilder(
                StreamMessage.Types.TOOL_UPDATE, ctx)
                .status(status);

        if (state != null) {
            JsonNode inputNode = state.get("input");
            if (inputNode != null && !inputNode.isNull()) {
                toolBuilder.input(jsonNodeToMap(inputNode));
            }
            String output = state.path("output").asText(null);
            if (output != null) {
                toolBuilder.output(output);
            }
            String error = state.path("error").asText(null);
            if (error != null) {
                msgBuilder.error(error);
            }
            String title = state.path("title").asText(null);
            if (title != null) {
                msgBuilder.title(title);
            }
        }

        return msgBuilder.tool(toolBuilder.build()).build();
    }

    private StreamMessage translateStepFinish(PartContext ctx, JsonNode part) {
        UsageInfo.UsageInfoBuilder usageBuilder = UsageInfo.builder();

        JsonNode tokensNode = part.get("tokens");
        if (tokensNode != null) {
            usageBuilder.tokens(jsonNodeToMap(tokensNode));
        }

        double cost = part.path("cost").asDouble(0);
        if (cost > 0) {
            usageBuilder.cost(cost);
        }

        String reason = part.path("reason").asText(null);
        if (reason != null) {
            usageBuilder.reason(reason);
        }

        return messageBuilder(
                StreamMessage.Types.STEP_DONE,
                ctx.sessionId(),
                ctx.messageId(),
                ctx.role())
                .usage(usageBuilder.build())
                .build();
    }

    private StreamMessage translateFilePart(PartContext ctx, JsonNode part) {
        return partBuilder(StreamMessage.Types.FILE, ctx)
                .file(FileInfo.builder()
                        .fileName(part.path("filename").asText(null))
                        .fileUrl(part.path("url").asText(null))
                        .fileMime(part.path("mime").asText(null))
                        .build())
                .build();
    }

    private StreamMessage translateSessionStatus(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        String rawStatus = props.path("status").path("type").asText(props.path("status").asText(""));
        String status = normalizeSessionStatus(rawStatus);
        if ("idle".equals(status)) {
            cache.clearSession(sessionId);
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

        String sessionId = ProtocolUtils.firstNonBlank(props.path("sessionID").asText(null),
                info.path("sessionID").asText(null));
        String messageId = ProtocolUtils.firstNonBlank(props.path("messageID").asText(null),
                info.path("id").asText(null));
        String role = ProtocolUtils.normalizeRole(info.path("role").asText(null));
        cache.rememberMessageRole(sessionId, messageId, role);

        // 当 role=user 时，尝试从缓存中获取该消息的文本内容并作为 TEXT_DONE 发射
        // 这解决了 message.part.updated 先到但 role 未知的时序问题
        if ("user".equals(role)) {
            String cachedText = cache.getMessageText(sessionId, messageId);
            if (cachedText != null) {
                cache.invalidateMessageText(sessionId, messageId);
                log.info("Emitting user TEXT_DONE from cache: sessionId={}, messageId={}, len={}",
                        sessionId, messageId, cachedText.length());
                return messageBuilder(StreamMessage.Types.TEXT_DONE, sessionId, messageId, role)
                        .content(cachedText)
                        .build();
            }
            // 文本还没到（message.updated 先于 message.part.updated 到达），
            // role 已缓存，后续 part 事件会正常走 translatePartUpdated 处理
            return null;
        }

        if (!info.has("finish")) {
            return null;
        }

        return messageBuilder(
                StreamMessage.Types.STEP_DONE,
                sessionId,
                messageId,
                role)
                .usage(UsageInfo.builder()
                        .reason(info.path("finish").path("reason").asText(null))
                        .build())
                .build();
    }

    private StreamMessage translatePermission(String eventType, JsonNode event) {
        JsonNode props = event.path("properties");
        if (props.isMissingNode() || props.isNull()) {
            return null;
        }

        String status = props.path("status").path("type").asText(props.path("status").asText(null));
        String response = normalizePermissionResponse(props);
        boolean explicitResolved = resolvePermissionResolvedFlag(props);
        boolean resolved = "permission.replied".equals(eventType)
                || response != null
                || explicitResolved
                || isResolvedPermissionStatus(status);
        if (!resolved && !isStandardPermissionEvent(eventType)) {
            log.debug("Unresolved permission event: eventType={}, sessionID={}, messageID={}, propertyKeys={}",
                    eventType,
                    props.path("sessionID").asText(null),
                    props.path("messageID").asText(null),
                    props.properties().stream().map(Map.Entry::getKey).toList());
        }
        String messageType = resolved
                ? StreamMessage.Types.PERMISSION_REPLY
                : StreamMessage.Types.PERMISSION_ASK;

        return messageBuilder(
                messageType,
                props.path("sessionID").asText(null),
                props.path("messageID").asText(null))
                .permission(PermissionInfo.builder()
                        .permissionId(ProtocolUtils.firstNonBlank(
                        props.path("id").asText(null),
                        props.path("requestID").asText(null)))
                        .permType(props.path("type").asText(props.path("permission").asText(null)))
                        .metadata(jsonNodeToMap(props.get("metadata")))
                        .response(response)
                        .build())
                .status(status)
                .title(props.path("title").asText(props.path("permission").asText(null)))
                .build();
    }

    private boolean isStandardPermissionEvent(String eventType) {
        return switch (eventType) {
            case "permission.asked", "permission.updated", "permission.replied" -> true;
            default -> false;
        };
    }

    private boolean isResolvedPermissionStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status) {
            case "completed", "resolved", "approved", "rejected", "denied" -> true;
            default -> false;
        };
    }

    private boolean resolvePermissionResolvedFlag(JsonNode props) {
        if (props == null || props.isMissingNode() || props.isNull()) {
            return false;
        }
        if (props.has("resolved")) {
            return props.path("resolved").asBoolean(false);
        }
        if (props.has("isResolved")) {
            return props.path("isResolved").asBoolean(false);
        }
        JsonNode resultNode = props.path("result");
        if (!resultNode.isMissingNode() && !resultNode.isNull()) {
            if (resultNode.has("resolved")) {
                return resultNode.path("resolved").asBoolean(false);
            }
            if (resultNode.has("isResolved")) {
                return resultNode.path("isResolved").asBoolean(false);
            }
        }
        return false;
    }

    private String normalizePermissionResponse(JsonNode props) {
        String raw = ProtocolUtils.firstNonBlank(
                props.path("response").asText(null),
                props.path("decision").asText(null));
        if (raw == null || raw.isBlank()) {
            raw = props.path("answer").asText(null);
        }
        if (raw == null || raw.isBlank()) {
            raw = props.path("reply").asText(null);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw) {
            case "allow", "approved", "approve", "yes" -> "once";
            case "always_allow", "always-allow", "allow_always" -> "always";
            case "deny", "denied", "reject", "rejected", "no" -> "reject";
            default -> raw;
        };
    }

    private StreamMessage translateQuestionAsked(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        JsonNode firstQuestion = ProtocolUtils.resolveQuestionPayload(props);
        if (firstQuestion == null) {
            return baseBuilder(StreamMessage.Types.QUESTION, sessionId).build();
        }

        List<String> options = ProtocolUtils.extractQuestionOptions(firstQuestion.path("options"));

        // callID and messageID live inside "properties.tool" in the question.asked
        // event,
        // but fall back to top-level properties for forward compatibility.
        JsonNode toolNode = props.path("tool");
        String callId = toolNode.path("callID").asText(
                props.path("callID").asText(props.path("toolCallId").asText(null)));
        String messageId = toolNode.path("messageID").asText(
                props.path("messageID").asText(null));

        String partId = props.path("id").asText(null);
        Integer partSeq = cache.rememberPartSeq(sessionId, messageId, partId);

        // Cache callId → partId so that the completed event from message.part.updated
        // can reuse this partId to update (not duplicate) the QuestionCard
        if (callId != null && partId != null) {
            cache.rememberQuestionPartId(sessionId, callId, partId);
        }

        return partBuilder(StreamMessage.Types.QUESTION, sessionId, messageId, partId, partSeq)
                .tool(ToolInfo.builder()
                        .toolName("question")
                        .toolCallId(callId)
                        .input(jsonNodeToMap(props))
                        .build())
                .status("running")
                .questionInfo(QuestionInfo.builder()
                        .header(firstQuestion.path("header").asText(null))
                        .question(firstQuestion.path("question").asText(null))
                        .options(options.isEmpty() ? null : options)
                        .build())
                .build();
    }

    private StreamMessage.StreamMessageBuilder baseBuilder(String type, String sessionId) {
        return StreamMessage.builder()
                .type(type)
                .sessionId(sessionId)
                .emittedAt(Instant.now().toString());
    }

    private StreamMessage.StreamMessageBuilder messageBuilder(String type, String sessionId, String sourceMessageId) {
        return messageBuilder(type, sessionId, sourceMessageId, cache.resolveMessageRole(sessionId, sourceMessageId));
    }

    private StreamMessage.StreamMessageBuilder messageBuilder(
            String type,
            String sessionId,
            String sourceMessageId,
            String role) {
        StreamMessage.StreamMessageBuilder builder = baseBuilder(type, sessionId)
                .role(ProtocolUtils.normalizeRole(role));
        if (sourceMessageId != null && !sourceMessageId.isBlank()) {
            builder.messageId(sourceMessageId);
            builder.sourceMessageId(sourceMessageId);
        }
        return builder;
    }

    private StreamMessage.StreamMessageBuilder partBuilder(String type, String sessionId, String sourceMessageId,
            String partId, Integer partSeq) {
        return partBuilder(type, new PartContext(sessionId, sourceMessageId, partId, partSeq,
                cache.resolveMessageRole(sessionId, sourceMessageId)));
    }

    private StreamMessage.StreamMessageBuilder partBuilder(String type, PartContext ctx) {
        StreamMessage.StreamMessageBuilder builder = messageBuilder(type, ctx.sessionId(), ctx.messageId(), ctx.role())
                .partId(ctx.partId());
        if (ctx.partSeq() != null) {
            builder.partSeq(ctx.partSeq());
        }
        return builder;
    }

    private boolean shouldIgnoreMessage(String role) {
        return "user".equals(ProtocolUtils.normalizeRole(role));
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
