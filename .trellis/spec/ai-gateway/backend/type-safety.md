# 类型安全

> `ai-gateway` 的协议层不是靠大量 `Map<String, Object>` 拼起来的；核心消息、REST 返回与持久化模型都已经有明确类型。
---

## 概览

| 类型形态 | 真实用法 | 代表类 |
|----------|----------|--------|
| Lombok 可变模型 | 适合 MyBatis / Jackson 双用的实体或扁平协议对象 | `GatewayMessage`, `AgentConnection`, `AkSkCredential`, `ApiResponse` |
| Java `record` | 适合只读响应 DTO 或轻量包装对象 | `RelayMessage`, `AgentSummaryResponse`, `AgentStatusResponse`, `InvokeResult` |
| `JsonNode` 载荷 | 协议 payload / usage / event 的半结构化部分 | `GatewayMessage.payload/event/usage` |
| 枚举 | 连接状态、凭证状态 | `AgentConnection.AgentStatus`, `AkSkCredential.CredentialStatus` |

## 协议载体：`GatewayMessage`

`GatewayMessage` 是 Gateway、Agent、Skill Server 之间的统一扁平协议载体；通过 `type` 常量区分语义，而不是使用 `@JsonTypeInfo` 多态。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java:49-77,347-363
@Data
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayMessage {
    public interface Type {
        String REGISTER = "register";
        String INVOKE = "invoke";
        String TOOL_EVENT = "tool_event";
        String STATUS_RESPONSE = "status_response";
        String IM_PUSH = "im_push";
    }

    public GatewayMessage withoutRoutingContext() { ... }
    public GatewayMessage ensureTraceId() { ... }
}
```

约束：

- 新消息类型先加到 `GatewayMessage.Type`，再在对应 handler/service 里分支处理。
- 路由上下文字段（`userId`、`source`、`gatewayInstanceId`）发送给 Agent 之前必须剥离，使用 `withoutRoutingContext()`。
- `traceId` 统一通过 `ensureTraceId()` 兜底生成，不要在各个 Service 里各写一份 UUID 逻辑。

## 中继包装：`RelayMessage`

GW→GW Redis 中继使用 `RelayMessage` record，而不是裸 `GatewayMessage`。它用 `type="relay"` 做格式判别，并额外挂载 `sourceType`、`routingKeys`、`relayType` 等元数据。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/model/RelayMessage.java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayMessage(
        String type,
        String sourceType,
        List<String> routingKeys,
        String originalMessage,
        String relayType,
        String targetSourceType,
        String targetSourceInstanceId
) {
    public static final String TYPE = "relay";
    public static RelayMessage of(String originalMessageJson) { ... }
    public static RelayMessage toSource(String targetSourceType, String targetSourceInstanceId, String payload) { ... }
    public static RelayMessage toCloudControl(String payload) { ... }
}
```

`relayType` 的现有取值包括 `to-agent`（默认/空）、`to-source`、`to-cloud-control`。`to-source` 只表示精准投递到本机某个 Source WebSocket；GW→SS 缺少本机连接时不再使用 `RelayMessage` 做 `to-source-broadcast` 广播兜底，而是由 `SkillRelayService` 选择一个拥有本机 SS 连接的 `targetGw`，写入 `gw:l2:source:skill-server:{targetGw}` mailbox Stream。`to-cloud-control` 只用于 GW 内部云端控制帧（例如跨 GW 的 `abort_session`），接收端必须交给本机 cloud/business 路由处理，不要按 Agent 下行消息解析。

回源路由键不只存在于扁平字段。`GatewayMessage.messageId` 是 agent/cloud 回复流的主路由键；
`GatewayMessage.traceId` 是 terminal 事件补齐和缺少 `messageId` 时的降级路由键。
`GatewayMessage.toolSessionId` 和 `GatewayMessage.payload.toolSessionId` 仍是合法的最后降级 route key；
解析路由键时用 Jackson `JsonNode.path("toolSessionId")` 读取半结构化 payload，不要先把 payload 转成临时
`Map`，也不要把 payload-only 消息当成没有 route key。

`RelayMessageTest` 明确验证了 Jackson round-trip、`type="relay"` 判别字段、`routingKeys` 的 null/empty 行为和 `toCloudControl(...)` factory。来源：`src/test/java/com/opencode/cui/gateway/model/RelayMessageTest.java:24-175`。

## Scenario: SS invoke route identity vs user attribution

### 1. Scope / Trigger

- Trigger: `SkillRelayService.handleInvokeFromSkill(WebSocketSession, GatewayMessage)` receives a personal/local-assistant invoke from skill-server.
- Trigger: IM group rebuild or tool-session recreation can produce a valid `ak` with blank top-level `GatewayMessage.userId`.
- Trigger: `GatewayMessage.userId` is present but represents a route hint or caller context that differs from the registered AK owner.

### 2. Signatures

```java
public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message)
private boolean validateInvokeMessage(WebSocketSession session, GatewayMessage tracedMessage)
private void dispatchToAgent(GatewayMessage tracedMessage, String messageSource)
public boolean sendToLocalAgentIfPresent(String ak, GatewayMessage message)
public GatewayMessage withoutRoutingContext()
```

### 3. Contracts

