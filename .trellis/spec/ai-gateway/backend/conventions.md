# 编码约定

> `ai-gateway` 的通用编码规范。重点不是“模板式 Spring 代码”，而是围绕 Agent 握手、Redis 路由、WebSocket 中继建立统一写法。
---

## 依赖注入

一律使用**构造器注入**，不要使用字段注入。`AgentRegistryService` 直接把 Repository、Relay 服务和雪花 ID 生成器作为构造参数收口，便于测试与替换实现。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java:30-43
@Service
public class AgentRegistryService {
    private final AgentConnectionRepository repository;
    private final EventRelayService eventRelayService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public AgentRegistryService(AgentConnectionRepository repository,
            EventRelayService eventRelayService,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repository = repository;
        this.eventRelayService = eventRelayService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }
}
```

## 配置管理

- 统一从 `application.yml` 的 `gateway.*`、`skill.gateway.*`、`opencode.logging.*` 命名空间读取配置。
- 简单标量用 `@Value` 注入，例如 `gateway.instance-id`、`gateway.auth.mode`、`gateway.agent.heartbeat-timeout-seconds`。
- 结构化配置用 `*Properties` 承载，例如 `SnowflakeProperties`、`CloudTimeoutProperties`。
- `GatewayApplication` 已开启 `@MapperScan`，不要在每个 Mapper 上重复做手工注册。来源：`ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java:10-17`。

### 陷阱：`@Value(":<default>")` 的默认值是 fallback，不是"真实"默认值

`@Value("${gateway.foo:false}")` 的 `:false` 只在 `application.yml` **没写** `gateway.foo` 时才生效。一旦 yml 写了 `gateway.foo: true`，Java 源码里的 `:false` 就是假象。

**症状**：开发同学读 Java 代码以为某 feature flag 默认关，实际线上一直开着。debug 时按"代码里写的默认值"推理就会走偏。

**规则**：

- **声明真实默认值唯一权威是 `application.yml`**。Java 端 `@Value(":<x>")` 的 `<x>` 仅作为"yml 整段缺失"的兜底，不要把它当作 feature flag 的真实默认值。
- 新增 flag 时，**yml 必须显式写一行**（即使值等于 Java fallback），让 grep `application.yml` 就能看到全部默认值。
- review 写有 `@Value(":<default>")` 的代码时，第一件事是 `grep "<config-key>" application*.yml` 确认两边一致；不一致时以 yml 为准。

**真实事故**：`SkillRelayService.legacyRelayEnabled` 历史上 Java 写 `@Value(":false")`，但 `application.yml` 写的是 `GATEWAY_LEGACY_RELAY_ENABLED:true`——线上 legacy 兜底实际一直开着，直到 PR1 清理时才暴露。

## WebSocket 注册模式

WebSocket 端点统一由 `GatewayConfig` 注册，Handler 同时承担 `HandshakeInterceptor`。这样握手鉴权和消息处理能共享一份依赖图，不需要额外的 adapter 层。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/config/GatewayConfig.java:35-74
registry.addHandler(agentWebSocketHandler, "/ws/agent")
        .addInterceptors(agentWebSocketHandler)
        .setAllowedOrigins(allowedOrigins);

registry.addHandler(skillWebSocketHandler, "/ws/skill")
        .addInterceptors(skillWebSocketHandler)
        .setAllowedOrigins(allowedOrigins);
```

配套约束：

| 场景 | 约定 | 真实实现 |
|------|------|----------|
| Agent 握手 | 从 `Sec-WebSocket-Protocol` 里解析 `auth.{base64url-json}`，验签成功后把 `userId` / `ak` 写入 `session.getAttributes()` | `ws/AgentWebSocketHandler.java:127-194` |
| Skill Server 握手 | 用 `token + source + instanceId` 子协议做内部鉴权 | `ws/SkillWebSocketHandler.java:47-175` |
| REST MDC | 只拦截 `/api/**`，通过 `MdcRequestInterceptor` 自动注入 `traceId` 与 `scenario` | `config/GatewayConfig.java:47-53`, `config/MdcRequestInterceptor.java:16-32` |

## 服务编排模式

`ai-gateway` 的 Controller 不直接做 Redis 或 WebSocket 操作，而是把编排责任下沉到 Service：

