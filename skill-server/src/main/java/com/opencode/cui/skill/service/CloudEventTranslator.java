package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.StreamMessage.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 云端事件翻译器：使用注册表模式将云端 CloudEvent 翻译为前端 StreamMessage。
 *
 * <p>每种 event.type 对应一个 {@link CloudEventHandler}，在 {@link #init()} 中注册。
 * {@link #translate(JsonNode, String)} 查表调用对应 handler，并为 Part 级事件自动注入
 * messageId、sourceMessageId、role、partId、partSeq 等字段，使其与个人助手（OpenCodeEventTranslator）
 * 生成的 StreamMessage 保持一致。</p>
 */
@Slf4j
@Component
public class CloudEventTranslator {

    /**
     * 云端事件处理函数接口。
     */
    @FunctionalInterface
    public interface CloudEventHandler {
        StreamMessage handle(JsonNode event);
    }

    /**
     * 会话级事件类型集合——不需要注入 messageId / partId。
     */
    private static final Set<String> SESSION_LEVEL_TYPES = Set.of(
            "session.status", "session.title", "session.error");

    /**
     * 消息级事件类型集合——需要 messageId/role 但不需要 partId/partSeq。
     */
    private static final Set<String> MESSAGE_LEVEL_TYPES = Set.of(
            "step.start", "step.done");

    private final Map<String, CloudEventHandler> handlers = new HashMap<>();

    /**
     * 会话级 partSeq 计数器。对同一个 partId 固定返回同一个 partSeq，
     * 对新 partId 递增；session idle 时清理。
     */
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentHashMap<String, Integer>>
            partSeqCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            nextPartSeqCounters = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        // --- text ---
        handlers.put("text.delta", this::handleTextDelta);
        handlers.put("text.done", this::handleTextDone);

        // --- thinking ---
        handlers.put("thinking.delta", this::handleThinkingDelta);
        handlers.put("thinking.done", this::handleThinkingDone);

        // --- tool ---
        handlers.put("tool.update", this::handleToolUpdate);

        // --- step ---
        handlers.put("step.start", this::handleStepStart);
        handlers.put("step.done", this::handleStepDone);

        // --- question ---
        handlers.put("question", this::handleQuestion);

        // --- permission ---
        handlers.put("permission.ask", this::handlePermissionAsk);
        handlers.put("permission.reply", this::handlePermissionReply);

        // --- session ---
        handlers.put("session.status", this::handleSessionStatus);
        handlers.put("session.title", this::handleSessionTitle);
        handlers.put("session.error", this::handleSessionError);

        // --- file ---
        handlers.put("file", this::handleFile);

        // --- planning ---
        handlers.put("planning.delta", this::handlePlanningDelta);
        handlers.put("planning.done", this::handlePlanningDone);

        // --- search ---
        handlers.put("searching", this::handleSearching);
        handlers.put("search_result", this::handleSearchResult);

        // --- reference ---
        handlers.put("reference", this::handleReference);

        // --- ask_more ---
        handlers.put("ask_more", this::handleAskMore);
    }

    /**
     * 翻译云端事件为 StreamMessage（无 sessionId 版本，向后兼容）。
     *
     * @param event 云端事件 JSON
     * @return 翻译后的 StreamMessage，未知类型返回 null
     */
    public StreamMessage translate(JsonNode event) {
        return translate(event, null);
    }

    /**
     * 翻译云端事件为 StreamMessage，并根据 sessionId 自动注入消息标识字段。
     *
     * <p>Part 级事件（text/thinking/tool/question/permission/file/planning/searching 等）
     * 自动注入 messageId、sourceMessageId、role、partId、partSeq。
     * 会话级事件（session.status/title/error）不注入这些字段。</p>
     *
     * @param event     云端事件 JSON
     * @param sessionId 会话 ID，用于生成稳定的消息标识
     * @return 翻译后的 StreamMessage，未知类型返回 null
     */
    public StreamMessage translate(JsonNode event, String sessionId) {
        if (event == null) {
            return null;
        }

        String eventType = event.path("type").asText(null);
        if (eventType == null || eventType.isEmpty()) {
            return null;
        }

        CloudEventHandler handler = handlers.get(eventType);
        if (handler == null) {
            log.debug("Unknown cloud event type: {}", eventType);
            return null;
        }

        // handler 期望接收 properties 级别的数据（content/messageId/partId 等）
        // 如果 event 有 properties 子节点，传 properties；否则传 event 本身（兼容展平格式）
        JsonNode handlerInput = event.has("properties") ? event.get("properties") : event;
        StreamMessage msg = handler.handle(handlerInput);
        if (msg == null) {
            return null;
        }

        // 为 Part 级和 Message 级事件补充标识字段
        if (!SESSION_LEVEL_TYPES.contains(eventType)) {
            // messageId/partId 应由云端传入（协议必填），此处仅做 sourceMessageId 同步和 role 兜底
            if (msg.getMessageId() == null) {
                log.warn("[CloudEventTranslator] cloud event missing messageId: type={}, sessionId={}", eventType, sessionId);
            }
            if (msg.getSourceMessageId() == null && msg.getMessageId() != null) {
                msg.setSourceMessageId(msg.getMessageId());
            }

            // role 兜底
            if (msg.getRole() == null) {
                msg.setRole("assistant");
            }

            // Part 级事件：partId 应由云端传入，partSeq 由 SS 生成
            if (!MESSAGE_LEVEL_TYPES.contains(eventType)) {
                if (msg.getPartId() == null) {
                    log.warn("[CloudEventTranslator] cloud event missing partId: type={}, sessionId={}", eventType, sessionId);
                }
                // partSeq：同一 partId 固定，新 partId 递增（SS 内部生成，不需要云端传）
                if (msg.getPartSeq() == null && sessionId != null && msg.getPartId() != null) {
                    var partSequences = partSeqCounters.computeIfAbsent(sessionId,
                            k -> new java.util.concurrent.ConcurrentHashMap<>());
                    var nextSeq = nextPartSeqCounters.computeIfAbsent(sessionId,
                            k -> new java.util.concurrent.atomic.AtomicInteger(0));
                    int seq = partSequences.computeIfAbsent(msg.getPartId(),
                            k -> nextSeq.incrementAndGet());
                    msg.setPartSeq(seq);
                }
            }
        }

        // session.status=idle 时清理 partSeq 计数器
        if ("session.status".equals(eventType) && sessionId != null) {
            JsonNode props = event.has("properties") ? event.get("properties") : event;
            String status = props.path("status").asText(props.path("sessionStatus").asText(null));
            if ("idle".equals(status)) {
                partSeqCounters.remove(sessionId);
                nextPartSeqCounters.remove(sessionId);
            }
        }

        return msg;
    }

    // ==================== Text Handlers ====================

    private StreamMessage handleTextDelta(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.TEXT_DELTA)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .build();
    }

    private StreamMessage handleTextDone(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.TEXT_DONE)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .build();
    }

    // ==================== Thinking Handlers ====================

    private StreamMessage handleThinkingDelta(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.THINKING_DELTA)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .build();
    }

    private StreamMessage handleThinkingDone(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.THINKING_DONE)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .build();
    }

    // ==================== Tool Handler ====================

    private StreamMessage handleToolUpdate(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.TOOL_UPDATE)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .tool(ToolInfo.builder()
                        .toolName(event.path("toolName").asText(null))
                        .toolCallId(event.path("toolCallId").asText(null))
                        .input(extractValue(event, "input"))
                        .output(event.path("output").asText(null))
                        .build())
                .status(event.path("status").asText(null))
                .error(event.path("error").asText(null))
                .title(event.path("title").asText(null))
                .build();
    }

    private Object extractValue(JsonNode event, String fieldName) {
        if (event == null) {
            return null;
        }
        JsonNode value = event.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value;
    }

    // ==================== Step Handlers ====================

    private StreamMessage handleStepStart(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.STEP_START)
                .messageId(event.path("messageId").asText(null))
                .role(event.path("role").asText(null))
                .build();
    }

    @SuppressWarnings("unchecked")
    private StreamMessage handleStepDone(JsonNode event) {
        UsageInfo.UsageInfoBuilder usageBuilder = UsageInfo.builder();

        JsonNode tokensNode = event.get("tokens");
        if (tokensNode != null && !tokensNode.isNull() && tokensNode.isObject()) {
            Map<String, Object> tokens = new HashMap<>();
            tokensNode.fields().forEachRemaining(entry ->
                    tokens.put(entry.getKey(), entry.getValue().numberValue()));
            usageBuilder.tokens(tokens);
        }

        double cost = event.path("cost").asDouble(0);
        if (cost > 0) {
            usageBuilder.cost(cost);
        }

        String reason = event.path("reason").asText(null);
        if (reason != null) {
            usageBuilder.reason(reason);
        }

        return StreamMessage.builder()
                .type(Types.STEP_DONE)
                .messageId(event.path("messageId").asText(null))
                .usage(usageBuilder.build())
                .build();
    }

    // ==================== Question Handler ====================

    private StreamMessage handleQuestion(JsonNode event) {
        JsonNode questionsNode = event.get("questions");
        List<QuestionItem> questions;
        if (questionsNode != null && questionsNode.isArray() && !questionsNode.isEmpty()) {
            questions = new ArrayList<>();
            for (JsonNode q : questionsNode) {
                questions.add(QuestionItem.builder()
                        .header(q.path("header").asText(null))
                        .question(q.path("question").asText(null))
                        .options(com.opencode.cui.skill.service.ProtocolUtils.extractQuestionOptions(q.get("options")))
                        .multiSelect(extractMultiSelect(q))
                        .build());
            }
        } else {
            questions = List.of(QuestionItem.builder()
                    .header(event.path("header").asText(null))
                    .question(event.path("question").asText(null))
                    .options(com.opencode.cui.skill.service.ProtocolUtils.extractQuestionOptions(event.get("options")))
                    .multiSelect(extractMultiSelect(event))
                    .build());
        }
        QuestionItem first = questions.get(0);

        JsonNode extParamNode = event.get("extParam");
        JsonNode extParam = (extParamNode != null && !extParamNode.isNull()) ? extParamNode : null;

        return StreamMessage.builder()
                .type(Types.QUESTION)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .tool(ToolInfo.builder()
                        .toolCallId(event.path("toolCallId").asText(null))
                        .build())
                .status(resolveStatus(event, "running"))
                .questionInfo(QuestionInfo.builder()
                        .header(first.getHeader())
                        .question(first.getQuestion())
                        .options(first.getOptions())
                        .multiSelect(first.getMultiSelect())
                        .questions(questions)
                        .extParam(extParam)
                        .questionId(event.path("questionId").asText(null))
                        .build())
                .build();
    }

    private static String resolveStatus(JsonNode event, String defaultStatus) {
        String status = event.path("status").asText(null);
        return status != null && !status.isBlank() ? status : defaultStatus;
    }

    /** 解析 multiSelect 字段（缺/非 boolean → null，前端按单选默认行为处理）。 */
    private static Boolean extractMultiSelect(JsonNode node) {
        if (node == null) return null;
        JsonNode ms = node.get("multiSelect");
        return (ms != null && ms.isBoolean()) ? ms.asBoolean() : null;
    }

    // ==================== Permission Handlers ====================

    private StreamMessage handlePermissionAsk(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PERMISSION_ASK)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .status(resolveStatus(event, "pending"))
                .permission(PermissionInfo.builder()
                        .permissionId(event.path("permissionId").asText(null))
                        .permType(event.path("permType").asText(null))
                        .metadata(event.has("metadata") ? event.get("metadata") : null)
                        .build())
                .title(event.path("title").asText(null))
                .build();
    }

    private StreamMessage handlePermissionReply(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PERMISSION_REPLY)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .status(resolveStatus(event, "completed"))
                .permission(PermissionInfo.builder()
                        .permissionId(event.path("permissionId").asText(null))
                        .permType(event.path("permType").asText(null))
                        .response(event.path("response").asText(null))
                        .build())
                .build();
    }

    // ==================== Session Handlers ====================

    private StreamMessage handleSessionStatus(JsonNode event) {
        return StreamMessage.sessionStatus(extractSessionStatus(event));
    }

    private static String extractSessionStatus(JsonNode event) {
        if (event == null) {
            return null;
        }
        String status = event.path("status").asText(null);
        if (status != null && !status.isBlank()) {
            return status;
        }
        String sessionStatus = event.path("sessionStatus").asText(null);
        return sessionStatus != null && !sessionStatus.isBlank() ? sessionStatus : null;
    }

    private StreamMessage handleSessionTitle(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SESSION_TITLE)
                .title(event.path("title").asText(null))
                .build();
    }

    private StreamMessage handleSessionError(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SESSION_ERROR)
                .error(event.path("error").asText(null))
                .build();
    }

    // ==================== File Handler ====================

    private StreamMessage handleFile(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.FILE)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .file(FileInfo.builder()
                        .fileName(event.path("fileName").asText(null))
                        .fileUrl(event.path("fileUrl").asText(null))
                        .fileMime(event.path("fileMime").asText(null))
                        .build())
                .build();
    }

    // ==================== Planning Handlers ====================

    private StreamMessage handlePlanningDelta(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PLANNING_DELTA)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .content(event.path("content").asText(null))
                .build();
    }

    private StreamMessage handlePlanningDone(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PLANNING_DONE)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .content(event.path("content").asText(null))
                .build();
    }

    // ==================== Search Handlers ====================

    private StreamMessage handleSearching(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SEARCHING)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .keywords(toStringList(event.get("keywords")))
                .build();
    }

    private StreamMessage handleSearchResult(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SEARCH_RESULT)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .searchResults(toSearchResultList(event.get("searchResults")))
                .build();
    }

    // ==================== Reference Handler ====================

    private StreamMessage handleReference(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.REFERENCE)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .references(toReferenceList(event.get("references")))
                .build();
    }

    // ==================== Ask More Handler ====================

    private StreamMessage handleAskMore(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.ASK_MORE)
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .askMoreQuestions(toStringList(event.get("askMoreQuestions")))
                .build();
    }

    // ==================== Utility Methods ====================

    /**
     * 将 JSON 数组转换为 String 列表。
     */
    private List<String> toStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            list.add(item.asText());
        }
        return list.isEmpty() ? null : list;
    }

    /**
     * 将 JSON 数组转换为 SearchResultItem 列表。
     */
    private List<SearchResultItem> toSearchResultList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<SearchResultItem> list = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            list.add(SearchResultItem.builder()
                    .index(item.path("index").asText(null))
                    .title(item.path("title").asText(null))
                    .source(item.path("source").asText(null))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }

    /**
     * 将 JSON 数组转换为 ReferenceItem 列表。
     */
    private List<ReferenceItem> toReferenceList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<ReferenceItem> list = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            list.add(ReferenceItem.builder()
                    .index(item.path("index").asText(null))
                    .title(item.path("title").asText(null))
                    .source(item.path("source").asText(null))
                    .url(item.path("url").asText(null))
                    .content(item.path("content").asText(null))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }

}
