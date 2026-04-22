# External Sender User Account Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `/api/external/invoke` 的 `senderUserAccount` 从 `chat.payload` 提升为信封层字段，所有 action 强制必填，并透传至下游 service 签名与 Skill Server → Gateway 出站 payload。

**Architecture:** 最小入侵改动。`ExternalInvokeRequest` 新增顶层字段；`ExternalInboundController` validator 增加必填校验；`InboundProcessingService` 的 `processQuestionReply` / `processPermissionReply` / `processRebuild` 签名新增 `senderUserAccount` 形参，前两者的 Gateway 出站 payload 新增 `sendUserAccount` 字段；`processChat` 签名不变（形参已存在），仅改数据源；`processChat` 与 `ImSessionManager` 的 `isBlank()` 兜底保留（IM 共享路径）。硬切（D1）：`payload.senderUserAccount` 不再识别。

**Tech Stack:** Spring Boot 3.4 + Java 21 + Lombok + Jackson + Mockito + JUnit 5 + Maven

**Spec reference:** `docs/superpowers/specs/2026-04-22-external-sender-user-account-envelope-design.md`

---

## 项目执行约定

- **AI 不执行 `git add` / `git commit`**：项目 `.trellis/workflow.md` 明确规定。plan 内每个 Task 末尾的 commit 命令作为**开发者 checkpoint 参考**，由人类执行。subagent / 执行者完成 Task 内除 commit 外的所有 step 后，把 commit 命令打印给用户。
- **测试命令默认在仓库根目录执行**，通过 Maven `-pl skill-server` 聚焦到 skill-server 模块：
  ```bash
  mvn -pl skill-server -am test -Dtest=<ClassName>
  ```
  单方法：`-Dtest=<ClassName>#<methodName>`
- **所有命令一律单行书写**，不使用 bash 续行符 `\`，以便在 bash / PowerShell / zsh 之间无差别复制。如果在 PowerShell 里遇到 `&&` 问题，用 `;` 替代即可。
- **文本搜索用 `rg`（ripgrep）**，不用 `grep` —— 规避 PowerShell 下的转义/管道坑，同时保持跨平台一致。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java` | Modify | 新增顶层 `senderUserAccount` 字段 |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java` | Modify | validator 新增必填校验；4 个 switch 分支改从信封读；删除 `payloadString("senderUserAccount")` |
| `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java` | Modify | `processQuestionReply` / `processPermissionReply` / `processRebuild` 签名新增 `senderUserAccount`；前两者出站 payload 新增 `sendUserAccount`；`processRebuild` 把 `senderUserAccount` 透传给 `createSessionAsync` |
| `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java` | Modify | `buildRequest` helper 升级；4 个 action 的 verify 参数；新增必填 400 + D1 钉死两个测试 |
| `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java` | Modify | 所有现有下游方法调用加 `senderUserAccount` 形参；q/p reply session-ready 用例补 `sendUserAccount` payload 断言；`processChat` 群聊断言补 `sendUserAccount`；`processRebuild(no session)` 补 `createSessionAsync` 的 `senderUserAccount` 断言；新增 `processChat group + null sender → ownerWelinkId` 断言 |
| `skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java` | Modify | 新增 `buildInvoke_chat_extractsSendUserAccount`，用 `ArgumentCaptor<CloudRequestContext>` 断言字段抽取 |
| `docs/external-channel-api.md` | Modify | 信封字段表新增、5 段 JSON 示例补字段、3 处 Python 客户端 body 补字段、400 错误码表新增、顶部破坏性提示框 |
| `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java` | **不动** | IM 共享路径，保留 isBlank 兜底 |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java` | **不动** | 仅调 `processChat`，签名未变 |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java` | **不动** | first-party reply 路径的 sendUserAccount 补齐不在本次范围 |

---

## Task 1: 信封模型新增字段 + 控制器测试 helper 升级

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java`

**背景**：这是"准备工作" Task。先给 DTO 加字段、再给 helper 默认值补上，让所有现存用例在 Task 2 加 validator 必填校验后继续通过，不需要大修。

- [ ] **Step 1: 给 `ExternalInvokeRequest` 新增 `senderUserAccount` 字段**

修改 `ExternalInvokeRequest.java`，在 `assistantAccount` 和 `payload` 之间插入一行：

```java
package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ExternalInvokeRequest {

    /** 动作类型：chat / question_reply / permission_reply / rebuild */
    private String action;

    /** 业务域（需与 WS source 一致） */
    private String businessDomain;

    /** 会话类型：group / direct */
    private String sessionType;

    /** 业务侧会话 ID */
    private String sessionId;

    /** 助手账号 */
    private String assistantAccount;

    /** 本次消息/回复/重建的发起用户账号（信封必填） */
    private String senderUserAccount;

    /** action 专属数据 */
    private JsonNode payload;

    // ==================== Payload 便捷访问 ====================

    public String payloadString(String field) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return null;
        }
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
```

- [ ] **Step 2: 升级 `ExternalInboundControllerTest.buildRequest` helper**