| 层 | 典型职责 | 真实类 |
|----|----------|--------|
| Controller | 鉴权 header、参数校验、HTTP 状态码与 `ApiResponse` 组装 | `AgentController`, `CloudPushController` |
| WS Handler | 握手、消息解包、MDC 注入、把领域动作转交 Service | `AgentWebSocketHandler`, `SkillWebSocketHandler` |
| 核心 Service | Agent 生命周期、中继、路由学习、离线缓冲 | `AgentRegistryService`, `EventRelayService`, `SkillRelayService` |
| 基础设施 Service | Redis key/channel 操作、外部身份 API 调用 | `RedisMessageBroker`, `IdentityApiClient` |

## AK/SK 验签辅助模式

AK/SK 校验不是“抛异常 + 统一处理”的风格，而是显式返回 `userId` 或 `null`。所有公共校验都先过时间窗和 nonce，再按模式分支到本地 HMAC 或远端 identity API。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java:97-150
public String verify(String ak, String timestamp, String nonce, String signature) {
    if (ak == null || timestamp == null || nonce == null || signature == null) {
        return null;
    }
    // 1. 时间窗校验
    // 2. Redis SET NX 反重放
    // 3. gateway 模式走 verifyLocally；remote 模式走 resolveIdentity
}
```

与之配套的本地 HMAC 计算集中在一个私有辅助方法中，避免在握手层、Controller 层重复拼签名逻辑。来源：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java:161-194`。

## REST 响应约定

- 正常 REST 接口统一返回 `ResponseEntity<ApiResponse<T>>`。
- `ApiResponse.ok(data)` 表示 `code=0` 成功；`ApiResponse.error(code, message)` 表示业务失败。
- 兼容接口可以暂时返回 `Map<String, Object>`，但新接口优先使用 `record` 或专用 DTO。例：`AgentController` 的 `/agents/{id}/status` 和 `/agents/{id}/invoke` 仍保留 legacy Map 返回；见 `controller/AgentController.java:147-197`。
- 新增只读诊断接口仍按内部 Bearer 鉴权，返回 `ApiResponse<record>`。SS-GW 连接诊断接口固定为 `GET /api/gateway/source-connections?sourceType=skill-server`，响应 DTO 使用 `SourceConnectionOverviewResponse` / `SourceConnectionLinkResponse`，必须包含 `linkId`、`ssInstanceId`、`gwInstanceId`、`senderRunning`、`pending`。

## WebSocket Sender Owner 模式

`AsyncSessionSenderFactory` 是本地 WebSocket sender 生命周期的唯一 owner。`SkillRelayService` 和 `EventRelayService` 只能通过 factory 获取或移除 sender，不能各自维护 `Map<linkId, AsyncSessionSender>`。

```java
AsyncSessionSender sender = senderFactory.getOrCreate(session, onSenderFailure);
boolean enqueued = sender.enqueue(new TextMessage(payload));
```

约束：

- 一个 `linkId` 只能对应一个 `AsyncSessionSender`。
- 每个 sender 用一个串行发送线程 drain 自己的有界队列，避免同一 `WebSocketSession` 并发 `sendMessage(...)`。
- 队列容量由 `gateway.async-sender.queue-capacity` 配置，默认 `10000`。
- `enqueue(...)` 返回 `false`、session closed、queue full、`sendMessage(...)` 抛异常都属于真实投递风险；调用方必须失败返回或清理连接，不能继续重选另一条 link 静默补发。

## 事务与调度

- 涉及 MySQL 写入的方法显式加 `@Transactional`，例如 `AgentRegistryService.register/heartbeat/markOffline/checkTimeouts`；来源：`service/AgentRegistryService.java:48-49`, `93-101`, `133-148`。
- 定时任务只做状态收敛，不做外部阻塞 I/O；`checkTimeouts()` 负责找出超时 Agent、标记离线、移除本地会话。来源：`service/AgentRegistryService.java:131-148`。

## 运维可配置的"套餐" = SysConfig 数据 + Registry 运行时拼装

需要让运维零代码自由组合策略（如 request/response 协议套餐）时，**不要**把每个组合做成一个 `@Component` 类。改用：

