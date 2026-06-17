# 全事件报文示例

本文件只做一件事：把当前项目内出现的事件报文形状列全。字段值是示例值，可以替换；字段名、嵌套结构、`type`、`protocol`、`properties` 才是契约重点。

如需理解每个事件如何被翻译，请读 [05-event-to-streammessage-mapping.md](./05-event-to-streammessage-mapping.md)。如需理解 miniapp 如何消费最终消息，请读 [07-message-type-lifecycle.md](./07-message-type-lifecycle.md)。

## 0. 覆盖清单

| 类别 | 覆盖内容 |
| --- | --- |
| GatewayMessage | `register/register_ok/register_rejected/heartbeat/invoke/tool_event/tool_done/tool_error/session_created/agent_online/agent_offline/status_query/status_response/permission_request/route_confirm/route_reject/im_push` |
| OpenCode/local | `message.updated/message.part.updated/message.part.delta/message.part.removed/session.status/session.idle/session.updated/session.error/permission.updated/permission.asked/permission.replied/question.asked`，并展开 `message.part.updated` 的 6 种 part type。 |
| cloud skill-provider | `text.delta/text.done/thinking.delta/thinking.done/tool.update/question/permission.ask/permission.reply/step.start/step.done/session.status/session.title/session.error` |
| CloudEventTranslator standard extension | `planning.delta/planning.done/file/searching/search_result/reference/ask_more` |
| assistant_square SSE | `planning/think/processStep/message/searching/searchResult/reference/askMore/error/ping/FINISH/[DONE]/unsupported messageType/unknown protocolType` |
| StreamMessage | miniapp/externalWs 最终下行的全部 `StreamMessage.type`。 |

## 1. GatewayMessage 示例

这些是 Skill Server、AI Gateway、agent/cloud 之间的中间协议，不直接暴露给 miniapp/externalWs。

```json
[
  {
    "type": "register",
    "deviceName": "dev-machine",
    "macAddress": "00-11-22-33-44-55",
    "os": "windows",
    "toolType": "opencode",
    "toolVersion": "1.0.0"
  },
  {
    "type": "register_ok"
  },
  {
    "type": "register_rejected",
    "reason": "duplicate_ak"
  },
  {
    "type": "heartbeat"
  },
  {
    "type": "invoke",
    "ak": "agent-ak",
    "welinkSessionId": "9000000001",
    "traceId": "trace-001",
    "action": "chat",
    "assistantAccount": "assistant-001",
    "businessTag": "default",
    "payload": {
      "text": "hello",
      "toolSessionId": "tool-session-1",
      "messageId": "msg-001",
      "sendUserAccount": "user-001"
    }
  },
  {
    "type": "tool_event",
    "toolSessionId": "tool-session-1",
    "messageId": "msg-001",
    "event": {
      "protocol": "cloud",
      "type": "text.delta",
      "properties": {
        "messageId": "msg-001",
        "partId": "text-1",
        "content": "he"
      }
    }
  },
  {
    "type": "tool_done",
    "toolSessionId": "tool-session-1",
    "messageId": "msg-001",
    "usage": {
      "input": 10,
      "output": 20
    }
  },
  {
    "type": "tool_error",
    "toolSessionId": "tool-session-1",
    "messageId": "msg-001",
    "error": "cloud timeout",
    "reason": "timeout"
  },
  {
    "type": "session_created",
    "welinkSessionId": "9000000001",
    "toolSessionId": "tool-session-1",
    "session": {
      "sessionId": "tool-session-1"
    }
  },
  {
    "type": "agent_online",
    "ak": "agent-ak",
    "toolType": "opencode",
    "toolVersion": "1.0.0"
  },
  {
    "type": "agent_offline",
    "ak": "agent-ak"
  },
  {
    "type": "status_query"
  },
  {
    "type": "status_response",
    "opencodeOnline": true
  },
  {
    "type": "permission_request",
    "toolSessionId": "tool-session-1",
    "messageId": "msg-001",
    "payload": {
      "permissionId": "perm-1",
      "permType": "command",
      "metadata": {
        "command": "pwd"
      }
    }
  },
  {
    "type": "route_confirm",
    "toolSessionId": "tool-session-1",
    "welinkSessionId": "9000000001",
    "source": "skill-server"
  },
  {
    "type": "route_reject",
    "toolSessionId": "tool-session-orphan",
    "source": "skill-server"
  },
  {
    "type": "im_push",
    "welinkSessionId": "9000000001",
    "payload": {
      "content": "push message"
    }
  }
]
```

