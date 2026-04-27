# extParameters 嵌套化 + businessExtParam 跨入口透传 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给云端助理协议报文 `extParameters` 字段加 `businessExtParam`（业务透传）+ `platformExtParam`（平台占位 `{}`）两个嵌套子字段，IM/external/miniapp 三入口的 chat/question_reply/permission_reply 都新增 `businessExtParam` 入参，最终在 `BusinessScopeStrategy.buildInvoke` 唯一汇聚组装到云端报文。

**Architecture:** P1 透传式架构。上游纯透传（DTO → controller → service → payload string）不感知 scope；下游唯一组装点 `BusinessScopeStrategy.buildInvoke`。Personal scope 路径不消费 / 不剥离 ready 直发情况；任意 scope 的 pending/replay/重发路径接受 ext 丢失（D19）。

**Tech Stack:** Java 21 record + Lombok `@Data`、Spring Boot 3.4、Jackson `JsonNode`、JUnit 5 + Mockito、MyBatis（不涉及）。

---

## 实施顺序与依赖

```
Task 1: PayloadBuilder 工具层（基础，无依赖）
   ↓
Task 2: BusinessScopeStrategy 汇聚层（依赖 Task 1）
   ↓
Task 3: ImMessageRequest record + ImInboundController（依赖 Task 5）
Task 4: ExternalInboundController（依赖 Task 5）
Task 5: InboundProcessingService 签名 + dispatchChatToGateway（依赖 Task 1）
Task 6: ImSessionManager.createSessionAsync（依赖 Task 1, Task 5）
Task 7: SkillMessageController（依赖 Task 1）
   ↓
Task 8: 集成测试 + CloudRequestBuilderTest 新用例
   ↓
Task 9: 协议文档替换
```

> Task 1 必须先做（其他任务依赖）；Task 2 独立可并行；Task 3-7 都依赖 Task 1 + Task 5。Task 5 必须在 Task 3-7 之前。

---

## 通用约定

**包前缀**（所有 Java 类的根包）：`com.opencode.cui.skill`
**测试运行命令**：`mvn -pl skill-server test -DfailIfNoTests=false`
**单测运行命令**：`mvn -pl skill-server test -Dtest=ClassName -DfailIfNoTests=false`
**编译命令**：`mvn -pl skill-server compile`

**JsonNode import**：`import com.fasterxml.jackson.databind.JsonNode;`

---

# Task 1: PayloadBuilder.buildPayloadWithObjects 重载

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/PayloadBuilder.java`
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/PayloadBuilderTest.java`

- [ ] **Step 1: 写 PayloadBuilderTest 5 用例**

新建文件 `PayloadBuilderTest.java`：

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PayloadBuilderTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("buildPayloadWithObjects: String value 写入")
    void stringValueWritten() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", "v");
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("v", node.get("k").asText());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: JsonNode object 直接 set 不二次序列化")
    void jsonNodeObjectSetDirectly() throws Exception {
        ObjectNode inner = objectMapper.createObjectNode();
        inner.put("a", 1);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", inner);
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertTrue(node.get("k").isObject());
        assertEquals(1, node.get("k").get("a").asInt());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: Map/POJO value 走 valueToTree")
    void mapValueGoesValueToTree() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", Map.of("a", 1, "b", "x"));
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertTrue(node.get("k").isObject());
        assertEquals(1, node.get("k").get("a").asInt());
        assertEquals("x", node.get("k").get("b").asText());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: null value 跳过 key 不出现")
    void nullValueSkipped() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", null);
        fields.put("x", "y");
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertFalse(node.has("k"));
        assertEquals("y", node.get("x").asText());
    }

    @Test
    @DisplayName("buildPayloadWithObjects: NullNode 与 null 等价跳过")
    void nullNodeEquivalentToNull() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("k", objectMapper.nullNode());
        fields.put("x", "y");
        String json = PayloadBuilder.buildPayloadWithObjects(objectMapper, fields);
        JsonNode node = objectMapper.readTree(json);
        assertFalse(node.has("k"));
        assertEquals("y", node.get("x").asText());
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `mvn -pl skill-server test -Dtest=PayloadBuilderTest -DfailIfNoTests=false`
Expected: 编译失败 — `buildPayloadWithObjects` 方法不存在

- [ ] **Step 3: 实现 PayloadBuilder.buildPayloadWithObjects**

修改 `PayloadBuilder.java`，**保留旧 `buildPayload` 不动**，文件末尾追加：

```java
    /**
     * 支持嵌套对象 value 的 payload 构建工具。
     * 处理规则：
     * - null 跳过（key 不写入）
     * - JsonNode.isNull() 跳过（NullNode 与 null 等价）
     * - JsonNode 其他形态（含 ObjectNode/ArrayNode/TextNode 等）→ node.set
     * - String → node.put
     * - 其他对象 → objectMapper.valueToTree
     *
     * <p>本重载用于 chat / question_reply / permission_reply 三个 action 的 payload 组装，
     * 透传 businessExtParam（自由 JSON 对象）所需。
     */
    public static String buildPayloadWithObjects(ObjectMapper objectMapper, Map<String, Object> fields) {
        ObjectNode node = objectMapper.createObjectNode();
        fields.forEach((k, v) -> {
            if (v == null) return;
            if (v instanceof JsonNode jn) {
                if (jn.isNull()) return;
                node.set(k, jn);
            } else if (v instanceof String s) {
                node.put(k, s);
            } else {
                node.set(k, objectMapper.valueToTree(v));
            }
        });
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload with objects: {}", e.getMessage());
            return "{}";
        }
    }
```

文件顶部 imports 确认含 `import com.fasterxml.jackson.databind.JsonNode;`，如缺失则补上。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=PayloadBuilderTest -DfailIfNoTests=false`
Expected: 5 用例全 PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/PayloadBuilder.java skill-server/src/test/java/com/opencode/cui/skill/service/PayloadBuilderTest.java
git commit -m "feat(skill-server): add PayloadBuilder.buildPayloadWithObjects overload for nested JSON value"
```

---

# Task 2: BusinessScopeStrategy 汇聚层

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java`

- [ ] **Step 1: 在 BusinessScopeStrategyTest 增 9 个新用例**

打开 `BusinessScopeStrategyTest.java`，在已有用例之外追加（保持 `@Nested` 分组风格，分两组）：

**透传成功组（6 用例）**：

```java
    @Nested
    @DisplayName("businessExtParam 透传 — 6 个 action × {含/缺省} 用例")
    class BusinessExtParamPassthrough {

        @Test
        @DisplayName("chat 含 businessExtParam → 透传到 extParameters.businessExtParam")
        void chatWithBusinessExtParam() throws Exception {
            String payload = "{\"text\":\"hi\",\"businessExtParam\":{\"a\":1,\"k\":[1,2]},\"toolSessionId\":\"cloud-001\",\"assistantAccount\":\"asst-1\",\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "chat");
            JsonNode ext = result.get("extParameters");
            assertNotNull(ext);
            assertTrue(ext.get("businessExtParam").isObject());
            assertEquals(1, ext.get("businessExtParam").get("a").asInt());
            assertTrue(ext.get("businessExtParam").get("k").isArray());
            assertTrue(ext.get("platformExtParam").isObject());
            assertEquals(0, ext.get("platformExtParam").size());
        }

        @Test
        @DisplayName("chat 缺省 businessExtParam → extParameters.businessExtParam 兜底为 {}")
        void chatWithoutBusinessExtParam() throws Exception {
            String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-001\",\"assistantAccount\":\"asst-1\",\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "chat");
            JsonNode ext = result.get("extParameters");
            assertTrue(ext.get("businessExtParam").isObject());
            assertEquals(0, ext.get("businessExtParam").size());
            assertTrue(ext.get("platformExtParam").isObject());
            assertEquals(0, ext.get("platformExtParam").size());
        }

        @Test
        @DisplayName("question_reply 含 businessExtParam → 透传")
        void questionReplyWithBusinessExtParam() throws Exception {
            String payload = "{\"answer\":\"ok\",\"toolCallId\":\"tc-1\",\"businessExtParam\":{\"q\":\"x\"},\"toolSessionId\":\"cloud-001\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "question_reply");
            JsonNode ext = result.get("extParameters");
            assertEquals("x", ext.get("businessExtParam").get("q").asText());
            assertEquals(0, ext.get("platformExtParam").size());
        }

        @Test
        @DisplayName("question_reply 缺省 → 兜底 {}")
        void questionReplyWithoutBusinessExtParam() throws Exception {
            String payload = "{\"answer\":\"ok\",\"toolCallId\":\"tc-1\",\"toolSessionId\":\"cloud-001\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "question_reply");
            JsonNode ext = result.get("extParameters");
            assertEquals(0, ext.get("businessExtParam").size());
        }

        @Test
        @DisplayName("permission_reply 含 businessExtParam → 透传")
        void permissionReplyWithBusinessExtParam() throws Exception {
            String payload = "{\"permissionId\":\"p-1\",\"response\":\"once\",\"businessExtParam\":{\"p\":true},\"toolSessionId\":\"cloud-001\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "permission_reply");
            JsonNode ext = result.get("extParameters");
            assertTrue(ext.get("businessExtParam").get("p").asBoolean());
        }

        @Test
        @DisplayName("permission_reply 缺省 → 兜底 {}")
        void permissionReplyWithoutBusinessExtParam() throws Exception {
            String payload = "{\"permissionId\":\"p-1\",\"response\":\"once\",\"toolSessionId\":\"cloud-001\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "permission_reply");
            JsonNode ext = result.get("extParameters");
            assertEquals(0, ext.get("businessExtParam").size());
        }
    }
