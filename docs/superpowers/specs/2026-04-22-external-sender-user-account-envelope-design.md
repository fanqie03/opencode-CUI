# External Invoke Sender User Account Envelope Design

**日期**：2026-04-22
**作者**：Llllviaaaa
**状态**：Draft
**模块**：`skill-server`
**相关文件**：
- `skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java`
- `docs/external-channel-api.md`

---

## 1. 背景与动机

`/api/external/invoke` 接口当前对 `senderUserAccount` 字段的处理存在两处不对称：

1. **位置不对称**：在 `chat` action 下，字段位于 `payload.senderUserAccount`；而同样语义的 IM inbound 接口（`ImMessageRequest`）将 `senderUserAccount` 作为顶层信封字段。
2. **覆盖不对称**：在 external 入口上，只有 `chat` 分支读取该字段，`question_reply` / `permission_reply` / `rebuild` 三个 action 完全不涉及。这意味着**external 链路**上的审计、多用户群聊归属、以及云端助手 HTTP 请求的 `sendUserAccount` 透传，在非 chat 路径上是缺失的。

另外，`docs/external-channel-api.md` 的 chat payload 字段表**未列出** `senderUserAccount` —— 代码读取了，但从未作为公开契约暴露，这让本次协议调整的破坏面比字面数字更小。

本次调整将 `senderUserAccount` 提升为 external 信封级字段，所有 action 必填，并将该字段一路透传到下游 service 签名与 Skill Server → Gateway 的出站 payload，使 external 接口与 IM inbound 在协议形状上对齐。

**范围限定**：本次仅修 external 链路（`ExternalInboundController` → `InboundProcessingService` 的 question_reply / permission_reply / rebuild 分支 + 出站 payload）。miniapp / 个人助手 first-party 的 reply 路径（`SkillMessageController` 的 question_reply / permission_reply 出站 payload 同样无 `sendUserAccount`，见 `SkillMessageController.java:200-203` / `:403-406`）**不在本次范围**，留作独立工单。

---

## 2. 决策摘要

| 维度 | 决定 | 编号 |
|------|------|------|
| 字段位置 | 从 `chat.payload.senderUserAccount` → 信封顶层字段 | — |
| 适用 action | 4 个全部：`chat` / `question_reply` / `permission_reply` / `rebuild` | — |
| 必填性 | **全部必填**，任何 action 漏传返回 `HTTP 400` | C3 |
| 下游 service 签名 | `processQuestionReply` / `processPermissionReply` / `processRebuild` 都新增 `senderUserAccount` 形参；`processChat` 形参已存在，只改数据源 | B |
| 下游 Gateway payload | `question_reply` / `permission_reply` 出站 payload 新增 `sendUserAccount` 字段（与 chat 对齐；`rebuild` 不经 Gateway invoke 路径，不涉及） | B1 |
| 迁移策略 | 硬切：信封必填，`payload.senderUserAccount` 彻底忽略；文档同步更新 | D1 |

**命名不对称说明**：external 入参信封字段名为 `senderUserAccount`，Skill Server → Gateway 出站 payload 字段名为 `sendUserAccount`（少一个 "er"）。这是历史遗留命名，本次**不改**；触及 Gateway、`CloudRequestContext`、云端 HTTP 契约的命名统一属另一个工单。

---

## 3. 数据流

```
调用方 ─POST /api/external/invoke (envelope.senderUserAccount)─▶ ExternalInboundController
                                                                       │
                                                      validateEnvelope 强制必填
                                                                       │
                                                                       ▼
                            ┌──── chat ─────────▶ processChat(senderUserAccount, ...)
                            │                           │
                            │                           ├─ session 就绪 → Gateway payload.sendUserAccount
                            │                           └─ session 缺 → createSessionAsync(senderUserAccount, ...)
                            │
                            ├──── question_reply ─▶ processQuestionReply(senderUserAccount, ...)
                            │                           └─ Gateway payload.sendUserAccount ★新增
                            │
                            ├──── permission_reply ▶ processPermissionReply(senderUserAccount, ...)
                            │                           └─ Gateway payload.sendUserAccount ★新增
                            │
                            └──── rebuild ────────▶ processRebuild(senderUserAccount)
                                                        └─ session 缺 → createSessionAsync(senderUserAccount, ...)

                                                                       │
                                       ┌───────────────────────────────┴─────────────────────┐
                                       ▼                                                     ▼
                            personal scope (payload 原样透传)                       business scope
                                                                         BusinessScopeStrategy 已有逻辑自动
                                                                         抽取 payload.sendUserAccount →
                                                                         CloudRequestContext.sendUserAccount →
                                                                         云端 HTTP body.sendUserAccount
```