修改 `ExternalInboundControllerTest.java` 里的 `buildRequest` 私有方法，默认给 JSON 加信封 `senderUserAccount`：

```java
private ExternalInvokeRequest buildRequest(String action, String payload) throws Exception {
    String json = "{\"action\":\"" + action + "\","
            + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
            + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
            + "\"senderUserAccount\":\"user-001\","
            + "\"payload\":" + payload + "}";
    return objectMapper.readValue(json, ExternalInvokeRequest.class);
}
```

- [ ] **Step 3: 运行 ExternalInboundControllerTest 验证现有用例全绿**

```bash
mvn -pl skill-server -am test -Dtest=ExternalInboundControllerTest
```

**Expected**: BUILD SUCCESS，8 个现有用例全部通过。此时还没加 validator 必填，也没改 controller 业务逻辑，仅数据面新增字段。

- [ ] **Step 4: Commit（开发者执行）**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "refactor(skill-server): add senderUserAccount envelope field on ExternalInvokeRequest"
```

---

## Task 2: validator 必填校验 + D1 钉死测试（TDD）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:103-112` (validateEnvelope)
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java`

- [ ] **Step 1: 写失败测试 `missingSenderUserAccountReturns400`**

在 `ExternalInboundControllerTest.java` 新增：

```java
@Test
@DisplayName("missing senderUserAccount returns 400 for all actions")
void missingSenderUserAccountReturns400() throws Exception {
    for (String action : List.of("chat", "question_reply", "permission_reply", "rebuild")) {
        String json = "{\"action\":\"" + action + "\","
                + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
                + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
                + "\"payload\":{}}";
        var request = objectMapper.readValue(json, ExternalInvokeRequest.class);
        var response = controller.invoke(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("senderUserAccount is required",
                response.getBody().getErrormsg());
    }
}
```

（如果文件顶部没 `import java.util.List;`，一并补上。）

- [ ] **Step 2: 写失败测试 `legacyPayloadSenderUserAccountIsIgnored`**

紧邻上一个测试添加：

```java
@Test
@DisplayName("D1 hard cut: payload.senderUserAccount is ignored, envelope required")
void legacyPayloadSenderUserAccountIsIgnored() throws Exception {
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
```

- [ ] **Step 3: 运行两个新测试验证 FAIL**

```bash
mvn -pl skill-server -am test -Dtest='ExternalInboundControllerTest#missingSenderUserAccountReturns400+legacyPayloadSenderUserAccountIsIgnored'
```

**Expected**: 两个测试 FAIL。`missing` 场景下 Controller 继续往 `validatePayload` 走，chat 分支会因 `payload.content` 不存在返回 400 + 错文案 `payload.content is required for chat`，与 expected `senderUserAccount is required` 不一致；其他 action 类似。`legacy` 场景下 `processingService` 未 stub，调用会 NPE 或校验其他字段，无法达到 `verifyNoInteractions`。

- [ ] **Step 4: 在 `validateEnvelope` 新增必填校验**

修改 `ExternalInboundController.java` 的 `validateEnvelope`，在 `assistantAccount` 校验之后、`return null` 之前插入：

```java
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
```

- [ ] **Step 5: 运行新测试验证 PASS**

```bash
mvn -pl skill-server -am test -Dtest='ExternalInboundControllerTest#missingSenderUserAccountReturns400+legacyPayloadSenderUserAccountIsIgnored'
```

**Expected**: 两个测试 PASS。

- [ ] **Step 6: 运行完整 ExternalInboundControllerTest 回归**

```bash
mvn -pl skill-server -am test -Dtest=ExternalInboundControllerTest
```

**Expected**: BUILD SUCCESS，全部 10 个测试通过（8 原有 + 2 新增）。

- [ ] **Step 7: Commit（开发者执行）**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "feat(skill-server): require senderUserAccount in external invoke envelope"
```

---

## Task 3: chat 分支改从信封读（TDD）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:59-66` (chat case)
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java` chatAction

- [ ] **Step 1: 修改 `chatAction` 测试把 sender 断言从 `isNull()` 改为 `eq("user-001")`**

定位 `ExternalInboundControllerTest.chatAction()`，把 `verify(processingService).processChat(...)` 里第 5 个位置参数的 `isNull()` 改为 `eq("user-001")`：

```java
@Test
@DisplayName("chat action dispatches to processChat")
void chatAction() throws Exception {
    when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(InboundResult.ok());
    var request = buildRequest("chat", "{\"content\":\"hello\",\"msgType\":\"text\"}");
    var response = controller.invoke(request);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(0, response.getBody().getCode());
    verify(processingService).processChat(
            eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
            eq("user-001"),
            eq("hello"), eq("text"), isNull(), isNull(), eq("EXTERNAL"));
}
```

- [ ] **Step 2: 运行 chatAction 测试验证 FAIL**

```bash
mvn -pl skill-server -am test -Dtest=ExternalInboundControllerTest#chatAction
```

**Expected**: FAIL —— Mockito 报 "Argument(s) are different!"，因为 Controller 的 chat 分支当前是 `request.payloadString("senderUserAccount")`（返回 null），实际传给 `processChat` 的是 null，而测试期望 "user-001"。

- [ ] **Step 3: 修改 `ExternalInboundController` 的 chat case 改从信封读**

定位 `ExternalInboundController.invoke()` 里的 `case "chat"` 分支，把 `request.payloadString("senderUserAccount")` 替换为 `request.getSenderUserAccount()`：

```java
case "chat" -> processingService.processChat(
        request.getBusinessDomain(), request.getSessionType(),
        request.getSessionId(), request.getAssistantAccount(),
        request.getSenderUserAccount(),
        request.payloadString("content"), request.payloadString("msgType"),
        request.payloadString("imageUrl"), parseChatHistory(request.getPayload()),
        "EXTERNAL");
```

- [ ] **Step 4: 运行 chatAction 测试验证 PASS**

```bash
mvn -pl skill-server -am test -Dtest=ExternalInboundControllerTest#chatAction
```

**Expected**: PASS。

- [ ] **Step 5: 运行完整 ExternalInboundControllerTest 回归**

```bash
mvn -pl skill-server -am test -Dtest=ExternalInboundControllerTest
```

**Expected**: BUILD SUCCESS，10 个测试全部通过。

- [ ] **Step 6: Commit（开发者执行）**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "refactor(skill-server): read chat senderUserAccount from envelope instead of payload"
```

---

## Task 4: question_reply 新增 senderUserAccount 形参与出站 payload（TDD）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:191-224` (processQuestionReply)
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:67-71` (question_reply case)
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java` questionReplyAction

- [ ] **Step 1: 修改 `InboundProcessingServiceTest` 所有 processQuestionReply 调用加 `senderUserAccount` 形参**

共 3 处调用（`processQuestionReplySessionReady` / `processQuestionReplyAgentOfflineReturns503` / `processQuestionReplyMissingSessionReturns404EvenIfOffline`）。把每处 `service.processQuestionReply(...)` 的实参从：

```java
service.processQuestionReply(
        "im", "direct", "dm-001", "assist-001",
        "yes", "tc-001", null, null);
```

改为（在 `assist-001` 之后、content 之前插入 `"user-001"`）：

```java
service.processQuestionReply(
        "im", "direct", "dm-001", "assist-001",
        "user-001",
        "yes", "tc-001", null, null);
```

对 `processQuestionReplyAgentOfflineReturns503` / `processQuestionReplyMissingSessionReturns404EvenIfOffline` 两个调用做同样插入（保持每个用例原有的 content / toolCallId / subagentSessionId / inboundSource 不变）。

- [ ] **Step 2: 在 `processQuestionReplySessionReady` 追加 `sendUserAccount` 出站 payload 断言**

在 `processQuestionReplySessionReady()` 方法尾部已有 `assertTrue(captor.getValue().payload().contains("tool-001"));` 之后追加：

```java
assertTrue(captor.getValue().payload().contains("user-001"),
        "gateway payload should contain sendUserAccount=user-001");
assertTrue(captor.getValue().payload().contains("sendUserAccount"),
        "gateway payload should carry sendUserAccount field");
```

- [ ] **Step 3: 修改 `ExternalInboundControllerTest.questionReplyAction` 的 verify 参数加 `eq("user-001")`**

把：

```java
verify(processingService).processQuestionReply(
        eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
        eq("A"), eq("tc-1"), isNull(), eq("EXTERNAL"));
```

改为：

```java
verify(processingService).processQuestionReply(
        eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
        eq("user-001"),
        eq("A"), eq("tc-1"), isNull(), eq("EXTERNAL"));
```

同一测试顶部 `when(processingService.processQuestionReply(any(), any(), any(), any(), any(), any(), any(), any()))` 的 `any()` 数量也要从 8 个增加到 9 个（与新签名保持一致）：

```java
when(processingService.processQuestionReply(
        any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(InboundResult.ok());
```

- [ ] **Step 4: 运行测试验证编译失败**

```bash
mvn -pl skill-server -am test -Dtest='InboundProcessingServiceTest,ExternalInboundControllerTest'
```

**Expected**: 编译失败。`InboundProcessingService.processQuestionReply` 当前签名只接 8 参，测试传了 9 参；`ExternalInboundController` 的调用同理。

- [ ] **Step 5: 修改 `InboundProcessingService.processQuestionReply` 签名与出站 payload**

把整个方法改为：

```java
public InboundResult processQuestionReply(String businessDomain, String sessionType,
                                           String sessionId, String assistantAccount,
                                           String senderUserAccount,
                                           String content, String toolCallId,
                                           String subagentSessionId, String inboundSource) {
    AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
    if (resolveResult == null) {
        return InboundResult.error(404, "Invalid assistant account");
    }
    String ak = resolveResult.ak();
    String ownerWelinkId = resolveResult.ownerWelinkId();

    SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
    if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
        return InboundResult.error(404, "Session not found or not ready", sessionId,
                session != null ? String.valueOf(session.getId()) : null);
    }

    InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
    if (offline != null) return offline;

    String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
    Map<String, String> payloadFields = new LinkedHashMap<>();
    payloadFields.put("answer", content);
    payloadFields.put("toolCallId", toolCallId);
    payloadFields.put("toolSessionId", targetToolSessionId);
    payloadFields.put("sendUserAccount", senderUserAccount);
    gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
            ak, ownerWelinkId, String.valueOf(session.getId()),
            GatewayActions.QUESTION_REPLY,
            PayloadBuilder.buildPayload(objectMapper, payloadFields)));

    writeInvokeSource(session, inboundSource);
    return InboundResult.ok(sessionId, String.valueOf(session.getId()));
}
```

- [ ] **Step 6: 修改 `ExternalInboundController` 的 question_reply case 传 `senderUserAccount`**

```java
case "question_reply" -> processingService.processQuestionReply(
        request.getBusinessDomain(), request.getSessionType(),
        request.getSessionId(), request.getAssistantAccount(),
        request.getSenderUserAccount(),
        request.payloadString("content"), request.payloadString("toolCallId"),
        request.payloadString("subagentSessionId"), "EXTERNAL");
```

- [ ] **Step 7: 运行相关测试验证全绿**

```bash
mvn -pl skill-server -am test -Dtest='InboundProcessingServiceTest,ExternalInboundControllerTest'
```

**Expected**: BUILD SUCCESS，所有用例通过，`processQuestionReplySessionReady` 新增的 `sendUserAccount` payload 断言也 PASS。

- [ ] **Step 8: Commit（开发者执行）**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "feat(skill-server): thread senderUserAccount through question_reply and add to gateway payload"
```

---

## Task 5: permission_reply 新增 senderUserAccount 形参与出站 payload（TDD）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:238-283` (processPermissionReply)
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:72-76` (permission_reply case)
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java` permissionReplyAction

- [ ] **Step 1: 修改 `InboundProcessingServiceTest` 所有 processPermissionReply 调用加 `senderUserAccount`**

共 3 处调用（`processPermissionReplySessionReady` / `processPermissionReplyAgentOfflineReturns503` / `processPermissionReplyMissingSessionReturns404EvenIfOffline`）。插入 `"user-001"` 为第 5 个实参。例如 `processPermissionReplySessionReady` 改为：

```java
InboundResult result = service.processPermissionReply(
        "im", "direct", "dm-001", "assist-001",
        "user-001",
        "perm-001", "allow", null, null);
```

对另外两个用例同样在 `assist-001` 之后插入 `"user-001"`，其它参数保持原样。

- [ ] **Step 2: 在 `processPermissionReplySessionReady` 追加 `sendUserAccount` 出站 payload 断言**

在已有的 `assertTrue(invokeCaptor.getValue().payload().contains("allow"));` 之后追加：

```java
assertTrue(invokeCaptor.getValue().payload().contains("user-001"),
        "gateway payload should contain sendUserAccount=user-001");
assertTrue(invokeCaptor.getValue().payload().contains("sendUserAccount"),
        "gateway payload should carry sendUserAccount field");
```

- [ ] **Step 3: 修改 `ExternalInboundControllerTest.permissionReplyAction` 的 verify**

把：

```java
verify(processingService).processPermissionReply(
        eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
        eq("perm-1"), eq("once"), isNull(), eq("EXTERNAL"));
```

改为：

```java
verify(processingService).processPermissionReply(
        eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
        eq("user-001"),
        eq("perm-1"), eq("once"), isNull(), eq("EXTERNAL"));
```

同测试顶部 `when(processingService.processPermissionReply(any(), any(), any(), any(), any(), any(), any(), any()))` 的 `any()` 数量从 8 增到 9：

```java
when(processingService.processPermissionReply(
        any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(InboundResult.ok());
```

- [ ] **Step 4: 运行测试验证编译失败**

```bash
mvn -pl skill-server -am test -Dtest='InboundProcessingServiceTest,ExternalInboundControllerTest'
```

**Expected**: 编译失败，`processPermissionReply` 签名对不上。

- [ ] **Step 5: 修改 `InboundProcessingService.processPermissionReply` 签名与出站 payload**

```java
public InboundResult processPermissionReply(String businessDomain, String sessionType,
                                             String sessionId, String assistantAccount,
                                             String senderUserAccount,
                                             String permissionId, String response,
                                             String subagentSessionId, String inboundSource) {
    AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
    if (resolveResult == null) {
        return InboundResult.error(404, "Invalid assistant account");
    }
    String ak = resolveResult.ak();
    String ownerWelinkId = resolveResult.ownerWelinkId();

    SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
    if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
        return InboundResult.error(404, "Session not found or not ready", sessionId,
                session != null ? String.valueOf(session.getId()) : null);
    }

    InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
    if (offline != null) return offline;

    String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
    Map<String, String> payloadFields = new LinkedHashMap<>();
    payloadFields.put("permissionId", permissionId);
    payloadFields.put("response", response);
    payloadFields.put("toolSessionId", targetToolSessionId);
    payloadFields.put("sendUserAccount", senderUserAccount);
    gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
            ak, ownerWelinkId, String.valueOf(session.getId()),
            GatewayActions.PERMISSION_REPLY,
            PayloadBuilder.buildPayload(objectMapper, payloadFields)));

    // 广播权限回复消息到前端（原有逻辑不变）
    StreamMessage replyMsg = StreamMessage.builder()
            .type(StreamMessage.Types.PERMISSION_REPLY)
            .role("assistant")
            .permission(StreamMessage.PermissionInfo.builder()
                    .permissionId(permissionId)
                    .response(response)
                    .build())
            .subagentSessionId(subagentSessionId)
            .build();
    gatewayRelayService.publishProtocolMessage(String.valueOf(session.getId()), replyMsg);

    writeInvokeSource(session, inboundSource);
    return InboundResult.ok(sessionId, String.valueOf(session.getId()));
}
```

- [ ] **Step 6: 修改 `ExternalInboundController` 的 permission_reply case 传 `senderUserAccount`**

```java
case "permission_reply" -> processingService.processPermissionReply(
        request.getBusinessDomain(), request.getSessionType(),
        request.getSessionId(), request.getAssistantAccount(),
        request.getSenderUserAccount(),
        request.payloadString("permissionId"), request.payloadString("response"),
        request.payloadString("subagentSessionId"), "EXTERNAL");
```

- [ ] **Step 7: 运行相关测试验证全绿**

```bash
mvn -pl skill-server -am test -Dtest='InboundProcessingServiceTest,ExternalInboundControllerTest'
```

**Expected**: BUILD SUCCESS。

- [ ] **Step 8: Commit（开发者执行）**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "feat(skill-server): thread senderUserAccount through permission_reply and add to gateway payload"
```

---

## Task 6: rebuild 新增 senderUserAccount 形参并透传至 createSessionAsync（TDD）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:295-318` (processRebuild)
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:77-79` (rebuild case)
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java` rebuildAction

- [ ] **Step 1: 修改 `InboundProcessingServiceTest` 的 3 个 processRebuild 调用加 `senderUserAccount`**

涉及 `processRebuildSessionExists` / `processRebuildNoSession` / `processRebuildAgentOfflineReturns503`。把：

```java
InboundResult result = service.processRebuild(
        "im", "direct", "dm-001", "assist-001");
```

改为：

```java
InboundResult result = service.processRebuild(
        "im", "direct", "dm-001", "assist-001", "user-001");
```

对三个用例都做同样处理（`dm-new` 的保持 `dm-new`）。

- [ ] **Step 2: 修改 `processRebuildNoSession` 对 `createSessionAsync` 的 verify 加 senderUserAccount 断言**

把：

```java
verify(sessionManager).createSessionAsync(
        "im", "direct", "dm-new", "ak-001",
        "owner-001", "assist-001", null, null);
```

改为（第 7 位由 null 变 "user-001"，第 8 位保持 null 即 pendingMessage）：

```java
verify(sessionManager).createSessionAsync(
        "im", "direct", "dm-new", "ak-001",
        "owner-001", "assist-001", "user-001", null);
```

- [ ] **Step 3: 修改 `ExternalInboundControllerTest.rebuildAction`**

原测试：

```java
@Test
@DisplayName("rebuild dispatches correctly")
void rebuildAction() throws Exception {
    when(processingService.processRebuild(any(), any(), any(), any()))
            .thenReturn(InboundResult.ok());
    var request = buildRequest("rebuild", "{}");
    var response = controller.invoke(request);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(processingService).processRebuild("im", "direct", "dm-001", "assist-01");
}
```

改为：

```java
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
```

- [ ] **Step 4: 运行测试验证编译失败**

```bash
mvn -pl skill-server -am test -Dtest='InboundProcessingServiceTest,ExternalInboundControllerTest'
```

**Expected**: 编译失败，`processRebuild` 签名对不上（当前 4 参）。

- [ ] **Step 5: 修改 `InboundProcessingService.processRebuild` 签名并透传给 `createSessionAsync`**

```java
public InboundResult processRebuild(String businessDomain, String sessionType,
                                     String sessionId, String assistantAccount,
                                     String senderUserAccount) {
    AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
    if (resolveResult == null) {
        return InboundResult.error(404, "Invalid assistant account");
    }
    String ak = resolveResult.ak();
    String ownerWelinkId = resolveResult.ownerWelinkId();

    InboundResult offline = checkAgentOnline(businessDomain, sessionType, sessionId, ak, assistantAccount);
    if (offline != null) return offline;

    SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
    if (session != null) {
        sessionManager.requestToolSession(session, null);
        return InboundResult.ok(sessionId, String.valueOf(session.getId()));
    } else {
        sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                ak, ownerWelinkId, assistantAccount,
                senderUserAccount,
                null);
        SkillSession created = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        return InboundResult.ok(sessionId,
                created != null ? String.valueOf(created.getId()) : null);
    }
}
```

- [ ] **Step 6: 修改 `ExternalInboundController` 的 rebuild case 传 `senderUserAccount`**

```java
case "rebuild" -> processingService.processRebuild(
        request.getBusinessDomain(), request.getSessionType(),
        request.getSessionId(), request.getAssistantAccount(),
        request.getSenderUserAccount());
```

- [ ] **Step 7: 运行测试验证全绿**

```bash
mvn -pl skill-server -am test -Dtest='InboundProcessingServiceTest,ExternalInboundControllerTest'
```

**Expected**: BUILD SUCCESS。

- [ ] **Step 8: Commit（开发者执行）**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "feat(skill-server): thread senderUserAccount through rebuild to createSessionAsync"
```

---

## Task 7: processChat 群聊 sendUserAccount 与 null-sender 兜底断言（TDD）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

**目的**：
1. 给 `processChatSessionReady`（单聊分支）补 `sendUserAccount=ownerWelinkId` 的出站断言，防 4.2.1 数据源切换造成的回归
2. 新增一条"群聊 + null sender → ownerWelinkId 兜底"的 service-level 断言，把 spec 4.2.1 保留 `isBlank()` 兜底的决策钉死

- [ ] **Step 1: 在 `processChatSessionReady` 追加 sendUserAccount 断言**

在该用例末尾、`verify(rebuildService).appendPendingMessage("101", "hello");` 之后追加：

```java
assertTrue(captor.getValue().payload().contains("\"sendUserAccount\":\"owner-001\""),
        "direct chat should put ownerWelinkId as sendUserAccount");
```

- [ ] **Step 2: 新增 `processChatGroupNullSenderFallsBackToOwner` 测试**

在 `// ==================== processChat ====================` 区块末尾追加：

```java
@Test
@DisplayName("processChat: group + null sender → sendUserAccount falls back to ownerWelinkId")
void processChatGroupNullSenderFallsBackToOwner() {
    SkillSession session = buildReadySession();
    when(resolverService.resolve("assist-001"))
            .thenReturn(new AssistantResolveResult("ak-001", "owner-001"));
    when(sessionManager.findSession("im", "group", "grp-001", "ak-001"))
            .thenReturn(session);
    when(contextInjectionService.resolvePrompt(eq("group"), eq("hello"), any()))
            .thenReturn("hello");

    InboundResult result = service.processChat(
            "im", "group", "grp-001", "assist-001",
            null,
            "hello", "text", null, null, "IM");

    assertTrue(result.success());
    ArgumentCaptor<InvokeCommand> captor = ArgumentCaptor.forClass(InvokeCommand.class);
    verify(gatewayRelayService).sendInvokeToGateway(captor.capture());
    assertTrue(captor.getValue().payload().contains("\"sendUserAccount\":\"owner-001\""),
            "group chat with null sender should fall back to ownerWelinkId");
}
```

- [ ] **Step 3: 运行新测试验证直接 PASS（因为兜底逻辑本来就保留）**

```bash
mvn -pl skill-server -am test -Dtest='InboundProcessingServiceTest#processChatSessionReady+processChatGroupNullSenderFallsBackToOwner'
```

**Expected**: 两条测试 PASS。（本 Task 没有新增源码，只是把现有行为"锁"在测试里。）

- [ ] **Step 4: 运行完整 InboundProcessingServiceTest 回归**

```bash
mvn -pl skill-server -am test -Dtest=InboundProcessingServiceTest
```

**Expected**: BUILD SUCCESS。

- [ ] **Step 5: Commit（开发者执行）**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java
git commit -m "test(skill-server): pin processChat sendUserAccount fallback on null sender"
```

---

## Task 8: BusinessScopeStrategyTest 新增 sendUserAccount 抽取断言（TDD）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java`

**目的**：填补 `command.payload → CloudRequestContext.sendUserAccount` 的抽取测试盲点。**注意作用域**：仅测 `action=chat` 路径，不覆盖 q/p reply（见 spec 4.2.5 / 6.4，business scope 对 reply 的语义序列化尚未实现）。

- [ ] **Step 1: 新增 `buildInvoke_chat_extractsSendUserAccount` 测试**

在 `BusinessScopeStrategyTest.java` 的 `buildInvoke_callsCloudRequestBuilder` 之后追加：

```java
@Test
@DisplayName("buildInvoke(chat) extracts sendUserAccount from command.payload to CloudRequestContext")
void buildInvoke_chat_extractsSendUserAccount() {
    // 作用域说明：action=chat。q/p reply 在 business scope 的序列化尚未实现
    // （见 spec 4.2.5），因此不在此测试中断言。
    String payload = "{\"content\":\"hello\",\"sendUserAccount\":\"user-001\","
            + "\"assistantAccount\":\"asst-1\",\"toolSessionId\":\"tool-1\"}";
    InvokeCommand command = new InvokeCommand("ak-1", "owner-1", "session-1", "chat", payload);
    AssistantInfo info = new AssistantInfo();
    info.setAssistantScope("business");
    info.setAppId("app-123");
    when(cloudRequestBuilder.buildCloudRequest(any(), any(CloudRequestContext.class)))
            .thenReturn(objectMapper.createObjectNode());

    strategy.buildInvoke(command, info);

    ArgumentCaptor<CloudRequestContext> ctx = ArgumentCaptor.forClass(CloudRequestContext.class);
    verify(cloudRequestBuilder).buildCloudRequest(eq("app-123"), ctx.capture());
    assertEquals("user-001", ctx.getValue().getSendUserAccount(),
            "sendUserAccount should be extracted from command.payload");
    assertEquals("asst-1", ctx.getValue().getAssistantAccount());
}
```

如文件顶部缺 `import`，补：

```java
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import org.mockito.ArgumentCaptor;
```

（`InvokeCommand` / `AssistantInfo` / `objectMapper` / `cloudRequestBuilder` / `strategy` 等已由现有测试套件引入，无需新导入。如果发现缺失再按编译错误补齐。）

- [ ] **Step 2: 运行新测试验证 PASS**

```bash
mvn -pl skill-server -am test -Dtest=BusinessScopeStrategyTest#buildInvoke_chat_extractsSendUserAccount
```

**Expected**: PASS。这只证明了"字段抽取能力"，因为 `BusinessScopeStrategy.buildInvoke:65` 本来就在抽 `sendUserAccount`，此前只是没被断言。

- [ ] **Step 3: 运行完整 BusinessScopeStrategyTest 回归**

```bash
mvn -pl skill-server -am test -Dtest=BusinessScopeStrategyTest
```

**Expected**: BUILD SUCCESS。

- [ ] **Step 4: Commit（开发者执行）**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategyTest.java
git commit -m "test(skill-server): assert sendUserAccount extraction in BusinessScopeStrategy.buildInvoke for chat"
```

---

## Task 9: 全模块回归

**Files:** 无新改动，仅回归验证

- [ ] **Step 1: 运行 skill-server 完整测试**

```bash
mvn -pl skill-server -am test
```

**Expected**: BUILD SUCCESS，所有测试通过。若有任何 red：回到对应 Task 的 step 排查，不要在此 Task 修业务逻辑。

- [ ] **Step 2: 检查 lint / 编译警告**

```bash
mvn -pl skill-server -am compile -Dmaven.compile.showWarnings=true
```

**Expected**: BUILD SUCCESS；关注未使用 import / 未使用变量告警。若新增 import 有未用项，人工清理。

- [ ] **Step 3: Commit（若有编辑器自动清理产生 diff，开发者执行；否则跳过）**

```bash
git status
```

若有 diff（开发者执行）：

```bash
git add -p
git commit -m "chore(skill-server): clean up unused imports after senderUserAccount envelope refactor"
```

---

## Task 10: 更新 `docs/external-channel-api.md`

**Files:**
- Modify: `docs/external-channel-api.md`

**无单元测试**：文档变更。全部位置需严格穷举，避免官方示例 copy-paste 即 400。

- [ ] **Step 1: 顶部添加破坏性协议提示框**

在 `# External Channel 接口文档` 标题和 `> 为 IM 及其他业务模块...` 之间插入：

```markdown
> ⚠️ **协议变更**：`senderUserAccount` 已从 `chat` 的 payload 移到信封层，且所有 action 必填。
> 调用方必须在信封层传该字段，否则返回 HTTP 400。具体版本号见本次 release / changelog。
```

（版本号等合入前由人工确认替换为具体 release tag / changelog 栏目，不必在本 plan 中固定。）

- [ ] **Step 2: 1.2 请求格式的通用 JSON 示例补信封字段**

定位 `### 1.2 请求格式` 下的代码块（约第 47-56 行附近），把：

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "session-001",
  "assistantAccount": "assistant-001",
  "payload": { ... }
}
```

改为：

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "session-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": { ... }
}
```

- [ ] **Step 3: 信封字段表新增 `senderUserAccount` 行**

定位 `#### 信封字段（所有 action 必填）` 表格，在 `assistantAccount` 和 `payload` 之间插入：

```markdown
| `senderUserAccount` | String | 是 | 本次消息/回复/重建的发起用户账号 |
```

- [ ] **Step 4: 更新 1.3 各 Action 的 5 段 JSON 示例**

分别定位以下示例块，在 `assistantAccount` 那一行之后插入 `  "senderUserAccount": "user-001",`：

1. `### 1.3 各 Action 的 Payload 定义` → `#### action = chat（发送消息）` → 请求示例（direct 单聊）
2. 同节群聊带历史消息示例
3. `#### action = question_reply（回复 Agent 提问）` → 请求示例
4. `#### action = permission_reply（回复权限请求）` → 请求示例
5. `#### action = rebuild（重建会话）` → 请求示例

每个示例改动只有一行。例如 chat direct 示例原为：

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "payload": {
    "content": "帮我查一下今天的天气",
    "msgType": "text"
  }
}
```

改为：

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "content": "帮我查一下今天的天气",
    "msgType": "text"
  }
}
```

