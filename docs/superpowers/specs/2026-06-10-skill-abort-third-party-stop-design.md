# Skill Abort 第三方助手终止接口调用设计

> **日期**: 2026-06-10
> **需求来源**: `docs/human-docs/003终止skill对话流程.md`
> **目标**: Gateway 收到 `abort_session` 时，通过 remoteProperty 配置调用第三方助手的终止执行接口，实现异步 fire-and-forget。

---

## 1. 背景与问题

### 1.1 现有 abort 流程

```
前端 POST /api/skill/sessions/{id}/abort
  → skill-server: abortSession()
    → 发送 abort_session invoke 到 Gateway
    → 本地 finalize（persist buffer → idle → clear）
  → Gateway: CloudAgentService.handleInvoke()
    → cancelStreamingConnection() —— 仅关闭本地 SSE/WS 连接
```

### 1.2 缺失环节

Gateway 本地连接取消后，**第三方助手平台（assistant-agent-b）仍在继续推理**，导致：
- 后端资源浪费
- 可能产生"孤儿"响应，后续会话状态错乱

### 1.3 需求

在 Gateway 本地取消前/后，**调用第三方助手终止接口**：

```
POST https://xxx/api/digital-assistant/assistant-agent-b/stream_chat_stop
Headers: x-hw-id, x-hw-appkey, Content-Type: application/json
Body: { type, topicId, assistantAccount, sendUserAccount, imGroupId?, messageId?, clientLang?, clientType?, extParameters? }
```

> **注意**：鉴权 header 和入参与原来的 question 接口一致（需求原文），仅接口地址不同。
> 请求体与 `cloudRequest` 同构，`type` 字段值为 `"abort"`（区别于 `chat` 的 `"text"` 和 `question_reply` 的 `"question_reply"`）。

**约束**：异步执行（fire-and-forget），不等待响应。

---

## 2. 设计原则

1. **配置驱动**：复用现有 `remoteProperty` 机制，新增 `type="abort"` 配置项，零硬编码。
2. **与现有架构对称**：abort 回调的配置、解析、调用模式与 `chat` / `question` 完全一致。
3. **最小侵入**：不改 skill-server 现有 payload 契约（gateway 从 invokeMessage 提取所需字段）。
4. **异步非阻塞**：使用 `CompletableFuture.runAsync()` + 固定线程池异步发送 HTTP POST，不阻塞 `cancelStreamingConnection()`。

---

## 3. 详细设计

### 3.1 RemoteProperty 配置扩展

助手实例的 `remoteProperty` 列表中增加 `type="abort"` 项：

```json
{
  "remoteProperty": [
    {
      "type": "chat",
      "url": "https://xxx/api/digital-assistant/assistant-agent-b/stream_chat",
      "commProtocol": "sse",
      "headers": [...]
    },
    {
      "type": "question",
      "url": "https://xxx/api/digital-assistant/assistant-agent-b/question_reply",
      "commProtocol": "webhook",
      "headers": [...]
    },
    {
      "type": "abort",
      "url": "https://xxx/api/digital-assistant/assistant-agent-b/stream_chat_stop",
      "commProtocol": "webhook",
      "headers": [
        {"type": "3", "customKey": "x-hw-id", "customValue": "xxx"},
        {"type": "3", "customKey": "x-hw-appkey", "customValue": "xxx"}
      ]
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `type` | 固定 `"abort"`，表示终止回调 |
| `url` | 第三方终止接口完整地址 |
| `commProtocol` | 固定 `"webhook"`（同步 HTTP POST） |
| `headers` | 认证头，复用现有 `CloudAuthService` 解析逻辑 |

### 3.2 Gateway 侧代码变更

#### 3.2.1 `CloudAgentService` — 扩展 abilityType 映射

```java
private static String abilityType(String action) {
    return switch (action) {
        case "chat" -> "chat";
        case "abort_session" -> "abort";
        default -> "question"; // question_reply, permission_reply
    };
}
```

> 仅新增 `abort_session → "abort"` 分支，其余不变。

#### 3.2.2 新增 `CloudAgentConfig` — 专用线程池 Bean

```java
@Configuration
public class CloudAgentConfig {