---

## 4. 设计细节

### 4.1 信封契约变更

**`ExternalInvokeRequest.java`** 新增顶层字段：

```java
@Data
public class ExternalInvokeRequest {
    private String action;
    private String businessDomain;
    private String sessionType;
    private String sessionId;
    private String assistantAccount;
    private String senderUserAccount;   // ← 新增
    private JsonNode payload;
    // payloadString(...) 保持不变
}
```

**`ExternalInboundController.validateEnvelope`** 对所有 action 强制必填（和 `assistantAccount` 同级）：

```java
if (request.getSenderUserAccount() == null || request.getSenderUserAccount().isBlank())
    return "senderUserAccount is required";
```

`validatePayload` 阶段**不再**校验 `payload.senderUserAccount`；相关逻辑及 `request.payloadString("senderUserAccount")` 调用**全部删除**。

**`invoke` 方法的 switch 分发**改从信封取字段：

```java
case "chat" -> processingService.processChat(
        request.getBusinessDomain(), request.getSessionType(),
        request.getSessionId(), request.getAssistantAccount(),
        request.getSenderUserAccount(),                           // ← 从信封
        request.payloadString("content"), ...,
        "EXTERNAL");

case "question_reply" -> processingService.processQuestionReply(
        ..., request.getSenderUserAccount(), ..., "EXTERNAL");    // ← 下游新增位

case "permission_reply" -> processingService.processPermissionReply(
        ..., request.getSenderUserAccount(), ..., "EXTERNAL");    // ← 同上

case "rebuild" -> processingService.processRebuild(
        ..., request.getSenderUserAccount());                     // ← 同上
```

### 4.2 下游 service 签名与 Gateway 出站 payload

**跨 action 身份语义说明**：

- `processChat` 的 `effectiveSender` 仍保留 `group` 用 `senderUserAccount` / `direct` 用 `ownerWelinkId` 的分支 —— 这是产品语义（"单聊对话人就是 session owner"），不因 C3 必填而改变。
- `processQuestionReply` / `processPermissionReply` 将 `senderUserAccount` **无条件透传**（无 direct/group 分支）—— 因为 reply 场景下该字段语义是"谁触发了这次回复"，触发者就是触发者，不存在"单聊触发者应该归为 owner"的业务诉求。

这两种处理方式**是有意的差异**，不是疏漏。

#### 4.2.1 `processChat` — 仅改数据源，签名不变，不清理死枝

`senderUserAccount` 形参已存在，只是数据源从 `payload` 换信封。

**不能**清理现有的 `isBlank()` 兜底（`InboundProcessingService.java:163-167`）：

```java
// 保持原样，不改
String effectiveSender = "group".equals(sessionType)
        && senderUserAccount != null && !senderUserAccount.isBlank()
        ? senderUserAccount : ownerWelinkId;
payloadFields.put("sendUserAccount", effectiveSender);
```

理由：`processChat` 同时服务于 `ExternalInboundController`（C3 后非空）与 `ImInboundController.java:61-72`。IM 路径下 `senderUserAccount` 仍可空 —— `ImMessageRequest` 是 record（`ImMessageRequest.java:18-27`），字段无 `@NotBlank` / `@NotNull` 校验；`ImInboundController` 直接把 `request.senderUserAccount()` 透传给 `processChat`，该值完全可能为 `null`。如果此处删除 `isBlank()` 兜底，IM 群聊 null sender 会把 `null` 直接塞进 Gateway payload 的 `sendUserAccount` 字段，破坏现有行为。

**原则**：死枝清理只能作用于 **external 独占**的代码路径；`processChat` 是 external / IM 共享路径，保留兜底是必要的。

**覆盖归属**：该 null 兜底分支的**测试覆盖**归属于 `InboundProcessingServiceTest`（service-level 断言 `processChat` 在 null/blank sender + group 下回退到 ownerWelinkId），而不是 `ImInboundControllerTest` —— 后者是 controller delegation 测试，`processingService` 是 mock，不会真正执行 `processChat` 内部分支。若现有 service-level 用例未覆盖此分支，需新增，见 6.2。