- 策略本体（如 `*CloudRequestStrategy`、`SseEventDecoder` 实现）保持 `@Component` 自动注册；
- "套餐"用 record / POJO 表达（不是接口、不是 Bean），由 `@Service` Registry 在运行时按 SysConfig 两段查找拼装：①`<feature>_profile:<dimensionKey>` → profile name；②`<feature>_profile_def:<profileName>` → JSON 选择哪些策略。
- 约定 fallback：`profile_def` 缺失时按 "profile name == strategy bean name" 对称查找，避免运维必填两段。
- Registry 必须带 in-memory TTL cache（参考 5min / `gateway.cloud-protocol-profile.cache-ttl-ms`），SysConfig 查询不要进热路径每次都打。

参考：`CloudResponseProfileRegistry`、`CloudRequestProfileRegistry`（SS 侧）。跨服务时 profile name 字符串是 SS↔GW 的唯一契约。

## 废弃 SysConfig / 类的渐进迁移

迁移到新机制（如 profile-based）后，旧 SysConfig 数据**保留不删**作为回滚兜底，但代码侧不再读取；对应旧 Builder / Strategy 类加 `@Deprecated` + javadoc 注明替代品。例：`CloudRequestBuilder` + `cloud_request_strategy:<businessTag>`。这样 rollback 只需切回旧入口，不需要回填数据。

## 禁止事项

| 禁止 | 原因 | 正确做法 |
|------|------|----------|
| 在 Controller / WS Handler 中直接拼 Redis key | key 演进快，容易出现兼容遗漏 | 统一走 `RedisMessageBroker` |
| 在业务代码里直接 `MDC.put("traceId", ...)` | key 分散、难清理 | 使用 `MdcConstants` + `MdcHelper` |
| 在握手层抛未捕获异常 | WebSocket 握手失败语义不清 | 返回 `false` 或发送拒绝消息后关闭连接 |
| 为 Agent 状态接口返回裸 `Map` | 丢失类型约束 | 新接口使用 `AgentSummaryResponse`、`AgentStatusResponse`、`InvokeResult` |
| 在多个地方复制 HMAC / nonce 逻辑 | 易出现协议偏差 | 只在 `AkSkAuthService` 实现验签 |
## Assistant instance remote routing

### 1. Scope / Trigger

This applies when `CloudAgentService` receives an invoke payload with `assistantAccount` or `partnerAccount`.
Remote cloud routing must prefer the assistant instance API over legacy callback/profile fallback because remote assistants may not have an AK.

### 2. Signatures

- Instance lookup: `AssistantInstanceInfoService.getInstanceInfo(String partnerAccount)`.
- Route resolution: `CloudAgentService.resolveRemoteRoute(String assistantAccount, String action, String fallbackBusinessTag)`.
- HTTP auth application: `CloudAuthService.applyAuth(HttpRequest.Builder builder, String appId, String authType)`.
- WebSocket auth application: `CloudAuthService.applyAuth(WebSocket.Builder builder, String appId, String authType)`.
- Connection context: `CloudConnectionContext.authType()` and `CloudConnectionContext.cloudProfile()`.

### 3. Contracts

`assistantAccount` / `partnerAccount` is the primary identity for remote assistant routing.
`AssistantInstanceInfo.remoteProperty[]` is selected by action (`chat`, `question_reply`, `permission_reply`, etc.) and supplies protocol, endpoint, and auth type.
`remoteProperty.headers` is an array, but GW uses only the first `header.type` to derive `CloudConnectionContext.authType`; protocol executors must then call `CloudAuthService.applyAuth(..., authType)`.
`remoteProperty.headers[].customKey` and `customValue` are not raw outbound cloud request headers in this flow and must not be replayed by GW.
`AssistantInstanceInfo.remoteType` is authoritative when present: `0` means local/non-remote, `1` means assistant-square cloud profile, and `2` means default cloud profile. When `remoteType` is absent, keep the legacy remote check for compatibility.
`CloudConnectionContext.cloudProfile` comes from `AssistantInstanceInfo.remoteType` first (`1 -> assistant_square`, `2 -> default`), then `bizRobotTag` when present, then the SS-provided business tag / legacy `cloudProfile`; never use `remoteProperty.dataProtocol` as the profile override.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| Instance API returns `remoteType=1` with a matching `remoteProperty` for action | Use endpoint/protocol from instance data, derive `authType` from `remoteProperty.headers[0].type`, and derive `cloudProfile=assistant_square` without reading SysConfig package mapping. |
| Instance API returns `remoteType=2` with a matching `remoteProperty` for action | Use endpoint/protocol from instance data, derive `authType` from `remoteProperty.headers[0].type`, and derive `cloudProfile=default` without reading SysConfig package mapping. |
| Instance API returns `remoteType=0` even with legacy `remoteProperty` | Treat it as local/non-remote and use the legacy fallback path when configured. |
| Instance API succeeds but action property is missing | Emit remote-property-missing failure; do not invent a legacy endpoint. |
| Instance API fails or no partner account is present | Preserve legacy fallback path when configured. |
| `remoteProperty.headers` is empty | Use `authType="none"` so no auth headers are written. |
| First `header.type` is unsupported | Pass the normalized value as `authType`; `CloudAuthService` must fail fast rather than silently dropping auth material. |
| `customKey/customValue` are present | Ignore them for cloud outbound auth; never write them as raw request headers. |