## 2. OpenCode/local plugin 上游事件示例

OpenCode/local 协议在 plugin -> gateway 的 `gateway-schema` 边界不带 `protocol` 字段。

### 2.1 message.updated

```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-local-1",
      "sessionID": "ses-local-1",
      "role": "assistant",
      "time": {
        "created": 1773072823560,
        "completed": 1773072824560
      },
      "model": {
        "providerID": "bailian",
        "modelID": "kimi-k2.5"
      },
      "finish": "stop"
    }
  }
}
```

user 乱序补文本时也是同一事件：

```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-user-1",
      "sessionID": "ses-local-1",
      "role": "user",
      "time": {
        "created": 1773072823560
      }
    }
  }
}
```

### 2.2 message.part.updated：text

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt-text-1",
      "sessionID": "ses-local-1",
      "messageID": "msg-local-1",
      "type": "text",
      "text": "hello world"
    }
  }
}
```

### 2.3 message.part.updated：reasoning

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt-thinking-1",
      "sessionID": "ses-local-1",
      "messageID": "msg-local-1",
      "type": "reasoning",
      "text": "I should inspect the files first."
    }
  }
}
```

### 2.4 message.part.updated：tool

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt-tool-1",
      "sessionID": "ses-local-1",
      "messageID": "msg-local-1",
      "type": "tool",
      "tool": "bash",
      "callID": "call-tool-1",
      "state": {
        "status": "running",
        "title": "Run command",
        "output": "",
        "error": ""
      }
    }
  }
}
```

### 2.5 message.part.updated：tool question completed/error

`tool=question` 的 `pending/running` 不下发，`completed/error` 用于更新已有 question card。

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt-question-tool-1",
      "sessionID": "ses-local-1",
      "messageID": "msg-local-1",
      "type": "tool",
      "tool": "question",
      "callID": "call-question-1",
      "state": {
        "status": "completed",
        "output": "A"
      }
    }
  }
}
```

### 2.6 message.part.updated：step-start

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt-step-start-1",
      "sessionID": "ses-local-1",
      "messageID": "msg-local-1",
      "type": "step-start"
    }
  }
}
```

### 2.7 message.part.updated：step-finish

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt-step-finish-1",
      "sessionID": "ses-local-1",
      "messageID": "msg-local-1",
      "type": "step-finish",
      "tokens": {
        "input": 10,
        "output": 20
      },
      "cost": 0.02,
      "reason": "stop"
    }
  }
}
```

