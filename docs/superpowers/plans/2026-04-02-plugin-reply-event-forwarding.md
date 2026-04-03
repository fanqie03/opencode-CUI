# 插件 reply 事件转发缺失修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 message-bridge 插件支持转发 `permission.replied`、`question.replied`、`question.rejected` 事件，并在服务端正确翻译和持久化，修复权限审批和问题回答刷新后状态丢失的 bug。

**Architecture:** 插件侧在白名单 + extractor 中注册三个新事件类型（纯透传），服务端 translator 新增 question 回复翻译方法并修复 permission.replied 的字段名提取。

**Tech Stack:** TypeScript (message-bridge 插件)，Java 21 / Spring Boot 3.4 (skill-server)

---

### Task 1: 插件白名单扩展

**Files:**
- Modify: `plugins/agent-plugin/plugins/message-bridge/src/contracts/upstream-events.ts`

- [ ] **Step 1: 在白名单数组中追加三个事件类型**

在 `SUPPORTED_UPSTREAM_EVENT_TYPES` 数组的 `'question.asked'` 之后，`] as const;` 之前，追加：

```ts
  'permission.replied',
  'question.replied',
  'question.rejected',
```

- [ ] **Step 2: 追加 SDK v2 类型导入**

将第二个 import 块（从 `@opencode-ai/sdk/v2` 导入的部分）改为：

```ts
import type {
  EventMessagePartDelta,
  EventPermissionAsked,
  EventPermissionReplied,
  EventQuestionAsked,
  EventQuestionReplied,
  EventQuestionRejected,
} from '@opencode-ai/sdk/v2' with { 'resolution-mode': 'import' };
```

- [ ] **Step 3: 追加类型别名导出**

在 `export type QuestionAskedEvent = EventQuestionAsked;` 之后追加：

```ts
export type PermissionRepliedEvent = EventPermissionReplied;
export type QuestionRepliedEvent = EventQuestionReplied;
export type QuestionRejectedEvent = EventQuestionRejected;
```

- [ ] **Step 4: 扩展 SupportedUpstreamEvent union**

在 `SupportedUpstreamEvent` 的 union 中，`| EventQuestionAsked;` 之前追加：

```ts
  | EventPermissionReplied
  | EventQuestionReplied
  | EventQuestionRejected
```

使最终 union 以 `| EventQuestionRejected;` 结尾。

- [ ] **Step 5: Commit**

```bash
git add plugins/agent-plugin/plugins/message-bridge/src/contracts/upstream-events.ts
git commit -m "feat(message-bridge): add permission.replied, question.replied, question.rejected to event whitelist"
```

---

### Task 2: 插件 Extractor 注册

**Files:**
- Modify: `plugins/agent-plugin/plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts`

- [ ] **Step 1: 追加类型导入**

在现有的 import 块（从 `./SupportedUpstreamEvents.js` 导入的部分）中，在 `type QuestionAskedEvent,` 之后追加：

```ts
  type PermissionRepliedEvent,
  type QuestionRepliedEvent,
  type QuestionRejectedEvent,
```

- [ ] **Step 2: 扩展 UpstreamExtraByType**

在 `UpstreamExtraByType` 类型的 `'question.asked': undefined;` 之后追加：

```ts
  'permission.replied': undefined;
  'question.replied': undefined;
  'question.rejected': undefined;
```

- [ ] **Step 3: 新增三个 extractCommon 函数**

在 `extractQuestionAskedCommon` 函数之后、`UPSTREAM_EVENT_EXTRACTORS` 对象之前，追加：

```ts
function extractPermissionRepliedCommon(event: PermissionRepliedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractQuestionRepliedCommon(event: QuestionRepliedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}

function extractQuestionRejectedCommon(event: QuestionRejectedEvent): ExtractResult<CommonUpstreamFields> {
  const sessionResult = requireNonEmptyString(
    event.properties.sessionID,
    event.type,
    'common',
    'properties.sessionID',
  );
  if (!sessionResult.ok) return sessionResult;
  return ok(buildCommon(event.type, sessionResult.value));
}
```

- [ ] **Step 4: 在 UPSTREAM_EVENT_EXTRACTORS 中注册**

在 `'question.asked': { extractCommon: extractQuestionAskedCommon, extractExtra: noExtra },` 之后追加：

```ts
  'permission.replied': { extractCommon: extractPermissionRepliedCommon, extractExtra: noExtra },
  'question.replied': { extractCommon: extractQuestionRepliedCommon, extractExtra: noExtra },
  'question.rejected': { extractCommon: extractQuestionRejectedCommon, extractExtra: noExtra },
```

