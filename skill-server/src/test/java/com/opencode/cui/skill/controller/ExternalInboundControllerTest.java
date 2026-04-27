package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ExternalInvokeRequest;
import com.opencode.cui.skill.service.InboundProcessingService;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalInboundControllerTest {

    @Mock private InboundProcessingService processingService;
    private ObjectMapper objectMapper;
    private ExternalInboundController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new ExternalInboundController(processingService, objectMapper);
    }

    private ExternalInvokeRequest buildRequest(String action, String payload) throws Exception {
        String json = "{\"action\":\"" + action + "\","
                + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
                + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
                + "\"senderUserAccount\":\"user-001\","
                + "\"payload\":" + payload + "}";
        return objectMapper.readValue(json, ExternalInvokeRequest.class);
    }

    @Test
    @DisplayName("chat action dispatches to processChat")
    void chatAction() throws Exception {
        when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());
        var request = buildRequest("chat", "{\"content\":\"hello\",\"msgType\":\"text\"}");
        var response = controller.invoke(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        verify(processingService).processChat(
                eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
                eq("user-001"),
                eq("hello"), eq("text"), isNull(), isNull(), eq("EXTERNAL"), isNull());
    }

    @Test
    @DisplayName("missing action returns 400")
    void missingAction() {
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("dm-001");
        request.setAssistantAccount("assist-01");
        request.setSenderUserAccount("user-001");
        var response = controller.invoke(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("chat without content returns 400")
    void chatWithoutContent() throws Exception {
        var request = buildRequest("chat", "{\"msgType\":\"text\"}");
        var response = controller.invoke(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("question_reply dispatches correctly")
    void questionReplyAction() throws Exception {
        when(processingService.processQuestionReply(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());
        var request = buildRequest("question_reply", "{\"content\":\"A\",\"toolCallId\":\"tc-1\"}");
        var response = controller.invoke(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(processingService).processQuestionReply(
                eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
                eq("user-001"),
                eq("A"), eq("tc-1"), isNull(), eq("EXTERNAL"), isNull());
    }

    @Test
    @DisplayName("permission_reply dispatches correctly")
    void permissionReplyAction() throws Exception {
        when(processingService.processPermissionReply(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());
        var request = buildRequest("permission_reply", "{\"permissionId\":\"perm-1\",\"response\":\"once\"}");
        var response = controller.invoke(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(processingService).processPermissionReply(
                eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
                eq("user-001"),
                eq("perm-1"), eq("once"), isNull(), eq("EXTERNAL"), isNull());
    }

    @Test
    @DisplayName("rebuild dispatches correctly")
    void rebuildAction() throws Exception {
        when(processingService.processRebuild(any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());
        var request = buildRequest("rebuild", "{}");
        var response = controller.invoke(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(processingService).processRebuild("im", "direct", "dm-001", "assist-01", "user-001");
    }

    @Test
    @DisplayName("missing senderUserAccount returns 400 for all actions")
    void missingSenderUserAccountReturns400() throws Exception {
        // 不使用 buildRequest helper：这些测试需要故意 omit 或 mislocate senderUserAccount
        for (String action : List.of("chat", "question_reply", "permission_reply", "rebuild")) {
            String json = "{\"action\":\"" + action + "\","
                    + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
                    + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
                    + "\"payload\":{}}";
            var request = objectMapper.readValue(json, ExternalInvokeRequest.class);
            var response = controller.invoke(request);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "action=" + action);
            assertEquals("senderUserAccount is required",
                    response.getBody().getErrormsg(), "action=" + action);
        }
    }

    @Test
    @DisplayName("D1 hard cut: payload.senderUserAccount is ignored, envelope required")
    void legacyPayloadSenderUserAccountIsIgnored() throws Exception {
        // 不使用 buildRequest helper：这些测试需要故意 omit 或 mislocate senderUserAccount
        String json = "{\"action\":\"chat\","
                + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
                + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
                + "\"payload\":{\"content\":\"hello\",\"senderUserAccount\":\"legacy-user\"}}";
        var request = objectMapper.readValue(json, ExternalInvokeRequest.class);
        var response = controller.invoke(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("senderUserAccount is required",
                response.getBody().getErrormsg());
        verifyNoInteractions(processingService);
    }

    @Test
    @DisplayName("invalid permission response returns 400")
    void invalidPermissionResponse() throws Exception {
        var request = buildRequest("permission_reply", "{\"permissionId\":\"perm-1\",\"response\":\"invalid\"}");
        var response = controller.invoke(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("invoke chat 透传信封 businessExtParam 到 processChat")
    void invokeChatPassesBusinessExtParam() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode bep = om.readTree("{\"k\":\"v\"}");
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setAction("chat");
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("ext-1");
        request.setAssistantAccount("asst-1");
        request.setSenderUserAccount("u-1");
        request.setBusinessExtParam(bep);
        request.setPayload(om.readTree("{\"content\":\"hi\",\"msgType\":\"text\"}"));

        when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());

        controller.invoke(request);

        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> bepCap =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(processingService).processChat(
                eq("im"), eq("direct"), eq("ext-1"), eq("asst-1"),
                eq("u-1"), eq("hi"), eq("text"), isNull(), isNull(),
                eq("EXTERNAL"), bepCap.capture());
        assertEquals("v", bepCap.getValue().get("k").asText());
    }

    @Test
    @DisplayName("invoke question_reply 透传信封 businessExtParam 到 processQuestionReply")
    void invokeQuestionReplyPassesBusinessExtParam() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode bep = om.readTree("{\"q\":\"x\"}");
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setAction("question_reply");
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("ext-1");
        request.setAssistantAccount("asst-1");
        request.setSenderUserAccount("u-1");
        request.setBusinessExtParam(bep);
        request.setPayload(om.readTree("{\"content\":\"reply\",\"toolCallId\":\"tc-1\"}"));

        when(processingService.processQuestionReply(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());

        controller.invoke(request);

        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> bepCap =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(processingService).processQuestionReply(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq("EXTERNAL"), bepCap.capture());
        assertEquals("x", bepCap.getValue().get("q").asText());
    }

    @Test
    @DisplayName("invoke permission_reply 透传信封 businessExtParam 到 processPermissionReply")
    void invokePermissionReplyPassesBusinessExtParam() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode bep = om.readTree("{\"p\":true}");
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setAction("permission_reply");
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("ext-1");
        request.setAssistantAccount("asst-1");
        request.setSenderUserAccount("u-1");
        request.setBusinessExtParam(bep);
        request.setPayload(om.readTree("{\"permissionId\":\"perm-1\",\"response\":\"once\"}"));

        when(processingService.processPermissionReply(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());

        controller.invoke(request);

        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> bepCap =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(processingService).processPermissionReply(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq("EXTERNAL"), bepCap.capture());
        assertTrue(bepCap.getValue().get("p").asBoolean());
    }

    @Test
    @DisplayName("chat action: 离线 InboundResult 被组装为 HTTP 200 + code=503 + errormsg + data(sid,wsid)")
    void chatActionOfflineReturns503WithSessionData() throws Exception {
        when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.error(503, "msg-x", "ext-sid", "123"));

        var request = buildRequest("chat", "{\"content\":\"hello\",\"msgType\":\"text\"}");
        var response = controller.invoke(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(503, response.getBody().getCode());
        assertEquals("msg-x", response.getBody().getErrormsg());

        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, String>) response.getBody().getData();
        assertNotNull(data);
        assertEquals("ext-sid", data.get("businessSessionId"));
        assertEquals("123", data.get("welinkSessionId"));
    }
}