其余 4 段同模式修改，`user-001` 字面量保持一致。

- [ ] **Step 5: 1.4 响应格式 400 错误表新增 `senderUserAccount is required` 行**

定位 `HTTP 400（信封/Payload 校验失败）` 的错误表，在表末（`payload.response must be once/always/reject` 之后）新增：

```markdown
| `senderUserAccount is required` | 缺少信封字段 senderUserAccount（任何 action） |
```

- [ ] **Step 6: 4.3 Python 客户端示例 3 处 body 补字段**

定位 `### 4.3 客户端代码示例（Python）` 代码块，分别修改 3 处 `requests.post(...)` 的 json body：

1. chat（约第 1246-1253 行）：在 `"assistantAccount": "my-assistant",` 之后加 `"senderUserAccount": "user-001",`
2. permission_reply（约第 1274-1283 行）：同上插入位置
3. question_reply（约第 1289-1298 行）：同上插入位置

例 chat：

```python
json={
    "action": "chat",
    "businessDomain": SOURCE,
    "sessionType": "direct",
    "sessionId": "my-session-001",
    "assistantAccount": "my-assistant",
    "senderUserAccount": "user-001",
    "payload": {"content": "你好", "msgType": "text"}
}
```

其余两处同模式。

- [ ] **Step 7: 目视检查全文 `senderUserAccount` 出现次数**