- [ ] **Step 5: 新增测试 fixtures**

创建 `tests/fixtures/opencode-events/permission.replied.json`：

```json
{
  "type": "permission.replied",
  "properties": {
    "sessionID": "ses_permission_replied_1",
    "requestID": "perm_fixture_replied_1",
    "reply": "once"
  }
}
```

创建 `tests/fixtures/opencode-events/question.replied.json`：

```json
{
  "type": "question.replied",
  "properties": {
    "sessionID": "ses_question_replied_1",
    "requestID": "question_fixture_replied_1",
    "answers": [{"answer": "Option A"}]
  }
}
```

创建 `tests/fixtures/opencode-events/question.rejected.json`：

```json
{
  "type": "question.rejected",
  "properties": {
    "sessionID": "ses_question_rejected_1",
    "requestID": "question_fixture_rejected_1"
  }
}
```

- [ ] **Step 6: 运行插件测试**

Run: `cd plugins/agent-plugin/plugins/message-bridge && npm test`
Expected: 所有测试通过（包括 `registry covers every supported upstream event type` 测试，它校验 EXTRACTORS 和 SUPPORTED_TYPES 键一致）

- [ ] **Step 7: Commit**

```bash
git add plugins/agent-plugin/plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts
git add plugins/agent-plugin/plugins/message-bridge/tests/fixtures/opencode-events/permission.replied.json
git add plugins/agent-plugin/plugins/message-bridge/tests/fixtures/opencode-events/question.replied.json
git add plugins/agent-plugin/plugins/message-bridge/tests/fixtures/opencode-events/question.rejected.json
git commit -m "feat(message-bridge): register extractors for permission.replied, question.replied, question.rejected"
```

---

### Task 3: 服务端 translator 修复 permission.replied 字段提取

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`

- [ ] **Step 1: 修复 normalizePermissionResponse 追加 reply 字段**

在 `normalizePermissionResponse` 方法（第 494 行）中，当前代码：

```java
    private String normalizePermissionResponse(JsonNode props) {
        String raw = ProtocolUtils.firstNonBlank(
                props.path("response").asText(null),
                props.path("decision").asText(null));
        if (raw == null || raw.isBlank()) {
            raw = props.path("answer").asText(null);
        }
```

在 `raw = props.path("answer").asText(null);` 之后追加一行：

```java
        if (raw == null || raw.isBlank()) {
            raw = props.path("reply").asText(null);
        }
```

完整方法变为：

```java
    private String normalizePermissionResponse(JsonNode props) {
        String raw = ProtocolUtils.firstNonBlank(
                props.path("response").asText(null),
                props.path("decision").asText(null));
        if (raw == null || raw.isBlank()) {
            raw = props.path("answer").asText(null);
        }
        if (raw == null || raw.isBlank()) {
            raw = props.path("reply").asText(null);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw) {
            case "allow", "approved", "approve", "yes" -> "once";
            case "always_allow", "always-allow", "allow_always" -> "always";
            case "deny", "denied", "reject", "rejected", "no" -> "reject";
            default -> raw;
        };
    }
```

- [ ] **Step 2: 修复 translatePermission 追加 requestID fallback**

在 `translatePermission` 方法（第 440-452 行）中，当前 permissionId 提取：

```java
                .permissionId(props.path("id").asText(null))
```

改为：

```java
                .permissionId(ProtocolUtils.firstNonBlank(
                        props.path("id").asText(null),
                        props.path("requestID").asText(null)))
```

- [ ] **Step 3: 新增测试 — permission.replied 事件翻译**

在 `OpenCodeEventTranslatorTest.java` 的最后一个测试之后，追加：

```java
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
```

- [ ] **Step 4: 编译并运行测试**

Run: `cd skill-server && mvn compile test -pl . -Dtest=OpenCodeEventTranslatorTest -q`
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java
git commit -m "fix(skill-server): fix permission.replied field extraction (reply/requestID fallback)"
```

---

### Task 4: 服务端 translator 新增 question.replied / question.rejected 翻译

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java`

- [ ] **Step 1: 在 translate 方法的 switch 中追加 case**

在 `case "question.asked" -> translateQuestionAsked(event);`（第 61 行）之后追加：

```java
            case "question.replied" -> translateQuestionReplied(event);
            case "question.rejected" -> translateQuestionRejected(event);
```

- [ ] **Step 2: 新增 translateQuestionReplied 方法**

在 `translateQuestionAsked` 方法（第 553 行 `}` 结束）之后，`baseBuilder` 方法之前，追加：

```java
    private StreamMessage translateQuestionReplied(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        String requestId = props.path("requestID").asText(null);

        // 尝试解析 answers 数组为 output 字符串
        String output = null;
        JsonNode answers = props.path("answers");
        if (answers.isArray() && !answers.isEmpty()) {
            try {
                output = objectMapper.writeValueAsString(answers);
            } catch (Exception e) {
                log.warn("Failed to serialize question answers: {}", e.getMessage());
            }
        }

        return messageBuilder(StreamMessage.Types.QUESTION, sessionId, null)
                .tool(ToolInfo.builder()
                        .toolName("question")
                        .toolCallId(requestId)
                        .output(output)
                        .build())
                .status("completed")
                .build();
    }

    private StreamMessage translateQuestionRejected(JsonNode event) {
        JsonNode props = event.path("properties");
        String sessionId = props.path("sessionID").asText(null);
        String requestId = props.path("requestID").asText(null);

        return messageBuilder(StreamMessage.Types.QUESTION, sessionId, null)
                .tool(ToolInfo.builder()
                        .toolName("question")
                        .toolCallId(requestId)
                        .build())
                .status("error")
                .build();
    }
