package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ProtocolMessagePart;
import com.opencode.cui.skill.model.StreamMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the user-visible protocol effects that cloud and OpenCode events must
 * share after SS translation and history-part normalization.
 */
class CloudOpenCodeProtocolParityTest {

    private static final String SESSION_ID = "sess-parity";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CloudEventTranslator cloudTranslator;
    private OpenCodeEventTranslator openCodeTranslator;

    @BeforeEach
    void setUp() {
        cloudTranslator = new CloudEventTranslator();
        cloudTranslator.init();
        openCodeTranslator = new OpenCodeEventTranslator(objectMapper, new TranslatorSessionCache());
    }

    @Test
    @DisplayName("text.done has the same history part shape for cloud and OpenCode")
    void textDoneHistoryPartParity() throws Exception {
        ProtocolMessagePart cloud = toPart(cloud("""
                {"type":"text.done","properties":{
                  "messageId":"msg-1","partId":"text-1","role":"assistant","content":"hello"
                }}
                """));
        ProtocolMessagePart openCode = toPart(openCode("""
                {"type":"message.part.updated","properties":{
                  "sessionID":"sess-parity","messageID":"msg-1",
                  "part":{"id":"text-1","sessionID":"sess-parity","messageID":"msg-1",
                    "type":"text","text":"hello"}
                }}
                """));

        assertThat(cloud).usingRecursiveComparison().isEqualTo(openCode);
    }

    @Test
    @DisplayName("thinking.done has the same history part shape for cloud and OpenCode reasoning")
    void thinkingDoneHistoryPartParity() throws Exception {
        ProtocolMessagePart cloud = toPart(cloud("""
                {"type":"thinking.done","properties":{
                  "messageId":"msg-1","partId":"thinking-1","role":"assistant","content":"reason"
                }}
                """));
        ProtocolMessagePart openCode = toPart(openCode("""
                {"type":"message.part.updated","properties":{
                  "sessionID":"sess-parity","messageID":"msg-1",
                  "part":{"id":"thinking-1","sessionID":"sess-parity","messageID":"msg-1",
                    "type":"reasoning","text":"reason"}
                }}
                """));

        assertThat(cloud).usingRecursiveComparison().isEqualTo(openCode);
    }

    @Test
    @DisplayName("tool.update preserves JSON object input like OpenCode before history mapping")
    void toolUpdateObjectInputHistoryPartParity() throws Exception {
        ProtocolMessagePart cloud = toPart(cloud("""
                {"type":"tool.update","properties":{
                  "messageId":"msg-1","partId":"tool-1",
                  "toolName":"bash","toolCallId":"call-1","status":"completed",
                  "input":{"cmd":"ls","args":["-la"]},
                  "output":"ok","error":"none","title":"Run command"
                }}
                """));
        ProtocolMessagePart openCode = toPart(openCode("""
                {"type":"message.part.updated","properties":{
                  "sessionID":"sess-parity","messageID":"msg-1",
                  "part":{"id":"tool-1","sessionID":"sess-parity","messageID":"msg-1",
                    "type":"tool","tool":"bash","callID":"call-1",
                    "state":{"status":"completed","input":{"cmd":"ls","args":["-la"]},
                      "output":"ok","error":"none","title":"Run command"}}
                }}
                """));

        assertThat(cloud.getType()).isEqualTo(openCode.getType()).isEqualTo("tool");
        assertThat(cloud.getPartId()).isEqualTo(openCode.getPartId());
        assertThat(cloud.getPartSeq()).isEqualTo(openCode.getPartSeq());
        assertThat(cloud.getToolName()).isEqualTo(openCode.getToolName());
        assertThat(cloud.getToolCallId()).isEqualTo(openCode.getToolCallId());
        assertThat(cloud.getStatus()).isEqualTo(openCode.getStatus());
        assertThat(cloud.getOutput()).isEqualTo(openCode.getOutput());
        assertThat(cloud.getError()).isEqualTo(openCode.getError());
        assertThat(cloud.getTitle()).isEqualTo(openCode.getTitle());
        assertThat(json(cloud.getInput())).isEqualTo(json(openCode.getInput()));
    }

