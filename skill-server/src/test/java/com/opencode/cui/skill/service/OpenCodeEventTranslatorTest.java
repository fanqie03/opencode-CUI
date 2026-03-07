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
        assertEquals("part-1", translated.getPartId());
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
        assertEquals("thinking", translated.getContent());
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
        assertEquals("Choose one", translated.getHeader());
        assertEquals("Which option?", translated.getQuestion());
        assertEquals(java.util.List.of("A", "B"), translated.getOptions());
    }
}