```

**异常分支组（3 用例）**：

```java
    @Nested
    @DisplayName("businessExtParam 异常 — 类型错误 / payload 解析失败兜底 3 用例")
    class BusinessExtParamFallback {

        @Test
        @DisplayName("businessExtParam 是字符串（非 object） → 兜底 {}")
        void businessExtParamAsString() throws Exception {
            String payload = "{\"text\":\"hi\",\"businessExtParam\":\"abc\",\"toolSessionId\":\"cloud-001\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "chat");
            JsonNode ext = result.get("extParameters");
            assertTrue(ext.get("businessExtParam").isObject());
            assertEquals(0, ext.get("businessExtParam").size());
        }

        @Test
        @DisplayName("businessExtParam 是数组 → 兜底 {}")
        void businessExtParamAsArray() throws Exception {
            String payload = "{\"text\":\"hi\",\"businessExtParam\":[1,2,3],\"toolSessionId\":\"cloud-001\"}";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "chat");
            JsonNode ext = result.get("extParameters");
            assertTrue(ext.get("businessExtParam").isObject());
            assertEquals(0, ext.get("businessExtParam").size());
        }

        @Test
        @DisplayName("payload 整体非法 JSON → 不抛异常，兜底 {}")
        void payloadInvalidJson() throws Exception {
            String payload = "not-a-json";
            ObjectNode result = invokeBuildAndExtractCloudRequest(payload, "chat");
            JsonNode ext = result.get("extParameters");
            assertTrue(ext.get("businessExtParam").isObject());
            assertEquals(0, ext.get("businessExtParam").size());
        }
    }
```

在 test class 内私有 helper（如已存在则复用，否则新增）：

```java
    /**
     * 调用 buildInvoke 并从其返回的 invoke message 字符串中提取 cloudRequest 节点。
     * 假定 buildInvoke 把 cloudRequest 放到 message.payload.cloudRequest（与现有实现一致）。
     */
    private ObjectNode invokeBuildAndExtractCloudRequest(String commandPayload, String action) throws Exception {
        when(info.getAppId()).thenReturn("app-001");
        when(info.getAssistantScope()).thenReturn("business");
        when(cloudRequestBuilder.buildCloudRequest(eq("app-001"), any())).thenAnswer(inv -> {
            CloudRequestContext ctx = inv.getArgument(1);
            ObjectNode req = objectMapper.createObjectNode();
            req.put("type", ctx.getContentType() != null ? ctx.getContentType() : "text");
            req.put("content", ctx.getContent());
            req.set("extParameters", objectMapper.valueToTree(ctx.getExtParameters()));
            return req;
        });

        InvokeCommand command = new InvokeCommand("ak-1", "u-1", "1", action, commandPayload);
        String json = strategy.buildInvoke(command, info);
        ObjectNode message = (ObjectNode) objectMapper.readTree(json);
        return (ObjectNode) message.get("payload").get("cloudRequest");
    }
```

> ⚠ 现有 `BusinessScopeStrategyTest` 已有 mock `cloudRequestBuilder` / `info` / 实例化 `strategy` 的脚手架。如果现有测试用 `verify(cloudRequestBuilder).buildCloudRequest(...)` + `ArgumentCaptor` 风格，可在新用例里改用 ArgumentCaptor 捕 `CloudRequestContext` 然后断言 `ctx.getExtParameters()`，替代上面 thenAnswer 的写法。两种风格都可，按已有用例风格保持一致即可。

- [ ] **Step 2: 运行测试确认 9 用例失败**

Run: `mvn -pl skill-server test -Dtest=BusinessScopeStrategyTest -DfailIfNoTests=false`
Expected: 9 个新用例 FAIL（断言 `extParameters` 不为 null 时失败，因为现有 buildInvoke 没填该字段）

- [ ] **Step 3: 修改 BusinessScopeStrategy.buildInvoke 组装 extParameters**

打开 `BusinessScopeStrategy.java`，在 `buildInvoke` 方法内构造 `CloudRequestContext` **之前**插入：

```java
        // 取业务扩展参数（缺省 / 异常 → null，由下方兜底 {}）
        com.fasterxml.jackson.databind.JsonNode bep = extractObjectField(command.payload(), "businessExtParam");

        // 组装 extParameters：保证两个子字段永远存在且为 JSON object
        java.util.Map<String, Object> extParameters = new java.util.LinkedHashMap<>();
        extParameters.put("businessExtParam",
                bep != null ? bep : objectMapper.createObjectNode());
        extParameters.put("platformExtParam", objectMapper.createObjectNode());
```

然后在 `CloudRequestContext.builder()` 链式调用末位加 `.extParameters(extParameters)`，即原代码：

```java
        CloudRequestContext context = CloudRequestContext.builder()
                .content(content)
                .contentType("text")
                .topicId(toolSessionId)
                .assistantAccount(extractField(command.payload(), "assistantAccount"))
                .sendUserAccount(extractField(command.payload(), "sendUserAccount"))
                .imGroupId(extractField(command.payload(), "imGroupId"))
                .messageId(extractField(command.payload(), "messageId"))
                .clientLang("zh")
                .build();
```

改为：

```java
        CloudRequestContext context = CloudRequestContext.builder()
                .content(content)
                .contentType("text")
                .topicId(toolSessionId)
                .assistantAccount(extractField(command.payload(), "assistantAccount"))
                .sendUserAccount(extractField(command.payload(), "sendUserAccount"))
                .imGroupId(extractField(command.payload(), "imGroupId"))
                .messageId(extractField(command.payload(), "messageId"))
                .clientLang("zh")
                .extParameters(extParameters)               // ← 新增
                .build();
```

在 `extractField` 私有方法之后追加新私有 helper：

```java
    /**
     * 从 payload string 中安全提取嵌套 JSON object 字段。
     * 返 null 触发上层兜底为 {}：
     * - payload null/blank → null
     * - readTree 失败 → null + DEBUG 日志
     * - 字段缺失 / NullNode → null
     * - 字段非 object（string/array/number/bool）→ null + WARN 日志
     */
    private com.fasterxml.jackson.databind.JsonNode extractObjectField(String payload, String fieldName) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(payload);
            com.fasterxml.jackson.databind.JsonNode field = node.path(fieldName);
            if (field.isMissingNode() || field.isNull()) {
                return null;
            }
            if (!field.isObject()) {
                log.warn("[WARN] {} is not a JSON object, treating as empty: actualType={}, value={}",
                        fieldName, field.getNodeType(), field);
                return null;
            }
            return field;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("Failed to parse payload for object field extraction: field={}, error={}",
                    fieldName, e.getMessage());
            return null;
        }
    }