    @Test
    @DisplayName("step start/done preserve the same usage semantics")
    void stepSemanticParity() throws Exception {
        StreamMessage cloudStart = cloud("""
                {"type":"step.start","properties":{"messageId":"msg-step","role":"assistant"}}
                """);
        StreamMessage openCodeStart = openCode("""
                {"type":"message.part.updated","properties":{
                  "sessionID":"sess-parity","messageID":"msg-step",
                  "part":{"id":"step-start-1","sessionID":"sess-parity","messageID":"msg-step",
                    "type":"step-start"}
                }}
                """);
        assertThat(cloudStart.getType()).isEqualTo(openCodeStart.getType());
        assertThat(cloudStart.getMessageId()).isEqualTo(openCodeStart.getMessageId());
        assertThat(cloudStart.getRole()).isEqualTo(openCodeStart.getRole());

        StreamMessage cloudDone = cloud("""
                {"type":"step.done","properties":{
                  "messageId":"msg-step","tokens":{"input":10,"output":20},
                  "cost":0.02,"reason":"end_turn"
                }}
                """);
        StreamMessage openCodeDone = openCode("""
                {"type":"message.part.updated","properties":{
                  "sessionID":"sess-parity","messageID":"msg-step",
                  "part":{"id":"step-finish-1","sessionID":"sess-parity","messageID":"msg-step",
                    "type":"step-finish","tokens":{"input":10,"output":20},
                    "cost":0.02,"reason":"end_turn"}
                }}
                """);
        assertThat(cloudDone.getType()).isEqualTo(openCodeDone.getType());
        assertThat(cloudDone.getUsage().getTokens()).isEqualTo(openCodeDone.getUsage().getTokens());
        assertThat(cloudDone.getUsage().getCost()).isEqualTo(openCodeDone.getUsage().getCost());
        assertThat(cloudDone.getUsage().getReason()).isEqualTo(openCodeDone.getUsage().getReason());
        assertThat(ProtocolMessageMapper.toProtocolStreamingPart(cloudDone, objectMapper)).isNull();
        assertThat(ProtocolMessageMapper.toProtocolStreamingPart(openCodeDone, objectMapper)).isNull();
    }

    @Test
    @DisplayName("question ask keeps the same visible question card fields")
    void questionVisibleFieldParity() throws Exception {
        ProtocolMessagePart cloud = toPart(cloud("""
                {"type":"question","properties":{
                  "messageId":"msg-q","partId":"question-1",
                  "questionId":"question-1","toolCallId":"call-q",
                  "questions":[{"header":"Choose","question":"Pick one?",
                    "options":[{"label":"A"},{"label":"B"}]}]
                }}
                """));
        ProtocolMessagePart openCode = toPart(openCode("""
                {"type":"question.asked","properties":{
                  "id":"question-1","sessionID":"sess-parity",
                  "tool":{"callID":"call-q","messageID":"msg-q"},
                  "questions":[{"header":"Choose","question":"Pick one?",
                    "options":[{"label":"A"},{"label":"B"}]}]
                }}
                """));

        assertThat(cloud.getType()).isEqualTo(openCode.getType()).isEqualTo("question");
        assertThat(cloud.getPartId()).isEqualTo(openCode.getPartId());
        assertThat(cloud.getStatus()).isEqualTo(openCode.getStatus()).isEqualTo("running");
        assertThat(cloud.getQuestionId()).isEqualTo(openCode.getQuestionId());
        assertThat(cloud.getToolCallId()).isEqualTo(openCode.getToolCallId());
        assertThat(cloud.getHeader()).isEqualTo(openCode.getHeader());
        assertThat(cloud.getQuestion()).isEqualTo(openCode.getQuestion());
        assertThat(cloud.getOptions()).isEqualTo(openCode.getOptions());
    }