```bash
rg -n "senderUserAccount" docs/external-channel-api.md
```

**Expected**: 至少 10+ 行（1 表格行、1 破坏性提示、1 通用示例、5 个 action 示例、3 个 Python 示例、1 错误码表行）。若少于预期，回 step 2–6 检查遗漏。

- [ ] **Step 8: Commit（开发者执行）**

```bash
git add docs/external-channel-api.md
git commit -m "docs(external-channel-api): document senderUserAccount envelope field"
```

---

## Self-Review

完成以上所有 Task 后，人工或执行者按下面 checklist 复核：

**1. Spec 覆盖度（逐条对齐 spec）：**

| Spec 章节 | 覆盖的 Task |
|---|---|
| 4.1 信封契约变更（model + validator + 删除 payloadString） | Task 1（model）+ Task 2（validator）+ Task 3（chat）+ Tasks 4-6（其他 action 的 payloadString 本就没有，无需删） |
| 4.2 跨 action 身份语义说明 | 无代码改动，通过 Task 4-6 的无条件透传实现 |
| 4.2.1 processChat 保留兜底 | Task 3 只改数据源，`isBlank()` 不改动；Task 7 测试固化 |
| 4.2.2 processQuestionReply | Task 4 |
| 4.2.3 processPermissionReply | Task 5 |
| 4.2.4 processRebuild → createSessionAsync 透传 | Task 6 |
| 4.2.5 云端 scope 收益分解 | Task 8（仅证 chat 抽取）；reply 序列化缺口列为非范围 |
| 4.3 ImSessionManager 不动 | 无 Task（设计上明确不改） |
| 4.4 ImInboundController 不动 | 无 Task（签名未变自动兼容） |
| 4.5 错误码新增 | Task 2（senderUserAccount is required）+ Task 10（文档） |
| 5 文档更新（6 处位置） | Task 10 六条 step 对齐 |
| 6.1 ExternalInboundControllerTest | Task 1-6 逐 action 更新 + Task 2 新增必填/钉死 |
| 6.2 InboundProcessingServiceTest | Task 4-7 |
| 6.3 GatewayRelayServiceTest 不动 | 无 Task |
| 6.4 BusinessScopeStrategyTest chat 抽取 | Task 8 |
| 6.5 不新增端到端集成 | 无 Task |
| 7 影响面评估 | Task 1-10 已覆盖所有文件 |
| 8 非范围 | 不实现：SkillMessageController reply 补齐 / BusinessScope reply 序列化 / 命名统一 / IM 协议收紧 / processChat 死枝清理 / D2/D3 过渡 / 端到端新测 |