### 5. Good / Base / Bad Cases

Good:

```java
AssistantInstanceInfo info = assistantInstanceInfoService.getInstanceInfo(partnerAccount);
RemoteRoute route = resolveRemoteRouteFromInstance(info, action);
CloudConnectionContext ctx = CloudConnectionContext.builder()
        .authType(route.authType())       // from remoteProperty.headers[0].type
        .cloudProfile(route.cloudProfile()) // from remoteType profile, bizRobotTag, or SS fallback
        .build();
cloudAuthService.applyAuth(builder, ctx.getAppId(), ctx.getAuthType());
```

Base:

```java
RemoteRoute route = resolveRemoteRouteFromLegacyConfig(businessTag, action);
```

Bad:

```java
String ak = payload.path("ak").asText();
RemoteRoute route = resolveOnlyByAk(ak);
```

The bad case cannot route no-AK remote assistants and causes skill-server to block or degrade before the gateway can call the cloud endpoint.

### 6. Tests Required

- `AssistantInstanceInfoServiceTest`: success, not-exists/empty data, upstream failure, and cache behavior.
- `CloudAgentServiceTest`: action-specific `remoteProperty` selection, no-AK remote invocation, first `headers[0].type` auth mapping, `remoteType` cloudProfile mapping, `remoteType=0` local override, and legacy fallback.
- `CloudAuthServiceTest`: HTTP and WebSocket authType strategy dispatch, including unknown authType failure.

### 7. Wrong vs Correct

Wrong: use AK as the only route key for cloud assistants, map `remoteProperty.dataProtocol` into `cloudProfile`, or replay `remoteProperty.headers[].customKey/customValue` as raw outbound headers.

Correct: use `assistantAccount` / `partnerAccount` to load instance metadata, require a remote assistant before selecting `remoteProperty`, derive `authType` from the first `header.type`, keep `cloudProfile` from `remoteType` before `bizRobotTag` or SS fallback, and treat AK as optional context.

## Cloud stream abort cancellation

### 1. Scope / Trigger

This applies when skill-server sends `action=abort_session` for a cloud-backed
business or default-assistant session. Abort is a transport cancellation, not a
normal cloud callback action, and it must stop the active SSE/WebSocket stream
inside ai-gateway.

### 2. Signatures

- SS lifecycle command: `SkillSessionFlowService.abortSession(SkillSession)`.
- SS gateway send path: `GatewayRelayService.sendInvokeToGateway(InvokeCommand)`.
- SS connection selection: `GatewayWSClient.startIndexForMessage(String message, int count)`.
- GW source invoke entry: `SkillRelayService.handleInvokeFromSkill(WebSocketSession, GatewayMessage)`.
- GW-to-GW cloud control relay: `RelayMessage.toCloudControl(String)` and `EventRelayService.handleGwRelayMessage(String)`.
- GW cloud entry: `CloudAgentService.handleInvoke(GatewayMessage, Consumer<GatewayMessage>)`.
- Cloud stream owner route: `RedisMessageBroker.setCloudStreamRoute(String, String, Duration)`, `getCloudStreamRoute(String)`, and `removeCloudStreamRoute(String, String)`.
- Active stream handle: `CloudConnectionHandle.cancel()` and `CloudConnectionHandle.onCancel(Runnable)`.
- Protocol transports: `SseProtocolStrategy.connect(...)` and `WebSocketProtocolStrategy.connect(...)`.

### 3. Contracts