    @Test
    @DisplayName("cloud partSeq is stable per partId and increments only for new parts")
    void cloudPartSeqMatchesOpenCodeStablePartIdentity() throws Exception {
        StreamMessage cloudDelta = cloud("""
                {"type":"text.delta","properties":{
                  "messageId":"msg-1","partId":"text-1","content":"hel"
                }}
                """);
        StreamMessage cloudDone = cloud("""
                {"type":"text.done","properties":{
                  "messageId":"msg-1","partId":"text-1","content":"hello"
                }}
                """);
        StreamMessage cloudSecondPart = cloud("""
                {"type":"thinking.done","properties":{
                  "messageId":"msg-1","partId":"thinking-1","content":"reason"
                }}
                """);

        assertThat(cloudDelta.getPartSeq()).isEqualTo(1);
        assertThat(cloudDone.getPartSeq()).isEqualTo(1);
        assertThat(cloudSecondPart.getPartSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("permission ask/reply keeps the same semantic protocol fields")
    void permissionSemanticParity() throws Exception {
        ProtocolMessagePart cloudAsk = toPart(cloud("""
                {"type":"permission.ask","properties":{
                  "messageId":"msg-p","partId":"perm-1","permissionId":"perm-1",
                  "permType":"command","title":"Run command",
                  "metadata":{"command":"pwd"}
                }}
                """));
        ProtocolMessagePart openCodeAsk = toPart(openCode("""
                {"type":"permission.asked","properties":{
                  "sessionID":"sess-parity","messageID":"msg-p","id":"perm-1",
                  "type":"command","title":"Run command","status":{"type":"pending"},
                  "metadata":{"command":"pwd"}
                }}
                """));

        assertThat(cloudAsk.getType()).isEqualTo(openCodeAsk.getType()).isEqualTo("permission");
        assertThat(cloudAsk.getPermissionId()).isEqualTo(openCodeAsk.getPermissionId());
        assertThat(cloudAsk.getPermType()).isEqualTo(openCodeAsk.getPermType());
        assertThat(cloudAsk.getStatus()).isEqualTo(openCodeAsk.getStatus());
        assertThat(json(cloudAsk.getMetadata())).isEqualTo(json(openCodeAsk.getMetadata()));

        ProtocolMessagePart cloudReply = toPart(cloud("""
                {"type":"permission.reply","properties":{
                  "messageId":"msg-p","partId":"perm-1","permissionId":"perm-1",
                  "permType":"command","response":"once"
                }}
                """));
        ProtocolMessagePart openCodeReply = toPart(openCode("""
                {"type":"permission.replied","properties":{
                  "sessionID":"sess-parity","messageID":"msg-p",
                  "requestID":"perm-1","reply":"once"
                }}
                """));

        assertThat(cloudReply.getType()).isEqualTo(openCodeReply.getType()).isEqualTo("permission");
        assertThat(cloudReply.getPermissionId()).isEqualTo(openCodeReply.getPermissionId());
        assertThat(cloudReply.getStatus()).isEqualTo(openCodeReply.getStatus()).isEqualTo("completed");
        assertThat(cloudReply.getResponse()).isEqualTo(openCodeReply.getResponse());
    }

    @Test
    @DisplayName("session status/title/error produce the same StreamMessage semantics")
    void sessionSemanticParity() throws Exception {
        StreamMessage cloudStatus = cloud("""
                {"type":"session.status","properties":{"status":"busy"}}
                """);
        StreamMessage openCodeStatus = openCode("""
                {"type":"session.status","properties":{"sessionID":"sess-parity","status":{"type":"active"}}}
                """);
        assertThat(cloudStatus.getType()).isEqualTo(openCodeStatus.getType());
        assertThat(cloudStatus.getSessionStatus()).isEqualTo(openCodeStatus.getSessionStatus());

        StreamMessage cloudTitle = cloud("""
                {"type":"session.title","properties":{"title":"My session"}}
                """);
        StreamMessage openCodeTitle = openCode("""
                {"type":"session.updated","properties":{"sessionID":"sess-parity","title":"My session"}}
                """);
        assertThat(cloudTitle.getType()).isEqualTo(openCodeTitle.getType());
        assertThat(cloudTitle.getTitle()).isEqualTo(openCodeTitle.getTitle());

        StreamMessage cloudError = cloud("""
                {"type":"session.error","properties":{"error":"boom"}}
                """);
        StreamMessage openCodeError = openCode("""
                {"type":"session.error","properties":{"sessionID":"sess-parity","error":"boom"}}
                """);
        assertThat(cloudError.getType()).isEqualTo(openCodeError.getType());
        assertThat(cloudError.getError()).isEqualTo(openCodeError.getError());
    }

    @Test
    @DisplayName("file events normalize to the same history part fields")
    void fileHistoryPartParity() throws Exception {
        ProtocolMessagePart cloud = toPart(cloud("""
                {"type":"file","properties":{
                  "messageId":"msg-f","partId":"file-1",
                  "fileName":"report.pdf","fileUrl":"https://example.com/report.pdf",
                  "fileMime":"application/pdf"
                }}
                """));
        ProtocolMessagePart openCode = toPart(openCode("""
                {"type":"message.part.updated","properties":{
                  "sessionID":"sess-parity","messageID":"msg-f",
                  "part":{"id":"file-1","sessionID":"sess-parity","messageID":"msg-f",
                    "type":"file","filename":"report.pdf",
                    "url":"https://example.com/report.pdf","mime":"application/pdf"}
                }}
                """));

        assertThat(cloud).usingRecursiveComparison().isEqualTo(openCode);
    }

    @Test
    @DisplayName("cloud-only extension types stay live-only for history mapper")
    void cloudOnlyExtensionsDoNotBecomeGenericHistoryParts() throws Exception {
        String[] events = {
                """
                {"type":"planning.done","properties":{"messageId":"msg-x","partId":"planning-1","content":"plan"}}
                """,
                """
                {"type":"searching","properties":{"messageId":"msg-x","partId":"searching-1","keywords":["java"]}}
                """,
                """
                {"type":"search_result","properties":{"messageId":"msg-x","partId":"search-1",
                  "searchResults":[{"index":"1","title":"Doc","source":"https://example.com"}]}}
                """,
                """
                {"type":"reference","properties":{"messageId":"msg-x","partId":"ref-1",
                  "references":[{"index":"1","title":"Ref","source":"src","url":"https://example.com"}]}}
                """,
                """
                {"type":"ask_more","properties":{"messageId":"msg-x","partId":"ask-1",
                  "askMoreQuestions":["More?"]}}
                """
        };

        for (String event : events) {
            StreamMessage msg = cloud(event);
            assertThat(msg).isNotNull();
            assertThat(msg.getMessageId()).isEqualTo("msg-x");
            assertThat(msg.getPartId()).isNotBlank();
            assertThat(msg.getPartSeq()).isNotNull();
            assertThat(ProtocolMessageMapper.toProtocolStreamingPart(msg, objectMapper))
                    .as(msg.getType())
                    .isNull();
        }
    }

    private StreamMessage cloud(String json) throws Exception {
        return cloudTranslator.translate(objectMapper.readTree(json), SESSION_ID);
    }

    private StreamMessage openCode(String json) throws Exception {
        return openCodeTranslator.translate(objectMapper.readTree(json));
    }

    private ProtocolMessagePart toPart(StreamMessage msg) {
        assertThat(msg).isNotNull();
        ProtocolMessagePart part = ProtocolMessageMapper.toProtocolStreamingPart(msg, objectMapper);
        assertThat(part).isNotNull();
        return part;
    }

    private JsonNode json(Object value) {
        return objectMapper.valueToTree(value);
    }
}