#### 4.2.2 `processQuestionReply` — 新增形参 + 出站 payload

```java
public InboundResult processQuestionReply(
        String businessDomain, String sessionType, String sessionId,
        String assistantAccount,
        String senderUserAccount,                       // ← 新增
        String content, String toolCallId,
        String subagentSessionId, String inboundSource) {
    // ...既有解析 / 在线检查 / session 查找逻辑不变...
    Map<String, String> payloadFields = new LinkedHashMap<>();
    payloadFields.put("answer", content);
    payloadFields.put("toolCallId", toolCallId);
    payloadFields.put("toolSessionId", targetToolSessionId);
    payloadFields.put("sendUserAccount", senderUserAccount);  // ← B1 新增
    gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
            ak, ownerWelinkId, String.valueOf(session.getId()),
            GatewayActions.QUESTION_REPLY,
            PayloadBuilder.buildPayload(objectMapper, payloadFields)));
    // ...
}
```

#### 4.2.3 `processPermissionReply` — 新增形参 + 出站 payload

```java
public InboundResult processPermissionReply(
        String businessDomain, String sessionType, String sessionId,
        String assistantAccount,
        String senderUserAccount,                       // ← 新增
        String permissionId, String response,
        String subagentSessionId, String inboundSource) {
    // ...既有逻辑不变...
    Map<String, String> payloadFields = new LinkedHashMap<>();
    payloadFields.put("permissionId", permissionId);
    payloadFields.put("response", response);
    payloadFields.put("toolSessionId", targetToolSessionId);
    payloadFields.put("sendUserAccount", senderUserAccount);  // ← B1 新增
    gatewayRelayService.sendInvokeToGateway(...);
    // ...
}
```

#### 4.2.4 `processRebuild` — 新增形参，透传到 sessionManager

`rebuild` **不走** `GatewayRelayService.sendInvokeToGateway`，所以 B1 的"出站 payload 对齐"对它不适用。新增形参用于 session 不存在时的异步创建路径：

```java
public InboundResult processRebuild(
        String businessDomain, String sessionType, String sessionId,
        String assistantAccount,
        String senderUserAccount) {                     // ← 新增
    // ...既有解析 / 在线检查 / findSession 逻辑不变...
    if (session != null) {
        sessionManager.requestToolSession(session, null);
        return InboundResult.ok(sessionId, String.valueOf(session.getId()));
    } else {
        sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                ak, ownerWelinkId, assistantAccount,
                senderUserAccount,                      // ← 从 null 改为透传
                null);
        // ...
    }
}
```

#### 4.2.5 下游 scope 收益分解（按路径分开陈述，避免夸大）

本次变更对 `sendUserAccount` 在 Skill Server → Gateway / 云端的实际收益，按 scope × action 分解如下：

| scope | action | 本次收益 | 说明 |
|---|---|---|---|
| personal | chat | 无新增 | 原本就通过 payload.sendUserAccount 传给 Agent |
| personal | question_reply | Gateway invoke payload.sendUserAccount 新增 | 来自 4.2.2；通过 WS 原样下发给个人助手，不涉及"云端 HTTP body" |
| personal | permission_reply | 同上 | 来自 4.2.3 |
| personal | rebuild | 无（不经 gateway invoke） | 4.2.4 只影响 createSessionAsync 的入参，不触及 Gateway |
| business | chat | 云端 HTTP body.sendUserAccount 透传（原已工作，仍然工作） | `BusinessScopeStrategy.buildInvoke:65` 抽 sendUserAccount 进 `CloudRequestContext`，经 `CloudRequestBuilder` 序列化进 HTTP body |
| business | question_reply / permission_reply | **本次无收益** | 见下方独立说明 |

**关于 business scope 的 reply 路径**（重要偏差说明）：

`BusinessScopeStrategy.buildInvoke` (`BusinessScopeStrategy.java:51-104`) 只从 command payload 抽取通用字段（`content` / `assistantAccount` / `sendUserAccount` / `imGroupId` / `messageId` / `toolSessionId`）构造 `CloudRequestContext`，**丢弃了** reply 所需的 `answer` / `toolCallId` / `permissionId` / `response`。Gateway 侧 `CloudAgentService.handleInvoke` (`CloudAgentService.java:58-59`) 也只消费 `payload.cloudRequest` + `payload.toolSessionId`。