- `source` is a hard boundary field: it must match the source bound to the WebSocket session.
- `ak` is the delivery key for local agent lookup, remote GW relay, and pending queue.
- Top-level `userId` is attribution / diagnostic context for SS-origin invokes; it is not the delivery key.
- Missing or blank top-level `userId` must not block delivery when `source` and `ak` are valid.
- If non-blank `userId` differs from `RedisMessageBroker.getAgentUser(ak)`, log an observation and continue delivery.
- Before delivering to the Agent, always call `GatewayMessage.withoutRoutingContext()` so `userId`, `source`, and `gatewayInstanceId` are stripped from the agent-facing message.
- The real IM sender stays in the payload contract owned by skill-server, for example `sendUserAccount`; ai-gateway must not infer sender from top-level `userId`.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| Missing or mismatched `source` | Reject, log `[SKIP]`, send protocol error where existing code does so. |
| Missing or blank `ak` | Reject, log `[SKIP]`, do not route to local/remote/pending. |
| Missing or blank `userId` with valid `source` + `ak` | Continue to local delivery, remote relay, or pending queue; log at DEBUG at most. |
| Non-blank `userId` mismatches AK owner | Log `[OBSERVE] reason=userId_mismatch`; continue delivery. |
| `RedisMessageBroker.getAgentUser(ak)` returns blank while message has `userId` | Log observation and continue delivery. |

### 5. Good / Base / Bad Cases

Good: group rebuild sends `create_session` with `ak=local-assistant-ak`, `source=skill-server`, and blank `userId`; GW still calls `sendToLocalAgentIfPresent(ak, message.withoutRoutingContext())`.

Base: direct chat sends owner route hint in `userId`; GW may compare it with the registered AK owner for observation but still routes by `ak`.

Bad: using `GatewayMessage.userId` as a required auth gate for SS-origin invoke delivery, which drops group rebuild messages before local agent delivery.

### 6. Tests Required

- `SkillRelayServiceV2Test`: valid `source` + `ak` with blank `userId` still calls `EventRelayService.sendToLocalAgentIfPresent(ak, ...)`.
- `SkillRelayServiceV2Test`: mismatched non-blank `userId` still calls local delivery and does not enqueue or remote-relay when local delivery succeeds.
- `SkillWebSocketHandlerTest`: invoke messages still delegate to `SkillRelayService.handleInvokeFromSkill(...)`.
- `SkillRelayServiceTest`: business/cloud route branches still bypass personal invoke validation when the strategy handles the message.

### 7. Wrong vs Correct

Wrong:

```java
String expectedUserId = redisMessageBroker.getAgentUser(message.getAk());
if (message.getUserId() == null || !message.getUserId().equals(expectedUserId)) {
    return false;
}
```

Correct:

```java
if (message.getAk() == null || message.getAk().isBlank()) {
    return false;
}
if (message.getUserId() == null || message.getUserId().isBlank()) {
    log.debug("[OBSERVE] reason=missing_userId");
    return true;
}
```

## REST DTO：不要再返回裸 `Map`

新 REST 接口优先使用 `record`。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/model/AgentSummaryResponse.java:13-37
public record AgentSummaryResponse(
        String ak,
        AgentConnection.AgentStatus status,
        String deviceName,
        String os,
        String toolType,
        String toolVersion,
        LocalDateTime connectedAt) {
    public static AgentSummaryResponse fromAgent(AgentConnection agent) { ... }
}
```

当前真实 DTO：

| DTO | 用途 |
|-----|------|
| `AgentSummaryResponse` | `/api/gateway/agents` 在线列表 |
| `AgentStatusResponse` | `/api/gateway/agents/status` 状态查询 |
| `InvokeResult` | `/api/gateway/invoke` 调用结果 |
| `ImPushRequest` | `/api/gateway/cloud/im-push` 请求体 |
| `ApiResponse<T>` | 所有 REST 接口统一包装 |

注意：文档示例只使用 `ai-gateway` 中真实存在的类型名。

## 持久化模型

`AgentConnection` 与 `AkSkCredential` 都是 Lombok 模型，兼容 MyBatis 映射与 Jackson 序列化。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/model/AgentConnection.java:15-63
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentConnection {
    @Builder.Default
    private String toolType = "channel";

    @Builder.Default
    private AgentStatus status = AgentStatus.OFFLINE;

    public enum AgentStatus { ONLINE, OFFLINE }
}
```

`AkSkCredential` 同样使用 `@Builder.Default` 给 `status=ACTIVE` 提供默认值。来源：`model/AkSkCredential.java:15-53`。

## Jackson 约定

| 场景 | 约定 | 真实类 |
|------|------|--------|
| 忽略空字段 | 类或 `record` 上使用 `@JsonInclude(NON_NULL)` | `GatewayMessage`, `RelayMessage`, `ApiResponse`, `AgentSummaryResponse`, `AgentStatusResponse` |
| 协议半结构化字段 | 使用 `JsonNode`，不提前硬编码所有 payload 结构 | `GatewayMessage.payload`, `event`, `usage` |
| 多态识别 | 通过显式 `type` 字段，不使用 `@JsonTypeInfo` | `GatewayMessage`, `RelayMessage` |
| 响应泛型 | 用 `ApiResponse<T>` 包裹数据与错误码 | `model/ApiResponse.java:15-46` |

## 测试驱动的类型约束

| 测试 | 说明 |
|------|------|
| `RelayMessageTest` | 验证 `RelayMessage` 的 discriminator、routingKeys、Jackson round-trip |
| `MdcHelperTest` | 验证 `GatewayMessage` 能稳定提取 `traceId/sessionId/ak/userId` |
| `CloudPushControllerTest` | 验证 `ImPushRequest -> GatewayMessage(Type.IM_PUSH)` 的字段映射 |
| `AgentControllerTest` | 验证 `AgentSummaryResponse` 替代裸 Map 的列表响应 |

## 禁止事项

1. 不要在 `ai-gateway` 文档里继续举不存在的类型名。
2. 不要给新响应继续返回 `Map<String, Object>`；只有 legacy 接口可以保留。
3. 不要在新协议对象里引入与 `GatewayMessage` 平行的第二套 `type` 常量体系。
4. 不要把 `JsonNode` 立刻转成一堆未复用的小 DTO；先确认 payload 已经稳定成契约。