`abort_session` must carry the same `payload.toolSessionId` as the running
`chat` invoke. For default assistants, `close_session` may still skip GW, but
`abort_session` must not reuse that close-session filter because the cloud
stream lives in GW.

`GatewayWSClient` uses `payload.toolSessionId`, top-level `toolSessionId`, or
`welinkSessionId` as a sticky route key so the `chat` and `abort_session` for
one turn prefer the same SS->GW WebSocket pool slot.

`CloudAgentService` keeps an in-memory active connection table keyed by
`tool:{toolSessionId}` and `welink:{welinkSessionId}`. Each key maps to a set
of active handles, not a single handle: starting a second `chat` in the same
session must append a new handle and must not cancel the first stream. A
matching `abort_session` cancels all local handles found for the tool/welink
keys and returns without relaying `tool_error`.

Because miniapp/source WebSocket load balancing can put the original `chat`
stream and the later `abort_session` on different GW instances, cloud stream
ownership is also registered in Redis. `gw:cloud-stream:{toolSessionId}` keeps
a latest-owner compatibility value, while
`gw:cloud-stream:{toolSessionId}:owners` is the multi-owner set used for abort
fan-out. If local memory has no active connection, or after cancelling local
handles, GW must look up all remote owners and send a `RelayMessage` with
`relayType=to-cloud-control` to each `gw:relay:{ownerGatewayId}` except itself.
Relayed cloud-control payloads must be marked so the receiving GW cancels only
its local handles and does not relay the same abort again. These keys are for
GW stream ownership only; do not confuse them with `gw:route:{toolSessionId}`,
which maps a session back to source service instances.

SSE cancellation closes the response `InputStream`, exits the read loop, and
skips decoder tail flush after cancellation. WebSocket cancellation calls
`WebSocket.abort()` and releases the thread waiting on the close latch.
Lifecycle timeouts (`first_event_timeout`, `idle_timeout`, `max_duration`)
must reuse the same cancellation path: the timeout callback cancels the active
`CloudConnectionHandle` and then emits at most one `tool_error`.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| Default-assistant session has `toolSessionId` and user aborts | SS sends `abort_session` to GW with that `toolSessionId`. |
| Multiple cloud streams are active under the same `toolSessionId`/`welinkSessionId` on one GW | GW cancels every matching local handle and closes each registered transport. |
| Abort reaches a different GW than one or more stream owners | Receiver relays a `to-cloud-control` message to every remote owner from `gw:cloud-stream:{toolSessionId}:owners`, falling back to the latest-owner KV when needed. |
| Abort reaches GW after stream already ended | Log `no_active_connection`; do not emit `tool_error`. |
| Cancellation causes read/WS error | Suppress the error path and do not relay cancellation as a cloud failure. |
| User sends the next chat after abort | Register a fresh `CloudConnectionHandle`; do not keep session-level suppress state. |

### 5. Good / Base / Bad Cases

Good:

```java
public void abortSession(SkillSession session) {
    if (hasAssistantIdentity(session.getAk(), session.getAssistantAccount())
            && session.getToolSessionId() != null) {
        gatewayRelayService.sendInvokeToGateway(lifecycleCommand(session, GatewayActions.ABORT_SESSION));
    }
}
```

Base:

```java
if ("abort_session".equals(normalizeAction(message.getAction()))) {
    if (!cancelLocalActiveConnection(message, payloadToolSessionId)) {
        relayAbortToOwningGateway(message, payloadToolSessionId);
    }
    return;
}
```

Bad:

```java
if (isDefaultAssistant(session)) {
    return; // wrong for abort_session: GW still owns the upstream stream
}
```

```java
String route = redisMessageBroker.getSessionRoute(toolSessionId);
// wrong for cloud abort owner: this is sourceType:sourceInstanceId, not a GW stream owner
```

### 6. Tests Required