这与 `docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md:1294-1345`（10.2 / 10.3 节）所定义的 "GW → 云端旁路 `/reply` REST" 协议草案**尚未落地**。因此：

- 即使 4.2.2 / 4.2.3 把 `sendUserAccount` 塞进了 `question_reply` / `permission_reply` 的 gateway payload，`BusinessScopeStrategy` 在序列化时**既不会把 answer/permissionId 传出去、也不会保留 reply 语义**，本次变更对 business scope 的 reply 路径没有任何端到端收益。
- `sendUserAccount` 会被 `BusinessScopeStrategy.extractField` 抽到 `CloudRequestContext`，但整个 `cloudRequest` 的 reply 语义本就缺失，字段能被抽到不代表路径可用。

**云端 HTTP 契约影响**：**仅 `chat` action**。business scope 的 chat HTTP body 已经包含 `sendUserAccount`（现状），本次变更对该路径无字段变化。若未来落地 10.2/10.3 的旁路 reply，那个工单需要自行决定是否把 sendUserAccount 放进旁路 body，并与云端对齐 schema —— 不属本次职责。

### 4.3 `ImSessionManager.createSessionAsync` 的不对称处理

`ImSessionManager.createSessionAsync` 同样是 external **和** IM inbound 共用路径。IM inbound 路径下 `senderUserAccount` 仍可空，因此 `ImSessionManager.java:151-155` 的 `isBlank()` 兜底**保持不动**：

```java
// ImSessionManager.java — 保持原样
String effectiveSender = "group".equals(sessionType)
        && senderUserAccount != null && !senderUserAccount.isBlank()
        ? senderUserAccount : ownerWelinkId;
payloadFields.put("sendUserAccount", effectiveSender);
```

这与 4.2.1 中 `processChat` 保留兜底的决策一致：两处都是 external / IM 共享路径，均不清理。

### 4.4 `ImInboundController` 不受影响

`ImInboundController` 只调 `processChat`（该方法签名未变），其他三个下游方法的 IM 侧调用不存在。签名同步只确保编译通过，**本次不在 IM 侧新增调用**。

### 4.5 错误处理

`validateEnvelope` 在其他信封校验（`action` / `businessDomain` / `sessionType` / `sessionId` / `assistantAccount`）之后、`validatePayload` 之前执行 `senderUserAccount` 必填判断。新增错误码：

| errormsg | HTTP | 触发场景 |
|---|---|---|
| `senderUserAccount is required` | 400 | 任意 action 的信封漏传 `senderUserAccount` |

其他错误码保持不变：503 离线、404 session / assistant 不存在、认证失败 401 语义全部沿用。

---

## 5. 文档变更

`docs/external-channel-api.md` 需要更新的**所有**位置（严格穷举，避免漏改导致官方示例复制即 400）：

1. **信封字段表**（`external-channel-api.md:60` 附近）新增一行：

   ```diff
    | `assistantAccount` | String | 是 | 助手账号（用于解析对应的 AK 和 Agent） |
   +| `senderUserAccount` | String | 是 | 本次消息/回复/重建的发起用户账号 |
    | `payload` | Object | 是 | 各 action 的专属数据（JSON 对象） |
   ```

2. **1.2 请求格式的通用 JSON 示例**（`external-channel-api.md:47-56`）在信封位置补字段。

3. **1.3 各 Action 的请求 JSON 示例**（chat / question_reply / permission_reply / rebuild 4 处，含 chat 的 direct / 群聊带历史两份示例，共 **5 段 JSON**）全部在信封位置补 `"senderUserAccount": "user-001"`。

4. **4.3 Python 客户端示例**中 3 处 `requests.post(...)` 的 json body（`external-channel-api.md:1243-1254` chat、`:1274-1283` permission_reply、`:1289-1298` question_reply）全部加 `"senderUserAccount": "user-001"`（或从上下文变量取）。

5. **1.4 响应格式 — 400 错误表**新增一行 `senderUserAccount is required`。