**2. Placeholder scan:** 全文 `rg -n "TODO|TBD|FIXME" docs/superpowers/plans/2026-04-22-external-sender-user-account-envelope.md`，命中即修。

**3. Type consistency:**

- 新签名 `processQuestionReply`(9 参) / `processPermissionReply`(9 参) / `processRebuild`(5 参) 所有调用点（Controller + Test + Controller Test）一致
- 出站 payload 字段名 **`sendUserAccount`**（`send` 少 er），信封字段名 **`senderUserAccount`**（`sender` 带 er），在 spec 4.2 命名不对称说明中已标注；全 plan 严格使用两个不同写法，不要混用
- `ArgumentCaptor<InvokeCommand>` / `ArgumentCaptor<CloudRequestContext>` 类型与现有测试风格一致

---

## 执行方式

本 plan 支持两种落地模式：

- **Subagent-Driven**：每个 Task 派发一个新 subagent，Task 之间做两阶段 review，上下文干净、迭代快。对应 `superpowers:subagent-driven-development` 技能。
- **Inline Execution**：在单会话中用 `superpowers:executing-plans` 技能批量执行，按 checkpoint review。上下文连续，调错方便。

**项目约定**：无论哪种模式，执行者/subagent 完成 Task 内非 commit step 后，**将 commit 命令以代码块形式打印给用户，由人类执行 `git add` / `git commit`**（见本 plan 顶部"项目执行约定"）。