    @Bean(name = "cloudAbortExecutor")
    public Executor cloudAbortExecutor(
            @Value("${gateway.cloud.abort.executor.core-size:2}") int coreSize,
            @Value("${gateway.cloud.abort.executor.max-size:10}") int maxSize,
            @Value("${gateway.cloud.abort.executor.queue-capacity:100}") int queueCapacity) {
        return new ThreadPoolExecutor(
            coreSize,
            maxSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cloud-abort-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.DiscardPolicy() // 队列满时丢弃，不打断主流程
        );
    }
}
```

> **为什么不用虚拟线程**：项目里 `BusinessInvokeRouteStrategy` 用虚拟线程执行 webhook，但 abort 请求是旁路通知（非主业务路径），使用固定线程池更可控，避免虚拟线程无限制增长。
> **专用线程池**：与 `RedisConfig` 中的 `redisListenerExecutor` / `redisSubscriptionExecutor` 对齐，独立配置、独立生命周期、独立监控。

#### 3.2.3 `CloudAgentService` — 注入专用线程池

```java
private final Executor abortExecutor;

@Autowired
public CloudAgentService(...,
                         @Qualifier("cloudAbortExecutor") Executor abortExecutor) {
    // ... 现有字段注入 ...
    this.abortExecutor = abortExecutor;
}
```

#### 3.2.4 `CloudAgentService` — 修改 abort_session 处理

```java
public void handleInvoke(GatewayMessage invokeMessage, Consumer<GatewayMessage> onRelay) {
    // ... 前置字段提取不变 ...

    if (ACTION_ABORT_SESSION.equals(action)) {
        // 【新增】异步调用第三方终止接口（fire-and-forget）
        invokeRemoteAbortIfConfigured(invokeMessage, toolSessionId, assistantAccount, businessTag);

        // 【原有】取消本地活跃 SSE/WS 连接
        cancelStreamingConnection(invokeMessage, toolSessionId);
        return;
    }
    // ... 其余 action 处理不变 ...
}
```

#### 3.2.5 `CloudAgentService` — 新增 invokeRemoteAbortIfConfigured

```java
private void invokeRemoteAbortIfConfigured(GatewayMessage invokeMessage,
                                           String toolSessionId,
                                           String assistantAccount,
                                           String businessTag) {
    RemoteRoute route = resolveRemoteRoute(assistantAccount, ACTION_ABORT_SESSION, businessTag);
    if (route == null) {
        log.debug("[CLOUD_AGENT] No remote abort route configured, skipping third-party stop call");
        return;
    }

    // 构造请求体（与 question 接口同构）
    ObjectNode body = buildAbortBody(invokeMessage, toolSessionId);

    // 异步发送（fire-and-forget）
    // 使用 CompletableFuture + 固定线程池，与 EventRelayService 中 requestAgentStatus() 的异步模式一致
    CompletableFuture.runAsync(() -> {
        try {
            sendAbortRequest(route, body, invokeMessage.getTraceId());
        } catch (Exception e) {
            log.warn("[CLOUD_AGENT] Async abort request failed: traceId={}, error={}",
                    invokeMessage.getTraceId(), e.getMessage());
        }
    }, abortExecutor);
}
```

> 使用 `CompletableFuture.runAsync()` + 固定线程池异步执行，不阻塞主流程。
> 失败仅打 WARN 日志，不回传 tool_error。

#### 3.2.6 `CloudAgentService` — 构造终止请求体（与 question 接口一致）

> **设计依据**：需求明确"只是接口地址变了，鉴权header和入参和原来的question接口一致"。
> 因此 abort 请求体不定义独立 DTO，而是构造与 question_reply 的 `cloudRequest` 同构的 JSON 对象
> （不含 `replyContext`，因为终止操作无需回传答案）。

请求体格式：

```json
{
  "type": "abort",
  "assistantAccount": "...",
  "sendUserAccount": "...",
  "imGroupId": null,
  "clientLang": "zh",
  "clientType": "asst-pc",
  "topicId": "...",
  "messageId": "...",
  "extParameters": {
    "businessExtParam": {},
    "platformExtParam": {}
  }
}
```

```java
private ObjectNode buildAbortBody(GatewayMessage invokeMessage, String toolSessionId) {
    JsonNode payload = invokeMessage.getPayload();
    ObjectNode body = objectMapper.createObjectNode();

    body.put("type", "abort");
    body.put("assistantAccount", firstNonBlank(
            invokeMessage.getAssistantAccount(),
            textAt(payload, "assistantAccount"),
            textAt(payload, "partnerAccount")));
    body.put("sendUserAccount", firstNonBlank(
            invokeMessage.getUserId(), textAt(payload, "sendUserAccount")));
    body.put("topicId", toolSessionId);

    // 可选字段
    putIfText(body, payload, "imGroupId");
    putIfText(body, payload, "messageId");
    String clientLang = textAt(payload, "clientLang");
    body.put("clientLang", (clientLang != null && !clientLang.isBlank()) ? clientLang : "zh");
    putIfText(body, payload, "clientType");

    // extParameters：与 question 接口对齐
    ObjectNode extParams = objectMapper.createObjectNode();
    extParams.set("businessExtParam",
            (payload != null && payload.has("businessExtParam") && payload.get("businessExtParam").isObject())
                    ? payload.get("businessExtParam") : objectMapper.createObjectNode());
    extParams.set("platformExtParam", objectMapper.createObjectNode());
    body.set("extParameters", extParams);

    return body;
}
```

#### 3.2.7 `CloudAgentService` — 参数来源映射

| 请求体字段 | Gateway 来源 | 说明 |
|---|---|---|
| `type` | 固定 `"abort"` | 与 question 接口的 `type` 字段对齐 |
| `topicId` | `toolSessionId`（已提取） | 必填 |
| `assistantAccount` | `invokeMessage.getAssistantAccount()` → payload.assistantAccount → payload.partnerAccount | 必填，三级降级 |
| `sendUserAccount` | `invokeMessage.getUserId()` → payload.sendUserAccount | 必填 |
| `imGroupId` | payload.imGroupId | 可选，缺省不写 |
| `messageId` | payload.messageId | 可选，缺省不写 |
| `clientLang` | payload.clientLang | 可选，缺省 `"zh"` |
| `clientType` | payload.clientType | 可选，缺省不写 |
| `extParameters` | payload.businessExtParam（透传）+ platformExtParam（占位 `{}`） | 与 question 接口对齐 |

#### 3.2.8 `CloudAgentService` — 发送终止请求

复用 `WebHookExecutor` 的 HTTP 发送模式，但简化（不处理 response body 的业务语义，仅校验 HTTP 状态码）：

```java
private void sendAbortRequest(RemoteRoute route, ObjectNode body, String traceId) {
    String bodyStr = objectMapper.writeValueAsString(body);
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(route.channelAddress()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
        .timeout(Duration.ofSeconds(10));
    if (traceId != null) {
        builder.header("X-Trace-Id", traceId);
    }
    cloudAuthService.applyAuth(builder, route.appId(), route.authType());

    HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 200) {
        log.info("[CLOUD_AGENT] Abort request sent successfully: url={}, status={}, traceId={}",
                route.channelAddress(), resp.statusCode(), traceId);
    } else {
        log.warn("[CLOUD_AGENT] Abort request returned non-2xx: url={}, status={}, body={}, traceId={}",
                route.channelAddress(), resp.statusCode(), resp.body(), traceId);
    }
}
```

> **响应处理**：需求明确第三方终止接口返回 `{"code":200, "msg":"success", "data":null}`（成功）或
> `{"code":500, "msg":"pc is offline", "data":null}`（失败）。当前实现仅校验 HTTP 状态码（200 = 成功），
> 不解析 response body 中的 `code` 字段。后续如有需要可扩展 body 解析逻辑。
> **为什么内联而非复用 WebHookExecutor**：abort 是 fire-and-forget 旁路通知，失败不回传 `tool_error`；
> WebHookExecutor 失败会回调 `onRelay` 产生 `tool_error`，语义不匹配。

### 3.3 与现有 remote route 的复用关系

`resolveRemoteRoute()` 和 `invokeRemoteRoute()` 已存在的逻辑：
- 按 `assistantAccount` 查询 `AssistantInstanceInfo`
- 按 `abilityType(action)` 匹配 `remoteProperty.type`
- 解析 `url`, `commProtocol`, `headers` → `RemoteRoute`

**abort 完全复用以上流程**，无需新增解析逻辑。

### 3.4 时序图

```mermaid
sequenceDiagram
    autonumber
    participant FE as Frontend
    participant SS as Skill-Server
    participant GW as Gateway
    participant CA as Cloud-Assistant

    FE->>SS: POST /api/skill/sessions/{id}/abort
    SS->>GW: invoke(action=abort_session)
    GW->>GW: resolveRemoteRoute(type=abort)
    alt 配置了 abort remoteProperty
        GW->>CA: 异步 POST /stream_chat_stop<br/>(fire-and-forget, 不等待响应)
    else 未配置
        GW->>GW: 跳过第三方调用
    end
    GW->>GW: cancelStreamingConnection()<br/>(关闭本地 SSE/WS)
    SS-->>FE: session.status(idle)<br/>WebSocket 推送