6. **顶部 / "1. REST 接口" 小节开头**添加破坏性变更提示框：

   ```markdown
   > ⚠️ **协议变更（vX.Y）**：`senderUserAccount` 已从 `chat` 的 payload 移到信封，且所有 action 必填。
   > 调用方必须在信封层传该字段，否则返回 HTTP 400。
   ```

   版本号占位 `vX.Y` 在合入前替换为实际 release 编号 / changelog 栏目名。

---

## 6. 测试更新

### 6.1 `ExternalInboundControllerTest`

**Helper 改动** — `buildRequest` 的基础 JSON 增加信封 `senderUserAccount`：

```java
String json = "{\"action\":\"" + action + "\","
        + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
        + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
        + "\"senderUserAccount\":\"user-001\","
        + "\"payload\":" + payload + "}";
```

**4 个 action 的分发断言**加 `eq("user-001")` 实参位：

- `processChat` 第 5 参从 `isNull()` → `eq("user-001")`
- `processQuestionReply` / `processPermissionReply` 新增 `eq("user-001")` 位
- `processRebuild` 从 4 参扩到 5 参，最后一位 `eq("user-001")`

**新增测试** — 信封必填校验（循环风格，紧凑；如定位失败体验差可后续拆为 4 个方法）：

```java
@Test
@DisplayName("missing senderUserAccount returns 400 for all actions")
void missingSenderUserAccountReturns400() throws Exception {
    for (String action : List.of("chat", "question_reply", "permission_reply", "rebuild")) {
        String json = """
            {"action":"%s","businessDomain":"im","sessionType":"direct",
             "sessionId":"dm-001","assistantAccount":"assist-01","payload":{}}
            """.formatted(action);
        var request = objectMapper.readValue(json, ExternalInvokeRequest.class);
        var response = controller.invoke(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("senderUserAccount is required", response.getBody().getErrormsg());
    }
}
```

**新增测试（钉死 D1 硬切）** — 旧形状（字段只在 `payload`、信封缺失）仍然返回 400，防止未来回退成 fallback：

```java
@Test
@DisplayName("D1 hard cut: payload.senderUserAccount is ignored, envelope required")
void legacyPayloadSenderUserAccountIsIgnored() throws Exception {
    // 旧客户端形状：senderUserAccount 只在 payload 里，信封缺失
    String json = """
        {"action":"chat","businessDomain":"im","sessionType":"direct",
         "sessionId":"dm-001","assistantAccount":"assist-01",
         "payload":{"content":"hello","senderUserAccount":"legacy-user"}}
        """;
    var request = objectMapper.readValue(json, ExternalInvokeRequest.class);
    var response = controller.invoke(request);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("senderUserAccount is required", response.getBody().getErrormsg());
    verifyNoInteractions(processingService);
}
```

### 6.2 `InboundProcessingServiceTest`

**签名更新** — 12 个用例里所有对 `processChat` / `processQuestionReply` / `processPermissionReply` / `processRebuild` 的调用都加 `senderUserAccount` 实参（统一 `"user-001"`）。

**B1 关键新增断言** — `processQuestionReplySessionReady` 与 `processPermissionReplySessionReady` 抓取 `gatewayRelayService.sendInvokeToGateway` 的 `InvokeCommand`，断言 payload JSON 含 `"sendUserAccount":"user-001"`：

```java
ArgumentCaptor<InvokeCommand> cmd = ArgumentCaptor.forClass(InvokeCommand.class);
verify(gatewayRelayService).sendInvokeToGateway(cmd.capture());
JsonNode payload = objectMapper.readTree(cmd.getValue().payload());
assertEquals("user-001", payload.get("sendUserAccount").asText());
```

同时顺手为 `processChatSessionReady` 群聊分支补一个 `sendUserAccount` 断言，防止 4.2.1 死枝清理导致行为退化。

**processRebuild 的 createSessionAsync 断言** — `processRebuildNoSession` 用例里，`createSessionAsync` 的 `senderUserAccount` 参数位从 `null` / 未断言 → `eq("user-001")`。

**processChat 用例不删除兜底测试** — 因 4.2.1 保留 `isBlank()` 兜底，现有 `processChat*` 用例中以 null / 空白 senderUserAccount 走 external 路径的场景仍然有效。扫描现有用例，仅将 **external 路径**（`inboundSource="EXTERNAL"`）的 null sender 改为 `"user-001"`（C3 后该路径不会再出现 null）；**IM 路径**（`inboundSource="IM"`）的 null sender 保留。