```

> ⚠ `extractField` 老方法保持完全不动；新增 `extractObjectField` 是独立私有方法。

- [ ] **Step 4: 运行测试确认 9 用例通过**

Run: `mvn -pl skill-server test -Dtest=BusinessScopeStrategyTest -DfailIfNoTests=false`
Expected: 全部用例 PASS（含原有用例与 9 个新用例）

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java
git commit -m "feat(skill-server): assemble extParameters with businessExtParam/platformExtParam in BusinessScopeStrategy"
```

---

# Task 3: ImMessageRequest record + ImInboundController

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ImInboundControllerTest.java`

> 依赖：Task 5 必须先完成，因为 `processChat` 需先扩签名。

- [ ] **Step 1: 修改 ImMessageRequest record，末位追加字段**

打开 `ImMessageRequest.java`，导入 `JsonNode`，record 末位加字段：

```java
package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record ImMessageRequest(
        String businessDomain,
        String sessionType,
        String sessionId,
        String assistantAccount,
        String senderUserAccount,
        String content,
        String msgType,
        String imageUrl,
        List<ChatMessage> chatHistory,
        JsonNode businessExtParam) {       // ← 末位新增

    public boolean isTextMessage() {
        return msgType == null || msgType.isBlank() || "text".equalsIgnoreCase(msgType);
    }

    public record ChatMessage(
            String senderAccount,
            String senderName,
            String content,
            long timestamp) {
    }
}
```

- [ ] **Step 2: 修复 ImInboundControllerTest 7 处 positional 构造**

打开 `ImInboundControllerTest.java`，line 42 / 66 / 86 / 113 / 126 / 139 / 152 共 7 处 `new ImMessageRequest(...)` positional 构造**末位补 null**。例如：

原来：
```java
ImMessageRequest request = new ImMessageRequest(
        "im", "direct", "g-1", "asst-1", "u-1", "hi", "text", null, null);
```

改为：
```java
ImMessageRequest request = new ImMessageRequest(
        "im", "direct", "g-1", "asst-1", "u-1", "hi", "text", null, null,
        null);   // ← 末位 businessExtParam=null（向后兼容）
```

7 处全部按同样模式补 null。

- [ ] **Step 3: 在 ImInboundControllerTest 增 2 用例**

测试类 imports 需补：

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
```

新增 2 个测试方法（放在已有测试之后，保持 class 结构）：

```java
    @Test
    @DisplayName("receiveMessage 透传 businessExtParam 到 processChat")
    void receiveMessagePassesBusinessExtParam() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"key\":\"v\"}");
        ImMessageRequest request = new ImMessageRequest(
                "im", "direct", "g-1", "asst-1", "u-1", "hello", "text", null, null,
                bep);

        when(processingService.processChat(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(),
                anyString(), any()))
                .thenReturn(InboundProcessingService.InboundResult.ok("g-1", "1"));

        controller.receiveMessage(request);

        ArgumentCaptor<JsonNode> bepCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(processingService).processChat(
                eq("im"), eq("direct"), eq("g-1"), eq("asst-1"),
                eq("u-1"), eq("hello"), eq("text"), isNull(), isNull(),
                eq("IM"), bepCaptor.capture());
        assertEquals("v", bepCaptor.getValue().get("key").asText());
    }

    @Test
    @DisplayName("receiveMessage 缺省 businessExtParam → service 收到 null")
    void receiveMessageWithoutBusinessExtParam() {
        ImMessageRequest request = new ImMessageRequest(
                "im", "direct", "g-1", "asst-1", "u-1", "hello", "text", null, null,
                null);

        when(processingService.processChat(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(),
                anyString(), any()))
                .thenReturn(InboundProcessingService.InboundResult.ok("g-1", "1"));

        controller.receiveMessage(request);

        verify(processingService).processChat(
                eq("im"), eq("direct"), eq("g-1"), eq("asst-1"),
                eq("u-1"), eq("hello"), eq("text"), isNull(), isNull(),
                eq("IM"), isNull());
    }
```

> ⚠ 上述 `processChat` 签名以末位 `JsonNode businessExtParam` 为准（与 Task 5 一致）。如果现有 mock 使用 `eq(null)` 而非 `isNull()`，按 mock 风格保持。

- [ ] **Step 4: 修改 ImInboundController.receiveMessage 透传**

打开 `ImInboundController.java`，line 61-71 把 `processingService.processChat(...)` 调用末位追加 `request.businessExtParam()`：

```java
        InboundResult result = processingService.processChat(
                request.businessDomain(),
                request.sessionType(),
                request.sessionId(),
                request.assistantAccount(),
                request.senderUserAccount(),
                request.content(),
                request.msgType(),
                request.imageUrl(),
                request.chatHistory(),
                "IM",
                request.businessExtParam());     // ← 新增
```

INFO 日志（line 44-50）扩展为也打印 `businessExtParam`：

```java
        log.info(
                "[ENTRY] ImInboundController.receiveMessage: domain={}, sessionType={}, sessionId={}, assistant={}, msgType={}, businessExtParam={}",
                request != null ? request.businessDomain() : null,
                request != null ? request.sessionType() : null,
                request != null ? request.sessionId() : null,
                request != null ? request.assistantAccount() : null,
                request != null ? request.msgType() : null,
                request != null ? request.businessExtParam() : null);
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=ImInboundControllerTest -DfailIfNoTests=false`
Expected: 全部用例 PASS（原 7 用例 + 新 2 用例）

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java skill-server/src/test/java/com/opencode/cui/skill/controller/ImInboundControllerTest.java
git commit -m "feat(skill-server): pass businessExtParam through IM inbound chat path"
```

---

# Task 4: ExternalInboundController

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java`

> 依赖：Task 5 必须先完成。

- [ ] **Step 1: ExternalInvokeRequest 加信封顶级字段**

打开 `ExternalInvokeRequest.java`，加 `JsonNode businessExtParam` 字段（与 senderUserAccount 同位置 — 信封顶级）：

```java
package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ExternalInvokeRequest {

    private String action;
    private String businessDomain;
    private String sessionType;
    private String sessionId;
    private String assistantAccount;
    private String senderUserAccount;

    /** 业务扩展参数（信封顶级；chat/question_reply/permission_reply 三个 action 透传到云端） */
    private JsonNode businessExtParam;     // ← 新增

    /** action 专属数据 */
    private JsonNode payload;

    // ... payloadString 方法不变
    public String payloadString(String field) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return null;
        }
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
```

- [ ] **Step 2: 在 ExternalInboundControllerTest 增 3 用例**

打开 `ExternalInboundControllerTest.java`，imports 补 `JsonNode` / `ArgumentCaptor`，新增：

