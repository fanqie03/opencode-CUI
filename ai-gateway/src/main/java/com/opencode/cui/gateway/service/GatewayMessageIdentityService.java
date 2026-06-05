package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Normalizes message identity used by GW return routing.
 *
 * <p>{@code traceId} is the turn/call context. {@code messageId} is the
 * agent/cloud reply identity and is the primary key for preserving ordering
 * over one GW-to-SS link.</p>
 */
@Slf4j
@Service
public class GatewayMessageIdentityService {

    private static final Duration IDENTITY_TTL = Duration.ofMinutes(30);
    private static final String TRACE_PREFIX = "trace:";

    private final Cache<String, String> messageIdByTraceId = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(IDENTITY_TTL)
            .build();

    public GatewayMessage normalizeForSkillRelay(GatewayMessage message) {
        if (message == null) {
            return null;
        }

        GatewayMessage traced = message.ensureTraceId();
        String explicitMessageId = firstNonBlank(
                traced.getMessageId(),
                extractEventMessageId(traced.getEvent()),
                textAt(traced.getPayload(), "messageId"));

        String messageId = explicitMessageId;
        if (!hasText(messageId)) {
            messageId = findByTraceId(traced.getTraceId());
        }

        GatewayMessage normalized = traced;
        if (hasText(messageId)) {
            normalized = withMessageIdEverywhere(traced, messageId);
            rememberTraceMessageId(normalized.getTraceId(), messageId, explicitMessageId);
        }

        if (isTerminalMessage(traced) && !hasText(explicitMessageId)) {
            if (hasText(messageId)) {
                log.warn("[MSG_ID] Recovered terminal messageId by traceId: type={}, traceId={}, messageId={}",
                        traced.getType(), traced.getTraceId(), messageId);
            } else {
                log.warn("[MSG_ID] Terminal event missing messageId and no trace binding: type={}, traceId={}, toolSessionId={}",
                        traced.getType(), traced.getTraceId(), traced.getToolSessionId());
            }
        }

        return normalized;
    }

    public String extractMessageId(GatewayMessage message) {
        if (message == null) {
            return null;
        }
        return firstNonBlank(
                message.getMessageId(),
                extractEventMessageId(message.getEvent()),
                textAt(message.getPayload(), "messageId"));
    }

    private void rememberTraceMessageId(String traceId, String messageId, String explicitMessageId) {
        if (!hasText(traceId) || !hasText(messageId) || !hasText(explicitMessageId)) {
            return;
        }
        String key = TRACE_PREFIX + traceId;
        String previous = messageIdByTraceId.getIfPresent(key);
        if (hasText(previous) && !previous.equals(messageId)) {
            log.warn("[MSG_ID] Conflicting messageId under same traceId; replacing binding: traceId={}, previous={}, current={}",
                    traceId, previous, messageId);
        }
        messageIdByTraceId.put(key, messageId);
    }

    private String findByTraceId(String traceId) {
        if (!hasText(traceId)) {
            return null;
        }
        return messageIdByTraceId.getIfPresent(TRACE_PREFIX + traceId);
    }

    private GatewayMessage withMessageIdEverywhere(GatewayMessage message, String messageId) {
        JsonNode event = message.getEvent();
        if (event == null || !event.isObject()) {
            return hasText(message.getMessageId()) && messageId.equals(message.getMessageId())
                    ? message
                    : message.withMessageId(messageId);
        }

        ObjectNode eventCopy = event.deepCopy();
        JsonNode props = eventCopy.get("properties");
        ObjectNode propsObj;
        if (props != null && props.isObject()) {
            propsObj = (ObjectNode) props;
        } else {
            propsObj = JsonNodeFactory.instance.objectNode();
            eventCopy.set("properties", propsObj);
        }
        if (!hasText(propsObj.path("messageId").asText(null))) {
            propsObj.put("messageId", messageId);
        }
        return message.toBuilder()
                .messageId(messageId)
                .event(eventCopy)
                .build();
    }

    private static String extractEventMessageId(JsonNode event) {
        if (event == null || !event.isObject()) {
            return null;
        }
        String topLevel = textAt(event, "messageId");
        if (hasText(topLevel)) {
            return topLevel;
        }
        JsonNode props = event.path("properties");
        return textAt(props, "messageId");
    }

    private static String textAt(JsonNode node, String fieldName) {
        if (node == null || !node.isObject() || fieldName == null) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return hasText(text) ? text : null;
    }

    private static boolean isTerminalMessage(GatewayMessage message) {
        return GatewayMessage.Type.TOOL_DONE.equals(message.getType())
                || GatewayMessage.Type.TOOL_ERROR.equals(message.getType());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
