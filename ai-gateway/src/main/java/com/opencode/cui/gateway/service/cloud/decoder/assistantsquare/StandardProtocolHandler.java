package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 助手广场 {@code standard} 协议派系 handler（MVP 核心实现）。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>事件类型映射：{@code event:planning} / {@code event:think} / {@code event:message(TEXT)}
 *       → {@code planning.delta} / {@code thinking.delta} / {@code text.delta}（流式 part）；
 *       {@code event:searching} / {@code event:searchResult} / {@code event:reference} /
 *       {@code event:askMore} → 单次性事件</li>
 *   <li>状态机：累积流式 part 内容；类型/messageId 切换或被单次性事件中断时补 {@code <type>.done}（带累积全文）</li>
 *   <li>补齐：首个事件前补 {@code session.status=busy}/{@code step.start}；
 *       {@code flush} 时（part .done 之后）补 {@code step.done}/{@code session.status=idle}</li>
 *   <li>终态：{@code event:error} → 顶层 {@code TOOL_ERROR}</li>
 *   <li>不支持的 messageType（HTML/IMAGE-IM/卡片/processStep/TEXT_LIST 等）丢弃，不报错</li>
 * </ul>
 */
@Slf4j
@Component
public class StandardProtocolHandler implements AssistantSquareProtocolHandler {

    public static final String PROTOCOL_TYPE = "standard";

    // ====== 流式事件类型 ======
    private static final String TEXT = "text";
    private static final String THINKING = "thinking";
    private static final String PLANNING = "planning";

    private final ObjectMapper objectMapper;

    public StandardProtocolHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProtocolType() {
        return PROTOCOL_TYPE;
    }

    @Override
    public List<GatewayMessage> handle(String eventType, JsonNode data, AssistantSquareDecoderSession session) {
        List<GatewayMessage> out = new ArrayList<>();

        String messageId = data.path("messageId").asText(null);
        rememberMessageId(session, messageId);

        // 1) 顶层终态：error → TOOL_ERROR
        if ("error".equalsIgnoreCase(eventType)) {
            String message = firstText(data, "message", "error", "messageEn", "errorEn");
            if (message == null || message.isBlank()) {
                message = "cloud error";
            }
            out.add(GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_ERROR)
                    .messageId(session.getLastMessageId())
                    .error(message)
                    .build());
            return out;
        }

        // 2) 计算映射的标准事件类型
        String messageType = data.path("messageType").asText(null);
        String newStreamType = mapEventType(eventType, messageType);

        // 3) 首事件前补 step.start（无论该事件本身是否支持都补，保证 step 边界对齐）
        if (!session.isStepStarted()) {
            session.setStepStarted(true);
            out.add(buildSessionStatus("busy", session.getLastMessageId()));
            out.add(buildStepStart(messageId));
        }

        // 4) 不支持的 messageType / eventType → 跳过（step.start 已发，下游事件序列仍闭合）
        if (newStreamType == null) {
            return out;
        }

        boolean isStreaming = isStreamingType(newStreamType);

        // 5) 判断要不要先补上一段的 done
        if (isStreaming) {
            String typeOnly = stripDelta(newStreamType);
            if (session.getOpenPartType() != null
                    && (!session.getOpenPartType().equals(typeOnly)
                        || !equalsNullable(session.getOpenPartMessageId(), messageId))) {
                out.add(buildPartDone(session));
                resetOpenPart(session);
            }
        } else {
            // 单次性事件中断流式段
            if (session.getOpenPartType() != null) {
                out.add(buildPartDone(session));
                resetOpenPart(session);
            }
        }

        // 6) 发新事件
        if (isStreaming) {
            String typeOnly = stripDelta(newStreamType);
            if (session.getOpenPartType() == null) {
                session.setOpenPartType(typeOnly);
                session.setOpenPartMessageId(messageId);
                session.setOpenPartContent(new StringBuilder());
            }
            String delta = extractStreamingContent(typeOnly, data);
            if (delta == null) delta = "";
            session.getOpenPartContent().append(delta);
            out.add(buildDelta(newStreamType, delta, messageId));
        } else {
            out.add(buildSingleEvent(newStreamType, data, messageId));
        }