**新增 service-level 兜底分支断言** — 现有测试未在 service 层证明 `processChat` null/blank sender 的 fallback 行为（`ImInboundControllerTest` 只是 controller delegation + mock，不执行 service 内分支）。新增一条直接在 `InboundProcessingServiceTest` 里打到 `processChat` 内部的断言，钉住"null/blank sender + group → ownerWelinkId 回退"的行为，防止后续有人误以为是死枝再次删除：

```java
@Test
@DisplayName("processChat: group + null/blank sender → sendUserAccount falls back to ownerWelinkId")
void processChatGroupNullSenderFallsBackToOwner() {
    // Arrange — session 已就绪，走出站 invoke 路径
    when(resolverService.resolve("assist-01"))
            .thenReturn(new AssistantResolveResult("ak-1", "owner-welink-01"));
    // ...其余 mock 与其他 processChat*SessionReady 用例一致...
    SkillSession readySession = ...;
    when(sessionManager.findSession("im", "group", "grp-001", "ak-1")).thenReturn(readySession);

    // Act — 群聊 + senderUserAccount=null（模拟 IM 入站 null sender）
    service.processChat("im", "group", "grp-001", "assist-01",
            null,                                   // ← null sender
            "hello", "text", null, null, "IM");

    // Assert — Gateway 出站 payload 的 sendUserAccount 回退到 ownerWelinkId
    ArgumentCaptor<InvokeCommand> cmd = ArgumentCaptor.forClass(InvokeCommand.class);
    verify(gatewayRelayService).sendInvokeToGateway(cmd.capture());
    JsonNode payload = objectMapper.readTree(cmd.getValue().payload());
    assertEquals("owner-welink-01", payload.get("sendUserAccount").asText());
}
```

对空白字符串（`" "`）路径，若希望同等严格可再加一条 `processChatGroupBlankSenderFallsBackToOwner`（参数化或独立方法均可）。

### 6.3 `GatewayRelayServiceTest`

不受影响。该层是 transport 层，不关心 payload 语义。

### 6.4 `BusinessScopeStrategyTest` — 仅证 chat 路径的字段抽取能力

**作用域限定**：该用例**只证明** `command.payload.sendUserAccount → CloudRequestContext.sendUserAccount` 的抽取能力（填补 `BusinessScopeStrategy` 的测试盲点），**不宣称**对 q/p reply 的云端收益 —— 如 4.2.5 所述，business scope 的 reply 路径本身尚未实现 reply 语义序列化。

现有覆盖盲点：

- `CloudRequestBuilderTest.java:108` 测的是 `CloudRequestContext → JSON`，起点已经是构造好的 Context。
- `BusinessScopeStrategyTest.java:94-110` 仅断言 `cloudRequestBuilder.buildCloudRequest(...)` 被调用，未捕获并断言 `CloudRequestContext` 的字段。

新增用例（或扩展现有 `buildInvoke_callsCloudRequestBuilder`）用 `ArgumentCaptor<CloudRequestContext>` 捕获 chat 场景下的 Context，断言 `sendUserAccount` 正确从 command.payload 抽出：

```java
@Test
@DisplayName("buildInvoke(chat) extracts sendUserAccount from command.payload to CloudRequestContext")
void buildInvoke_chat_extractsSendUserAccount() {
    // 注意：action 固定为 chat。q/p reply 在 business scope 的序列化尚未实现（见 spec 4.2.5），
    // 本用例不覆盖 q/p reply 场景，避免误导性测试。
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
    assertEquals("user-001", ctx.getValue().getSendUserAccount());
    assertEquals("asst-1", ctx.getValue().getAssistantAccount());
}
```

**不新增** q/p reply 场景的 BusinessScope 测试：在 reply 序列化缺口填补之前，这类测试只能证明"`sendUserAccount` 字段可以被抽到，然后 Context 进了一个没 reply 语义的 cloudRequest"——没有业务意义。

### 6.5 不新增端到端集成测试

单元测试链覆盖：信封校验（6.1）→ controller 透传（6.1）→ service 出站 payload（6.2）→ BusinessScopeStrategy 的 chat 字段抽取（6.4）→ CloudRequestBuilder 序列化（`CloudRequestBuilderTest`）。business scope 的 q/p reply 端到端因上游序列化缺口尚未落地，不在本次可测范围。

---

## 7. 影响面评估