### 2.8 message.part.updated：file

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "prt-file-1",
      "sessionID": "ses-local-1",
      "messageID": "msg-local-1",
      "type": "file",
      "filename": "report.md",
      "url": "https://example.test/report.md",
      "mime": "text/markdown"
    }
  }
}
```

### 2.9 message.part.delta

```json
{
  "type": "message.part.delta",
  "properties": {
    "sessionID": "ses-local-1",
    "messageID": "msg-local-1",
    "partID": "prt-text-1",
    "field": "text",
    "delta": "hello"
  }
}
```

### 2.10 message.part.removed

```json
{
  "type": "message.part.removed",
  "properties": {
    "sessionID": "ses-local-1",
    "messageID": "msg-local-1",
    "partID": "prt-text-1"
  }
}
```

### 2.11 session.status

```json
{
  "type": "session.status",
  "properties": {
    "sessionID": "ses-local-1",
    "status": {
      "type": "busy"
    }
  }
}
```

### 2.12 session.idle

```json
{
  "type": "session.idle",
  "properties": {
    "sessionID": "ses-local-1"
  }
}
```

### 2.13 session.updated

```json
{
  "type": "session.updated",
  "properties": {
    "sessionID": "ses-local-1",
    "info": {
      "id": "ses-local-1",
      "title": "New title"
    }
  }
}
```

### 2.14 session.error

```json
{
  "type": "session.error",
  "properties": {
    "sessionID": "ses-local-1",
    "error": {
      "message": "model unavailable"
    }
  }
}
```

### 2.15 permission.updated：未 resolved

```json
{
  "type": "permission.updated",
  "properties": {
    "sessionID": "ses-local-1",
    "id": "perm-1",
    "messageID": "msg-local-1",
    "type": "command",
    "title": "Run command",
    "metadata": {
      "command": "pwd"
    },
    "status": "pending"
  }
}
```

### 2.16 permission.updated：resolved

```json
{
  "type": "permission.updated",
  "properties": {
    "sessionID": "ses-local-1",
    "id": "perm-1",
    "messageID": "msg-local-1",
    "type": "command",
    "response": "approved",
    "resolved": true
  }
}
```

### 2.17 permission.asked

```json
{
  "type": "permission.asked",
  "properties": {
    "sessionID": "ses-local-1",
    "id": "perm-1",
    "messageID": "msg-local-1",
    "type": "command",
    "title": "Run command",
    "metadata": {
      "command": "pwd"
    }
  }
}
```

### 2.18 permission.replied

```json
{
  "type": "permission.replied",
  "properties": {
    "sessionID": "ses-local-1",
    "requestID": "perm-1",
    "reply": "once"
  }
}
```

### 2.19 question.asked

```json
{
  "type": "question.asked",
  "properties": {
    "sessionID": "ses-local-1",
    "id": "question-1",
    "questions": [
      {
        "header": "Choose",
        "question": "Pick one?",
        "options": [
          {
            "label": "A"
          },
          {
            "label": "B",
            "description": "Beta"
          }
        ]
      }
    ],
    "tool": {
      "messageID": "msg-local-1",
      "callID": "call-question-1"
    }
  }
}
```

### 2.20 question.replied / question.rejected（兼容丢弃分支）

这两个事件不在当前 `gateway-schema` OpenCode 白名单里；`OpenCodeEventTranslator` 兼容识别后返回 null，避免重复创建 question card。

```json
{
  "type": "question.replied",
  "properties": {
    "sessionID": "ses-local-1",
    "requestID": "question-1",
    "answer": "A"
  }
}
```

```json
{
  "type": "question.rejected",
  "properties": {
    "sessionID": "ses-local-1",
    "requestID": "question-1",
    "reason": "cancelled"
  }
}
```

## 3. cloud skill-provider 上游事件示例

这些事件必须带 `protocol:"cloud"`。这是 plugin -> gateway 的 skill-provider schema 白名单。

```json
[
  {
    "protocol": "cloud",
    "type": "text.delta",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "text-1",
      "content": "he"
    }
  },
  {
    "protocol": "cloud",
    "type": "text.done",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "text-1",
      "content": "hello"
    }
  },
  {
    "protocol": "cloud",
    "type": "thinking.delta",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "thinking-1",
      "content": "I should check the contract."
    }
  },
  {
    "protocol": "cloud",
    "type": "thinking.done",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "thinking-1",
      "content": "I should check the contract."
    }
  },
  {
    "protocol": "cloud",
    "type": "tool.update",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "tool-1",
      "toolName": "bash",
      "toolCallId": "call-tool-1",
      "status": "running",
      "title": "Run command",
      "input": "pwd"
    }
  },
  {
    "protocol": "cloud",
    "type": "question",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "question-display-1",
      "questionId": "question-target-1",
      "toolCallId": "question-target-1",
      "questions": [
        {
          "header": "Choose",
          "question": "Pick one?",
          "options": [
            {
              "label": "A"
            },
            {
              "label": "B",
              "description": "Beta"
            }
          ]
        }
      ]
    }
  },
  {
    "protocol": "cloud",
    "type": "permission.ask",
    "properties": {
      "messageId": "msg-cloud-1",
      "partId": "permission-display-1",
      "permissionId": "permission-target-1",
      "permType": "command",
      "title": "Run command",
      "metadata": {
        "command": "pwd"
      }
    }
  },
  {
    "protocol": "cloud",
    "type": "permission.reply",
    "properties": {
      "permissionId": "permission-target-1",
      "response": "once",
      "permType": "command",
      "messageId": "msg-cloud-1",
      "partId": "permission-display-1"
    }
  },
  {
    "protocol": "cloud",
    "type": "step.start",
    "properties": {
      "messageId": "msg-cloud-1"
    }
  },
  {
    "protocol": "cloud",
    "type": "step.done",
    "properties": {
      "messageId": "msg-cloud-1",
      "tokens": {
        "input": 10,
        "output": 20
      },
      "cost": 0.02,
      "reason": "stop"
    }
  },
  {
    "protocol": "cloud",
    "type": "session.status",
    "properties": {
      "sessionStatus": "idle"
    }
  },
  {
    "protocol": "cloud",
    "type": "session.title",
    "properties": {
      "title": "New title"
    }
  },
  {
    "protocol": "cloud",
    "type": "session.error",
    "properties": {
      "error": "cloud error"
    }
  }
]
```

## 4. CloudEventTranslator standard extension 示例

这些事件是 SS `CloudEventTranslator` 支持的 standard 扩展；不是当前 plugin `gateway-schema` skill-provider 白名单。主要来源是 assistant_square decoder 或 SS 内部已归一事件。

```json
[
  {
    "type": "planning.delta",
    "properties": {
      "messageId": "msg-cloud-2",
      "partId": "planning-1",
      "content": "Plan step 1"
    }
  },
  {
    "type": "planning.done",
    "properties": {
      "messageId": "msg-cloud-2",
      "partId": "planning-1",
      "content": "Plan step 1"
    }
  },
  {
    "type": "file",
    "properties": {
      "messageId": "msg-cloud-2",
      "partId": "file-1",
      "fileName": "report.md",
      "fileUrl": "https://example.test/report.md",
      "fileMime": "text/markdown"
    }
  },
  {
    "type": "searching",
    "properties": {
      "messageId": "msg-cloud-2",
      "partId": "searching-1",
      "keywords": ["JDK8"]
    }
  },
  {
    "type": "search_result",
    "properties": {
      "messageId": "msg-cloud-2",
      "partId": "search-result-1",
      "searchResults": [
        {
          "index": "1",
          "title": "JDK 8 docs",
          "source": "web"
        }
      ]
    }
  },
  {
    "type": "reference",
    "properties": {
      "messageId": "msg-cloud-2",
      "partId": "reference-1",
      "references": [
        {
          "index": "1",
          "title": "Reference title",
          "source": "web",
          "url": "https://example.test/ref",
          "content": "Reference excerpt"
        }
      ]
    }
  },
  {
    "type": "ask_more",
    "properties": {
      "messageId": "msg-cloud-2",
      "partId": "ask-more-1",
      "askMoreQuestions": ["要不要继续查版本兼容性？"]
    }
  }
]
```

## 5. assistant_square SSE 示例

assistant_square SSE 的 `data:` 行是 JSON 字符串。`protocolType` 缺失、`standard`、`"5"` 都走 standard handler。

### 5.1 planning

```json
{
  "eventType": "planning",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "messageType": "PLANNING",
  "planning": "先查询资料。"
}
```

### 5.2 think

```json
{
  "eventType": "think",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "think": "需要确认协议字段。"
}
```

### 5.3 processStep

```json
{
  "eventType": "processStep",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "processStep": {
    "message": "正在检索"
  }
}
```

### 5.4 message TEXT

```json
{
  "eventType": "message",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "messageType": "TEXT",
  "messageBody": {
    "text": "hello"
  }
}
```

### 5.5 searching

```json
{
  "eventType": "searching",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "keywords": ["JDK8", "compatibility"]
}
```

### 5.6 searchResult

```json
{
  "eventType": "searchResult",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "results": [
    {
      "index": "1",
      "title": "JDK 8 docs",
      "source": "web"
    }
  ]
}
```

### 5.7 reference

```json
{
  "eventType": "reference",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "references": [
    {
      "index": "1",
      "title": "Reference title",
      "source": "web",
      "url": "https://example.test/ref",
      "content": "Reference excerpt"
    }
  ]
}
```

### 5.8 askMore

```json
{
  "eventType": "askMore",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "questions": ["还要继续查兼容性吗？"]
}
```

### 5.9 error

```json
{
  "eventType": "error",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "message": "assistant square error"
}
```

### 5.10 ping heartbeat

```json
{
  "eventType": "ping"
}
```

### 5.11 terminator

```text
FINISH
```

```text
[DONE]
```

### 5.12 nested data

root 的 `eventType/protocolType/code/message/error` 会补进内层 `data`。

```json
{
  "code": "200",
  "eventType": "message",
  "protocolType": "5",
  "data": {
    "messageId": "msg-as-1",
    "messageType": "TEXT",
    "messageBody": {
      "text": "hello"
    }
  }
}
```

### 5.13 unsupported messageType

这类事件会被丢弃；但如果是 stream 中第一个事件，Gateway 可能已经补了 `session.status busy` 和 `step.start`。

```json
{
  "eventType": "message",
  "protocolType": "5",
  "messageId": "msg-as-1",
  "messageType": "HTML",
  "messageBody": "<b>unsupported</b>"
}
```

### 5.14 unknown protocolType

```json
{
  "eventType": "message",
  "protocolType": "athena",
  "messageId": "msg-as-1",
  "messageType": "TEXT",
  "messageBody": "will be dropped"
}
```

## 6. 最终 StreamMessage 全类型示例

miniapp 和 externalWs 最终只消费这一层。

```json
[
  {
    "type": "text.delta",
    "seq": 1,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "text-1",
    "partSeq": 1,
    "content": "he"
  },
  {
    "type": "text.done",
    "seq": 2,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "text-1",
    "partSeq": 1,
    "content": "hello"
  },
  {
    "type": "thinking.delta",
    "seq": 3,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "thinking-1",
    "partSeq": 2,
    "content": "I should inspect"
  },
  {
    "type": "thinking.done",
    "seq": 4,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "thinking-1",
    "partSeq": 2,
    "content": "I should inspect the contract."
  },
  {
    "type": "tool.update",
    "seq": 5,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "tool-1",
    "partSeq": 3,
    "toolName": "bash",
    "toolCallId": "call-tool-1",
    "status": "running",
    "title": "Run command",
    "input": {
      "command": "pwd"
    }
  },
  {
    "type": "question",
    "seq": 6,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "question-1",
    "partSeq": 4,
    "toolName": "question",
    "toolCallId": "call-question-1",
    "status": "running",
    "header": "Choose",
    "question": "Pick one?",
    "options": ["A", "B"],
    "questionId": "question-1"
  },
  {
    "type": "file",
    "seq": 7,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "file-1",
    "partSeq": 5,
    "fileName": "report.md",
    "fileUrl": "https://example.test/report.md",
    "fileMime": "text/markdown"
  },
  {
    "type": "step.start",
    "seq": 8,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "role": "assistant",
    "sourceMessageId": "msg-1"
  },
  {
    "type": "step.done",
    "seq": 9,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "role": "assistant",
    "sourceMessageId": "msg-1",
    "tokens": {
      "input": 10,
      "output": 20
    },
    "cost": 0.02,
    "reason": "stop"
  },
  {
    "type": "session.status",
    "seq": 10,
    "welinkSessionId": "9000000001",
    "sessionStatus": "idle"
  },
  {
    "type": "session.title",
    "seq": 11,
    "welinkSessionId": "9000000001",
    "title": "New title"
  },
  {
    "type": "session.error",
    "seq": 12,
    "welinkSessionId": "9000000001",
    "error": "cloud error"
  },
  {
    "type": "permission.ask",
    "seq": 13,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "perm-1",
    "partSeq": 6,
    "permissionId": "perm-1",
    "permType": "command",
    "title": "Run command",
    "metadata": {
      "command": "pwd"
    },
    "status": "pending"
  },
  {
    "type": "permission.reply",
    "seq": 14,
    "welinkSessionId": "9000000001",
    "messageId": "msg-1",
    "sourceMessageId": "msg-1",
    "role": "assistant",
    "partId": "perm-1",
    "partSeq": 6,
    "permissionId": "perm-1",
    "permType": "command",
    "response": "once",
    "status": "completed"
  },
  {
    "type": "agent.online",
    "seq": 15,
    "welinkSessionId": "9000000001"
  },
  {
    "type": "agent.offline",
    "seq": 16,
    "welinkSessionId": "9000000001"
  },
  {
    "type": "message.user",
    "seq": 17,
    "welinkSessionId": "9000000001",
    "messageId": "user-msg-1",
    "messageSeq": 1,
    "role": "user",
    "content": "hello"
  },
  {
    "type": "planning.delta",
    "seq": 18,
    "welinkSessionId": "9000000001",
    "messageId": "msg-2",
    "sourceMessageId": "msg-2",
    "role": "assistant",
    "partId": "planning-1",
    "partSeq": 1,
    "content": "Plan step 1"
  },
  {
    "type": "planning.done",
    "seq": 19,
    "welinkSessionId": "9000000001",
    "messageId": "msg-2",
    "sourceMessageId": "msg-2",
    "role": "assistant",
    "partId": "planning-1",
    "partSeq": 1,
    "content": "Plan step 1"
  },
  {
    "type": "searching",
    "seq": 20,
    "welinkSessionId": "9000000001",
    "messageId": "msg-2",
    "sourceMessageId": "msg-2",
    "role": "assistant",
    "partId": "searching-1",
    "partSeq": 2,
    "keywords": ["JDK8"]
  },
  {
    "type": "search_result",
    "seq": 21,
    "welinkSessionId": "9000000001",
    "messageId": "msg-2",
    "sourceMessageId": "msg-2",
    "role": "assistant",
    "partId": "search-result-1",
    "partSeq": 3,
    "searchResults": [
      {
        "index": "1",
        "title": "JDK 8 docs",
        "source": "web"
      }
    ]
  },
  {
    "type": "reference",
    "seq": 22,
    "welinkSessionId": "9000000001",
    "messageId": "msg-2",
    "sourceMessageId": "msg-2",
    "role": "assistant",
    "partId": "reference-1",
    "partSeq": 4,
    "references": [
      {
        "index": "1",
        "title": "Reference title",
        "source": "web",
        "url": "https://example.test/ref",
        "content": "Reference excerpt"
      }
    ]
  },
  {
    "type": "ask_more",
    "seq": 23,
    "welinkSessionId": "9000000001",
    "messageId": "msg-2",
    "sourceMessageId": "msg-2",
    "role": "assistant",
    "partId": "ask-more-1",
    "partSeq": 5,
    "askMoreQuestions": ["要不要继续查兼容性？"]
  },
  {
    "type": "error",
    "seq": 24,
    "welinkSessionId": "9000000001",
    "error": "stream error"
  },
  {
    "type": "snapshot",
    "seq": 25,
    "welinkSessionId": "9000000001",
    "messages": [
      {
        "id": "msg-1",
        "role": "assistant",
        "content": "hello",
        "parts": []
      }
    ]
  },
  {
    "type": "streaming",
    "seq": 26,
    "welinkSessionId": "9000000001",
    "messageId": "msg-2",
    "sessionStatus": "busy",
    "parts": [
      {
        "partId": "text-1",
        "type": "text",
        "content": "partial",
        "isStreaming": true
      }
    ]
  }
]
```

## 7. 不下发/丢弃事件示例

这些示例用于解释“为什么抓到上游事件但 miniapp 没看到新消息”。

| 示例 | 行为 |
| --- | --- |
| `message.part.removed` | 只清理 OpenCode translator cache，不下发。 |
| `message.part.delta` 在 part type cache 建立前到达 | 丢弃。 |
| OpenCode `tool=question` 且 `status=pending/running` | 丢弃，由 `question.asked` 创建 question card。 |
| OpenCode `question.replied/question.rejected` | translator 兼容识别但不下发；完成态由 question tool completed/error 覆盖。 |
| cloud 未知 `event.type` | `CloudEventTranslator` 返回 null。 |
| plugin uplink 显式 `protocol` 不是 `"cloud"` | `gateway-schema` fail-closed。 |
| assistant_square `eventType=ping` | heartbeat，忽略。 |
| assistant_square 未知 `protocolType` | fallback handler 丢弃。 |
| assistant_square unsupported `messageType` | 该业务事件丢弃；可能已补 `session.status busy` / `step.start`。 |
