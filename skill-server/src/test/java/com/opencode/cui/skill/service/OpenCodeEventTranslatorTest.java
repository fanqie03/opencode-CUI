package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenCodeEventTranslatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenCodeEventTranslator translator = new OpenCodeEventTranslator(objectMapper);

    @Test
    @DisplayName("message.part.delta is translated after part type is learned from updated event")
    void translatesTextDeltaAfterUpdatedEvent() throws Exception {
        var updated = objectMapper.readTree("""
                {
                  "type": "message.part.updated",
                  "properties": {
                    "part": {
                      "id": "part-1",
                      "sessionID": "sess-1",
                      "messageID": "msg-1",
                      "type": "text",
                      "text": ""
                    }
                  }
                }
                """);
        var delta = objectMapper.readTree("""
                {
                  "type": "message.part.delta",
                  "properties": {
                    "sessionID": "sess-1",
                    "messageID": "msg-1",
                    "partID": "part-1",
                    "field": "text",
                    "delta": "hello"
                  }
                }
                """);

        StreamMessage seed = translator.translate(updated);
        StreamMessage translated = translator.translate(delta);

        assertNotNull(seed);
        assertNotNull(translated);
        assertEquals(StreamMessage.Types.TEXT_DELTA, translated.getType());
        assertEquals("sess-1", translated.getSessionId());
        assertEquals("msg-1", translated.getMessageId());
        assertEquals("msg-1", translated.getSourceMessageId());
        assertEquals("part-1", translated.getPartId());
        assertEquals(1, translated.getPartSeq());
        assertEquals("assistant", translated.getRole());
        assertEquals("hello", translated.getContent());
    }

    @Test
    @DisplayName("message.part.delta for reasoning becomes thinking.delta")
    void translatesReasoningDelta() throws Exception {
        var updated = objectMapper.readTree("""
                {
                  "type": "message.part.updated",
                  "properties": {
                    "part": {
                      "id": "part-r",
                      "sessionID": "sess-2",
                      "messageID": "msg-2",
                      "type": "reasoning",
                      "text": ""
                    }
                  }
                }
                """);
        var delta = objectMapper.readTree("""
                {
                  "type": "message.part.delta",
                  "properties": {
                    "sessionID": "sess-2",
                    "messageID": "msg-2",
                    "partID": "part-r",
                    "field": "text",
                    "delta": "thinking"
                  }
                }
                """);

        translator.translate(updated);
        StreamMessage translated = translator.translate(delta);

        assertNotNull(translated);
        assertEquals(StreamMessage.Types.THINKING_DELTA, translated.getType());
        assertEquals("sess-2", translated.getSessionId());
        assertEquals("msg-2", translated.getMessageId());
        assertEquals("thinking", translated.getContent());
    }

    @Test
    @DisplayName("user message role from message.updated suppresses echoed text parts")
    void ignoresUserMessagePartsAfterRoleIsLearned() throws Exception {
        var messageUpdated = objectMapper.readTree("""
                {
                  "type": "message.updated",
                  "properties": {
                    "sessionID": "sess-user",
                    "messageID": "msg-user",
                    "info": {
                      "id": "msg-user",
                      "role": "user"
                    }
                  }
                }
                """);
        var partUpdated = objectMapper.readTree("""
                {
                  "type": "message.part.updated",
                  "properties": {
                    "part": {
                      "id": "part-user",
                      "sessionID": "sess-user",
                      "messageID": "msg-user",
                      "type": "text",
                      "text": "user prompt"
                    }
                  }
                }
                """);
        var partDelta = objectMapper.readTree("""
                {
                  "type": "message.part.delta",
                  "properties": {
                    "sessionID": "sess-user",
                    "messageID": "msg-user",
                    "partID": "part-user",
                    "field": "text",
                    "delta": "user prompt"
                  }
                }
                """);

        StreamMessage roleEvent = translator.translate(messageUpdated);
        StreamMessage seededPart = translator.translate(partUpdated);
        StreamMessage deltaEvent = translator.translate(partDelta);

        assertNull(roleEvent);
        assertNull(seededPart);
        assertNull(deltaEvent);
    }

    @Test
    @DisplayName("question.asked is mapped to a frontend question message")
    void translatesQuestionAsked() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "type": "question.asked",
                  "properties": {
                    "id": "question-1",
                    "questions": [
                      {
                        "header": "Choose one",
                        "question": "Which option?",
                        "options": [
                          { "label": "A", "description": "Alpha" },
                          { "label": "B", "description": "Beta" }
                        ]
                      }
                    ]
                  }
                }
                """);

        StreamMessage translated = translator.translate(event);

        assertNotNull(translated);
        assertEquals(StreamMessage.Types.QUESTION, translated.getType());
        assertEquals("question-1", translated.getPartId());
        assertEquals("assistant", translated.getRole());
        assertEquals("Choose one", translated.getHeader());
        assertEquals("Which option?", translated.getQuestion());
        assertEquals(java.util.List.of("A", "B"), translated.getOptions());
    }

    @Test
    @DisplayName("tool question running with nested questions payload is mapped to frontend question")
    void translatesRunningToolQuestionWithNestedPayload() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "type": "message.part.updated",
                  "properties": {
                    "part": {
                      "id": "part-question-1",
                      "sessionID": "sess-question",
                      "messageID": "msg-question",
                      "type": "tool",
                      "callID": "call-question-1",
                      "tool": "question",
                      "state": {
                        "status": "running",
                        "input": {
                          "questions": [
                            {
                              "header": "实现方案",
                              "question": "选 A 还是 B？",
                              "options": [
                                { "label": "A", "description": "只改最小范围" },
                                { "label": "B", "description": "做完整重构" }
                              ]
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                """);

        StreamMessage translated = translator.translate(event);

        assertNotNull(translated);
        assertEquals(StreamMessage.Types.QUESTION, translated.getType());
        assertEquals("sess-question", translated.getSessionId());
        assertEquals("msg-question", translated.getMessageId());
        assertEquals("part-question-1", translated.getPartId());
        assertEquals("call-question-1", translated.getToolCallId());
        assertEquals("实现方案", translated.getHeader());
        assertEquals("选 A 还是 B？", translated.getQuestion());
        assertEquals(java.util.List.of("A", "B"), translated.getOptions());
        assertNotNull(translated.getInput());
    }

    @Test
    @DisplayName("session.status values are normalized to protocol status domain")
    void normalizesSessionStatusValues() throws Exception {
        var reconnectingEvent = objectMapper.readTree("""
                {
                  "type": "session.status",
                  "properties": {
                    "sessionID": "sess-1",
                    "status": {
                      "type": "reconnecting"
                    }
                  }
                }
                """);
        var activeEvent = objectMapper.readTree("""
                {
                  "type": "session.status",
                  "properties": {
                    "sessionID": "sess-1",
                    "status": {
                      "type": "active"
                    }
                  }
                }
                """);

        StreamMessage reconnecting = translator.translate(reconnectingEvent);
        StreamMessage active = translator.translate(activeEvent);

        assertNotNull(reconnecting);
        assertNotNull(active);
        assertEquals("retry", reconnecting.getSessionStatus());
        assertEquals("busy", active.getSessionStatus());
    }
}