```java
    @Test
    @DisplayName("invoke chat 透传信封 businessExtParam 到 processChat")
    void invokeChatPassesBusinessExtParam() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setAction("chat");
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("ext-1");
        request.setAssistantAccount("asst-1");
        request.setSenderUserAccount("u-1");
        request.setBusinessExtParam(om.readTree("{\"k\":\"v\"}"));
        request.setPayload(om.readTree("{\"content\":\"hi\",\"msgType\":\"text\"}"));

        when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundProcessingService.InboundResult.ok("ext-1", "1"));

        controller.invoke(request);

        ArgumentCaptor<JsonNode> bep = ArgumentCaptor.forClass(JsonNode.class);
        verify(processingService).processChat(
                eq("im"), eq("direct"), eq("ext-1"), eq("asst-1"),
                eq("u-1"), eq("hi"), eq("text"), isNull(), isNull(),
                eq("EXTERNAL"), bep.capture());
        assertEquals("v", bep.getValue().get("k").asText());
    }

    @Test
    @DisplayName("invoke question_reply 透传 businessExtParam")
    void invokeQuestionReplyPassesBusinessExtParam() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setAction("question_reply");
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("ext-1");
        request.setAssistantAccount("asst-1");
        request.setSenderUserAccount("u-1");
        request.setBusinessExtParam(om.readTree("{\"q\":\"x\"}"));
        request.setPayload(om.readTree("{\"content\":\"reply\",\"toolCallId\":\"tc-1\"}"));

        when(processingService.processQuestionReply(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundProcessingService.InboundResult.ok("ext-1", "1"));

        controller.invoke(request);

        ArgumentCaptor<JsonNode> bep = ArgumentCaptor.forClass(JsonNode.class);
        verify(processingService).processQuestionReply(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq("EXTERNAL"), bep.capture());
        assertEquals("x", bep.getValue().get("q").asText());
    }

    @Test
    @DisplayName("invoke permission_reply 透传 businessExtParam")
    void invokePermissionReplyPassesBusinessExtParam() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setAction("permission_reply");
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("ext-1");
        request.setAssistantAccount("asst-1");
        request.setSenderUserAccount("u-1");
        request.setBusinessExtParam(om.readTree("{\"p\":true}"));
        request.setPayload(om.readTree("{\"permissionId\":\"perm-1\",\"response\":\"once\"}"));

        when(processingService.processPermissionReply(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundProcessingService.InboundResult.ok("ext-1", "1"));

        controller.invoke(request);

        ArgumentCaptor<JsonNode> bep = ArgumentCaptor.forClass(JsonNode.class);
        verify(processingService).processPermissionReply(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq("EXTERNAL"), bep.capture());
        assertTrue(bep.getValue().get("p").asBoolean());
    }
```

- [ ] **Step 3: 修改 ExternalInboundController.invoke 三 case 透传**

打开 `ExternalInboundController.java`，line 59-83 的 switch 三 case 末位追加 `request.getBusinessExtParam()`：

```java
        InboundResult result = switch (request.getAction()) {
            case "chat" -> processingService.processChat(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount(),
                    request.payloadString("content"), request.payloadString("msgType"),
                    request.payloadString("imageUrl"), parseChatHistory(request.getPayload()),
                    "EXTERNAL", request.getBusinessExtParam());        // ← 新增
            case "question_reply" -> processingService.processQuestionReply(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount(),
                    request.payloadString("content"), request.payloadString("toolCallId"),
                    request.payloadString("subagentSessionId"), "EXTERNAL",
                    request.getBusinessExtParam());                    // ← 新增
            case "permission_reply" -> processingService.processPermissionReply(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount(),
                    request.payloadString("permissionId"), request.payloadString("response"),
                    request.payloadString("subagentSessionId"), "EXTERNAL",
                    request.getBusinessExtParam());                    // ← 新增
            case "rebuild" -> processingService.processRebuild(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.getSenderUserAccount());                   // ← 不动
            default -> InboundResult.error(400, "Unknown action: " + request.getAction());
        };
```

INFO 日志（line 42-47）扩展打印 `businessExtParam`：

```java
        log.info("[ENTRY] ExternalInboundController.invoke: action={}, domain={}, sessionType={}, sessionId={}, assistant={}, businessExtParam={}",
                request != null ? request.getAction() : null,
                request != null ? request.getBusinessDomain() : null,
                request != null ? request.getSessionType() : null,
                request != null ? request.getSessionId() : null,
                request != null ? request.getAssistantAccount() : null,
                request != null ? request.getBusinessExtParam() : null);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=ExternalInboundControllerTest -DfailIfNoTests=false`
Expected: 全部用例 PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "feat(skill-server): pass businessExtParam through external inbound chat/question/permission paths"
```

---

# Task 5: InboundProcessingService 签名 + dispatchChatToGateway

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

> ⚠ 这是 Task 3/4/6/7 的依赖。必须在它们之前完成（除了 Task 1）。

- [ ] **Step 1: 修改 InboundProcessingService 三 process 方法签名**

打开 `InboundProcessingService.java`：

`processChat` 签名（line 119-123）末位追加 `JsonNode businessExtParam`：

```java
    public InboundResult processChat(String businessDomain, String sessionType, String sessionId,
                                      String assistantAccount, String senderUserAccount,
                                      String content, String msgType,
                                      String imageUrl, List<ImMessageRequest.ChatMessage> chatHistory,
                                      String inboundSource,
                                      JsonNode businessExtParam) {     // ← 新增
```

`processQuestionReply` 签名（line 351-355）末位追加：

```java
    public InboundResult processQuestionReply(String businessDomain, String sessionType,
                                               String sessionId, String assistantAccount,
                                               String senderUserAccount,
                                               String content, String toolCallId,
                                               String subagentSessionId, String inboundSource,
                                               JsonNode businessExtParam) {       // ← 新增
```

`processPermissionReply` 签名（line 408-412）末位追加：

```java
    public InboundResult processPermissionReply(String businessDomain, String sessionType,
                                                 String sessionId, String assistantAccount,
                                                 String senderUserAccount,
                                                 String permissionId, String response,
                                                 String subagentSessionId, String inboundSource,
                                                 JsonNode businessExtParam) {     // ← 新增
```

文件顶部 imports 补 `import com.fasterxml.jackson.databind.JsonNode;` 如缺失。

`processRebuild` 签名 **完全不动**。

- [ ] **Step 2: 修改 dispatchChatToGateway 私有方法签名 + payload 组装**

`dispatchChatToGateway` 签名（line 304-307）末位追加：

```java
    private InboundResult dispatchChatToGateway(SkillSession session, String prompt,
            String ak, String ownerWelinkId, String assistantAccount,
            String senderUserAccount, String sessionType, String sessionId,
            String inboundSource, String content, boolean appendToPending,
            JsonNode businessExtParam) {         // ← 新增
```

方法内 payload 组装改为 `Map<String,Object>` + 用 `buildPayloadWithObjects`（替换原 line 318-332）：

```java
        Map<String, Object> payloadFields = new LinkedHashMap<>();
        payloadFields.put("text", prompt);
        payloadFields.put("toolSessionId", session.getToolSessionId());
        payloadFields.put("assistantAccount", assistantAccount);
        String effectiveSender = "group".equals(sessionType)
                && senderUserAccount != null && !senderUserAccount.isBlank()
                ? senderUserAccount : ownerWelinkId;
        payloadFields.put("sendUserAccount", effectiveSender);
        payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : null);
        payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
        payloadFields.put("businessExtParam", businessExtParam);              // ← 新增
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.CHAT,
                PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields)));   // ← 改用对象重载
```

- [ ] **Step 3: 修改 processChat 内三个 dispatchChatToGateway 调用点**

`processChat` 内有 3 处 `dispatchChatToGateway(...)` 调用，全部追加末位参数：

**情况 B 自愈成功后（line 198-199）**：透 `businessExtParam`

```java
                return dispatchChatToGateway(session, prompt, ak, ownerWelinkId, assistantAccount,
                        senderUserAccount, sessionType, sessionId, inboundSource, content, false,
                        businessExtParam);          // ← 透传当前请求
```

**情况 B legacy 重放 for 循环（line 184-196）**：传 **null**（D17）

```java
                if (!legacyPending.isEmpty()) {
                    log.warn("[WARN] business self-heal: replaying pending messages, welinkSessionId={}, count={}",
                            session.getId(), legacyPending.size());
                    for (String legacyMsg : legacyPending) {
                        if (legacyMsg == null || legacyMsg.isBlank() || legacyMsg.equals(prompt)) {
                            continue;
                        }
                        dispatchChatToGateway(session, legacyMsg, ak, ownerWelinkId, assistantAccount,
                                senderUserAccount, sessionType, sessionId, inboundSource, legacyMsg, false,
                                null);              // ← D17：legacy replay 不带 ext
                    }
                }