- `SkillSessionControllerTest`: default-assistant abort sends `GatewayActions.ABORT_SESSION`.
- `DefaultAssistantRuleE2EIntegrationTest`: rule-injected session abort emits wire payload with `payload.toolSessionId`.
- `GatewayWSClientTest`: chat and abort with the same `toolSessionId` choose the same pool slot.
- `CloudAgentServiceTest`: overlapping chats with the same session key do not cancel each other; abort cancels all local active streams; cancellation suppresses late errors; stream registration writes owner keys; no-local abort relays to every remote owner GW.
- `RedisMessageBrokerTest`: cloud stream latest-owner route set/get/remove uses TTL and conditional delete; owner set add/members/remove supports multi-owner abort fan-out.
- `EventRelayServiceTest`: `relayType=to-cloud-control` dispatches to local cloud-control handling instead of Agent delivery.
- `SkillRelayServiceTest`: cloud-control relay routes through the business invoke strategy locally.
- `SseProtocolStrategyTest`: cancellation closes the stream and skips decoder flush.
- `WebSocketProtocolStrategyTest`: cancellation aborts the cloud WebSocket and releases the wait latch.

### 7. Wrong vs Correct

Wrong: treat `abort_session` as an unknown cloud action or block default
assistant aborts in SS because default assistant close skips GW.

Correct: treat `abort_session` as a GW-side transport cancellation keyed by the
current `toolSessionId`/`welinkSessionId`; close all matching local active
streams, relay once to every remote owner in
`gw:cloud-stream:{toolSessionId}:owners`, and allow the next chat to create a
new active handle.

---

## Agent-to-SS 回源 L1/L2 路由

Agent/cloud 事件回 SS 时，`messageId` 是同一条 agent/cloud 回复流的顺序主键；
`traceId` 是一次调用链路的上下文与 terminal 事件补齐依据。`toolSessionId` 可能在
`GatewayMessage.toolSessionId` 顶层字段，也可能只存在于 `payload.toolSessionId`，但它只作为
缺少 `messageId/traceId` 时的降级路由键。回源链路只允许两层：

- L1：当前 GW 直接选择一个本机 `skill-server` Source WebSocket 并发送。
- L2：当前 GW 先按 routing key 从有本机 `skill-server` 连接的 GW 中确定一个 `targetGw`，
  再写入 `gw:l2:source:skill-server:{targetGw}` mailbox Stream；只有 `targetGw` 消费后再走 L1。

### 1. Scope / Trigger

- Trigger: `EventRelayService.relayToSkillServer(...)` 收到 Agent/cloud 回源事件。
- Trigger: `SkillRelayService.relayToSkill(GatewayMessage)` 处理 `session_created`、`agent_online`、
  `agent_offline`、`im_push`、`tool_done`、`tool_error`、`tool_event` 等需要给 SS 的消息。
- Trigger: 当前 GW 没有本机 `skill-server` WebSocket，但集群内其他 GW 可能有。

### 2. Signatures

```java
public boolean relayToSkill(GatewayMessage message)
private boolean v2RelayToSkillWithoutBroadcast(GatewayMessage message)
private LocalDeliveryResult deliverToOneLocalSource(String sourceType, GatewayMessage message,
                                                    String routingKey, String stage)
private boolean enqueueSkillServerL2Work(GatewayMessage message, String routingKey)
public void consumeSkillServerL2Work()
```

### 3. Contracts

- `EventRelayService.relayToSkillServer(...)` 在回源消息缺少 `source` 时补 `source=skill-server`。
- `SkillRelayService.resolveTargetSourceType(...)` 优先使用 `UpstreamRoutingTable.resolveSourceType(...)`，
  再用 `GatewayMessage.source`，最后默认 `skill-server`；历史值 `skill-service` 必须规范化为
  `skill-server`。
- `GatewayMessageIdentityService.normalizeForSkillRelay(...)` 必须先把
  `event.properties.messageId` / 顶层 `messageId` 归一到 `GatewayMessage.messageId`；如果
  `tool_done/tool_error` 没带 `messageId`，用同一 `traceId` 下已学习到的 `messageId` 补齐。
- `SkillRelayService.resolveRoutingKey(...)` 的优先级是 `messageId`、`traceId`、顶层
  `toolSessionId`、`payload.toolSessionId`、`welinkSessionId`、`ak`。
- L1 对目标 sourceType 的本机连接只选择一个：优先用 consistent hash ring，缺少 routing key
  或 ring 不可用时取一个 open session。
- 同一 `sourceType + routingKey` 会绑定一条本机 link；绑定 link 失效后不能静默换 link 继续发，
  必须移除本地连接池 / hash ring / Redis source-conn，并让当前投递失败或进入死信路径。
- L2 只支持 `skill-server`，不读取 SS Redis；目标 GW 来自 `gw:source-conn:skill-server:*`
  中仍存活的 GW 实例，并用 routing key 做确定性单目标选择。