```

---

## 4. 文件变更清单

### 修改文件

| 文件 | 变更 |
|---|---|
| `ai-gateway/.../CloudAgentService.java` | 1) `abilityType()` 新增 abort 分支；2) `ACTION_TO_SCOPE` 新增 `abort_session → callback:weagent:abort`；3) `handleInvoke()` abort 逻辑前置调用 `invokeRemoteAbortIfConfigured()`；4) `invokeRemoteAbortIfConfigured()` 新增 SysConfig 兜底回退；5) 新增 `buildAbortBody()`, `sendAbortRequest()`；6) 注入专用 `abortExecutor` 线程池 |
| `ai-gateway/.../config/CloudAgentConfig.java` | **新增**：声明 `cloudAbortExecutor` Bean，专用线程池配置 |
| `ai-gateway/.../SysConfigFallbackProviderV2.java` | `SCOPE_TO_SHORT_NAME` 新增 `callback:weagent:abort → "abort"` 映射 |

### 不改动文件

| 文件 | 原因 |
|---|---|
| `SkillSessionFlowService.java` | payload 已有足够字段（toolSessionId, assistantAccount, userId），无需扩展 |
| `SkillSessionController.java` | abort REST 接口行为不变 |
| `BusinessInvokeRouteStrategy.java` | abort 已 inline 处理，逻辑不变 |
| `WebHookExecutor.java` | abort 请求单独在 CloudAgentService 内发送（更轻量，无需 onRelay 回调） |
| `AssistantInstanceInfo.java` | remoteProperty 结构已足够，无需新增字段 |

---

## 5. 错误处理与边界情况

| 场景 | 行为 |
|---|---|---|
| 未配置 `type=abort` 的 remoteProperty | 回退到 SysConfig 兜底（`cloud_route_fallback_v2:{businessTag}:abort`）；若也未配置则跳过第三方调用，仅执行本地 cancel |
| 第三方接口返回非 200（含 500 `"pc is offline"`） | 打 WARN 日志（含 response body），不影响本地 abort 流程 |
| 第三方接口超时/网络异常 | 打 WARN 日志，不影响本地 abort 流程 |
| 缺少 assistantAccount | `resolveRemoteRoute()` 返回 null，跳过 |
| `userId` 为空 | `sendUserAccount` 为 null，第三方接口可能返回错误（仅打日志） |

---

## 6. 测试策略

| 测试项 | 类型 | 验证点 |
|---|---|---|
| `abilityType("abort_session")` 返回 `"abort"` | 单元 | `CloudAgentServiceTest` |
| `resolveRemoteRoute()` 匹配 `type=abort` | 单元 | `CloudAgentServiceTest` |
| `buildAbortBody()` 字段映射正确（与 question 接口同构） | 单元 | `CloudAgentServiceTest` |
| 无 abort 配置时跳过调用 | 单元 | `CloudAgentServiceTest` |
| remoteProperty 未配置 abort → 回退 SysConfig 兜底 | 单元 | `CloudAgentServiceTest` |
| 异步发送失败不抛异常 | 单元 | mock httpClient |
| 端到端：配置 abort → 触发 abort → 验证 HTTP POST body 与 question 接口一致 | 集成 | 使用 WireMock / Testcontainers |

---

## 7. 配置示例（完整）

```json
{
  "partnerAccount": "assistant-bot-001",
  "remoteType": 1,
  "remoteProperty": [
    {
      "type": "chat",
      "url": "https://assistant.example.com/api/digital-assistant/assistant-agent-b/stream_chat",
      "commProtocol": "sse",
      "headers": [{"type": "3", "customKey": "Authorization", "customValue": "Bearer xxx"}]
    },
    {
      "type": "abort",
      "url": "https://assistant.example.com/api/digital-assistant/assistant-agent-b/stream_chat_stop",
      "commProtocol": "webhook",
      "headers": [
        {"type": "3", "customKey": "x-hw-id", "customValue": "hw-123"},
        {"type": "3", "customKey": "x-hw-appkey", "customValue": "appkey-456"}
      ]
    }
  ]
}
```

---

## 8. 后续扩展（可选）

- **响应处理**：如需根据第三方终止响应做后续动作（如刷新 UI 状态），可将 `sendAbortRequest` 改为返回 `CompletableFuture`，由调用方决定等待或忽略。
- **批量终止**：子代理场景下，abort 可能需要级联终止多个 topicId。
- **指标监控**：增加 `abort_remote_request_total` / `abort_remote_error_total` 计数器。

---

## 9. 测试建议（供测试人员参考）

> 以下测试用例面向手工测试 / 接口测试 / 集成测试人员，建议结合 WireMock / Mock Server / 实际第三方环境执行。

### 9.1 配置与路由匹配测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| CFG-001 | 配置 abort remoteProperty | 助手实例 `remoteProperty` 包含 `type="abort"` | 触发 `abort_session` | `resolveRemoteRoute()` 成功匹配到 abort 配置，`invokeRemoteAbortIfConfigured()` 执行异步调用 |
| CFG-002 | 未配置 abort 时跳过 | 助手实例 `remoteProperty` 只有 `chat` 和 `question` | 触发 `abort_session` | 不调用第三方终止接口，仅执行本地 `cancelStreamingConnection()`，日志输出 `No remote abort route configured, skipping` |
| CFG-003 | abilityType 映射正确 | 服务启动 | 调用 `abilityType("abort_session")` | 返回 `"abort"`，与 `remoteProperty.type` 匹配 |
| CFG-004 | 多助手实例隔离 | 助手 A 配置 abort，助手 B 未配置 | 分别对 A、B 触发 abort_session | A 调用第三方终止，B 不调用，两者互不干扰 |

### 9.2 请求参数映射测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| PARAM-001 | 必填字段完整 | 标准 abort_session invoke | 检查构造的请求体 JSON | `type="abort"`，`topicId=toolSessionId`，`assistantAccount` 正确，`sendUserAccount=invokeMessage.userId` |
| PARAM-002 | assistantAccount 降级 | `invokeMessage.assistantAccount` 为空，但 payload 含 `assistantAccount` | 触发 abort_session | 请求体 `assistantAccount` 取 payload.assistantAccount |
| PARAM-003 | assistantAccount 二次降级 | `invokeMessage.assistantAccount` 和 payload.assistantAccount 均为空，payload 含 `partnerAccount` | 触发 abort_session | 请求体 `assistantAccount` 取 payload.partnerAccount |
| PARAM-004 | 可选字段缺省 | payload 不含 `imGroupId`、`messageId`、`clientType` | 触发 abort_session | 请求体中不含这些字段（或为 null），`clientLang="zh"` |
| PARAM-005 | clientLang 非空 | payload `clientLang="en"` | 触发 abort_session | 请求体 `clientLang="en"` |
| PARAM-006 | clientLang 为空字符串 | payload `clientLang=""` | 触发 abort_session | 请求体 `clientLang="zh"`（空字符串按缺省处理） |
| PARAM-007 | userId 为空 | `invokeMessage.userId` 为空 | 触发 abort_session | `sendUserAccount=null`，第三方接口可能返回错误，但本地 abort 流程不受影响 |
| PARAM-008 | 请求体含 extParameters | payload 含 `businessExtParam` | 触发 abort_session | 请求体 `extParameters.businessExtParam` 透传，`platformExtParam={}` |
| PARAM-009 | 请求体 type 字段 | 标准 abort_session invoke | 检查请求体 | `type="abort"`（与 question 接口对齐，但 type 值不同） |

### 9.3 异步与非阻塞测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| ASYNC-001 | 不阻塞本地 cancel | 配置 abort remoteProperty | 触发 abort_session，第三方接口延迟 5 秒响应 | `cancelStreamingConnection()` 立即执行，SSE/WS 连接在毫秒级内关闭，不等待第三方响应 |
| ASYNC-002 | 异步任务实际执行 | 配置 abort remoteProperty | 触发 abort_session | 第三方接口在后台收到 POST 请求，与本地 cancel 并发执行 |
| ASYNC-003 | 线程池独立 | 服务启动 | 连续触发 20 次 abort_session | 任务提交到 `cloudAbortExecutor` 线程池，线程名以 `cloud-abort-` 开头，不与主业务线程混淆 |
| ASYNC-004 | 队列满时丢弃策略 | 线程池队列容量=100，核心线程=2 | 快速触发 200 次 abort_session | 超出队列容量的任务被丢弃（`DiscardPolicy`），不打断主流程，不抛异常 |

### 9.4 容错与异常测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| ERR-001 | 第三方返回非 200 | 配置 abort，Mock 第三方返回 500 `{"code":500,"msg":"pc is offline","data":null}` | 触发 abort_session | 本地 cancel 正常完成，日志输出 WARN：`Abort request returned non-2xx` 含 response body |
| ERR-002 | 第三方超时 | 配置 abort，Mock 第三方不响应 | 触发 abort_session | 10 秒超时后打 WARN 日志，本地 cancel 已完成，无阻塞 |
| ERR-003 | 第三方网络不可达 | 配置 abort，URL 指向不存在地址 | 触发 abort_session | 打 WARN 日志，本地 cancel 正常完成 |
| ERR-004 | 序列化异常 | `buildAbortBody` 中 `objectMapper` 写 JSON 异常 | 触发 abort_session | 异常被 `CompletableFuture.runAsync` 的 try-catch 捕获，打 WARN 日志，不影响本地 cancel |
| ERR-005 | 认证头构造异常 | `cloudAuthService.applyAuth()` 抛异常 | 触发 abort_session | 异常被捕获，打 WARN 日志，本地 cancel 正常完成 |

### 9.5 HTTP 请求细节测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| HTTP-001 | 请求方法正确 | 配置 abort | 抓取第三方请求 | HTTP 方法为 POST |
| HTTP-002 | Content-Type 正确 | 配置 abort | 抓取第三方请求 | Header 包含 `Content-Type: application/json` |
| HTTP-003 | X-Trace-Id 传递 | `invokeMessage.traceId="trace-123"` | 抓取第三方请求 | Header 包含 `X-Trace-Id: trace-123` |
| HTTP-004 | 无 TraceId 时不传 | `invokeMessage.traceId=null` | 抓取第三方请求 | 请求中不包含 `X-Trace-Id` header |
| HTTP-005 | 认证头应用 | 配置 abort，headers 含 `x-hw-id`、`x-hw-appkey` | 抓取第三方请求 | 请求包含对应认证 header，值与 remoteProperty 配置一致 |
| HTTP-006 | 请求体结构 | 配置 abort | 解析第三方请求 body | JSON 包含 `type`、`topicId`、`assistantAccount`、`sendUserAccount`、`imGroupId`、`messageId`、`clientLang`、`clientType`、`extParameters` 字段（与 question 接口同构） |
| HTTP-007 | 请求体 type 为 abort | 配置 abort | 解析第三方请求 body | `type="abort"`（区别于 question_reply 的 `type="question_reply"`） |

### 9.6 端到端流程测试

| 用例编号 | 用例名称 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|----------|----------|----------|
| E2E-001 | 完整 abort 流程（有第三方） | 前端、skill-server、gateway、Mock 第三方就绪 | 前端调用 `POST /api/skill/sessions/{id}/abort` | 1. skill-server 发送 abort_session invoke；2. gateway 异步调用第三方 /stream_chat_stop；3. gateway 本地 cancel SSE/WS；4. skill-server finalize 并推送给前端 idle 状态 |
| E2E-002 | 完整 abort 流程（无第三方） | 同上，但助手未配置 abort remoteProperty | 前端调用 abort | 1~3 步中跳过第 2 步，其余流程与 E2E-001 一致 |
| E2E-003 | 多次 abort 同一 session | 服务启动 | 对同一 session 连续调用 3 次 abort | 每次都执行本地 cancel，每次都尝试异步调用第三方（如配置），无状态错乱 |
| E2E-004 | abort 后新会话正常 | 执行 abort 后 | 在同一助手下开启新会话并正常对话 | 新会话不受之前 abort 影响，消息收发正常 |

### 9.7 回归测试 checklist

- [ ] `mvn test` 全量通过，新增 `CloudAgentServiceTest` 覆盖 abilityType、resolveRemoteRoute、buildAbortBody、sendAbortRequest。
- [ ] 存量 `abort_session` 流程未变：skill-server 侧 payload 契约、Gateway 侧 `cancelStreamingConnection()` 行为均保持原样。
- [ ] `SkillSessionFlowService`、`SkillSessionController`、`BusinessInvokeRouteStrategy` 未做任何改动。
- [ ] 线程池配置参数可通过 `application.yml` 调整，默认值合理（core=2, max=10, queue=100）。
- [ ] 异步执行失败时不回传 `tool_error`，避免前端收到无关错误消息。
- [ ] 专用线程池 `cloudAbortExecutor` 与 `redisListenerExecutor` 等已有线程池隔离，互不干扰。