```

**情况 C 直接转发（line 213-215）**：透 `businessExtParam`

```java
        return dispatchChatToGateway(session, prompt, ak, ownerWelinkId, assistantAccount,
                senderUserAccount, sessionType, sessionId, inboundSource, content, appendToPending,
                businessExtParam);                  // ← 透传当前请求
```

- [ ] **Step 4: processChat 情况 A 透传到 createSessionAsync**

修改情况 A（line 154-165）：

```java
        if (session == null) {
            log.info("No existing session found, creating async: domain={}, sessionType={}, sessionId={}, ak={}",
                    businessDomain, sessionType, sessionId, ak);
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, senderUserAccount, prompt,
                    businessExtParam);                  // ← 新增
            SkillSession created = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
            writeInvokeSource(created, inboundSource);
            return InboundResult.ok(sessionId,
                    created != null ? String.valueOf(created.getId()) : null);
        }
```

- [ ] **Step 5: processRebuild 内 createSessionAsync 调用补 null**

`processRebuild` 末段 line 526-527 调用 `createSessionAsync(...)`，签名变更后必须末位补 null：

```java
        } else {
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, senderUserAccount, null,
                    null);                              // ← rebuild 无 ext，传 null
            SkillSession created = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
            return InboundResult.ok(sessionId,
                    created != null ? String.valueOf(created.getId()) : null);
        }
```

- [ ] **Step 6: 修改 processQuestionReply / processPermissionReply 内 payload 组装**

`processQuestionReply`（line 381-389）payload 改 `Map<String,Object>` + put `businessExtParam`：

```java
        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, Object> payloadFields = new LinkedHashMap<>();
        payloadFields.put("answer", content);
        payloadFields.put("toolCallId", toolCallId);
        payloadFields.put("toolSessionId", targetToolSessionId);
        payloadFields.put("sendUserAccount", senderUserAccount);
        payloadFields.put("businessExtParam", businessExtParam);              // ← 新增
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.QUESTION_REPLY,
                PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields)));    // ← 改用对象重载
```

`processPermissionReply`（line 437-446）类似改造：

```java
        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, Object> payloadFields = new LinkedHashMap<>();
        payloadFields.put("permissionId", permissionId);
        payloadFields.put("response", response);
        payloadFields.put("toolSessionId", targetToolSessionId);
        payloadFields.put("sendUserAccount", senderUserAccount);
        payloadFields.put("businessExtParam", businessExtParam);              // ← 新增
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.PERMISSION_REPLY,
                PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields)));    // ← 改用对象重载
```

> 注：`payloadFields` 局部变量类型 `Map<String, String>` 改为 `Map<String, Object>`，import 不变（同一个 java.util.Map）。

- [ ] **Step 7: 修复现有 InboundProcessingServiceTest 调用签名**

打开 `InboundProcessingServiceTest.java`，所有 `service.processChat(...)` / `processQuestionReply(...)` / `processPermissionReply(...)` 调用末位**补传 null**（向后兼容验证），例如：

```java
        InboundResult result = service.processChat(
                "im", "direct", "g-1", "asst-1", "u-1", "hello", "text", null, null, "IM",
                null);     // ← 末位 businessExtParam=null
```

逐处修复（`processChatSessionReady` / `processChatAssistantUnknownReturns404` / `processChatAssistantNotExistsReturns410` / `processQuestionReplyAssistantNotExistsReturns410` / `processPermissionReplyAssistantNotExistsReturns410` 等用例）。

- [ ] **Step 8: 在 InboundProcessingServiceTest 增 5 用例（ⓓ-1..ⓓ-5）**

```java
    @Test
    @DisplayName("ⓓ-1: processChat 情况 C session ready 透传 businessExtParam")
    void processChatCaseCPassesBusinessExtParam() throws Exception {
        // 准备 session ready 路径所需的 mock（按现有 ImSessionManager mock 风格补）
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"k\":\"v\"}");
        // ...（按现有 setUp() 补 sessionManager.findSession 返 SkillSession with toolSessionId
        //      + assistantInfoService 返 business scope）
        // 调用：
        service.processChat("im", "direct", "g-1", "asst-1", "u-1", "hi", "text",
                null, null, "IM", bep);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        JsonNode payload = om.readTree(capt.getValue().payload());
        assertEquals("v", payload.get("businessExtParam").get("k").asText());
    }

    @Test
    @DisplayName("ⓓ-2: processChat 情况 A session 不存在 透传到 createSessionAsync")
    void processChatCaseAPassesBepToCreateSessionAsync() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"x\":1}");
        // 准备 mock：sessionManager.findSession 返 null（首次 → 走情况 A）
        when(sessionManager.findSession(any(), any(), any(), any())).thenReturn(null);

        service.processChat("im", "direct", "g-1", "asst-1", "u-1", "hi", "text",
                null, null, "IM", bep);

        ArgumentCaptor<JsonNode> bepCap = ArgumentCaptor.forClass(JsonNode.class);
        verify(sessionManager).createSessionAsync(
                eq("im"), eq("direct"), eq("g-1"), any(), any(), any(), any(), any(),
                bepCap.capture());
        assertEquals(1, bepCap.getValue().get("x").asInt());
    }

    @Test
    @DisplayName("ⓓ-3: processChat 情况 B legacy replay 显式不带 businessExtParam（D17）")
    void processChatLegacyReplayDoesNotCarryBep() throws Exception {
        // 准备 session 存在但 toolSessionId 为 null + business scope + legacy pending 非空
        // mock rebuildService.consumePendingMessages 返非空 List<String>
        // ...
        // 调用 processChat 后断言：legacy replay 触发的 InvokeCommand.payload 中
        //   不含 "businessExtParam" 键（或值为 null）
        // 而当前请求消息触发的 InvokeCommand.payload 含 businessExtParam
        // ...
    }

    @Test
    @DisplayName("ⓓ-4: processQuestionReply 透传 businessExtParam")
    void processQuestionReplyPassesBep() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"q\":\"x\"}");
        // 准备 session ready 的 mock
        // ...
        service.processQuestionReply("im", "direct", "g-1", "asst-1", "u-1",
                "ok", "tc-1", null, "IM", bep);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        JsonNode payload = om.readTree(capt.getValue().payload());
        assertEquals("x", payload.get("businessExtParam").get("q").asText());
    }

    @Test
    @DisplayName("ⓓ-5: processPermissionReply 透传 businessExtParam")
    void processPermissionReplyPassesBep() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"p\":true}");
        // 准备 session ready 的 mock
        // ...
        service.processPermissionReply("im", "direct", "g-1", "asst-1", "u-1",
                "perm-1", "once", null, "IM", bep);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        JsonNode payload = om.readTree(capt.getValue().payload());
        assertTrue(payload.get("businessExtParam").get("p").asBoolean());
    }
```

> ⚠ ⓓ-3 实现细节较复杂（涉及 rebuildService.consumePendingMessages mock + 验证多次 sendInvokeToGateway），如本任务工时不够可降级为"在 D17 实施时跑过手动验证 + log assert"，留 Note。

- [ ] **Step 9: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=InboundProcessingServiceTest -DfailIfNoTests=false`
Expected: 全部用例 PASS

- [ ] **Step 10: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java
git commit -m "feat(skill-server): extend InboundProcessingService process methods with businessExtParam parameter"
```

---

# Task 6: ImSessionManager.createSessionAsync business 立即发送透传

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/ImSessionManagerTest.java`

> 依赖：Task 1 + Task 5 完成。

- [ ] **Step 1: createSessionAsync 签名追加 JsonNode businessExtParam**

打开 `ImSessionManager.java`，修改 `createSessionAsync` 签名（line 88-93 附近，具体位置以现有签名为准）末位追加：