- L2 写入 target-GW mailbox Stream 后即认为 GW 已接管跨 GW 转交；消费端只有在本机存在
  `skill-server` 连接时才读取自己的 mailbox，成功 enqueue 到本机 SS link 后 `XACK`。
- 禁止恢复 L3：不得调用 `discoverAllSourceGwInstances()`、`publishToSourceRelay(...)` fan-out、
  `RelayMessage.to-source-broadcast` 或任何 “broadcast to all source GW” 兜底。

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| 当前 GW 有本机 `skill-server` | L1 发送给一个本机 SS WebSocket，返回 `true`，不写 Redis Stream。 |
| 当前 GW 无本机 `skill-server` | L2 选择一个 `targetGw` 并写入 `gw:l2:source:skill-server:{targetGw}` mailbox，返回入队结果。 |
| 目标 sourceType 不是 `skill-server` 且无本机连接 | 返回 `false`，不写 L2，因为 L2 只承诺 SS 回源。 |
| L2 消费端无本机 `skill-server` | 不读取自己的 mailbox Stream，避免 claim 后无法投递。 |
| L2 消费端发送失败 | 按 `attempt` 重入队；达到 `gateway.l2-source-stream.max-attempts` 后写 `:dead` 死信并 ACK 原消息。 |
| `messageId` 存在 | 作为 routing key 参与 L1 link affinity 与 L2 target-GW 选择。 |
| `messageId` 缺失但 `traceId` 存在 | 用 `traceId` 作为降级 routing key，并保留 WARN 诊断。 |
| `payload.toolSessionId` 是唯一 session key | 只作为最后降级 routing key，不退化为广播。 |

### 5. Good / Base / Bad Cases

Good: `tool_event` 携带 `messageId=M1`，当前 GW 无本机 SS；GW 用 `M1` 选择一个
`targetGw=gw-b`，写入 `gw:l2:source:skill-server:gw-b`，`gw-b` 消费后用 `M1` 绑定一条本机
SS link，后续同 `M1` 的事件继续走同一 link。

Base: `tool_done` 没带 `messageId`，但同一 `traceId` 前面已经学习到 `messageId=M1`；GW 补齐
顶层 `messageId=M1` 后再路由。

Bad: 当前 GW 无本机 SS 时，对所有 `gw:source-conn:skill-server:*` 查出的 GW 发
`to-source-broadcast`，导致 16 个 GW 中多个实例重复尝试、SS 收到重复回源事件。

### 6. Tests Required

- `SkillRelayServiceV2Test`: 无本机 `skill-server` 时 `relayToSkill(...)` 只调用
  `RedisMessageBroker.enqueueSourceL2Work(sourceType, targetGw, ...)`，不调用 `publishToSourceRelay(...)`。
- `SkillRelayServiceV2Test`: 同一 `messageId` 的两条本地回源事件只发送到同一条 SS link。
- `SkillRelayServiceV2Test`: `messageId` 能作为 L2 `routingKey` 入队，且不调用
  `discoverAllSourceGwInstances()`。
- `GatewayMessageIdentityServiceTest`: `tool_done/tool_error` 缺 `messageId` 时能通过同一 `traceId`
  找回已学习的 `messageId`。
- `SkillRelayServiceV2Test`: `consumeSkillServerL2Work()` 在本机无 SS 时不读自己的 mailbox；
  有 SS 时发送一个连接并 `ackSourceL2Work(sourceType, targetGw, streamId)`。
- `EventRelayServiceTest`: Redis relay 只处理 `to-source` 和 `to-cloud-control`，不存在
  `to-source-broadcast` 分支。

### 7. Wrong vs Correct

Wrong:

```java
List<String> gwIds = redisMessageBroker.discoverAllSourceGwInstances();
gwIds.forEach(gw -> redisMessageBroker.publishToSourceRelay(gw, "skill-server", "*", payload));
```

Correct:

```java
LocalDeliveryResult delivered = deliverToOneLocalSource("skill-server", message, routingKey, "[V2-L1]");
if (!delivered.delivered()) {
    String targetGw = selectTargetSourceGateway("skill-server", routingKey);
    redisMessageBroker.enqueueSourceL2Work("skill-server", targetGw, payload, routingKey, traceId, type, maxLen);
}
```
