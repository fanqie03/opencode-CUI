# 类型安全

> `skill-server` 当前的类型策略是“可变持久化模型 + 不可变 record 命令对象 + 单一 `StreamMessage` 聚合 DTO”。

---

## 概览

- **Java 21**：允许 `record`、switch 表达式等现代语法
- **Lombok**：用于大多数可变模型类
- **Jackson**：字段重命名、忽略、`@JsonUnwrapped`
- **MyBatis**：枚举以名字映射，不以 ordinal 映射

重要校准：

- 当前 `StreamMessage` **没有使用 Jackson 多态子类**；它是一个单一 DTO，靠嵌套静态类 + `@JsonUnwrapped` 表达不同消息组。
- `senderUserAccount` 已完成**信封层迁移**，不要再把它放进 payload。

---

## 模型分层

| 类型 | 代表类 | 用途 |
|------|--------|------|
| 持久化实体 | `SkillSession`, `SkillMessage`, `SkillMessagePart` | MySQL / MyBatis 映射 |
| 协议 DTO | `StreamMessage`, `ApiResponse`, `ExternalInvokeRequest` | WebSocket / REST 协议 |
| 命令 / 查询对象 | `InvokeCommand`, `SessionListQuery`, `ImMessageRequest` | service 间传递、不可变参数 |

代码证据：

- `SkillSession`：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:23-120`
- `SkillMessage`：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessage.java:14-76`
- `SkillMessagePart`：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessagePart.java:19-109`
- `StreamMessage`：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:23-277`
- `InvokeCommand`：`skill-server/src/main/java/com/opencode/cui/skill/model/InvokeCommand.java:54-102`

---

## Lombok 使用策略

### 1. 可变模型类使用 Lombok

