package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.ExternalInvokeRequest;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.service.InboundProcessingService;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/external")
public class ExternalInboundController {

    private static final Set<String> VALID_ACTIONS = Set.of("chat", "question_reply", "permission_reply", "rebuild");
    private static final Set<String> VALID_SESSION_TYPES = Set.of("group", "direct");
    private static final Set<String> VALID_RESPONSES = Set.of("once", "always", "reject");

    private final InboundProcessingService processingService;
    private final ObjectMapper objectMapper;

    public ExternalInboundController(InboundProcessingService processingService, ObjectMapper objectMapper) {
        this.processingService = processingService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/invoke")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<?>> invoke(@RequestBody ExternalInvokeRequest request) {
        log.info("[ENTRY] ExternalInboundController.invoke: action={}, domain={}, sessionType={}, sessionId={}, assistant={}",
                request != null ? request.getAction() : null,
                request != null ? request.getBusinessDomain() : null,
                request != null ? request.getSessionType() : null,
                request != null ? request.getSessionId() : null,
                request != null ? request.getAssistantAccount() : null);

        String envelopeError = validateEnvelope(request);
        if (envelopeError != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, envelopeError));
        }

        String payloadError = validatePayload(request);
        if (payloadError != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, payloadError));
        }

        InboundResult result = switch (request.getAction()) {
            case "chat" -> processingService.processChat(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount(),
                    request.payloadString("content"), request.payloadString("msgType"),
                    request.payloadString("imageUrl"), parseChatHistory(request.getPayload()),
                    "EXTERNAL");
            case "question_reply" -> processingService.processQuestionReply(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount(),
                    request.payloadString("content"), request.payloadString("toolCallId"),
                    request.payloadString("subagentSessionId"), "EXTERNAL");
            case "permission_reply" -> processingService.processPermissionReply(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount(),
                    request.payloadString("permissionId"), request.payloadString("response"),
                    request.payloadString("subagentSessionId"), "EXTERNAL");
            case "rebuild" -> processingService.processRebuild(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount());
            default -> InboundResult.error(400, "Unknown action: " + request.getAction());
        };

        // 构建 session 信息（成功和失败都返回）
        Map<String, String> sessionData = new java.util.LinkedHashMap<>();
        if (result.businessSessionId() != null) {
            sessionData.put("businessSessionId", result.businessSessionId());
        }
        if (result.welinkSessionId() != null) {
            sessionData.put("welinkSessionId", result.welinkSessionId());
        }

        if (!result.success()) {
            ApiResponse<Map<String, String>> errorResp = ApiResponse.<Map<String, String>>builder()
                    .code(result.code())
                    .errormsg(result.message())
                    .data(sessionData.isEmpty() ? null : sessionData)
                    .build();
            return ResponseEntity.ok(errorResp);
        }
        return ResponseEntity.ok(ApiResponse.ok(sessionData.isEmpty() ? null : sessionData));
    }

    private String validateEnvelope(ExternalInvokeRequest request) {
        if (request == null) return "Request body is required";
        if (request.getAction() == null || request.getAction().isBlank()) return "action is required";
        if (!VALID_ACTIONS.contains(request.getAction())) return "Invalid action: " + request.getAction();
        if (request.getBusinessDomain() == null || request.getBusinessDomain().isBlank()) return "businessDomain is required";
        if (request.getSessionType() == null || !VALID_SESSION_TYPES.contains(request.getSessionType())) return "Invalid sessionType";
        if (request.getSessionId() == null || request.getSessionId().isBlank()) return "sessionId is required";
        if (request.getAssistantAccount() == null || request.getAssistantAccount().isBlank()) return "assistantAccount is required";
        if (request.getSenderUserAccount() == null || request.getSenderUserAccount().isBlank()) return "senderUserAccount is required";
        return null;
    }

    private String validatePayload(ExternalInvokeRequest request) {
        return switch (request.getAction()) {
            case "chat" -> {
                String content = request.payloadString("content");
                yield (content == null || content.isBlank()) ? "payload.content is required for chat" : null;
            }
            case "question_reply" -> {
                String content = request.payloadString("content");
                String toolCallId = request.payloadString("toolCallId");
                if (content == null || content.isBlank()) yield "payload.content is required for question_reply";
                if (toolCallId == null || toolCallId.isBlank()) yield "payload.toolCallId is required for question_reply";
                yield null;
            }
            case "permission_reply" -> {
                String permissionId = request.payloadString("permissionId");
                String resp = request.payloadString("response");
                if (permissionId == null || permissionId.isBlank()) yield "payload.permissionId is required";
                if (resp == null || !VALID_RESPONSES.contains(resp)) yield "payload.response must be once/always/reject";
                yield null;
            }
            case "rebuild" -> null;
            default -> "Unknown action";
        };
    }

    private List<ImMessageRequest.ChatMessage> parseChatHistory(JsonNode payload) {
        if (payload == null) return null;
        JsonNode historyNode = payload.path("chatHistory");
        if (historyNode.isMissingNode() || historyNode.isNull() || !historyNode.isArray()) return null;
        try {
            List<ImMessageRequest.ChatMessage> history = new ArrayList<>();
            for (JsonNode item : historyNode) {
                history.add(new ImMessageRequest.ChatMessage(
                        item.path("senderAccount").asText(null),
                        item.path("senderName").asText(null),
                        item.path("content").asText(null),
                        item.path("timestamp").asLong(0)));
            }
            return history;
        } catch (Exception e) {
            log.warn("Failed to parse chatHistory: {}", e.getMessage());
            return null;
        }
    }
}