```java
    public void createSessionAsync(String businessDomain, String sessionType,
            String sessionId, String ak, String ownerWelinkId,
            String assistantAccount, String senderUserAccount,
            String pendingMessage,
            JsonNode businessExtParam) {            // ← 末位新增
```

文件顶部 imports 补 `import com.fasterxml.jackson.databind.JsonNode;`。

- [ ] **Step 2: business 立即发送分支改 Map<String,Object> + 透 ext**

修改 line 146-166 business 立即发送分支：

```java
            if (generatedToolSessionId != null) {
                sessionService.updateToolSessionId(created.getId(), generatedToolSessionId);
                log.info("Business assistant: toolSessionId pre-generated locally, skillSessionId={}, toolSessionId={}, ak={}",
                        created.getId(), generatedToolSessionId, ak);
                if (pendingMessage != null && !pendingMessage.isBlank()) {
                    Map<String, Object> payloadFields = new LinkedHashMap<>();   // ← String → Object
                    payloadFields.put("text", pendingMessage);
                    payloadFields.put("toolSessionId", generatedToolSessionId);
                    payloadFields.put("assistantAccount", assistantAccount);
                    String effectiveSender = "group".equals(sessionType)
                            && senderUserAccount != null && !senderUserAccount.isBlank()
                            ? senderUserAccount : ownerWelinkId;
                    payloadFields.put("sendUserAccount", effectiveSender);
                    payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : null);
                    payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
                    payloadFields.put("businessExtParam", businessExtParam);    // ← 新增
                    gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                            ak,
                            ownerWelinkId,
                            String.valueOf(created.getId()),
                            GatewayActions.CHAT,
                            PayloadBuilder.buildPayloadWithObjects(             // ← 改用对象重载
                                    objectMapper, payloadFields)));
                    log.info("Business assistant: chat invoke sent immediately, skillSessionId={}, ak={}",
                            created.getId(), ak);
                }
            } else {
                // personal 分支不变（D19：requestToolSession 仅 pending 纯文本，不带 ext）
                requestToolSession(created, pendingMessage);
            }
```

- [ ] **Step 3: 修复其他调用 createSessionAsync 的地方（如有）**

执行 grep 找所有调用：

Run: `grep -rn "createSessionAsync" skill-server/src --include="*.java"`

预期只在 `InboundProcessingService.processChat` 与 `processRebuild` 两处出现（已在 Task 5 修过末位补 null/businessExtParam）。如有其他位置，按"末位补 null（rebuild 语义）或 businessExtParam（chat 语义）"原则同步修复。

- [ ] **Step 4: ImSessionManagerTest 增 1 用例（ⓓ-6）**

打开 `ImSessionManagerTest.java`（如不存在则新建，参考已有同包测试风格 mock SessionService / GatewayRelayService）。新增：

```java
    @Test
    @DisplayName("ⓓ-6: createSessionAsync business 立即发送透传 businessExtParam 到 InvokeCommand.payload")
    void createSessionAsyncBusinessImmediatePassesBep() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"k\":\"v\"}");
        // mock business scope strategy 返 cloud-... ID
        when(scopeDispatcher.getStrategy(eq("business")))
                .thenReturn(businessStrategy);
        when(businessStrategy.generateToolSessionId()).thenReturn("cloud-" + UUID.randomUUID());
        when(assistantInfoService.getCachedScope(any())).thenReturn("business");

        SkillSession created = SkillSession.builder()
                .id(1L).ak("ak-1").build();
        when(sessionService.findByBusinessSession(any(), any(), any(), any())).thenReturn(null);
        when(sessionService.createSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(redisTemplate.opsForValue()).thenReturn(redisValueOps);
        when(redisValueOps.setIfAbsent(any(), any(), any())).thenReturn(true);

        manager.createSessionAsync("im", "direct", "g-1", "ak-1", "u-1", "asst-1",
                "u-1", "hello", bep);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        JsonNode payload = om.readTree(capt.getValue().payload());
        assertEquals("v", payload.get("businessExtParam").get("k").asText());
    }
```

如现有测试没有 setup 这种深 mock 链，可拷贝同包内最近的 createSessionAsync 测试用例的 mock 设置作为基础。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=ImSessionManagerTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: 全包测试看是否影响其他用例**

Run: `mvn -pl skill-server test -DfailIfNoTests=false`
Expected: 全绿（如有其他 createSessionAsync 调用方未修，会编译失败 → 回头补）

- [ ] **Step 7: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java skill-server/src/test/java/com/opencode/cui/skill/service/ImSessionManagerTest.java
git commit -m "feat(skill-server): pass businessExtParam through ImSessionManager.createSessionAsync business immediate-send branch"
```

---

# Task 7: SkillMessageController DTO + 三处 payload 组装

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java`

> 依赖：Task 1 完成。

- [ ] **Step 1: 修改 SendMessageRequest / PermissionReplyRequest 加字段**

打开 `SkillMessageController.java`，文件顶部 imports 补 `import com.fasterxml.jackson.databind.JsonNode;` 如缺失。

底部内部类（line 514-538）修改：

```java
    /** 发送消息请求体。 */
    @Data
    public static class SendMessageRequest {
        private String content;
        private String toolCallId;
        private String subagentSessionId;
        private JsonNode businessExtParam;       // ← 新增
    }

    // SendToImRequest 不动

    /** 权限回复请求体。 */
    @Data
    public static class PermissionReplyRequest {
        private String response;
        private String subagentSessionId;
        private JsonNode businessExtParam;       // ← 新增
    }
```

- [ ] **Step 2: routeToGateway chat 分支改 Map<String,Object> + 透 ext**

修改 line 217-227 chat 分支：

```java
        } else {
            action = GatewayActions.CHAT;
            Map<String, Object> payloadFields = new LinkedHashMap<>();           // ← String → Object
            payloadFields.put("text", request.getContent());
            payloadFields.put("toolSessionId", session.getToolSessionId());
            payloadFields.put("sendUserAccount",
                    userIdCookie != null && !userIdCookie.isBlank() ? userIdCookie : session.getUserId());
            payloadFields.put("assistantAccount", session.getAssistantAccount());
            payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
            payloadFields.put("businessExtParam", request.getBusinessExtParam());// ← 新增
            payload = PayloadBuilder.buildPayloadWithObjects(objectMapper, payloadFields);   // ← 改用对象重载
        }
```

- [ ] **Step 3: routeToGateway question_reply 分支改 LinkedHashMap + 透 ext**

修改 line 206-215 question_reply 分支（原 `Map.of(...)` 改 `LinkedHashMap`）：

```java
        if (request.getToolCallId() != null && !request.getToolCallId().isBlank()) {
            action = GatewayActions.QUESTION_REPLY;
            String targetToolSessionId = request.getSubagentSessionId() != null
                    ? request.getSubagentSessionId()
                    : session.getToolSessionId();
            Map<String, Object> qr = new LinkedHashMap<>();                      // ← Map.of → LinkedHashMap
            qr.put("answer", request.getContent());
            qr.put("toolCallId", request.getToolCallId());
            qr.put("toolSessionId", targetToolSessionId);
            qr.put("businessExtParam", request.getBusinessExtParam());           // ← 新增
            payload = PayloadBuilder.buildPayloadWithObjects(objectMapper, qr);  // ← 改用对象重载
        } else {
            // ...（chat 分支见 Step 2）
        }
```

- [ ] **Step 4: replyPermission 改 LinkedHashMap + 透 ext**

修改 line 415-425 `replyPermission` 内 payload 构造：

```java
        Map<String, Object> pr = new LinkedHashMap<>();                         // ← Map.of → LinkedHashMap
        pr.put("permissionId", permId);
        pr.put("response", request.getResponse());
        pr.put("toolSessionId", targetToolSessionId);
        pr.put("businessExtParam", request.getBusinessExtParam());              // ← 新增
        String payload = PayloadBuilder.buildPayloadWithObjects(objectMapper, pr);  // ← 改用对象重载
```

- [ ] **Step 4.5: SkillMessageController 三处 [ENTRY] INFO 日志加打 businessExtParam（D14）**

