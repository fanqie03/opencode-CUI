package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.StreamMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CloudEventTranslator 单元测试：验证云端事件到 StreamMessage 的翻译。
 * TDD：先写测试，再写实现。
 */
class CloudEventTranslatorTest {

    private final ObjectMapper om = new ObjectMapper();
    private CloudEventTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new CloudEventTranslator();
        translator.init();
    }

    // ==================== Helper ====================

    private ObjectNode event(String type) {
        ObjectNode node = om.createObjectNode();
        node.put("type", type);
        return node;
    }

    // ==================== text.delta ====================

    @Test
    @DisplayName("text.delta -> TEXT_DELTA with content and role")
    void textDelta() {
        ObjectNode e = event("text.delta");
        e.put("content", "hello");
        e.put("role", "assistant");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.TEXT_DELTA, msg.getType());
        assertEquals("hello", msg.getContent());
        assertEquals("assistant", msg.getRole());
    }

    // ==================== text.done ====================

    @Test
    @DisplayName("text.done -> TEXT_DONE with content, role, messageId, partId")
    void textDone() {
        ObjectNode e = event("text.done");
        e.put("content", "full text");
        e.put("role", "assistant");
        e.put("messageId", "msg-1");
        e.put("partId", "part-1");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.TEXT_DONE, msg.getType());
        assertEquals("full text", msg.getContent());
        assertEquals("assistant", msg.getRole());
        assertEquals("msg-1", msg.getMessageId());
        assertEquals("part-1", msg.getPartId());
    }

    // ==================== tool.update ====================

    @Test
    @DisplayName("tool.update -> TOOL_UPDATE with toolName, toolCallId, status, input, output, error, title")
    void toolUpdate() {
        ObjectNode e = event("tool.update");
        e.put("toolName", "bash");
        e.put("toolCallId", "call-1");
        e.put("status", "running");
        e.put("input", "ls -la");
        e.put("output", "file.txt");
        e.put("error", "");
        e.put("title", "Run command");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.TOOL_UPDATE, msg.getType());
        assertNotNull(msg.getTool());
        assertEquals("bash", msg.getTool().getToolName());
        assertEquals("call-1", msg.getTool().getToolCallId());
        assertEquals("running", msg.getStatus());
        assertEquals("ls -la", msg.getTool().getInput());
        assertEquals("file.txt", msg.getTool().getOutput());
        assertEquals("Run command", msg.getTitle());
    }

    // ==================== question ====================

    @Test
    @DisplayName("question -> QUESTION with toolCallId, question, header, options, status")
    void question() {
        ObjectNode e = event("question");
        e.put("toolCallId", "call-q");
        e.put("questionId", "question-request-1");
        e.put("question", "Continue?");
        e.put("header", "Confirmation");
        e.put("status", "running");
        ArrayNode opts = om.createArrayNode();
        opts.add("Yes");
        opts.add("No");
        e.set("options", opts);

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.QUESTION, msg.getType());
        assertEquals("call-q", msg.getTool().getToolCallId());
        assertEquals("running", msg.getStatus());
        assertNotNull(msg.getQuestionInfo());
        assertEquals("Continue?", msg.getQuestionInfo().getQuestion());
        assertEquals("Confirmation", msg.getQuestionInfo().getHeader());
        assertEquals("question-request-1", msg.getQuestionInfo().getQuestionId());
        assertEquals(List.of("Yes", "No"), msg.getQuestionInfo().getOptions());
    }

    @Test
    @DisplayName("projected cloud question defaults to running and preserves questionId")
    void projectedCloudQuestionDefaultsRunningAndPreservesQuestionId() throws Exception {
        String json = """
            {"protocol":"cloud","type":"question",
             "properties":{
               "messageId":"msg-1",
               "partId":"part-display-1",
               "questionId":"question-target-1",
               "toolCallId":"question-target-1",
               "questions":[{"header":"h","question":"q","options":[{"label":"A"}]}]}}
            """;
        StreamMessage msg = translator.translate(om.readTree(json));

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.QUESTION, msg.getType());
        assertEquals("msg-1", msg.getMessageId());
        assertEquals("part-display-1", msg.getPartId());
        assertEquals("question-target-1", msg.getTool().getToolCallId());
        assertEquals("running", msg.getStatus());
        assertNotNull(msg.getQuestionInfo());
        assertEquals("question-target-1", msg.getQuestionInfo().getQuestionId());
        assertEquals("q", msg.getQuestionInfo().getQuestion());
        assertEquals(List.of("A"), msg.getQuestionInfo().getOptions());
    }

    @Test
    @DisplayName("question legacy single structure -> wraps to single-element questions list, extParam null")
    void handleQuestion_legacySingleStructure_wrapsToSingleElementQuestions() throws Exception {
        String json = """
            {"type":"question","toolCallId":"call-1",
             "header":"h","question":"q","options":["A","B"]}
            """;
        StreamMessage msg = translator.translate(om.readTree(json));

        assertNotNull(msg);
        assertNotNull(msg.getQuestionInfo());
        assertNotNull(msg.getQuestionInfo().getQuestions());
        assertEquals(1, msg.getQuestionInfo().getQuestions().size());
        assertEquals("q", msg.getQuestionInfo().getQuestions().get(0).getQuestion());
        assertEquals("h", msg.getQuestionInfo().getQuestions().get(0).getHeader());
        assertEquals(List.of("A", "B"), msg.getQuestionInfo().getQuestions().get(0).getOptions());
        // 顶层兼容字段
        assertEquals("h", msg.getQuestionInfo().getHeader());
        assertEquals("q", msg.getQuestionInfo().getQuestion());
        assertEquals(List.of("A", "B"), msg.getQuestionInfo().getOptions());
        assertNull(msg.getQuestionInfo().getExtParam());
    }

    @Test
    @DisplayName("question with questions[] -> iterates all items, top-level fields take first")
    void handleQuestion_questionsArray_iteratesAll() throws Exception {
        String json = """
            {"type":"question","toolCallId":"call-1",
             "questions":[
               {"header":"h1","question":"q1","options":["A"]},
               {"question":"q2","options":["B","C"]}]}
            """;
        StreamMessage msg = translator.translate(om.readTree(json));

        assertNotNull(msg);
        assertNotNull(msg.getQuestionInfo());
        assertNotNull(msg.getQuestionInfo().getQuestions());
        assertEquals(2, msg.getQuestionInfo().getQuestions().size());
        assertEquals("q1", msg.getQuestionInfo().getQuestions().get(0).getQuestion());
        assertEquals("h1", msg.getQuestionInfo().getQuestions().get(0).getHeader());
        assertEquals(List.of("A"), msg.getQuestionInfo().getQuestions().get(0).getOptions());
        assertEquals("q2", msg.getQuestionInfo().getQuestions().get(1).getQuestion());
        assertNull(msg.getQuestionInfo().getQuestions().get(1).getHeader());
        assertEquals(List.of("B", "C"), msg.getQuestionInfo().getQuestions().get(1).getOptions());
        // 顶层取第一个
        assertEquals("q1", msg.getQuestionInfo().getQuestion());
        assertEquals("h1", msg.getQuestionInfo().getHeader());
        assertEquals(List.of("A"), msg.getQuestionInfo().getOptions());
    }

    @Test
    @DisplayName("question with extParam -> extParam passthrough as JsonNode")
    void handleQuestion_extParamPassthrough() throws Exception {
        String json = """
            {"type":"question","toolCallId":"call-1",
             "header":"h","question":"q","options":["A"],
             "extParam":{"foo":"bar","nested":{"k":1}}}
            """;
        StreamMessage msg = translator.translate(om.readTree(json));

        assertNotNull(msg);
        assertNotNull(msg.getQuestionInfo());
        assertNotNull(msg.getQuestionInfo().getExtParam());
        assertEquals("bar", msg.getQuestionInfo().getExtParam().get("foo").asText());
        assertEquals(1, msg.getQuestionInfo().getExtParam().get("nested").get("k").asInt());
    }

    // ==================== permission.ask ====================

    @Test
    @DisplayName("permission.ask -> PERMISSION_ASK with permissionId, permType, title, metadata")
    void permissionAsk() {
        ObjectNode e = event("permission.ask");
        e.put("permissionId", "perm-1");
        e.put("permType", "file_write");
        e.put("title", "Write to file");
        ObjectNode meta = om.createObjectNode();
        meta.put("path", "/tmp/test");
        e.set("metadata", meta);

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.PERMISSION_ASK, msg.getType());
        assertNotNull(msg.getPermission());
        assertEquals("perm-1", msg.getPermission().getPermissionId());
        assertEquals("file_write", msg.getPermission().getPermType());
        assertEquals("pending", msg.getStatus());
        assertEquals("Write to file", msg.getTitle());
    }

    // ==================== permission.reply ====================

    @Test
    @DisplayName("permission.reply -> PERMISSION_REPLY with permissionId, permType, response")
    void permissionReply() {
        ObjectNode e = event("permission.reply");
        e.put("permissionId", "perm-2");
        e.put("permType", "file_write");
        e.put("response", "once");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.PERMISSION_REPLY, msg.getType());
        assertNotNull(msg.getPermission());
        assertEquals("perm-2", msg.getPermission().getPermissionId());
        assertEquals("once", msg.getPermission().getResponse());
        assertEquals("completed", msg.getStatus());
    }

    // ==================== session.status ====================

    @Test
    @DisplayName("session.status -> SESSION_STATUS using sessionStatus() factory")
    void sessionStatus() {
        ObjectNode e = event("session.status");
        e.put("sessionStatus", "busy");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.SESSION_STATUS, msg.getType());
        assertEquals("busy", msg.getSessionStatus());
    }

    @Test
    @DisplayName("session.status accepts status alias")
    void sessionStatus_acceptsStatusAlias() {
        ObjectNode e = event("session.status");
        e.put("status", "busy");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.SESSION_STATUS, msg.getType());
        assertEquals("busy", msg.getSessionStatus());
    }

    @Test
    @DisplayName("session.status accepts properties.status alias")
    void sessionStatus_acceptsPropertiesStatusAlias() {
        ObjectNode e = event("session.status");
        ObjectNode props = om.createObjectNode();
        props.put("status", "idle");
        e.set("properties", props);

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.SESSION_STATUS, msg.getType());
        assertEquals("idle", msg.getSessionStatus());
    }

    // ==================== session.title ====================

    @Test
    @DisplayName("session.title -> SESSION_TITLE with title")
    void sessionTitle() {
        ObjectNode e = event("session.title");
        e.put("title", "My Session");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.SESSION_TITLE, msg.getType());
        assertEquals("My Session", msg.getTitle());
    }

    // ==================== session.error ====================

    @Test
    @DisplayName("session.error -> SESSION_ERROR with error")
    void sessionError() {
        ObjectNode e = event("session.error");
        e.put("error", "something went wrong");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.SESSION_ERROR, msg.getType());
        assertEquals("something went wrong", msg.getError());
    }

    // ==================== step.start ====================

    @Test
    @DisplayName("step.start -> STEP_START with messageId, role")
    void stepStart() {
        ObjectNode e = event("step.start");
        e.put("messageId", "msg-s");
        e.put("role", "assistant");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.STEP_START, msg.getType());
        assertEquals("msg-s", msg.getMessageId());
        assertEquals("assistant", msg.getRole());
    }

    // ==================== step.done ====================

    @Test
    @DisplayName("step.done -> STEP_DONE with tokens, cost, reason")
    void stepDone() {
        ObjectNode e = event("step.done");
        ObjectNode tokens = om.createObjectNode();
        tokens.put("input", 100);
        tokens.put("output", 50);
        e.set("tokens", tokens);
        e.put("cost", 0.05);
        e.put("reason", "end_turn");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.STEP_DONE, msg.getType());
        assertNotNull(msg.getUsage());
        assertNotNull(msg.getUsage().getTokens());
        assertEquals(0.05, msg.getUsage().getCost());
        assertEquals("end_turn", msg.getUsage().getReason());
    }

    // ==================== thinking.delta ====================

    @Test
    @DisplayName("thinking.delta -> THINKING_DELTA with content, role")
    void thinkingDelta() {
        ObjectNode e = event("thinking.delta");
        e.put("content", "let me think...");
        e.put("role", "assistant");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.THINKING_DELTA, msg.getType());
        assertEquals("let me think...", msg.getContent());
    }

    // ==================== thinking.done ====================

    @Test
    @DisplayName("thinking.done -> THINKING_DONE with content, role, messageId, partId")
    void thinkingDone() {
        ObjectNode e = event("thinking.done");
        e.put("content", "I concluded that...");
        e.put("role", "assistant");
        e.put("messageId", "msg-t");
        e.put("partId", "part-t");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.THINKING_DONE, msg.getType());
        assertEquals("I concluded that...", msg.getContent());
        assertEquals("msg-t", msg.getMessageId());
        assertEquals("part-t", msg.getPartId());
    }

    // ==================== file ====================

    @Test
    @DisplayName("file -> FILE with fileName, fileUrl, fileMime")
    void file() {
        ObjectNode e = event("file");
        e.put("fileName", "report.pdf");
        e.put("fileUrl", "https://example.com/report.pdf");
        e.put("fileMime", "application/pdf");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.FILE, msg.getType());
        assertNotNull(msg.getFile());
        assertEquals("report.pdf", msg.getFile().getFileName());
        assertEquals("https://example.com/report.pdf", msg.getFile().getFileUrl());
        assertEquals("application/pdf", msg.getFile().getFileMime());
    }

    // ==================== planning.delta ====================

    @Test
    @DisplayName("planning.delta -> PLANNING_DELTA with content")
    void planningDelta() {
        ObjectNode e = event("planning.delta");
        e.put("content", "Step 1: ...");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.PLANNING_DELTA, msg.getType());
        assertEquals("Step 1: ...", msg.getContent());
    }

    // ==================== planning.done ====================

    @Test
    @DisplayName("planning.done -> PLANNING_DONE with content")
    void planningDone() {
        ObjectNode e = event("planning.done");
        e.put("content", "Plan complete");

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.PLANNING_DONE, msg.getType());
        assertEquals("Plan complete", msg.getContent());
    }

    // ==================== searching ====================

    @Test
    @DisplayName("searching -> SEARCHING with keywords list")
    void searching() {
        ObjectNode e = event("searching");
        ArrayNode kw = om.createArrayNode();
        kw.add("java");
        kw.add("spring boot");
        e.set("keywords", kw);

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.SEARCHING, msg.getType());
        assertEquals(List.of("java", "spring boot"), msg.getKeywords());
    }

    // ==================== search_result ====================

    @Test
    @DisplayName("search_result -> SEARCH_RESULT with searchResults list")
    void searchResult() {
        ObjectNode e = event("search_result");
        ArrayNode results = om.createArrayNode();
        ObjectNode item = om.createObjectNode();
        item.put("index", "1");
        item.put("title", "Spring Docs");
        item.put("source", "https://spring.io");
        results.add(item);
        e.set("searchResults", results);

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.SEARCH_RESULT, msg.getType());
        assertNotNull(msg.getSearchResults());
        assertEquals(1, msg.getSearchResults().size());
        assertEquals("1", msg.getSearchResults().get(0).getIndex());
        assertEquals("Spring Docs", msg.getSearchResults().get(0).getTitle());
        assertEquals("https://spring.io", msg.getSearchResults().get(0).getSource());
    }

    // ==================== reference ====================

    @Test
    @DisplayName("reference -> REFERENCE with references list")
    void reference() {
        ObjectNode e = event("reference");
        ArrayNode refs = om.createArrayNode();
        ObjectNode ref = om.createObjectNode();
        ref.put("index", "1");
        ref.put("title", "Ref Title");
        ref.put("source", "source-1");
        ref.put("url", "https://example.com");
        ref.put("content", "Some content");
        refs.add(ref);
        e.set("references", refs);

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.REFERENCE, msg.getType());
        assertNotNull(msg.getReferences());
        assertEquals(1, msg.getReferences().size());
        assertEquals("1", msg.getReferences().get(0).getIndex());
        assertEquals("Ref Title", msg.getReferences().get(0).getTitle());
        assertEquals("https://example.com", msg.getReferences().get(0).getUrl());
        assertEquals("Some content", msg.getReferences().get(0).getContent());
    }

    // ==================== ask_more ====================

    @Test
    @DisplayName("ask_more -> ASK_MORE with askMoreQuestions list")
    void askMore() {
        ObjectNode e = event("ask_more");
        ArrayNode questions = om.createArrayNode();
        questions.add("What framework?");
        questions.add("What language?");
        e.set("askMoreQuestions", questions);

        StreamMessage msg = translator.translate(e);

        assertNotNull(msg);
        assertEquals(StreamMessage.Types.ASK_MORE, msg.getType());
        assertEquals(List.of("What framework?", "What language?"), msg.getAskMoreQuestions());
    }

    // ==================== unknown type ====================

    @Test
    @DisplayName("unknown event type returns null")
    void unknownType() {
        ObjectNode e = event("some.unknown.event");
        e.put("data", "foo");

        StreamMessage msg = translator.translate(e);

        assertNull(msg);
    }

    // ==================== null event ====================

    @Test
    @DisplayName("null event returns null")
    void nullEvent() {
        StreamMessage msg = translator.translate(null);
        assertNull(msg);
    }

    // ==================== event without type returns null ====================

    @Test
    @DisplayName("event without type field returns null")
    void noTypeField() {
        ObjectNode e = om.createObjectNode();
        e.put("content", "orphan");

        StreamMessage msg = translator.translate(e);

        assertNull(msg);
    }
}