`SkillSession` 是当前持久化实体的标准样式：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillSession {

    @JsonProperty("welinkSessionId")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Builder.Default
    private Status status = Status.ACTIVE;
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:23-92`

`StreamMessage` 也使用同一组合，但额外加 `@JsonInclude(NON_NULL)`：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamMessage {
    private String type;
    private Long seq;
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:23-88`

### cloud question / permission 的 SS 归一契约

当前 plugin main 会把 OpenCode 的交互事件投成 cloud protocol，SS 侧必须在 `CloudEventTranslator` 和历史 DTO 边界补齐本地协议已经天然具备的语义：

| cloud 事件 | SS `StreamMessage` 契约 | 历史 `ProtocolMessagePart` 契约 |
|----------|--------------------------|----------------------------------|
| `question` | `type=question`；缺省 `status=running`；`properties.questionId` 写入 `QuestionInfo.questionId`；`toolCallId` 保留为关联字段 | `type=question`；`toolName=question`；缺省 `status=running`；`input` 保存 canonical question payload；顶层 `questionId` 从 `input.questionId` 或 OpenCode 兼容字段 `input.id` 解析 |
| `permission.ask` | 缺省 `status=pending` | `type=permission`；`status=pending`；`permissionId` 使用 `toolCallId`/`partId` 归一 |
| `permission.reply` | 缺省 `status=completed` | 回填原 pending permission part；`status=completed`；`response` 写入原 part |

关键约束：

- `questionId` 是 reply target，不等同于展示用 `partId`；cloud 路径不能只依赖 `partId`。
- `question` 持久化时如果 `toolName` 为空，必须落成 `toolName=question`，否则历史 mapper 会把它当普通 tool。
- `question` 持久化时如果 `tool.input` 为空，必须从 `QuestionInfo` 构造 canonical input，至少保留 `header/question/options/questions/extParam/questionId` 中存在的字段。
- `tool.update(toolName=question)` 是 OpenCode question 工具生命周期事件；当同一 assistant message 已有 canonical question part 时，只能合并/更新原 question part，不能新增第二个 `type=question` 历史 part。
- `permission.ask` / `permission.reply` 的 status 可以由 cloud event 显式传入；缺省时 SS 分别补 `pending` / `completed`，保证实时 WS、snapshot 和 history 看到同一语义。
- reply 入口发出 `question_reply` / `permission_reply` 后，要回填原 pending part；优先按 `questionId`/`permissionId` 作为 partId 查找，失败后按 `toolCallId` 查 pending part。

测试要求：

- `CloudEventTranslatorTest` 覆盖 projected cloud question 缺 status 但带 `questionId` 的形状，以及 permission ask/reply 缺 status 的默认值。
- `MessagePersistenceServiceTest` 覆盖 cloud question canonical input、默认 running、history 顶层 `questionId`、question tool.update 防重复合并，以及 permission ask/reply 默认状态和 reply 回填。

### cloud / OpenCode 事件 parity 约束

SS 侧判断协议是否一致时，看的是处理后的效果，而不是原始 event 名称是否相同。对于 OpenCode 有等价语义的 cloud event，必须同时约束：

1. translator 输出的 `StreamMessage` 关键字段一致；
2. `partId` 对应的 `partSeq` 稳定且与 OpenCode 一样从 1 开始，同一个 partId 后续 delta/done 继续使用同一个 partSeq；
3. history/snapshot 通过 `ProtocolMessageMapper` 看到的用户可见 part 形态一致；
4. `MessagePersistenceService` 对终态事件的落盘语义一致。

| 语义族 | cloud event | OpenCode 来源 | SS 处理后要求 |
| --- | --- | --- | --- |
| 文本 | `text.delta` / `text.done` | `message.part.delta/updated` + `part.type=text` | `text.delta/done` 进入同一 text part；done 历史为 `type=text` |
| 思考 | `thinking.delta` / `thinking.done` | `part.type=reasoning` | 对外统一为 `thinking.delta/done`；done 历史为 `type=thinking` |
| 工具 | `tool.update` | `part.type=tool` | 保留 `toolName/toolCallId/status/input/output/error/title`；`input` 为 JSON object 时不能被 `asText` 压扁 |
| step | `step.start` / `step.done` | `step-start` / `step-finish` 或 `message.updated.finish` | step.start 只参与 live/context；step.done 更新 usage stats，不作为普通 history part 暴露 |
| question | `question` + `tool.update(toolName=question)` | `question.asked` + question tool completion | 只有一个可见 question part，回复更新原 part |
| permission | `permission.ask` / `permission.reply` | `permission.*` | ask 默认 `pending`，reply 默认 `completed`，reply 回填原 pending part |
| session | `session.status/title/error` | `session.status/idle/updated/error` | status 归一到 `busy/retry/idle` 语义；title/error 字段一致 |
| file | `file` | `part.type=file` | `fileName/fileUrl/fileMime` 历史字段一致 |

cloud-only 扩展类型 `planning.delta/done`、`searching`、`search_result`、`reference`、`ask_more` 没有 OpenCode 等价事件。它们可以参与 live/context，但在没有显式历史契约前，`ProtocolMessageMapper.toProtocolStreamingPart` 不能把它们误映射成 text/tool/question/permission/file。

测试要求：

- `CloudOpenCodeProtocolParityTest` 必须覆盖上表的 OpenCode 等价语义族，以及 cloud-only 扩展类型的 live-only 行为。
- 如果新增 cloud event，先判断是否有 OpenCode 等价语义；有则加入 parity 矩阵，无则明确标注 cloud-only，并补 history mapper 防误归类测试。

规则：

- 持久化实体、可变协议 DTO：`@Data + @Builder + @NoArgsConstructor + @AllArgsConstructor`
- 有默认值的字段必须加 `@Builder.Default`
- MyBatis / Jackson 共同参与的类不要删除无参构造

### 2. 不可变参数使用 record

`InvokeCommand` 和 `ImMessageRequest` 是当前 record 风格的基准：

```java
public record InvokeCommand(
                String ak,
                String userId,
                String sessionId,
                String action,
                String payload,
                Boolean suppressReply,
                String domain,
                String domainType,
                String businessSessionId,
                @Nullable List<String> allowedSlashCommands) {
    // 5/6/8/9 参 secondary constructor 兼容旧 caller（test + 非升级生产代码）
}
```

字段语义：
- 前 5 个：核心调用参数（ak / userId / sessionId / action / payload）
- `suppressReply`：null = 不写入 INVOKE 报文；仅群聊 + plugin channel 命中"禁群聊"白名单时置 true
- `domain` / `domainType` / `businessSessionId`：`SkillSession.businessSession*` 三字段，用于 `AssistantScopeDispatcher` 反查默认助手规则 + 构造 `platformExtParam`
- `allowedSlashCommands`（v3 allowed-slash-commands 任务）：personal scope CHAT 允许的 slash 命令清单；从 `sys_config(allowed_slash_commands, ${domain}_${type})` 解析得到；null = 不下发该 platformExtParam key；仅 A 表 3 处（A4 CHAT 分支 / A7 dispatchChatToGateway / A10 retryPendingMessages）显式传 list，其余 9 处生产代码 + 62 处 test callsite 通过 secondary constructor 默认 null

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/InvokeCommand.java:54-102`

```java
public record ImMessageRequest(
        String businessDomain,
        String sessionType,
        String sessionId,
        String assistantAccount,
        String senderUserAccount,
        String content,
        String msgType,
        String imageUrl,
        List<ChatMessage> chatHistory) {
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/ImMessageRequest.java:18-53`

规则：

- service 间传递的命令 / 查询参数，优先考虑 `record`
- 会被 MyBatis 直接填充、或需要逐步 mutate 的对象，不要改成 `record`

### 2.1 record 静态工厂命名：禁止与 accessor 重名

record 的每个 component 会自动生成同名 accessor 方法（如 `boolean online()` 生成 `public boolean online()`）。如果在 record 内定义同名的**静态**方法，Java 编译器不允许"返回值不同但参数为空"的两个方法共存，会报编译错误。

错误模式（`AvailabilityResult` 实际踩坑）：
```java
// ❌ static factory online() 与 accessor boolean online() 同名冲突
public record AvailabilityResult(boolean online, ...) {
    public static AvailabilityResult online() { ... }  // 编译错误
}
```

正确模式 — 静态工厂统一用 `of` 前缀：
```java
// ✅ 静态工厂用 of 前缀，与 accessor 区分
public record AvailabilityResult(boolean online, ...) {
    public static AvailabilityResult ofOnline() { ... }
    public static AvailabilityResult ofOfflineTyped(String message, String toolType) { ... }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/AvailabilityResult.java`

规则：

- record 静态工厂方法**必须**加 `of` 前缀（如 `ofOnline()`、`ofError()`），避免与 component accessor 方法名冲突。
- 静态工厂只用于"固定场景的便利构造"，不替代 Builder 或全参构造。

---

## SkillSession / SkillMessage / SkillMessagePart

### SkillSession

`SkillSession` 的几个关键类型约束：

- `id` 对外输出为 `welinkSessionId`
- `Long` 通过 `ToStringSerializer` 输出，防止前端精度丢失
- `lastActiveAt` 对外别名是 `updatedAt`
- `status` 是内部枚举，不暴露裸字符串常量

```java
@JsonProperty("welinkSessionId")
@JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
private Long id;

@JsonProperty("updatedAt")
private LocalDateTime lastActiveAt;

public enum Status {
    ACTIVE, IDLE, CLOSED
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:41-92`

### SkillMessage

`SkillMessage` 的类型边界较窄：角色与内容类型都用 enum。

```java
public enum Role {
    USER, ASSISTANT, SYSTEM, TOOL
}

public enum ContentType {
    MARKDOWN, CODE, PLAIN
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessage.java:35-75`

### SkillMessagePart

`SkillMessagePart` 目前仍是“宽表式 part 实体”，不同 part 类型共用一套对象：

```java
private String partType;
private String content;
private String toolName;
private String toolCallId;
private String toolStatus;
private String fileName;
private Integer tokensIn;
private Double cost;
private String subagentSessionId;
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessagePart.java:34-109`

规则：

- part 级扩展先确认是否真需要加列；不要为了临时协议字段把实体无限做宽。
- role、status、contentType 这类有限值优先 enum，不要裸字符串横飞。

---

## StreamMessage：单一聚合 DTO，而非多态子类

当前 `StreamMessage` 的设计重点不是多态，而是**单对象 + 嵌套分组平铺**。

```java
@JsonIgnore
private String sessionId;
private String welinkSessionId;
private String emittedAt;

@JsonUnwrapped
private ToolInfo tool;

@JsonUnwrapped
private PermissionInfo permission;

@JsonUnwrapped
private QuestionInfo questionInfo;

@JsonUnwrapped
private UsageInfo usage;

@JsonUnwrapped
private FileInfo file;
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:32-87`

这意味着：

- 不要给 `StreamMessage` 新增 Jackson polymorphic 注解（`@JsonTypeInfo` / `@JsonSubTypes`）
- 新消息类型通常先复用既有字段组；确实不够时，再新增一个小的嵌套静态类

类型常量统一收口在 `Types`：

```java
public static final class Types {
    public static final String TEXT_DELTA = "text.delta";
    public static final String TOOL_UPDATE = "tool.update";
    public static final String SESSION_STATUS = "session.status";
    public static final String PERMISSION_REPLY = "permission.reply";
    public static final String ERROR = "error";
    public static final String SEARCH_RESULT = "search_result";
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:175-213`

静态工厂方法也已经稳定存在：

```java
public static StreamMessage sessionStatus(String status) { ... }
public static StreamMessage error(String errorMessage) { ... }
public static StreamMessage agentOnline() { ... }
public static StreamMessage agentOffline() { ... }
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:217-277`

---

## canonical sessionId / welinkSessionId 规则

`StreamMessageEmitter` 会把内部 `sessionId` 和对外 `welinkSessionId` 统一写成同一个 canonical sessionId：

```java
private void enrich(String sessionId, StreamMessage msg) {
    if (msg == null || sessionId == null) return;

    msg.setSessionId(sessionId);
    msg.setWelinkSessionId(sessionId);
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java:64-81`

测试已经把这个行为固定下来：

```java
StreamMessage msg = StreamMessage.builder()
        .type(StreamMessage.Types.TEXT_DELTA)
        .welinkSessionId("business-123")
        .role("assistant")
        .build();

emitter.emitToSession(session, "101", "user-a", msg);
assertEquals("101", msg.getWelinkSessionId());
```

来源：`skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java:42-55`

规则：

- 业务侧 sessionId 不要直接塞进 `StreamMessage.welinkSessionId`
- 统一让 emitter 做 canonical overwrite

---

## scope + protocol 字段的类型约束

personal-scope 事件现在会根据顶层 `protocol` 字段决定翻译器：

```java
JsonNode protocolNode = event.path("protocol");
if (protocolNode.isMissingNode() || protocolNode.isNull()) {
    return openCodeEventTranslator.translate(event);
}
String protocol = protocolNode.asText("");
if ("cloud".equalsIgnoreCase(protocol)) {
    return cloudEventTranslator.translate(event, sessionId);
}
if ("opencode".equalsIgnoreCase(protocol)) {
    return openCodeEventTranslator.translate(event);
}
log.warn("[PersonalScope] unknown protocol value=\"{}\", fallback to OpenCodeEventTranslator, ...", protocol, ...);
return openCodeEventTranslator.translate(event);
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java:75-97`

cloud protocol 的 part/message 标识由 `CloudEventTranslator` 二次归一化：

```java
if (!SESSION_LEVEL_TYPES.contains(eventType)) {
    if (msg.getSourceMessageId() == null && msg.getMessageId() != null) {
        msg.setSourceMessageId(msg.getMessageId());
    }
    if (msg.getRole() == null) {
        msg.setRole("assistant");
    }
    if (!MESSAGE_LEVEL_TYPES.contains(eventType)) {
        if (msg.getPartSeq() == null && sessionId != null && msg.getPartId() != null) {
            ...
            msg.setPartSeq(seq);
        }
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java:150-188`

测试证据：`skill-server/src/test/java/com/opencode/cui/skill/service/scope/PersonalScopeCloudProtocolIntegrationTest.java:104-203`

规则：

- `protocol` 只接受 `cloud` / `opencode` / 缺失，未知值只能 fallback，不要扩散魔法字符串。
- cloud event 要带稳定的 `messageId` / `partId`；SS 只负责补 `sourceMessageId` / `partSeq` / 默认 role。
- cloud `session.status` 入站兼容两种字段名：优先读取 `status`，其次读取 `sessionStatus`；但 SS 对 miniapp 的出站字段仍统一为 `sessionStatus`。这是为了兼容云端部分 profile 发送 `properties.status` 的场景，避免 `StreamMessage.sessionStatus=null` 后被 `@JsonInclude(NON_NULL)` 省略。

---

## senderUserAccount 信封层迁移

`senderUserAccount` 的当前稳定类型位置：

- external：`ExternalInvokeRequest.senderUserAccount`
- IM：`ImMessageRequest.senderUserAccount`
- service：`InboundProcessingService` 通过方法参数接收，再写入 `sendUserAccount`

external DTO：

```java
@Data
public class ExternalInvokeRequest {
    private String action;
    private String businessDomain;
    private String sessionType;
    private String sessionId;
    private String assistantAccount;
    private String senderUserAccount;
    private JsonNode payload;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java:10-49`

下游组包时直接透传信封层 `senderUserAccount`，**不再做 group/direct 差异化 ownerWelinkId 兜底**：

```java
// 2026-05-20 起：sender 在 controller 已强制非空，service 直接信任
String effectiveSender = senderUserAccount;
payloadFields.put("sendUserAccount", effectiveSender);
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java#dispatchChatToGateway`

测试明确规定 legacy payload 字段无效：

`skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java:137-150`

规则：

- 不要再定义 `payload.senderUserAccount`
- 新入口如果需要发送者身份，字段名和位置必须与现有 envelope 对齐
- **禁止恢复 ownerWelinkId 兜底**：详细契约见 `error-handling.md` → "Inbound chat senderUserAccount 必填且不再 fallback"

---

## Gateway/plugin 下行 payload 的 imGroupId 空值契约

### 1. Scope / Trigger

当 SS 组装发往 Gateway/plugin 的 `InvokeCommand.payload`，且 payload 类型为 `chat` / `question_reply` / `permission_reply` 时适用。本契约只约束出站 wire payload，不改变 `PendingChatRequest.imGroupId`、`InvokeCommand.businessSessionId` 或 `platformExtParam.businessSessionId` 的内部 null 语义。

### 2. Signatures

- 同步 chat producer：`InboundProcessingService#dispatchChatToGateway(...)`
- 首次 business/default 会话立即 chat producer：`ImSessionManager#sendBusinessChatImmediately(...)`
- create_session 回调后的 retry producer：`GatewayMessageRouter#retryPendingMessages(...)`
- 反向回复 producer：`InboundProcessingService#processQuestionReply(...)` / `processPermissionReply(...)`
- wire 字段：`payload.imGroupId`

### 3. Contracts

- 群聊：`payload.imGroupId` 必须是业务侧 group session id，例如 `"group-001"`。
- 非群聊 / 空 group：`payload.imGroupId` 必须出现，且值为 `""`，不要写 JSON `null`，也不要省略 key。
- `PendingChatRequest.imGroupId` 可继续为 Java `null`，retry producer 在下发边界转换为 `""`。
- `platformExtParam.businessSessionId` 继续反映业务 session id 语义；旧 entry 或 direct session 可为 JSON `null`，不要为了本契约改成空字符串。

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| `sessionType=group`, `sessionId=group-001` | 下发 payload 含 `"imGroupId":"group-001"` |
| `sessionType=direct` | 下发 payload 含 `"imGroupId":""` |
| retry `PendingChatRequest.imGroupId()==null` | 下发 payload 含 `"imGroupId":""`，pending 对象仍保留 null |
| question / permission reply in direct session | 原始下发 payload 含 `"imGroupId":""` |

### 5. Good / Base / Bad Cases

Good:

```java
payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : "");
```

Base:

```java
chatPayload.put("imGroupId", req.imGroupId() != null ? req.imGroupId() : "");
```

Bad:

```java
payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : null);
```

### 6. Tests Required

- `InboundProcessingServiceTest`: direct `chat` / `question_reply` / `permission_reply` payload asserts `payload.imGroupId == ""`.
- `GatewayMessageRouterTest`: direct retry payload asserts `payload.imGroupId == ""`.
- `ImSessionManagerTest`: business/default direct immediate chat payload asserts `"imGroupId":""`; group case keeps real id.

### 7. Wrong vs Correct

Wrong: changing `PayloadBuilder.buildPayloadWithObjects(...)` to serialize every null value as an empty string; that helper is shared by multiple payload fields and flows.

Correct: convert only `imGroupId` at the concrete producer boundary that sends the plugin-facing payload, preserving internal DTO and platform extension null semantics.

---

## MyBatis 枚举映射

枚举在 XML 中统一走 `EnumTypeHandler`，按名字映射：

```xml
<result property="status" column="status"
        typeHandler="org.apache.ibatis.type.EnumTypeHandler"
        javaType="com.opencode.cui.skill.model.SkillSession$Status"/>

<result property="role" column="role"
        typeHandler="org.apache.ibatis.type.EnumTypeHandler"
        javaType="com.opencode.cui.skill.model.SkillMessage$Role"/>
```

Java 侧来源：

- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java:58-92`
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessage.java:35-75`

配套 XML：

- `skill-server/src/main/resources/mapper/SkillSessionMapper.xml:7-22`
- `skill-server/src/main/resources/mapper/SkillMessageMapper.xml:7-22`

规则：

- 不要在 XML 里映射 enum ordinal
- service 层要用 `Status.CLOSED.name()` 这类显式字符串下沉到 SQL

---

## 反序列化对抗性输入：先 readTree 再按 schema 校验

Redis list / MQ payload 这类 "内容可能直接来自用户输入" 的存储位置，**禁止**直接 `mapper.readValue(raw, TargetDto.class)` 来判断 "新格式 vs 老格式"。用户消息正文本身可能是合法 JSON（如 `{"foo":"bar"}`），`readValue` 会成功反序列化得到一个 `text=null` 的对象，把原始文本静默吞掉。

正确做法：**先 `readTree(JsonNode)`，严格按 schema 关键字段判断**，三重校验都通过才走新格式 deserialize，否则当 raw 老格式处理。

```java
// ❌ 直接 readValue → 对抗性 JSON 文本被误判，丢消息
try {
    PendingChatRequest req = mapper.readValue(raw, PendingChatRequest.class);
    // req.text 可能是 null，原始文本丢了
} catch (JsonProcessingException e) {
    return PendingChatRequest.fromSessionFallback(session, raw);
}

// ✅ readTree + schema 三重校验
JsonNode node;
try {
    node = mapper.readTree(raw);
} catch (JsonProcessingException e) {
    return PendingChatRequest.fromSessionFallback(session, raw);
}
if (node.isObject()
        && node.path("text").isTextual()
        && !node.path("text").asText().isEmpty()) {
    return mapper.treeToValue(node, PendingChatRequest.class);  // 新格式
}
return PendingChatRequest.fromSessionFallback(session, raw);     // 老格式 / 对抗输入
```

参考实现：`skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java::consumePendingMessages`

规则：

- 凡 "存储内容可能是用户原始输入" 的反序列化点（Redis list/hash value、MQ body、文件输入），都走 `readTree` + 字段 schema 校验。
- schema 判断必须挑**新格式独有**的字段（这里是 `text` 必为非空 textual），不能只判断 `isObject`。
- fallback 路径必须能拿到原始 raw 字符串，不要在 `readTree` 之前先 trim / decode。

---

## 外部 API 模型类：`@JsonProperty` vs `@JsonNaming`

### 1. Scope / Trigger

当定义与外部 API 交互的 Java record / DTO（请求体、响应体）时适用。外部 API 的 JSON 字段名遵循其自有规范（通常 snake_case），不应通过类级别 `@JsonNaming` 隐式转换。

### 2. Convention

**使用 `@JsonProperty` 在每个字段上显式指定 wire format，禁止使用 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`。**

### 3. Rationale

| 方案 | 问题 |
|------|------|
| `@JsonNaming(SnakeCaseStrategy.class)` 类级别 | 隐式转换整个类，字段增删时容易漏掉命名不一致；读者需要记住类上有策略注解才能理解 wire format |
| `@JsonProperty("snake_case")` 每字段 | 显式对照，一目了然 Java 名 ↔ wire 名的映射关系；支持个别字段特殊命名 |

### 4. Examples

Good — 显式 `@JsonProperty`:

```java
public record AppNotifyRequest(
    @JsonProperty("client_notify_id") String clientNotifyId,
    @JsonProperty("notify_scope") int notifyScope,
    @JsonProperty("notify_tenant") String notifyTenant,
    @JsonProperty("notify_accounts") List<String> notifyAccounts,
    @JsonProperty("notify_module") String notifyModule,
    @JsonProperty("notify_data") String notifyData
) {}

public record ImAppNotifyResponse(
    @JsonProperty("error") ErrorInfo error
) {
    public record ErrorInfo(
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_msg") String errorMsg
    ) {}
}
```

Bad — 隐式类级别策略:

```java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)  // ← 禁止
public record AppNotifyRequest(
    String clientNotifyId,    // 隐式 → client_notify_id？读者需记住类注解
    int notifyScope,
    ...
) {}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/AppNotifyRequest.java`、`AppNotifyData.java`、`ImAppNotifyResponse.java`

---

## 外部 API 响应：类型化 record 替代 JsonNode

### 1. Scope / Trigger

当调用外部 HTTP API 并解析其 JSON 响应时适用。禁止使用 `JsonNode` + `path()` 手动遍历响应结构。

### 2. Convention

**定义专用的响应 record，通过 `RestTemplate.postForEntity(url, entity, TypedResponse.class)` 直接反序列化，通过 record accessor 访问字段。**

### 3. Rationale

| 方案 | 问题 |
|------|------|
| `ResponseEntity<JsonNode>` + `respBody.path("error").path("error_code").asText(null)` | 字符串路径无编译检查，字段名拼写错误运行时才发现；fallback 链 (`error_code` / `errorCode`) 冗长 |
| `ResponseEntity<ImAppNotifyResponse>` + `respBody.error().errorCode()` | 编译期类型检查，IDE 自动补全，单一事实来源 |

### 4. Examples

Good:

```java
ResponseEntity<ImAppNotifyResponse> response = restTemplate.postForEntity(
    appNotifyUrl, new HttpEntity<>(body, headers), ImAppNotifyResponse.class);

ImAppNotifyResponse respBody = response.getBody();
if (respBody != null && respBody.error() != null) {
    String errorCode = respBody.error().errorCode();
    if (errorCode != null && !errorCode.isBlank()) {
        log.warn("business error: errorCode={}, errorMsg={}", errorCode, respBody.error().errorMsg());
    }
}
```

Bad:

```java
ResponseEntity<JsonNode> response = restTemplate.postForEntity(url, entity, JsonNode.class);
JsonNode respBody = response.getBody();
if (respBody != null) {
    JsonNode errorNode = respBody.path("error");  // 字符串路径，无类型检查
    if (!errorNode.isMissingNode()) {
        String errorCode = firstNonBlank(          // 需要 fallback 兼容
            errorNode.path("error_code").asText(null),
            errorNode.path("errorCode").asText(null));
    }
}
```

### 5. Good / Base / Bad Cases

- Good: 响应 record 覆盖所有需要读取的字段，通过 accessor 访问
- Base: 响应仅成功时无需读取 body（`response.getStatusCode().is2xxSuccessful()` 即足够）
- Bad: `JsonNode.path()` 链式遍历 + fallback 兼容多命名

### 6. Tests Required

- 单元测试中 mock `RestTemplate.postForEntity` 返回 `ResponseEntity.ok(new ImAppNotifyResponse(null))` 或 `new ImAppNotifyResponse(new ErrorInfo("ERR", "msg"))`
- 不应再出现 `new ObjectMapper().readTree("{}")` 来构造 mock 响应

### 7. Wrong vs Correct

Wrong: 用 `JsonNode` 接收外部 API 响应，通过字符串路径访问字段，写 fallback 兼容多命名。

Correct: 定义 record 响应类（`@JsonProperty` 每字段），`RestTemplate` 直接反序列化，通过 accessor 访问。

---

## 外部 API URL 配置化

### 1. Scope / Trigger

当在代码中构造外部 HTTP 请求 URL 时适用。禁止在代码中硬编码路径片段（如 `/v1/app-notify`），禁止通过 `joinUrl(baseUrl, "/path")` 拼接。

### 2. Convention

**URL 通过配置属性完整注入，代码中直接使用，不做拼接。配置值可使用 Spring 占位符引用其他属性。**

```yaml
# application.yml
skill:
  sync:
    im:
      app-notify:
        url: ${skill.im.api-url}/v1/app-notify  # 完整 URL，路径可配置
```

```java
// 代码中直接使用完整 URL，不做拼接
String appNotifyUrl = syncProperties.getIm().getAppNotify().getUrl();
restTemplate.postForEntity(appNotifyUrl, entity, ResponseType.class);
```

### 3. Rationale

| 方案 | 问题 |
|------|------|
| `joinUrl(imApiUrl, "/v1/app-notify")` | 路径硬编码在代码中，变更需改代码 + 重新部署；base URL 与 path 拼接逻辑多此一举 |
| 配置属性完整注入 | URL 变更只需改配置，热重载或多环境 profile 即可 |

### 4. Good / Base / Bad Cases

- Good: `SyncProperties.Im.AppNotify.url` 完整 URL，配置值 `${skill.im.api-url}/v1/app-notify`
- Base: `@Value("${skill.im.api-url}")` 注入 base URL，但至少路径也走配置
- Bad: 代码中 `joinUrl(imApiUrl, "/v1/app-notify")` 硬编码路径

---

## 常见错误

1. 不要把 `StreamMessage` 重构成 Jackson 多态层级；当前实现不是这个方向。
2. 不要删掉模型类的无参构造；MyBatis 和 Jackson 都会依赖它。
3. 不要把 Long ID 原样当 number 输出给前端；`SkillSession.id` 必须保持字符串化。
4. 不要恢复 `payload.senderUserAccount`；信封层迁移已经完成。
5. 不要手动拼 `welinkSessionId`；让 `StreamMessageEmitter` 统一覆写。
6. 不要为单聊 `sendUserAccount` 引入 `ownerWelinkId` 默认值；`senderUserAccount` 在 controller 已经强制非空。
7. 不要在外部 API 模型类上用 `@JsonNaming(SnakeCaseStrategy.class)`；用 `@JsonProperty` 在每个字段显式指定 wire format。
8. 不要用 `JsonNode.path()` 手动解析外部 API 响应；定义类型化 record 响应类。
9. 不要在代码中硬编码外部 API 请求路径；走配置属性完整注入 URL。
