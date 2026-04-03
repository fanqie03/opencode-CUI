package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.StreamMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OpenCodeEventTranslator 单元测试：验证 Gateway 事件到 Skill 协议消息的翻译。 */
class OpenCodeEventTranslatorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OpenCodeEventTranslator translator = new OpenCodeEventTranslator(
      objectMapper, new TranslatorSessionCache());

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
  @DisplayName("user message role from message.updated emits TEXT_DONE for text parts, suppresses deltas")
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

    // message.updated(role=user) with no cached text returns null
    assertNull(roleEvent);
    // part.updated with known user role emits TEXT_DONE (user text special
    // handling)
    assertNotNull(seededPart);
    assertEquals(StreamMessage.Types.TEXT_DONE, seededPart.getType());
    assertEquals("user", seededPart.getRole());
    assertEquals("user prompt", seededPart.getContent());
    // delta for user message is suppressed (shouldIgnoreMessage)
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
    assertEquals("Choose one", translated.getQuestionInfo().getHeader());
    assertEquals("Which option?", translated.getQuestionInfo().getQuestion());
    assertEquals(java.util.List.of("A", "B"), translated.getQuestionInfo().getOptions());
  }

  @Test
  @DisplayName("tool question pending from message.part.updated is skipped (waiting for question.asked)")
  void skipsToolQuestionFromPartUpdatedWhenPending() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "message.part.updated",
          "properties": {
            "part": {
              "id": "part-question-pending-1",
              "sessionID": "sess-question",
              "messageID": "msg-question",
              "type": "tool",
              "callID": "call-question-1",
              "tool": "question",
              "state": {
                "status": "pending",
                "input": {}
              }
            }
          }
        }
        """);

    StreamMessage translated = translator.translate(event);

    assertNull(translated);
  }

  @Test
  @DisplayName("tool question running from message.part.updated is skipped (handled by question.asked)")
  void skipsToolQuestionFromPartUpdatedToAvoidDuplicate() throws Exception {
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

    // question from message.part.updated is intentionally skipped
    // to avoid duplicate — the dedicated "question.asked" event is the single
    // source of truth
    assertNull(translated);
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

  @Test
  @DisplayName("question completed from message.part.updated produces QUESTION type with same partId")
  void translatesQuestionCompletedAsQuestionType() throws Exception {
    // Step 1: Simulate question.asked event (establishes callId → partId cache)
    var questionAsked = objectMapper.readTree("""
        {
          "type": "question.asked",
          "properties": {
            "id": "question-abc",
            "sessionID": "sess-q",
            "questions": [
              {
                "header": "方案选择",
                "question": "选 A 还是 B？",
                "options": [
                  { "label": "A", "description": "选项A" },
                  { "label": "B", "description": "选项B" }
                ]
              }
            ],
            "tool": {
              "callID": "call-q-1",
              "messageID": "msg-q"
            }
          }
        }
        """);
    StreamMessage asked = translator.translate(questionAsked);
    assertNotNull(asked);
    assertEquals(StreamMessage.Types.QUESTION, asked.getType());
    assertEquals("question-abc", asked.getPartId());

    // Step 2: Simulate tool completed event for the same question
    var toolCompleted = objectMapper.readTree("""
        {
          "type": "message.part.updated",
          "properties": {
            "part": {
              "id": "part-tool-xyz",
              "sessionID": "sess-q",
              "messageID": "msg-q",
              "type": "tool",
              "callID": "call-q-1",
              "tool": "question",
              "state": {
                "status": "completed",
                "input": {
                  "questions": [
                    {
                      "header": "方案选择",
                      "question": "选 A 还是 B？",
                      "options": [
                        { "label": "A", "description": "选项A" },
                        { "label": "B", "description": "选项B" }
                      ]
                    }
                  ]
                },
                "output": "A"
              }
            }
          }
        }
        """);

    StreamMessage translated = translator.translate(toolCompleted);

    assertNotNull(translated, "completed question tool should NOT be skipped");
    assertEquals(StreamMessage.Types.QUESTION, translated.getType(),
        "should emit QUESTION type, not TOOL_UPDATE");
    assertEquals("completed", translated.getStatus());
    // Key assertion: partId must match the original question.asked partId,
    // NOT the tool part id, so the frontend updates the existing QuestionCard
    assertEquals("question-abc", translated.getPartId(),
        "partId must match original question.asked partId to avoid duplicate");
    assertEquals("sess-q", translated.getSessionId());
    assertEquals("msg-q", translated.getMessageId());
    assertNotNull(translated.getTool());
    assertEquals("question", translated.getTool().getToolName());
    assertEquals("call-q-1", translated.getTool().getToolCallId());
    assertEquals("A", translated.getTool().getOutput());
  }

  @Test
  @DisplayName("question wrapped output is normalized to raw answer")
  void normalizesWrappedQuestionOutput() throws Exception {
    var questionAsked = objectMapper.readTree("""
        {
          "type": "question.asked",
          "properties": {
            "id": "question-raw-answer",
            "sessionID": "sess-q",
            "questions": [
              {
                "header": "实现方案选择",
                "question": "实现方案选 A 还是 B？",
                "options": [
                  { "label": "A", "description": "只改最小范围" },
                  { "label": "B", "description": "做完整重构" }
                ]
              }
            ],
            "tool": {
              "callID": "call-q-raw-answer",
              "messageID": "msg-q"
            }
          }
        }
        """);
    translator.translate(questionAsked);

    var toolCompleted = objectMapper.readTree(
        """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "part-tool-raw-answer",
                  "sessionID": "sess-q",
                  "messageID": "msg-q",
                  "type": "tool",
                  "callID": "call-q-raw-answer",
                  "tool": "question",
                  "state": {
                    "status": "completed",
                    "input": {
                      "questions": [
                        {
                          "header": "实现方案选择",
                          "question": "实现方案选 A 还是 B？",
                          "options": [
                            { "label": "A", "description": "只改最小范围" },
                            { "label": "B", "description": "做完整重构" }
                          ]
                        }
                      ]
                    },
                    "output": "User has answered your questions: \\"实现方案选 A 还是 B？\\"=\\"123\\". You can now continue with the user's answers in mind."
                  }
                }
              }
            }
            """);

    StreamMessage translated = translator.translate(toolCompleted);

    assertNotNull(translated);
    assertNotNull(translated.getTool());
    assertEquals("123", translated.getTool().getOutput());
  }

  @Test
  @DisplayName("permission resolved event is mapped to permission.reply")
  void translatesPermissionResolvedEvent() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "permission.updated",
          "properties": {
            "sessionID": "sess-perm",
            "messageID": "msg-perm",
            "id": "perm-1",
            "type": "command",
            "title": "Run command",
            "metadata": {
              "command": "rm -rf /tmp/demo"
            },
            "status": {
              "type": "approved"
            },
            "response": "allow"
          }
        }
        """);

    StreamMessage translated = translator.translate(event);

    assertNotNull(translated);
    assertEquals(StreamMessage.Types.PERMISSION_REPLY, translated.getType());
    assertEquals("approved", translated.getStatus());
    assertNotNull(translated.getPermission());
    assertEquals("perm-1", translated.getPermission().getPermissionId());
    assertEquals("command", translated.getPermission().getPermType());
    assertEquals("once", translated.getPermission().getResponse());
    assertEquals("Run command", translated.getTitle());
  }

  @Test
  @DisplayName("permission status approved without response is still mapped to permission.reply")
  void translatesPermissionApprovedStatusWithoutResponse() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "permission.completed",
          "properties": {
            "sessionID": "sess-perm",
            "messageID": "msg-perm",
            "id": "perm-2",
            "permission": "command",
            "title": "Run command",
            "status": {
              "type": "approved"
            }
          }
        }
        """);

    StreamMessage translated = translator.translate(event);

    assertNotNull(translated);
    assertEquals(StreamMessage.Types.PERMISSION_REPLY, translated.getType());
    assertEquals("approved", translated.getStatus());
    assertNotNull(translated.getPermission());
    assertEquals("perm-2", translated.getPermission().getPermissionId());
    assertEquals("command", translated.getPermission().getPermType());
    assertNull(translated.getPermission().getResponse());
  }

  @Test
  @DisplayName("unresolved permission event is mapped to permission.ask")
  void translatesUnknownPermissionEventAsAskWhenUnresolved() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "permission.pending",
          "properties": {
            "sessionID": "sess-perm",
            "messageID": "msg-perm",
            "id": "perm-3",
            "type": "command",
            "title": "Run command",
            "status": {
              "type": "pending"
            }
          }
        }
        """);

    StreamMessage translated = translator.translate(event);

    assertNotNull(translated);
    assertEquals(StreamMessage.Types.PERMISSION_ASK, translated.getType());
    assertEquals("pending", translated.getStatus());
    assertNotNull(translated.getPermission());
    assertEquals("perm-3", translated.getPermission().getPermissionId());
    assertEquals("command", translated.getPermission().getPermType());
  }

  @Test
  @DisplayName("permission.replied with reply field is mapped to permission.reply with correct response")
  void translatesPermissionRepliedEvent() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "permission.replied",
          "properties": {
            "sessionID": "sess-perm",
            "requestID": "perm-replied-1",
            "reply": "once"
          }
        }
        """);

    StreamMessage translated = translator.translate(event);

    assertNotNull(translated);
    assertEquals(StreamMessage.Types.PERMISSION_REPLY, translated.getType());
    assertNotNull(translated.getPermission());
    assertEquals("perm-replied-1", translated.getPermission().getPermissionId());
    assertEquals("once", translated.getPermission().getResponse());
  }

  @Test
  @DisplayName("permission.replied with always reply is normalized to always")
  void translatesPermissionRepliedAlways() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "permission.replied",
          "properties": {
            "sessionID": "sess-perm",
            "requestID": "perm-replied-2",
            "reply": "always"
          }
        }
        """);

    StreamMessage translated = translator.translate(event);

    assertNotNull(translated);
    assertEquals(StreamMessage.Types.PERMISSION_REPLY, translated.getType());
    assertEquals("perm-replied-2", translated.getPermission().getPermissionId());
    assertEquals("always", translated.getPermission().getResponse());
  }

  @Test
  @DisplayName("question.replied is ignored (handled by message.part.updated)")
  void questionRepliedReturnsNull() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "question.replied",
          "properties": {
            "sessionID": "sess-q",
            "requestID": "q-replied-1",
            "answers": [{"answer": "Option A"}]
          }
        }
        """);

    assertNull(translator.translate(event));
  }

  @Test
  @DisplayName("question.rejected is ignored (handled by message.part.updated)")
  void questionRejectedReturnsNull() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "question.rejected",
          "properties": {
            "sessionID": "sess-q",
            "requestID": "q-rejected-1"
          }
        }
        """);

    assertNull(translator.translate(event));
  }
}