修改 `sendMessage` line 124-125：

```java
        log.info("[ENTRY] SkillMessageController.sendMessage: sessionId={}, contentLength={}, businessExtParam={}",
                sessionId,
                request.getContent() != null ? request.getContent().length() : 0,
                request.getBusinessExtParam());
```

修改 `replyPermission` line 380-381：

```java
        log.info("[ENTRY] SkillMessageController.replyPermission: sessionId={}, permId={}, response={}, businessExtParam={}",
                sessionId, permId, request.getResponse(), request.getBusinessExtParam());
```

- [ ] **Step 5: SkillMessageControllerTest 增 3 用例**

打开 `SkillMessageControllerTest.java`，imports 补 `JsonNode` / `ArgumentCaptor`，新增：

```java
    @Test
    @DisplayName("ⓔ-6: sendMessage chat 分支透传 businessExtParam")
    void sendMessageChatPassesBep() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"k\":\"v\"}");
        SkillMessageController.SendMessageRequest request = new SkillMessageController.SendMessageRequest();
        request.setContent("hi");
        request.setBusinessExtParam(bep);

        // 准备 session ready 的 mock（与已有 sendMessage200 用例同口径）
        // ...

        controller.sendMessage("u-1", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        JsonNode payload = om.readTree(capt.getValue().payload());
        assertEquals("v", payload.get("businessExtParam").get("k").asText());
    }

    @Test
    @DisplayName("ⓔ-7: sendMessage question_reply 分支透传 businessExtParam")
    void sendMessageQuestionReplyPassesBep() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"q\":\"x\"}");
        SkillMessageController.SendMessageRequest request = new SkillMessageController.SendMessageRequest();
        request.setContent("reply");
        request.setToolCallId("tc-1");
        request.setBusinessExtParam(bep);

        // 同 sendMessageWithToolCallIdSendsQuestionReply 用例的 mock 风格
        // ...

        controller.sendMessage("u-1", "1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        JsonNode payload = om.readTree(capt.getValue().payload());
        assertEquals("x", payload.get("businessExtParam").get("q").asText());
    }

    @Test
    @DisplayName("ⓔ-8: replyPermission 透传 businessExtParam")
    void replyPermissionPassesBep() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode bep = om.readTree("{\"p\":true}");
        SkillMessageController.PermissionReplyRequest request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");
        request.setBusinessExtParam(bep);

        // 同 replyPermission 现有用例的 mock 风格
        // ...

        controller.replyPermission("u-1", "1", "perm-1", request);

        ArgumentCaptor<InvokeCommand> capt = ArgumentCaptor.forClass(InvokeCommand.class);
        verify(gatewayRelayService).sendInvokeToGateway(capt.capture());
        JsonNode payload = om.readTree(capt.getValue().payload());
        assertTrue(payload.get("businessExtParam").get("p").asBoolean());
    }
```

> ⚠ 已有 sendMessage200 / sendMessageWithToolCallIdSendsQuestionReply / replyPermission 用例的 mock 设置可以直接复用，新用例只补 `setBusinessExtParam` 与捕获 verify。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -pl skill-server test -Dtest=SkillMessageControllerTest -DfailIfNoTests=false`
Expected: 全部用例 PASS

- [ ] **Step 7: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java
git commit -m "feat(skill-server): pass businessExtParam through miniapp send/reply paths in SkillMessageController"
```

---

# Task 8: 集成测试 + CloudRequestBuilderTest 嵌套结构用例

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/cloud/CloudRequestBuilderTest.java`
- Create or扩展: 集成测试文件（已有 `PersonalScopeCloudProtocolIntegrationTest` 可参考；新建 `ExtParametersIntegrationTest` 或挂同名测试类）

- [ ] **Step 1: CloudRequestBuilderTest 增 1 用例（嵌套结构序列化）**

在 `CloudRequestBuilderTest.java` 已有 `DefaultStrategyBuild` `@Nested` 内追加：

```java
        @Test
        @DisplayName("extParameters 含 businessExtParam/platformExtParam 嵌套结构正确序列化")
        void buildsNestedExtParametersStructure() throws Exception {
            ObjectNode bepNode = objectMapper.createObjectNode();
            bepNode.put("isHwEmployee", false);
            bepNode.set("knowledgeId", objectMapper.createArrayNode().add("kb-1"));

            Map<String, Object> ext = new java.util.LinkedHashMap<>();
            ext.put("businessExtParam", bepNode);
            ext.put("platformExtParam", objectMapper.createObjectNode());

            CloudRequestContext context = CloudRequestContext.builder()
                    .content("hi")
                    .contentType("text")
                    .extParameters(ext)
                    .build();

            ObjectNode result = defaultStrategy.build(context);

            assertNotNull(result.get("extParameters"));
            assertTrue(result.get("extParameters").isObject());
            assertTrue(result.get("extParameters").get("businessExtParam").isObject());
            assertEquals(false, result.get("extParameters").get("businessExtParam").get("isHwEmployee").asBoolean());
            assertTrue(result.get("extParameters").get("businessExtParam").get("knowledgeId").isArray());
            assertTrue(result.get("extParameters").get("platformExtParam").isObject());
            assertEquals(0, result.get("extParameters").get("platformExtParam").size());
        }
```

- [ ] **Step 2: 创建 ExtParametersIntegrationTest（端到端 ⓕ-1）**

新建文件 `skill-server/src/test/java/com/opencode/cui/skill/service/scope/ExtParametersIntegrationTest.java`：

```java
package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.service.SysConfigService;
import com.opencode.cui.skill.service.cloud.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ⓕ-1: business chat → BusinessScopeStrategy → cloud body 端到端 extParameters 透传")
class ExtParametersIntegrationTest {

    @Mock private CloudEventTranslator cloudEventTranslator;
    @Mock private SysConfigService sysConfigService;
    @Mock private AssistantInfo info;