        return out;
    }

    @Override
    public List<GatewayMessage> flush(AssistantSquareDecoderSession session) {
        List<GatewayMessage> out = new ArrayList<>();
        if (session.getOpenPartType() != null) {
            out.add(buildPartDone(session));
            resetOpenPart(session);
        }
        if (session.isStepStarted()) {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", "step.done");
            ObjectNode props = objectMapper.createObjectNode();
            props.put("role", "assistant");
            putMessageId(props, session.getLastMessageId());
            event.set("properties", props);
            out.add(GatewayMessage.builder()
                    .type(GatewayMessage.Type.TOOL_EVENT)
                    .messageId(session.getLastMessageId())
                    .event(event)
                    .build());
            out.add(buildSessionStatus("idle", session.getLastMessageId()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * standard 派系下的事件类型映射。
     *
     * @return 标准协议事件类型；null 表示不支持，丢弃
     */
    private String mapEventType(String eventType, String messageType) {
        if (eventType == null) return null;
        switch (eventType) {
            case "planning":
                // 只接受 PLANNING messageType；其他 messageType（如 HTML 内嵌）丢弃
                if (messageType == null || "PLANNING".equalsIgnoreCase(messageType)) {
                    return "planning.delta";
                }
                return null;
            case "think":
                return "thinking.delta";
            case "processStep":
                return "thinking.delta";
            case "message":
                // 只接受 TEXT；HTML / IMAGE-IM / FILE-IM / 卡片 / TEXT_LIST / SLOT / processStep / WeLink-CARD 等丢弃
                if ("TEXT".equalsIgnoreCase(messageType)) {
                    return "text.delta";
                }
                return null;
            case "searching":
                return "searching";
            case "searchResult":
                return "search_result";
            case "reference":
                return "reference";
            case "askMore":
                return "ask_more";
            default:
                return null;
        }
    }

    private boolean isStreamingType(String streamType) {
        return "text.delta".equals(streamType)
                || "thinking.delta".equals(streamType)
                || "planning.delta".equals(streamType);
    }

    private String stripDelta(String streamType) {
        int idx = streamType.lastIndexOf(".delta");
        return idx > 0 ? streamType.substring(0, idx) : streamType;
    }

    private void rememberMessageId(AssistantSquareDecoderSession session, String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            session.setLastMessageId(messageId);
        }
    }

    /**
     * 从 data 里提取流式 part 的 delta 文本。
     *
     * <p>助手广场不同事件 data 字段命名不同：</p>
     * <ul>
     *   <li>{@code event:planning} → {@code data.planning} 或 {@code data.messageBody}</li>
     *   <li>{@code event:think} → {@code data.think} 或 {@code data.messageBody}</li>
     *   <li>{@code event:message} → {@code data.messageBody} 或 {@code data.text}</li>
     * </ul>
     */
    private String extractStreamingContent(String typeOnly, JsonNode data) {
        // 多个候选字段按 type 优先级匹配
        String[] candidates;
        String[] nestedCandidates;
        switch (typeOnly) {
            case PLANNING:
                candidates = new String[]{"planning", "messageBody", "text"};
                nestedCandidates = new String[]{"planning", "text", "content"};
                break;
            case THINKING:
                candidates = new String[]{"processStep", "think", "thinking", "messageBody", "text"};
                nestedCandidates = new String[]{"message", "messageEn", "think", "thinking", "text", "content"};
                break;
            case TEXT:
            default:
                candidates = new String[]{"messageBody", "text", "content"};
                nestedCandidates = new String[]{"text", "content", "message"};
                break;
        }
        for (String key : candidates) {
            JsonNode n = data.path(key);
            if (n.isMissingNode() || n.isNull()) continue;
            String scalar = scalarText(n);
            if (scalar != null) return scalar;
            String nested = firstText(n, nestedCandidates);
            if (nested != null) return nested;
            // 复杂结构（如 array / object）退化为 JSON 字符串
            return n.toString();
        }
        return "";
    }

    private GatewayMessage buildDelta(String streamType, String delta, String messageId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", streamType);
        ObjectNode props = objectMapper.createObjectNode();
        props.put("content", delta);
        putMessageId(props, messageId);
        event.set("properties", props);
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .messageId(messageId)
                .event(event)
                .build();
    }

    private GatewayMessage buildPartDone(AssistantSquareDecoderSession session) {
        String type = session.getOpenPartType() + ".done";
        String content = session.getOpenPartContent() != null
                ? session.getOpenPartContent().toString() : "";
        String messageId = session.getOpenPartMessageId();

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", type);
        ObjectNode props = objectMapper.createObjectNode();
        props.put("content", content);
        putMessageId(props, messageId);
        event.set("properties", props);
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .messageId(messageId)
                .event(event)
                .build();
    }

    private GatewayMessage buildStepStart(String messageId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "step.start");
        ObjectNode props = objectMapper.createObjectNode();
        props.put("role", "assistant");
        putMessageId(props, messageId);
        event.set("properties", props);
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .messageId(messageId)
                .event(event)
                .build();
    }

    private GatewayMessage buildSessionStatus(String status, String messageId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "session.status");
        ObjectNode props = objectMapper.createObjectNode();
        props.put("status", status);
        props.put("sessionStatus", status);
        putMessageId(props, messageId);
        event.set("properties", props);
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .messageId(messageId)
                .event(event)
                .build();
    }

    private GatewayMessage buildSingleEvent(String streamType, JsonNode data, String messageId) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", streamType);
        ObjectNode props = objectMapper.createObjectNode();
        putMessageId(props, messageId);
        // 按事件类型透传 payload（字段命名贴近 OpenCode 标准协议）
        switch (streamType) {
            case "searching": {
                JsonNode keywords = firstNonMissing(data, "searching", "keywords");
                if (keywords != null) props.set("keywords", keywords);
                break;
            }
            case "search_result": {
                JsonNode results = firstNonMissing(data, "searchResult", "results");
                if (results != null) props.set("results", results);
                break;
            }
            case "reference": {
                JsonNode refs = firstNonMissing(data, "reference", "references");
                if (refs != null) props.set("references", refs);
                break;
            }
            case "ask_more": {
                JsonNode questions = firstNonMissing(data, "askMore", "askMoreQuestions", "questions");
                if (questions != null) props.set("askMoreQuestions", questions);
                break;
            }
            default:
                break;
        }
        event.set("properties", props);
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_EVENT)
                .messageId(messageId)
                .event(event)
                .build();
    }

    private static void putMessageId(ObjectNode props, String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            props.put("messageId", messageId);
        }
    }

    private JsonNode firstNonMissing(JsonNode data, String... fields) {
        for (String f : fields) {
            JsonNode n = data.path(f);
            if (!n.isMissingNode() && !n.isNull()) return n;
        }
        JsonNode messageBody = data.path("messageBody");
        if (messageBody.isObject()) {
            for (String f : fields) {
                JsonNode n = messageBody.path(f);
                if (!n.isMissingNode() && !n.isNull()) return n;
            }
        }
        return null;
    }

    private static String firstText(JsonNode data, String... fields) {
        for (String field : fields) {
            String value = scalarText(data.path(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isContainerNode()) {
            return null;
        }
        return node.asText();
    }

    private void resetOpenPart(AssistantSquareDecoderSession session) {
        session.setOpenPartType(null);
        session.setOpenPartMessageId(null);
        session.setOpenPartContent(null);
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