```

注意：`objectMapper` 是 `OpenCodeEventTranslator` 的已有字段（在构造函数中注入）。

- [ ] **Step 3: 确认 objectMapper 字段存在**

检查 `OpenCodeEventTranslator` 类是否已有 `objectMapper` 字段。如果没有，需要在构造函数中添加。通过搜索确认：

Run: `grep -n "objectMapper" skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java | head -5`

如果不存在，需要添加字段和构造函数参数。

- [ ] **Step 4: 新增测试**

在 `OpenCodeEventTranslatorTest.java` 中追加：

```java
  @Test
  @DisplayName("question.replied is mapped to QUESTION with completed status")
  void translatesQuestionRepliedEvent() throws Exception {
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

    StreamMessage translated = translator.translate(event);

    assertNotNull(translated);
    assertEquals(StreamMessage.Types.QUESTION, translated.getType());
    assertEquals("completed", translated.getStatus());
    assertNotNull(translated.getTool());
    assertEquals("question", translated.getTool().getToolName());
    assertEquals("q-replied-1", translated.getTool().getToolCallId());
    assertNotNull(translated.getTool().getOutput());
  }

  @Test
  @DisplayName("question.rejected is mapped to QUESTION with error status")
  void translatesQuestionRejectedEvent() throws Exception {
    var event = objectMapper.readTree("""
        {
          "type": "question.rejected",
          "properties": {
            "sessionID": "sess-q",
            "requestID": "q-rejected-1"
          }
        }
        """);

    StreamMessage translated = translator.translate(event);

    assertNotNull(translated);
    assertEquals(StreamMessage.Types.QUESTION, translated.getType());
    assertEquals("error", translated.getStatus());
    assertNotNull(translated.getTool());
    assertEquals("question", translated.getTool().getToolName());
    assertEquals("q-rejected-1", translated.getTool().getToolCallId());
  }
```

- [ ] **Step 5: 编译并运行测试**

Run: `cd skill-server && mvn compile test -pl . -Dtest=OpenCodeEventTranslatorTest -q`
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java
git add skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java
git commit -m "feat(skill-server): add question.replied/rejected translation in OpenCodeEventTranslator"
```

---

### Task 5: 手动验证

- [ ] **Step 1: 构建并重启插件和服务端**

1. 构建插件：`cd plugins/agent-plugin/plugins/message-bridge && npm run build`
2. 重启 OpenCode（使插件生效）
3. 重启 skill-server（使 translator 改动生效）

- [ ] **Step 2: 验证 permission.replied 转发和持久化**

1. 在主会话触发需要多个 permission 的 tool（如 write 到外部目录，触发 external_directory + edit）
2. 逐一点击审批
3. 检查插件日志无 `unsupported_event` WARN for `permission.replied`
4. 刷新页面 → 确认所有 permission 显示"已处理"
5. 查询数据库确认 `tool_status=completed, tool_output` 不为 NULL

- [ ] **Step 3: 验证 question.replied 转发和持久化**

1. 在主会话触发 question
2. 回答问题
3. 刷新页面 → 确认 question 显示已回答

- [ ] **Step 4: Subagent 回归测试**

1. 在有 subagent 的会话中触发权限请求和问题
2. 确认行为正常