    private ObjectMapper objectMapper;
    private BusinessScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        DefaultCloudRequestStrategy defaultStrategy = new DefaultCloudRequestStrategy(objectMapper);
        CloudRequestBuilder builder = new CloudRequestBuilder(List.of(defaultStrategy), sysConfigService);
        strategy = new BusinessScopeStrategy(builder, cloudEventTranslator, objectMapper);
    }

    @Test
    @DisplayName("chat payload 含 businessExtParam → cloudRequest.extParameters.businessExtParam 等值，platformExtParam 为 {}")
    void e2eChat() throws Exception {
        when(info.getAppId()).thenReturn("app-001");
        when(info.getAssistantScope()).thenReturn("business");
        when(sysConfigService.getValue(any(), eq("app-001"))).thenReturn(null);

        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"cloud-1\",\"assistantAccount\":\"asst-1\","
                + "\"sendUserAccount\":\"u-1\",\"messageId\":\"m-1\","
                + "\"businessExtParam\":{\"isHwEmployee\":false,\"knowledgeId\":[\"kb-1\"]}}";
        InvokeCommand cmd = new InvokeCommand("ak-1", "u-1", "1", "chat", payload);

        String invokeMessage = strategy.buildInvoke(cmd, info);
        ObjectNode message = (ObjectNode) objectMapper.readTree(invokeMessage);
        JsonNode cloudRequest = message.get("payload").get("cloudRequest");

        assertNotNull(cloudRequest);
        JsonNode ext = cloudRequest.get("extParameters");
        assertTrue(ext.isObject());
        assertEquals(false, ext.get("businessExtParam").get("isHwEmployee").asBoolean());
        assertTrue(ext.get("businessExtParam").get("knowledgeId").isArray());
        assertEquals("kb-1", ext.get("businessExtParam").get("knowledgeId").get(0).asText());
        assertTrue(ext.get("platformExtParam").isObject());
        assertEquals(0, ext.get("platformExtParam").size());
    }
}
```

- [ ] **Step 3: 在已有 GatewayRelayServiceTest 增 1 用例（ⓕ-2）**

打开 `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java`（已存在），复用其中 `setUp` 已经初始化好的实例与 mock。在已有用例之后追加：

```java
    @Test
    @DisplayName("ⓕ-2: personal scope chat invoke：businessExtParam 字段随 payload 原样透下游不剥离")
    void personalScopeDoesNotStripBusinessExtParam() throws Exception {
        ObjectMapper om = new ObjectMapper();

        // mock：personal scope（info.isBusiness() == false）
        AssistantInfo personalInfo = mock(AssistantInfo.class);
        when(personalInfo.isBusiness()).thenReturn(false);
        when(personalInfo.getAssistantScope()).thenReturn("personal");
        when(assistantInfoService.getAssistantInfo("ak-personal")).thenReturn(personalInfo);

        // 构造 InvokeCommand payload 含 businessExtParam
        String payload = "{\"text\":\"hi\",\"toolSessionId\":\"open-1\","
                + "\"businessExtParam\":{\"k\":\"v\"}}";
        InvokeCommand cmd = new InvokeCommand("ak-personal", "u-1", "1", "chat", payload);

        // 调用 sendInvokeToGateway 触发 buildInvokeMessage 路径
        gatewayRelayService.sendInvokeToGateway(cmd);

        // 捕获下游 sender 收到的 message string，断言 payload 中保留 businessExtParam 键
        ArgumentCaptor<String> messageCap = ArgumentCaptor.forClass(String.class);
        verify(downstreamSender).send(messageCap.capture());
        JsonNode message = om.readTree(messageCap.getValue());
        JsonNode innerPayload = om.readTree(message.get("payload").asText());
        // 注意：buildInvokeMessage 把 InvokeCommand.payload string 直接挂到 message.payload
        // 上述断言假设 buildInvokeMessage 用 message.put("payload", commandPayload)。
        // 若现有实现改用 message.set("payload", parsedNode)，则 innerPayload 直接是 message.get("payload")。

        assertNotNull(innerPayload.get("businessExtParam"));
        assertEquals("v", innerPayload.get("businessExtParam").get("k").asText());
    }
```

> 实施要点：先读 `GatewayRelayService.buildInvokeMessage` 实际怎么把 payload string 挂到出站 message（可能是 `message.put("payload", str)` 或 `message.set("payload", parsedNode)`）。两种形态对断言代码影响不同，按实际代码调整 `innerPayload` 的获取方式。`downstreamSender` 字段名以现有 `GatewayRelayServiceTest` 为准（也可能叫 `gatewayWsHandler` / 其他）。

- [ ] **Step 4: ⓕ-3 createSessionAsync E2E 已被覆盖，无需新建**

ⓕ-3 的端到端断言（"createSessionAsync 透 ext → 最终 cloud body 含 ext"）由两个已有测试组合成立：

- **Task 6 ⓓ-6** 验证：`createSessionAsync` 把 `businessExtParam` 写入 `InvokeCommand.payload` string
- **Task 8 ⓕ-1** 验证：含 `businessExtParam` 的 `InvokeCommand.payload` 经 `BusinessScopeStrategy.buildInvoke` 后落到 `cloudRequest.extParameters.businessExtParam`

两者串联即覆盖端到端透传。无需新建额外集成测试文件，避免 mock 链冗余。

> 如要做严格 wiring 验证，可在 `ExtParametersIntegrationTest`（Step 2 创建）内补一个 `@Test` 方法直接拼接 `ImSessionManager` + `BusinessScopeStrategy` 的真实实例，但收益小于成本，**默认跳过**。

- [ ] **Step 5: 运行所有测试确认通过**

Run: `mvn -pl skill-server test -DfailIfNoTests=false`
Expected: 全绿

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/
git commit -m "test(skill-server): add integration tests for extParameters end-to-end + nested CloudRequestBuilder serialization"
```

---

# Task 9: 协议文档替换

**Files:**
- Modify: `docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md`

- [ ] **Step 1: 替换 line 147–151（请求示例）**

将原扁平 `extParameters` 示例：

```json
"extParameters": {
  "isHwEmployee": false,
  "actionParam": "",
  "knowledgeId": []
}
```

替换为嵌套结构：

```json
"extParameters": {
  "businessExtParam": {
    "isHwEmployee": false,
    "actionParam": "",
    "knowledgeId": []
  },
  "platformExtParam": {}
}
```

- [ ] **Step 2: 替换 line 202–205（字段表格）**

原表格 3 行：

```markdown
| extParameters | Object | ✅ | 扩展参数 |
| extParameters.isHwEmployee | Boolean | ✅ | 是否华为雇员 |
| extParameters.actionParam | String | | 机器人自定义参数，用户点击 Markdown 链接协议时携带 |
| extParameters.knowledgeId | List\<String\> | | 知识库 ID 列表 |
```

替换为：

```markdown
| extParameters | Object | ✅ | 扩展参数容器（始终为 object） |
| extParameters.businessExtParam | Object | ✅ | 业务方扩展参数（自由 JSON 对象）；由 IM/external/miniapp 入参信封透传；缺省 / 类型异常 / 解析失败时 skill-server 兜底为 `{}`；业务字段（如 `isHwEmployee` / `actionParam` / `knowledgeId`）由业务方自行定义放入此对象 |
| extParameters.platformExtParam | Object | ✅ | 平台扩展参数（skill-server 自填）；首期占位 `{}`，将来用于塞 traceId / 版本号 / 租户标识等平台元数据 |
```

- [ ] **Step 3: 替换 line 270–275（第二处请求示例 — 同 Step 1）**

同 Step 1 把扁平结构替换为嵌套结构。

- [ ] **Step 4: 修改 line 1137（actionParam 与 markdown 链接协议关联段落）**

原文：

```markdown
| actionParam | String | 额外自定义参数，随 chat 接口发送到服务端。对应 cloudRequest 中的 `extParameters.actionParam` |
```

修改为：

```markdown
| actionParam | String | 额外自定义参数，随 chat 接口发送到服务端。业务方应将其塞入 `cloudRequest.extParameters.businessExtParam.actionParam`（嵌套结构升级后的位置） |
```

- [ ] **Step 5: 全文搜索是否还有遗留扁平 `extParameters.isHwEmployee` 等表述**

Run: `grep -n "extParameters\." docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md | grep -v "businessExtParam\|platformExtParam"`

Expected: 无匹配（除非有 markdown 表格分隔符 `extParameters`）

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md
git commit -m "docs: update cloud-agent-protocol extParameters to nested {businessExtParam, platformExtParam}"
```

---

# 最终验证

- [ ] **Step 1: 运行全包测试**

Run: `mvn -pl skill-server test -DfailIfNoTests=false`
Expected: 全绿

- [ ] **Step 2: 编译产出 jar**

Run: `mvn -pl skill-server package -DskipTests`
Expected: SUCCESS

- [ ] **Step 3: gitnexus_detect_changes 验证改动范围**

Run: `npx gitnexus analyze` 然后 `gitnexus_detect_changes()` 验证只影响 design doc 列出的 9 个文件 + 8 个测试 + 1 个集成测试 + 1 个文档。如有意外文件被改 → 检查并回退。

- [ ] **Step 4: 完结**

不要 git push。让用户测试后再决定 push / 创建 PR。

---

## 备注：实施过程中的处理建议

- 若任一 Task 测试失败：先检查 Task 5（service 签名）是否完整改动；其次检查 Task 1（PayloadBuilder 重载）签名是否正确导出。
- 若编译失败：用 `grep -rn "createSessionAsync\|processChat\|processQuestionReply\|processPermissionReply" skill-server/src` 搜索未发现的调用方。
- 若 mock 链复杂：参考同包内现有最相近的测试（`PersonalScopeCloudProtocolIntegrationTest` / `BusinessScopeStrategyTest` / `InboundProcessingServiceTest` 现有用例的 setup）。
- 不要 `--no-verify` 跳过 hooks；不要修改 `git config`。