| 层次 | 影响 |
|---|---|
| `ExternalInvokeRequest` | 新增 1 字段 |
| `ExternalInboundController` | validator 新增 1 条规则；4 个 switch 分支改数据源；删除 `payloadString("senderUserAccount")` 调用 |
| `InboundProcessingService` | `processChat` 仅改数据源（保留 `isBlank()` 兜底，因 IM 共享）；`processQuestionReply` / `processPermissionReply` / `processRebuild` 签名各新增 1 参；前两者出站 payload 各新增 1 字段 |
| `ImSessionManager` | 不变 |
| `ImInboundController` | 不变（仅调 `processChat`，签名未变） |
| `SkillMessageController` | 不变（miniapp first-party reply 路径的 `sendUserAccount` 补齐不在本次范围） |
| `BusinessScopeStrategy` / `CloudRequestBuilder` 源码 | 不变。注意 business scope 的 q/p reply **本身不序列化 reply 语义**（见 4.2.5），本次对其 reply 路径无端到端收益；对 chat 路径的 HTTP body 维持现状，无字段变化 |
| `docs/external-channel-api.md` | 新增信封字段说明、**5 段 JSON 示例 + 3 处 Python 客户端 body** 补字段、新增 400 错误码、破坏性提示框 |
| 单元测试 | `ExternalInboundControllerTest`（基础断言 + 必填测试 + D1 硬切钉死测试）、`InboundProcessingServiceTest`（签名更新 + 出站断言）、`BusinessScopeStrategyTest`（新增 `sendUserAccount` 抽取断言） |

### 破坏性

**调用方必须同步改造**：本次为硬切，发布后所有 `/api/external/invoke` 调用必须在信封层带 `senderUserAccount`，否则返回 400。由于 `docs/external-channel-api.md` 从未将该字段作为公开契约列出，预期影响面主要是与 external channel 对接的已知业务方（如 IM 平台自身的 external 链路）。

### 回滚

如需回滚：
1. `ExternalInvokeRequest` 删除 `senderUserAccount` 字段；
2. `validateEnvelope` 删除必填规则；
3. Controller 的 `chat` 分支恢复 `request.payloadString("senderUserAccount")`；
4. `processQuestionReply` / `processPermissionReply` / `processRebuild` 恢复原签名，出站 payload 删除 `sendUserAccount`；
5. 文档回退。

回滚路径清晰，未修改任何持久化 schema，无数据层影响。

---

## 8. 非范围（YAGNI）

以下内容**不**在本次范围：

- **miniapp / 个人助手 first-party reply 路径补齐** `sendUserAccount`：`SkillMessageController.java:200-203`（question_reply 出站 payload）与 `:403-406`（permission_reply 出站 payload）当前均无 `sendUserAccount` 字段。本次仅对齐 external 链路，first-party 路径留作独立工单。
- **`BusinessScopeStrategy` 对 question_reply / permission_reply 的 reply 语义序列化**（即落地 `cloud-agent-protocol.md` 10.2 / 10.3 描述的旁路 `/reply` REST 桥接）：当前 `buildInvoke` 丢弃 `answer` / `toolCallId` / `permissionId` / `response`，`CloudAgentService` 也只消费 `cloudRequest` + `toolSessionId`；该缺口与本次协议对齐正交，属独立工单。届时再决定云端 `/reply` body 是否携带 `sendUserAccount`，并与云端对齐 schema。
- **业务路由行为变更**（C 选项）：如"群聊 question_reply 只允许原提问对象回复" / "permission_reply 做发起人权限校验"。本次仅对齐协议形状与数据透传，不变更路由判断。
- **字段命名统一**（将 Gateway 出站 / `CloudRequestContext` 的 `sendUserAccount` 也改为 `senderUserAccount`）。涉及 Gateway、云端 HTTP 契约等多方，属独立工单。
- **IM inbound 协议收紧**（把 `ImMessageRequest.senderUserAccount` 也改为必填）。本次仅对 external 接口生效。
- **`processChat` 死枝清理**（删除 `isBlank()` 兜底）：因 `processChat` 是 external / IM 共享路径，IM 侧仍可空，清理会破坏 IM 现有行为。保留现状。
- **双读 / 双写过渡期**（D2 / D3）：已明确选择硬切（D1）。
- **端到端集成测试新增**：现有单元测试链已覆盖。
